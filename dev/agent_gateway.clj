(ns dev.agent-gateway
  "CLI gateway that routes mission interactions (including orchestrated runs) through governed runtimes.
  Commands accept an EDN map payload.

  Examples:
  clojure -M:dev -m dev.agent-gateway fetch '{:mission/id \"M-20251121-405\"}'
  clojure -M:dev -m dev.agent-gateway plan '{:mission/id \"M-20251121-405\" :agent/id \"codex\" :plan/requirements [:req/trace] :plan/notes \"Break down work\"}'
  clojure -M:dev -m dev.agent-gateway run-mission '{:mission/id \"M-20251121-801\" :context/bundle-path \"missions/logs/M-20251121-801/context-bundle.edn\" :agent/id \"codex\"}'
  clojure -M:dev -m dev.agent-gateway edit-graph '{:mission/id \"M-20251121-814\" :context/bundle-path \"missions/logs/M-20251121-814/agent-edit-context-bundle.edn\" :agent/id \"codex\"}'"
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.pprint :as pprint]
   [intuition.gateway.orchestrator :as orchestrator]
   [intuition.sfs.missions.runtime :as missions]))

(defn- parse-payload
  [payload]
  (if (str/blank? payload)
    {}
    (edn/read-string payload)))

(defn- require-bundle-path
  [payload]
  (let [bundle-path (or (:context/bundle-path payload)
                        (:bundle/path payload))]
    (when (str/blank? (str bundle-path))
      (throw (ex-info "context/bundle-path is required for run-mission"
                      {:field :context/bundle-path})))
    bundle-path))

(defn- run-mission
  [payload]
  (let [bundle-path (require-bundle-path payload)
        payload (assoc payload :context/bundle-path bundle-path)]
    (orchestrator/run-mission! payload)))

(defn- edit-graph
  [payload]
  (let [bundle-path (require-bundle-path payload)
        payload (assoc payload :context/bundle-path bundle-path)]
    (orchestrator/edit-graph! payload)))

(defn- dispatch
  [command payload]
  (case command
    "fetch" (missions/get-mission payload)
    "plan" (missions/plan-step! payload)
    "edit" (missions/edit-step! payload)
    "tool-run" (missions/tool-run-step! payload)
    "decision" (missions/decision-step! payload)
    "artifacts" (missions/list-step-artifacts payload)
    "run-mission" (run-mission payload)
    "edit-graph" (edit-graph payload)
    (throw (ex-info "Unknown command"
                    {:command command
                     :commands #{"fetch" "plan" "edit" "tool-run" "decision" "artifacts" "run-mission" "edit-graph"}}))))

(defn -main
  [& args]
  (let [[command payload-str] args]
    (when (str/blank? command)
      (binding [*out* *err*]
        (println "Agent Gateway requires a command (fetch|plan|edit|tool-run|decision|artifacts|run-mission|edit-graph)."))
      (System/exit 1))
    (try
      (-> (dispatch command (parse-payload (or payload-str "{}")))
          pprint/pprint)
      (catch Exception e
        (binding [*out* *err*]
          (println "Agent Gateway error:" (.getMessage e))
          (when-let [data (ex-data e)]
            (println "Details:" (pr-str data))))
        (System/exit 1)))))
