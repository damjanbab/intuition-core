(ns protocols-sequence-test
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is]]
   [datomic.client.api :as d]
   [intuition.sfs.protocols.runtime :as protocols]
   [support.datomic :as support]))

(def permissions
  #{:permission/env.bootstrap
    :permission/locks.manage
    :permission/tests.run
    :permission/docs.write
    :permission/system-map.write})

(def base-context
  {:mission/id :mission/demo
   :agent/id "tester"
   :workspace/root "work/m02"
   :tests/enabled? true
   :tests/suite :test.suite/contract
   :tests/paths ["test/actions_contract_test.clj"]
   :tests/error-mode :fail-fast
   :docs/paths ["docs/M-02.md"]
   :system-map/entities [:action/test.run-suite]
   :codetype/paths ["src/intuition/sfs/missions/runtime.clj"]})

(deftest mission-standard-sequence
  (support/with-test-conn
   (fn [conn]
     (let [result (protocols/run!
                   {:conn conn
                    :protocol/ident :protocol/mission-standard
                    :context base-context
                    :permissions permissions})]
       (is (= :status/succeeded (:status result)))
      (is (= [:step/mission-sync
              :step/acquire-locks
              :step/code-materialize
              :step/lint
              :step/run-tests
              :step/codetype
              :step/docs
               :step/system-map
               :step/release-locks]
              (map :id (:steps result))))
       (is (= #{:work-track/tests :work-track/docs :work-track/system-map :work-track/code}
              (:work-tracks result)))
       (let [status (-> (d/q '[:find ?status
                               :where [?e :protocol.run/ident :protocol/mission-standard]
                                      [?e :protocol.run/status ?status]]
                             (d/db conn))
                        ffirst)]
         (is (= :status/succeeded status)))))))

(deftest branching-requires-work-tracks
  (support/with-test-conn
   (fn [conn]
     (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"missing required work tracks"
          (protocols/run!
           {:conn conn
            :protocol/ident :protocol/mission-standard
            :context (assoc base-context :tests/enabled? false)
            :permissions permissions}))))))

(deftest dangling-locks-are-detected
  (support/with-test-conn
   (fn [conn]
     (let [entity (d/pull (d/db conn)
                          [:protocol/steps]
                          [:protocol/ident :protocol/mission-standard])
           steps (edn/read-string (:protocol/steps entity))
           without-release (->> steps
                                 (remove #(= :step/release-locks (:step/id %)))
                                 vec)
           truncated (pr-str without-release)]
       (d/transact conn {:tx-data [{:protocol/ident :protocol/mission-standard
                                    :protocol/steps truncated}]})
       (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"dangling locks"
            (protocols/run!
             {:conn conn
              :protocol/ident :protocol/mission-standard
              :context base-context
              :permissions permissions})))))))
