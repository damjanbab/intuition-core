(ns actions-contract-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [datomic.client.api :as d]
   [intuition.sfs.actions.runtime :as actions]
   [support.datomic :as support]))

(defn- latest-action-executions
  [conn]
  (d/q '[:find ?action ?status
         :where [?e :action.execution/action ?action]
                [?e :action.execution/status ?status]]
       (d/db conn)))

(deftest config-validation-test
  (support/with-test-conn
   (fn [conn]
     (testing "config validation runs before handler"
       (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Invalid config"
            (actions/execute!
             {:conn conn
              :action/ident :action/test.run-suite
              :config {:test/suite :test.suite/core}
              :permissions #{:permission/tests.run}}))))
     (testing "permissions are enforced"
       (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Missing permissions"
            (actions/execute!
             {:conn conn
              :action/ident :action/test.run-suite
              :config {:mission/id :mission/demo
                       :test/suite :test.suite/core}
              :permissions #{}})))))))

(deftest output-validation-test
  (support/with-test-conn
   (fn [conn]
     (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Invalid output"
          (actions/execute!
           {:conn conn
            :action/ident :action/test.run-suite
            :config {:mission/id :mission/demo
                     :test/suite :test.suite/contract
                     :test/simulate-invalid-output? true}
            :permissions #{:permission/tests.run}}))))))

(deftest failure-propagation-test
  (support/with-test-conn
   (fn [conn]
     (testing "handler exception bubbles up"
       (is (= :test/simulated-error
              (try
                (actions/execute!
                 {:conn conn
                  :action/ident :action/test.run-suite
                  :config {:mission/id :mission/demo
                           :test/suite :test.suite/contract
                           :test/simulate-error? true}
                  :permissions #{:permission/tests.run}})
                (catch clojure.lang.ExceptionInfo ex
                  (:type (ex-data ex)))))))
     (testing "execution log recorded"
       (let [result (actions/execute!
                     {:conn conn
                      :action/ident :action/docs.sync
                      :config {:mission/id :mission/demo
                               :docs/paths ["docs/M-02.md"]}
                      :permissions #{:permission/docs.write}})
             executions (latest-action-executions conn)]
         (is (= :action/docs.sync (:action/ident result)))
         (is (some (fn [[action status]]
                     (and (= :action/docs.sync action)
                          (= :status/succeeded status)))
                   executions)))))))
