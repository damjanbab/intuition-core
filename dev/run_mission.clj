(ns dev.run-mission
  "Quick helper that exercises the mission lifecycle runtime so stewards can see the happy path."
  (:require
   [intuition.sfs.missions.runtime :as missions]))

(def sample-mission-id "M-20251117-001")
(def sample-agent "dev-agent")

(defn run-mission!
  []
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
  [& _]
  (run-mission!))
