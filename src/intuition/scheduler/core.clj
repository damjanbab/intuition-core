(ns intuition.scheduler.core
  "Mission scheduler that polls the governed `missions/list-ready-missions` API,
  selects the next ready mission using the registry helpers (§§3.3–3.6),
  and launches an agent session via a configured command."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [intuition.sfs.missions.registry :as mission-registry]
   [intuition.sfs.missions.runtime :as missions])
  (:import
   (java.time Duration Instant)))

(def default-command-template
  ["clojure" "-M:dev" "-m" "dev.agent-gateway" "fetch" "{{mission-edn}}"])

(def ^:private failure-statuses
  #{:mission.status/draft
    :mission.status/ready
    :mission.status/abandoned})

(def ^:private success-statuses
  #{:mission.status/in-progress
    :mission.status/revision
    :mission.status/awaiting-review
    :mission.status/done})

(defn- normalize-queue-tags
  [tags]
  (->> tags
       (keep identity)
       (map (fn [tag]
              (cond
                (keyword? tag) tag
                (string? tag) (keyword tag)
                :else tag)))
       vec))

(defn- summarize-mission
  [mission]
  (let [queue-tags (vec (or (:mission/queue-tags mission) []))]
    (-> mission
        (select-keys [:mission/id
                      :mission/title
                      :mission/summary
                      :mission/category
                      :mission/priority
                      :mission/protocol])
        (assoc :mission/queue-tags queue-tags))))

(defn- queue-opts
  [queue-tags]
  (cond-> {}
    (seq queue-tags) (assoc :queue/tags queue-tags)))

(defn- build-mission-command
  [{:mission/keys [id title]}
   agent-id
   template]
  (let [payload {:mission/id id
                 :mission/title title
                 :agent/id agent-id}
        payload-str (pr-str payload)
        replacements {"{{mission-id}}" (str id)
                      "{{agent-id}}" (str agent-id)
                      "{{mission-edn}}" payload-str}]
    (mapv (fn [token]
            (reduce-kv
             (fn [acc placeholder value]
               (str/replace acc placeholder value))
             token
             replacements))
          (or (seq template) default-command-template))))

(defn- default-command-runner
  [{:keys [command]}]
  (apply shell/sh command))

(defn- scheduler-dir
  [log-root mission-id]
  (io/file log-root mission-id "scheduler"))

(defn- write-edn!
  [file data]
  (io/make-parents file)
  (with-open [w (io/writer file)]
    (binding [*print-namespace-maps* false]
      (pprint/pprint data w))))

(defn- mission-failed?
  [status]
  (contains? failure-statuses status))

(defn- mission-progressing?
  [status]
  (contains? success-statuses status))

(defn- compute-status
  [mission-id exit-status mission-status]
  (cond
    (not (zero? exit-status))
    {:status :scheduler.status/failure
     :error (format "Agent command exited %s" exit-status)}

    (mission-failed? mission-status)
    {:status :scheduler.status/failure
     :error (format "Mission %s remained in %s" mission-id mission-status)}

    :else
    {:status :scheduler.status/success}))

(defn- duration-ms
  [^Instant start ^Instant end]
  (.toMillis (Duration/between start end)))

(defn- log-run!
  [log-root mission-id data failure?]
  (let [dir (scheduler-dir log-root mission-id)
        run-file (io/file dir "scheduler-run.edn")
        failure-file (io/file dir "scheduler-failure.edn")]
    (write-edn! run-file data)
    (when failure?
      (write-edn! failure-file data))))

(defn run-once!
  "Polls the mission registry, selects a ready mission, launches an agent session,
  and records scheduler-run/scheduler-failure artifacts under the mission logs.

  Supported options:
  - :queue/tags – optional filters for queue tags (keywords or strings).
  - :mission/id – optional mission id override.
  - :agent/id – agent identifier used in the command payload. Defaults to \"codex-scheduler\".
  - :scheduler/log-root – base log directory (defaults to \"missions/logs\").
  - :scheduler/command-template – vector of command tokens. Supports the placeholders
    {{mission-id}}, {{agent-id}}, and {{mission-edn}}.
  - :scheduler/list-ready-fn – dependency injection hook for list-ready-missions.
  - :scheduler/fetch-mission-fn – hook for fetching mission status post-launch.
  - :scheduler/command-runner – hook for executing the agent command.
  - :scheduler/now-fn – injectable clock for tests."
  [opts]
  (let [queue-tags (normalize-queue-tags (:queue/tags opts))
        mission-id-override (:mission/id opts)
        agent-id (or (:agent/id opts) "codex-scheduler")
        log-root (or (:scheduler/log-root opts) "missions/logs")
        command-template (or (:scheduler/command-template opts) default-command-template)
        list-ready-fn (or (:scheduler/list-ready-fn opts) missions/list-ready-missions)
        fetch-mission-fn (or (:scheduler/fetch-mission-fn opts) missions/get-mission)
        command-runner (or (:scheduler/command-runner opts) default-command-runner)
        now-fn (or (:scheduler/now-fn opts) #(Instant/now))
        ready (list-ready-fn (queue-opts queue-tags))
        mission (mission-registry/select-startable {:missions (:mission/list ready)
                                                    :mission-id mission-id-override
                                                    :queue-tags queue-tags
                                                    :active-queues (:mission/active-queues ready)})
        mission-id (:mission/id mission)
        start (now-fn)
        command (build-mission-command mission agent-id command-template)
        command-result (try
                         (or (command-runner {:command command
                                              :mission mission
                                              :agent/id agent-id})
                             {:exit 0})
                         (catch Exception e
                           (let [end (now-fn)
                                 summary (summarize-mission mission)
                                 run-data {:mission summary
                                           :scheduler/agent-id agent-id
                                           :scheduler/command command
                                           :scheduler/start-time (str start)
                                           :scheduler/end-time (str end)
                                           :scheduler/duration-ms (duration-ms start end)
                                           :command/result {:exit -1}
                                           :scheduler/final-status :scheduler.status/failure
                                           :scheduler/error (.getMessage e)}]
                             (log-run! log-root mission-id run-data true)
                             (throw e))))
        exit-code (long (:exit command-result 0))
        monitor-result (try
                         (fetch-mission-fn {:mission/id mission-id})
                         (catch Exception e
                           {:mission/status :mission.status/unknown
                            :scheduler/monitor-error (.getMessage e)}))
        mission-status (:mission/status monitor-result :mission.status/unknown)
        end (now-fn)
        {:keys [status error]} (compute-status mission-id exit-code mission-status)
        summary (summarize-mission mission)
        run-data (cond-> {:mission summary
                          :scheduler/agent-id agent-id
                          :scheduler/command command
                          :scheduler/start-time (str start)
                          :scheduler/end-time (str end)
                          :scheduler/duration-ms (duration-ms start end)
                          :mission/status mission-status
                          :mission/progressing? (mission-progressing? mission-status)
                          :command/result (select-keys command-result [:exit :out :err])
                          :scheduler/final-status status}
                   error (assoc :scheduler/error error)
                   (:scheduler/monitor-error monitor-result)
                   (assoc :scheduler/monitor-error (:scheduler/monitor-error monitor-result)))]
    (log-run! log-root mission-id run-data (= status :scheduler.status/failure))
    run-data))
