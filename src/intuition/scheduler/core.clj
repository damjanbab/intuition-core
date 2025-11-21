(ns intuition.scheduler.core
  "Mission scheduler that polls the governed `missions/list-ready-missions` API,
  selects the next ready mission using the registry helpers (§§3.3–3.6),
  and launches an agent session via a configured command."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [intuition.sfs.missions.registry :as mission-registry]
   [intuition.sfs.missions.runtime :as missions])
  (:import
   (java.time Duration Instant)))

(def default-command-template
  ["clojure" "-M:dev" "-m" "dev.agent-gateway" "run-mission" "{{payload-edn}}"])

(def ^:private default-backoff-ms 2000)

(def ^:private failure-statuses
  #{:mission.status/draft
    :mission.status/ready
    :mission.status/abandoned})

(def ^:private success-statuses
  #{:mission.status/in-progress
    :mission.status/revision
    :mission.status/awaiting-review
    :mission.status/done})

(defn- canonical-path
  [path]
  (some-> path io/file .getCanonicalPath))

(defn- context-bundle-path
  [log-root mission-id override]
  (canonical-path (or override (io/file log-root mission-id "context-bundle.edn"))))

(defn- load-bundle
  [bundle-path]
  (let [file (io/file bundle-path)]
    (when-not (.exists file)
      (throw (ex-info "Context bundle missing for mission"
                      {:path bundle-path})))
    (edn/read-string (slurp file))))

(defn- bundle-token
  [bundle]
  (let [token-file (some-> (:auth/token-path bundle) io/file)
        token-path (some-> token-file canonical-path)]
    (when (and token-file (not (.exists token-file)))
      (throw (ex-info "Auth token path missing" {:path token-path})))
    (let [token (some-> token-file slurp str/trim)]
      (when (and token-file (str/blank? token))
        (throw (ex-info "Auth token is blank" {:path token-path})))
      {:token token
       :token-path token-path})))

(defn- locks-required
  [bundle]
  (set (or (:locks/required bundle) [])))

(defn- retry-config
  [bundle]
  (let [cfg (or (:retry bundle) {})]
    {:max-attempts (long (max 1 (:max-attempts cfg 1)))
     :backoff-ms (long (max 0 (:backoff-ms cfg default-backoff-ms)))}))

