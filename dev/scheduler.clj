(ns dev.scheduler
  "CLI entrypoint that runs the scheduler once. Successful runs emit scheduler-run.edn
  artifacts per SYSTEM_SPEC §§3.3–3.6, §5.1, §5.3, §6.2, §9."
  (:require
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [intuition.analytics.runtime :as analytics]
   [intuition.scheduler.core :as scheduler])
  (:gen-class))

(def ^:private usage
  (str/join
   \newline
   ["Scheduler usage:"
    "  clojure -M:dev -m dev.scheduler [options]"
    ""
    "Options:"
    "  --queue <mission.queue/ident>   Filter by queue tag (repeatable)."
    "  --mission <mission-id>          Force a specific mission id."
    "  --agent-id <agent-id>           Override the agent id recorded in artifacts."
    "  --log-root <path>               Mission log root (default missions/logs)."
    "  --command <edn-vector>          EDN vector of command tokens (supports {{mission-id}},"
    "                                  {{agent-id}}, and {{mission-edn}} placeholders)."
    "  --simulate                      Use a stubbed ready mission for dry runs."
    "  --simulate-failure              Same as --simulate plus a forced failure outcome."
    "  --help                          Print this help text."]))

(defn- parse-command-template
  [value]
  (try
    (let [parsed (edn/read-string value)]
      (when-not (and (vector? parsed) (every? string? parsed))
        (throw (ex-info "Command template must be an EDN vector of strings"
                        {:value value
                         :parsed parsed})))
      parsed)
    (catch Exception e
      (throw (ex-info "Unable to parse --command value"
                      {:value value}
                      e)))))

(defn- parse-args
  [args]
  (loop [opts {:queue/tags []}
         remaining args]
    (if-let [arg (first remaining)]
      (case arg
        "--queue"
        (let [[queue & more] (rest remaining)]
          (when-not queue
            (throw (ex-info "--queue requires a mission queue ident" {})))
          (recur (update opts :queue/tags conj queue) more))

        "--mission"
        (let [[mission-id & more] (rest remaining)]
          (when-not mission-id
            (throw (ex-info "--mission requires an id" {})))
          (recur (assoc opts :mission/id mission-id) more))

        "--agent-id"
        (let [[agent-id & more] (rest remaining)]
          (when-not agent-id
            (throw (ex-info "--agent-id requires a value" {})))
          (recur (assoc opts :agent/id agent-id) more))

        "--command"
        (let [[command-value & more] (rest remaining)]
          (when-not command-value
            (throw (ex-info "--command requires an EDN vector argument" {})))
          (recur (assoc opts :command-template (parse-command-template command-value)) more))

        "--log-root"
        (let [[root & more] (rest remaining)]
          (when-not root
            (throw (ex-info "--log-root requires a directory" {})))
          (recur (assoc opts :scheduler/log-root root) more))

        "--simulate"
        (recur (assoc opts :simulate? true) (rest remaining))

        "--simulate-failure"
        (recur (assoc opts :simulate? true
                           :simulate-failure? true)
               (rest remaining))

        "--help"
        (assoc opts :help? true)

        (throw (ex-info "Unknown scheduler argument"
                        {:argument arg})))
      opts)))

(defn- simulated-ready
  [mission-id]
  {:mission/list [{:mission/id mission-id
                   :mission/title (str "Simulated run for " mission-id)
                   :mission/summary "Dry run placeholder"
                   :mission/status :mission.status/ready
                   :mission/priority :mission.priority/p1
                   :mission/queue-tags [:mission.queue/core]}]
   :mission/active-queues {}})

(defn- simulated-fetch
  [failure?]
  (fn [_]
    {:mission/status (if failure?
                       :mission.status/ready
                       :mission.status/in-progress)}))

(defn- simulated-command-runner
  [failure?]
  (fn [{:keys [command]}]
    (if failure?
      {:exit 1
       :err "Simulated agent failure"
       :out ""}
      {:exit 0
       :err ""
       :out (str "Simulated launch: " (str/join " " command))})))

(defn- apply-simulate
  [opts {:keys [simulate? simulate-failure?]}]
  (if-not simulate?
    opts
    (let [mission-id (or (:mission/id opts) "M-SIMULATED")
          ready (simulated-ready mission-id)]
      (merge opts
             {:mission/id mission-id
              :scheduler/list-ready-fn (fn [_] ready)
              :scheduler/fetch-mission-fn (simulated-fetch simulate-failure?)
              :scheduler/command-runner (simulated-command-runner simulate-failure?)}))))

(defn- build-run-opts
  [parsed]
  (-> {:queue/tags (:queue/tags parsed)}
      (cond->
          (:mission/id parsed) (assoc :mission/id (:mission/id parsed))
          (:agent/id parsed) (assoc :agent/id (:agent/id parsed))
          (:command-template parsed) (assoc :scheduler/command-template (:command-template parsed))
          (:scheduler/log-root parsed) (assoc :scheduler/log-root (:scheduler/log-root parsed)))
      (apply-simulate parsed)))

(defn- run-analytics-hook!
  [run-result log-root]
  (when-let [mission-id (get-in run-result [:mission :mission/id])]
    (analytics/generate! {:log-root log-root
                          :mission-log-id mission-id
                          :source :analytics.source/scheduler})))

(defn- print-result-and-exit!
  [result]
  (pprint/pprint result)
  (System/exit (if (= :scheduler.status/success (:scheduler/final-status result))
                 0
                 1)))

(defn- handle-no-ready!
  [data]
  (println "No ready missions matched the provided filters.")
  (when-let [tags (:queue/tags data)]
    (println "Queue filter:" tags))
  (System/exit 0))

(defn -main
  [& args]
  (try
    (let [parsed (parse-args args)]
      (when (:help? parsed)
        (println usage)
        (System/exit 0))
      (let [opts (build-run-opts parsed)
            result (scheduler/run-once! opts)]
        (run-analytics-hook! result (or (:scheduler/log-root opts) "missions/logs"))
        (print-result-and-exit! result)))
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        (if (= (:type data) :mission.registry/not-found)
          (handle-no-ready! data)
          (do
            ;; TODO(M-20251121-410): Surface SYSTEM_SPEC §5.4 protocol owner/escalation data here
            ;; so failures automatically ping the steward/backup roles listed in the table.
            (binding [*out* *err*]
              (println "Scheduler error:" (.getMessage e))
              (when data
                (println "Details:" (pr-str data))))
            (System/exit 1)))))
    (catch Exception e
      (binding [*out* *err*]
        (println "Scheduler error:" (.getMessage e)))
      (System/exit 1))))
