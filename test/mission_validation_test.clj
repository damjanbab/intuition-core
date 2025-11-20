(ns mission-validation-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [intuition.sfs.missions.state-machine :as sm]))

(def base-mission
  {:mission/id "M-TEST"
   :mission/title "Validation harness"
   :mission/summary "Covers §3.1 metadata rules."
   :mission/category :mission.category/governance
   :mission/priority :mission.priority/p1
   :mission/status :mission.status/draft
   :mission/protocol :protocol/mission-standard
   :mission/protocol-version 1
   :mission/scope "{:paths [\"src\"]}"
   :mission/prerequisites ["M-20251117-001"]
   :mission/deliverables ["resources/dictionary/missions.edn" "dev/run-mission.clj"]
   :mission/work-tracks [:work-track/planning :work-track/code]
   :mission/tests ["test/actions_contract_test.clj"]
   :mission/spec-section :spec/phase-1
   :mission/owner :role/steward})

(def context {:known-missions #{"M-20251117-001" "M-20251117-002"}})

(deftest mission-must-have-scope-and-tracks
  (testing "missing scope rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Mission scope is required"
         (sm/validate! (assoc base-mission :mission/scope "") context))))
  (testing "missing work-type matrix rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Mission requires at least one work track"
         (sm/validate! (assoc base-mission :mission/work-tracks []) context)))))

(deftest mission-deliverables-unique
  (testing "duplicate deliverables cause failure"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Mission deliverables must be unique"
         (sm/validate! (assoc base-mission :mission/deliverables ["docs" "docs"]) context)))))

(deftest prerequisites-must-exist
  (testing "invalid prerequisite rejected"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Mission prerequisite does not exist"
         (sm/validate! (assoc base-mission :mission/prerequisites ["M-BOGUS"]) context)))))
