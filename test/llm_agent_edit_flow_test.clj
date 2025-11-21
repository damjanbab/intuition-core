(ns llm-agent-edit-flow-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [intuition.gateway.orchestrator :as orchestrator]
   [support.datomic :as support]))

(def mission-id "M-20251121-822")
(def bundle-path "missions/logs/M-20251121-822/llm-edit-context-bundle.edn")
(def manifest-path "missions/logs/M-20251121-822/edit-flow/manifest.edn")
(def run-log-path "missions/logs/M-20251121-822/edit-flow/run.log")
(def llm-log-path "missions/logs/M-20251121-822/llm/code-proposals.edn")
(def llm-test-doc-log "missions/logs/M-20251121-822/llm/test-doc-suggestions.edn")
(def default-materialization-log "missions/logs/m-20251121-822/code-materialization.edn")
(def sandbox-root "tmp/missions/M-20251121-822/edit-sandbox")

(defn- delete-tree!
  [^java.io.File file]
  (when (and file (.exists file))
    (doseq [child (.listFiles file)]
      (delete-tree! child))
    (io/delete-file file true)))

(defn- cleanup!
  []
  (doseq [path [manifest-path run-log-path llm-log-path llm-test-doc-log default-materialization-log]]
    (let [file (io/file path)]
      (when (.exists file)
        (io/delete-file file true))))
  (delete-tree! (io/file sandbox-root)))

(defn- fake-self-report
  []
  {:confidence :high
   :reason "llm-agent-edit-flow-test"
   :assumptions []
   :uncertainties []})

(defn- fake-proposals
  []
  [{:code.proposal/type :proposal.type/code-definition
    :code.proposal/op :proposal.op/add
    :code.proposal/payload {:code.definition/ident :code/agent-edit.demo
                            :code.definition/name "Agent edit demo runtime"
                            :code.definition/type :code.type/dev
                            :code.definition/spec-sections ["3.3" "3.4" "4.7" "5" "6" "9" "11"]
                            :code.definition/paths ["dev/generated/{{IDENT}}.clj"]
                            :code.definition/dependencies [:code/intuition.datomic]
                            :code.definition/validators [:validator/ns-loads]
                            :code.definition/tests []}}
   {:code.proposal/type :proposal.type/spec-fragment
    :code.proposal/op :proposal.op/add
    :code.proposal/payload {:spec/id :spec/agent-edit-demo
                            :spec/title "Agent edit flow demo"
                            :spec/summary "Sample spec fragment for the edit-graph pipeline"
                            :spec/requirements ["REQ-EDIT-GRAPH-1" "REQ-EDIT-GRAPH-2"]
                            :spec/acceptance-criteria ["AC-PROPOSAL-VALIDATED" "AC-GENERATED-CODE" "AC-CI-PASSED"]
                            :spec/test-contracts [:code/sample.validator]
                            :spec/spec-sections ["3.3" "3.4" "3.5" "3.6" "4.7" "5" "6" "9" "11"]}}])

(deftest llm-proposals-apply
  (testing "LLM-driven proposals can drive the edit-graph flow"
    (support/with-test-conn
      (fn [conn]
        (cleanup!)
        (let [fake-response (fn [_]
                              {:status :response.status/ok
                               :payload {:code/proposals (fake-proposals)}
                               :self-report (fake-self-report)})
              result (orchestrator/edit-graph! {:mission/id mission-id
                                                :context/bundle-path bundle-path
                                                :agent/id "llm-demo"
                                                :llm.code-proposal/mode :apply
                                                :llm.code-proposal/fake-response-fn fake-response
                                                :conn conn})
              manifest (edn/read-string (slurp manifest-path))
              llm-meta (:llm/code-proposal manifest)]
          (is (= :status/ok (:action/status result)))
          (is (= :status/ok (:action/status manifest)))
          (is (= :llm.status/applied (:llm.code-proposal/status llm-meta)))
          (is (= :llm.status/applied (get llm-meta :llm.code-proposal/status)))
          (is (seq (:code.definition/transacted manifest)))
          (is (.exists (io/file llm-log-path)) "LLM proposals log should exist")
          (cleanup!))))))

(deftest llm-proposals-abort-fallbacks-to-deterministic
  (testing "Abort status keeps deterministic proposals intact"
    (support/with-test-conn
      (fn [conn]
        (cleanup!)
        (let [abort-response (fn [_]
                               {:status :response.status/ok
                                :payload {:status :abort
                                          :reason "abort/coverage-check"}
                                :self-report (fake-self-report)})
              deterministic (edn/read-string (slurp "missions/logs/M-20251121-822/agent-proposals.edn"))
              result (orchestrator/edit-graph! {:mission/id mission-id
                                                :context/bundle-path bundle-path
                                                :agent/id "llm-abort"
                                                :code.proposal/proposals deterministic
                                                :llm.code-proposal/mode :apply
                                                :llm.code-proposal/fake-response-fn abort-response
                                                :conn conn})
              manifest (edn/read-string (slurp manifest-path))
              llm-meta (:llm/code-proposal manifest)]
          (is (= :status/ok (:action/status result)))
          (is (= :llm.status/abort (:llm.code-proposal/status llm-meta)))
          (is (seq (:code.definition/transacted manifest)))
          (cleanup!))))))
