(ns dev.agent-gateway
  "Simple CLI stub that routes all mission interactions through the Agent Gateway.
  Commands accept an EDN map payload.

  Examples:
  clojure -M:dev -m dev.agent-gateway fetch '{:mission/id \"M-20251121-405\"}'
  clojure -M:dev -m dev.agent-gateway plan '{:mission/id \"M-20251121-405\" :agent/id \"codex\" :plan/requirements [:req/trace] :plan/notes \"Break down work\"}'"
  (:require
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [intuition.sfs.missions.runtime :as missions]))

(defn- parse-payload
  [payload]
  (if (str/blank? payload)
    {}
    (edn/read-string payload)))

(defn- dispatch
  [command payload]
  (case command
    "fetch" (missions/get-mission payload)
    "plan" (missions/plan-step! payload)
    "edit" (missions/edit-step! payload)
    "tool-run" (missions/tool-run-step! payload)
    "decision" (missions/decision-step! payload)
    "artifacts" (missions/list-step-artifacts payload)
    (throw (ex-info "Unknown command"
                    {:command command
                     :commands #{"fetch" "plan" "edit" "tool-run" "decision" "artifacts"}}))))

(defn -main
  [& args]
  (let [[command payload-str] args]
    (when (str/blank? command)
      (binding [*out* *err*]
        (println "Agent Gateway requires a command (fetch|plan|edit|tool-run|decision|artifacts)."))
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