(defn- redact-command
  [command token]
  (if (str/blank? (str token))
    command
    (mapv #(str/replace % (str token) "<redacted>") command)))

(defn- build-gateway-payload
  [{:keys [mission agent-id bundle-path token queue-tags locks attempt run-id]}]
  (let [priority (:mission/priority mission)]
    {:mission/id (:mission/id mission)
     :context/bundle-path bundle-path
     :agent/id agent-id
     :auth/token token
     :mission/priority priority
     :mission/queue-tags (vec queue-tags)
     :locks/requested (vec locks)
      :trace {:channel :scheduler
              :scheduler/agent agent-id
              :scheduler/attempt attempt
              :run-id run-id
              :queue/tags (vec queue-tags)
              :mission/priority priority}}))

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
   template
   payload
   bundle-path
   token
   queue-tags]
  (let [payload-str (pr-str payload)
        mission-edn (pr-str {:mission/id id
                             :mission/title title
                             :mission/queue-tags (vec queue-tags)})
        replacements {"{{mission-id}}" (str id)
                      "{{agent-id}}" (str agent-id)
                      "{{mission-edn}}" mission-edn
                      "{{payload-edn}}" payload-str
                      "{{bundle-path}}" (str bundle-path)
                      "{{auth-token}}" (or token "")
                      "{{mission-queue}}" (pr-str (vec queue-tags))}]
    (mapv (fn [token-str]
            (reduce-kv
             (fn [acc placeholder value]
               (str/replace acc placeholder value))
             token-str
             replacements))
          (or (seq template) default-command-template))))

(defn- default-command-runner
  [{:keys [command]}]
  (apply shell/sh command))

(def ^:private datomic-lock
  (io/file "data/datomic-spec/spec-system/intuition-core/.lock"))

(defn- clear-datomic-lock!
  []
  (when (.exists datomic-lock)
    (.delete datomic-lock)))

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
  - :context/bundle-path – override the default bundle path (<log-root>/<mission-id>/context-bundle.edn).
  - :scheduler/command-template – vector of command tokens. Supports the placeholders
    {{mission-id}}, {{agent-id}}, {{mission-edn}}, {{bundle-path}}, {{mission-queue}},
    {{auth-token}}, and {{payload-edn}}.
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
        base-mission-id (or mission-id-override "M-UNKNOWN")
        bundle-path (context-bundle-path log-root base-mission-id (:context/bundle-path opts))
        bundle (load-bundle bundle-path)
        mission-id (or mission-id-override (:mission/id bundle))
        mission-record (:mission/record bundle)
        ready (if mission-record
                {:mission/list [(merge {:mission/id mission-id
                                        :mission/status :mission.status/ready}
                                       mission-record)]
                 :mission/active-queues {}}
                (list-ready-fn (queue-opts queue-tags)))
        mission (mission-registry/select-startable {:missions (:mission/list ready)
                                                    :mission-id mission-id
                                                    :queue-tags queue-tags
                                                    :active-queues (:mission/active-queues ready)})
        mission-id (:mission/id mission)
        mission-queue-tags (vec (or (:mission/queue-tags mission) queue-tags))
        _ (when-not (= (str mission-id) (str (:mission/id bundle)))
            (throw (ex-info "Context bundle mission/id mismatch"
                            {:mission/id mission-id
                             :bundle/mission-id (:mission/id bundle)})))
        locks (locks-required bundle)
        {:keys [token token-path]} (bundle-token bundle)
        retry (retry-config bundle)
        max-attempts (:max-attempts retry)
        backoff-ms (:backoff-ms retry)
        attempt-run (fn [attempt]
                      (clear-datomic-lock!)
                      (let [start (now-fn)
                            run-id (format "scheduler-%s-%s-%d" mission-id attempt (.toEpochMilli ^Instant start))
                            payload (build-gateway-payload {:mission mission
                                                            :agent-id agent-id
                                                            :bundle-path bundle-path
                                                            :token token
                                                            :queue-tags mission-queue-tags
                                                            :run-id run-id
                                                            :locks locks
                                                            :attempt attempt})
                            command (build-mission-command mission
                                                           agent-id
                                                           command-template
                                                           payload
                                                           bundle-path
                                                           token
                                                           mission-queue-tags)
                            command-result (try
                                             (or (command-runner {:command command
                                                                  :mission mission
                                                                  :agent/id agent-id
                                                                  :gateway/payload payload})
                                                 {:exit 0})
                                             (catch Exception e
                                               {:exit -1
                                                :err (.getMessage e)}))
                            exit-code (long (:exit command-result 0))
                            monitor-result (try
                                             (fetch-mission-fn {:mission/id mission-id})
                                             (catch Exception e
                                               {:mission/status :mission.status/unknown
                                                :scheduler/monitor-error (.getMessage e)}))
                            mission-status (:mission/status monitor-result :mission.status/unknown)
                            end (now-fn)
                            {:keys [status error]} (compute-status mission-id exit-code mission-status)
                            retry-details (assoc retry :attempt attempt)
                            summary (summarize-mission mission)
                            run-data (cond-> {:mission summary
                                              :mission/priority (:mission/priority summary)
                                              :mission/queue-tags mission-queue-tags
                                              :locks/requested locks
                                              :context/bundle-path bundle-path
                                              :trace/run-id run-id
                                              :auth/token-path token-path
                                              :auth/token-present? (boolean token)
                                              :scheduler/retry retry-details
                                              :scheduler/agent-id agent-id
                                              :scheduler/command (redact-command command token)
                                              :scheduler/start-time (str start)
                                              :scheduler/end-time (str end)
                                              :scheduler/duration-ms (duration-ms start end)
                                              :mission/status mission-status
                                              :mission/progressing? (mission-progressing? mission-status)
                                              :command/result (select-keys command-result [:exit :out :err])
                                              :scheduler/gateway-payload (dissoc payload :auth/token)
                                              :scheduler/final-status status}
                                       error (assoc :scheduler/error error)
                                       (:scheduler/monitor-error monitor-result)
                                       (assoc :scheduler/monitor-error (:scheduler/monitor-error monitor-result)))]
                        run-data))]
    (loop [attempt 1]
      (let [run-data (attempt-run attempt)
            status (:scheduler/final-status run-data)
            failure? (= status :scheduler.status/failure)
            final-attempt? (>= attempt max-attempts)]
        (if (and failure? (not final-attempt?))
          (do
            (when (pos? backoff-ms)
              (Thread/sleep backoff-ms))
            (recur (inc attempt)))
          (let [run-data' (assoc run-data
                                 :scheduler/attempt attempt
                                 :scheduler/max-attempts max-attempts
                                 :scheduler/backoff-ms backoff-ms)]
            (log-run! log-root mission-id run-data' failure?)
            run-data'))))))
