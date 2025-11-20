(ns dev-tools-smoke-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.list-missions :as dev.list-missions]
   [dev.run-mission :as dev.run-mission]
   [dev.run-protocol :as dev.run-protocol]
   [intuition.datomic :as db]
   [intuition.dictionary :as dictionary]
   [intuition.sfs.missions.runtime :as missions]
   [intuition.sfs.protocols.runtime :as protocols]))

(deftest list-missions-smoke
  ;; SYSTEM_SPEC §§3.3–3.6 require operator helpers to have traceable coverage.
  (testing "dev.list-missions prints grouped ready missions when runtime returns stub data"
    (with-redefs [missions/list-ready-missions
                  (fn [_]
                    {:mission/list [{:mission/id "M-TEST"
                                     :mission/title "Sample"
                                     :mission/priority :mission.priority/p1
                                     :mission/queue-tags [:mission.queue/review]}]
                     :mission/active-queues {:mission.queue/review true}})]
      (let [output (with-out-str (dev.list-missions/print-ready! [:mission.queue/review]))]
        (is (string? output))
        (is (re-find #"M-TEST" output))))))

(deftest run-mission-smoke
  ;; SYSTEM_SPEC §5.1 mandates regression tests for mission lifecycle helpers.
  (testing "dev.run-mission returns stubbed mission lifecycle maps"
    (with-redefs [missions/start!
                  (fn [_]
                    {:mission/status :mission.status/in-progress
                     :context {:lint {:status :ok}}})
                  missions/transition!
                  (fn [_]
                    {:mission/status :mission.status/awaiting-review
                     :context {:tests {:status :ok}}})]
      (let [result (dev.run-mission/run-mission!)]
        (is (map? result))
        (is (map? (:start result)))
        (is (map? (:transition result)))
        (is (= :mission.status/awaiting-review
               (get-in result [:transition :mission/status])))))))

(deftest run-protocol-smoke
  ;; SYSTEM_SPEC §§3.3–3.6 + §5.1 insist protocol helpers avoid regressions.
  (testing "dev.run-protocol delegates to protocols/run! without touching Datomic"
    (with-redefs [db/ensure-db!
                  (fn [& _]
                    :test-conn)
                  dictionary/seed-all!
                  (fn [_] :seeded)
                  protocols/run!
                  (fn [{:keys [context]}]
                    {:status :ok
                     :mission (:mission/id context)
                     :log "protocol smoke"})]
      (let [result (dev.run-protocol/run-mission-standard!)]
        (is (map? result))
        (is (= {:status :ok
                :mission :mission/sample
                :log "protocol smoke"}
               result))))))
