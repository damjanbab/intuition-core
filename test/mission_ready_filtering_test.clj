(ns mission-ready-filtering-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [datomic.client.api :as d]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [intuition.sfs.missions.registry :as registry]
   [intuition.sfs.missions.runtime :as missions]
   [support.datomic :as support]))

(def registry-missions
  [{:mission/id "M-CORE-BLOCKED"
    :mission/title "Core backlog"
    :mission/summary "Covers dictionary upgrades"
    :mission/category :mission.category/governance
    :mission/priority :mission.priority/p0
    :mission/status :mission.status/ready
    :mission/queue-tags [:mission.queue/core]
    :mission/work-tracks [:work-track/planning]
    :mission/tests []}
   {:mission/id "M-SPEC-READY"
    :mission/title "Spec sync"
    :mission/summary "Docs refresh"
    :mission/category :mission.category/spec
    :mission/priority :mission.priority/p1
    :mission/status :mission.status/ready
    :mission/queue-tags [:mission.queue/spec-sync]
    :mission/work-tracks [:work-track/doc]
    :mission/tests []}
   {:mission/id "M-INPROGRESS"
    :mission/title "Active core run"
    :mission/summary "Simulation of ongoing work"
    :mission/category :mission.category/governance
    :mission/priority :mission.priority/p0
    :mission/status :mission.status/in-progress
    :mission/queue-tags [:mission.queue/core]
    :mission/work-tracks [:work-track/code]
    :mission/tests []}])

(deftest ready-missions-apply-queue-filters
  (testing "priority ordering prefers higher-ranked queues"
    (let [ready (registry/ready-missions {:missions registry-missions})
          ids (map :mission/id ready)
          blocked (first ready)]
      (is (= ["M-CORE-BLOCKED" "M-SPEC-READY"] ids))
      (is (:mission/blocked? blocked))
      (is (= [:mission.queue/core] (:mission/conflicts blocked)))))
  (testing "queue filters trim the list"
    (let [filtered (registry/ready-missions {:missions registry-missions
                                             :queue-tags [:mission.queue/spec-sync]})]
      (is (= ["M-SPEC-READY"] (map :mission/id filtered))))))

(def base-runtime-mission
  {:mission/title "Runtime queue harness"
   :mission/summary "Ensures queue-aware auto-start works"
   :mission/category :mission.category/governance
   :mission/priority :mission.priority/p1
   :mission/status :mission.status/ready
   :mission/protocol :protocol/mission-standard
   :mission/protocol-version 1
   :mission/scope "{:paths [\"src\"]}"
   :mission/prerequisites []
   :mission/deliverables ["README.md"]
   :mission/work-tracks [:work-track/planning :work-track/code]
   :mission/tests ["test/actions_contract_test.clj"]
   :mission/spec-section :spec/phase-1
   :mission/owner :role/steward})

(defn- queue-mission
  [id status queue-tags priority]
  (assoc base-runtime-mission
         :mission/id id
         :mission/status status
         :mission/queue-tags queue-tags
         :mission/priority priority))

(defn- delete-tree!
  [^java.io.File f]
  (when (and f (.exists f))
    (doseq [child (.listFiles f)]
      (delete-tree! child))
    (io/delete-file f true)))

(defn- cleanup-runtime-files!
  [mission-id agent-id]
  (let [fragment (bootstrap/sanitize-fragment mission-id)]
    (delete-tree! (io/file "tmp" "missions" fragment agent-id))
    (delete-tree! (io/file "missions" "logs" fragment))))

(deftest start-mission-respects-conflicts
  (support/with-test-conn
    (fn [conn]
      (missions/prepare-conn! conn)
      (d/transact conn {:tx-data [(queue-mission "M-READY-CORE" :mission.status/ready [:mission.queue/core] :mission.priority/p0)
                                  (queue-mission "M-READY-SPEC" :mission.status/ready [:mission.queue/spec-sync] :mission.priority/p1)
                                  (queue-mission "M-RUNNING" :mission.status/in-progress [:mission.queue/core] :mission.priority/p1)]})
      (let [{:mission/keys [list]} (missions/list-ready-missions {:conn conn})
            core-entry (first list)
            agent-id "queue-agent"]
        (is (:mission/blocked? core-entry))
        (let [result (missions/start-mission! {:agent/id agent-id
                                               :conn conn})
              selection (:mission/selection result)
              start (:mission/start result)]
          (try
            (let [status (-> (d/pull (d/db conn)
                                     [:mission/status]
                                     [:mission/id (:mission/id selection)])
                             :mission/status)]
              (is (= "M-READY-SPEC" (:mission/id selection)))
              (is (= :mission.status/in-progress status))
              (is (= :mission.status/in-progress (:mission/status start)))
              (is (some? (:sandbox start))))
            (finally
              (cleanup-runtime-files! (:mission/id selection) agent-id))))))))
