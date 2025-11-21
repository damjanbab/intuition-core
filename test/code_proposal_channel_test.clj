(ns code-proposal-channel-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [datomic.client.api :as d]
   [intuition.sfs.actions.runtime :as actions]
   [support.datomic :as support]))

(def ^:private mission-id "M-TEST-CODE-PROPOSAL")
(def ^:private agent-id "agent/tester")

(defn- proposal-path
  []
  "tmp/code-proposal-demo/should-not-exist.clj")

(def ^:private base-proposals
  [{:code.proposal/type :proposal.type/code-definition
    :code.proposal/op :proposal.op/add
    :code.proposal/payload {:code.definition/ident :code/test.new
                            :code.definition/name "Test code definition"
                            :code.definition/type :code.type/runtime
                            :code.definition/spec-sections ["4.7"]
                            :code.definition/paths [(proposal-path)]
                            :code.definition/dependencies [:code/intuition.datomic]}}
   {:code.proposal/type :proposal.type/spec-fragment
    :code.proposal/payload {:spec/id :spec/code-proposal-demo
                            :spec/requirements ["Datomic-first code proposal channel"]
                            :spec/spec-sections ["3.3" "4.7"]}}])

(deftest validation-rejects-bad-proposals
  (support/with-test-conn
   (fn [conn]
     (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"missing required"
          (actions/execute!
           {:conn conn
            :action/ident :action/code.proposal.validate
            :config {:mission/id mission-id
                     :agent/id agent-id
                     :code.proposal/proposals [{:code.proposal/type :proposal.type/code-definition
                                                :code.proposal/payload {}}]}
            :permissions #{:permission/code.propose}})))
     (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Invalid config"
          (actions/execute!
           {:conn conn
            :action/ident :action/code.proposal.validate
            :config {:mission/id mission-id
                     :agent/id agent-id
                     :code.proposal/proposals [{:code.proposal/type :proposal.type/unknown
                                                :code.proposal/payload {}}]}
            :permissions #{:permission/code.propose}}))))))

(deftest apply-code-proposals
  (support/with-test-conn
   (fn [conn]
     (let [validation (actions/execute!
                       {:conn conn
                        :action/ident :action/code.proposal.validate
                        :config {:mission/id mission-id
                                 :agent/id agent-id
                                 :code.proposal/proposals base-proposals}
                        :permissions #{:permission/code.propose}})
           validation-log (get-in validation [:result :code.proposal/log-path])]
       (is (.exists (io/file validation-log)))
       (let [apply-result (actions/execute!
                           {:conn conn
                            :action/ident :action/code.proposal.apply
                            :config {:mission/id mission-id
                                     :agent/id agent-id
                                     :code.proposal/proposals base-proposals
                                     :code.proposal/validation-log validation-log}
                            :permissions #{:permission/code.proposal.apply}})
             result (:result apply-result)
             proposals-out (:code.proposal/proposals result)]
         (is (= :status/ok (:action/status result)))
         (is (seq proposals-out))
         (is (every? #(= :code.proposal.status/applied (:code.proposal/status %)) proposals-out))
         (is (.exists (io/file (:code.proposal/log-path result))))
         (is (.exists (io/file (:version.snapshot/path result))))
         (is (not (.exists (io/file (proposal-path)))))
         (is (seq (:code.proposal/artifacts result)))
         (let [db (d/db conn)
               stored (d/q '[:find ?ident ?status
                             :where [?e :code.proposal/ident ?ident]
                                    [?e :code.proposal/status ?status]]
                           db)]
           (is (some (fn [[ident status]]
                       (and (= "code/test.new" ident)
                            (= :code.proposal.status/applied status)))
                     stored))))))))
