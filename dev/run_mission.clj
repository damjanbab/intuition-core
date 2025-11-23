(ns dev.run-mission
  "Deprecated manual runner. Forward requests to the Agent Gateway (context-bundle driven) and refuse direct execution
  per SYSTEM_SPEC §§2.1–2.2, §§3.3–3.6, §5, §6, and §9. Use `clojure -M:dev -m dev.agent-gateway run-mission` with a
  context bundle instead."
  (:require
   [dev.agent-gateway :as gateway]
   [intuition.sfs.missions.runtime :as missions]))

(def sample-mission-id "M-20251117-001")
(def sample-agent "dev-agent")

(defn- warn
  []
  (binding [*out* *err*]
    (println "WARNING: dev.run-mission is deprecated. Use dev.agent-gateway run-mission with a context bundle.")
    (println "SYSTEM_SPEC §§2.1–2.2, §§3.3–3.6, §5, §6, §9 mandate scheduler→gateway only.")))

(defn run-mission!
  []
  (warn)
  (let [start-result (missions/start! {:mission/id sample-mission-id
                                       :agent/id sample-agent})
        transition (missions/transition! {:mission/id sample-mission-id
                                          :agent/id sample-agent
                                          :target :mission.status/awaiting-review
                                          :tests {:suite :test.suite/contract
                                                  :paths ["test/actions_contract_test.clj"]}
                                          :docs {:paths ["SYSTEM_SPEC.md"]}
                                          :system-map {:entities [:action/mission.validate
                                                                  :action/mission.transition]}})]
    (println "Mission start result:")
    (prn start-result)
    (println "\nMission transition result:")
    (prn transition)
    {:start start-result
     :transition transition}))

(defn -main
  [& args]
  (if (seq args)
    (do
      (warn)
      (apply gateway/-main args))
    (run-mission!)))
