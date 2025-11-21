(ns agent-edit-flow-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [datomic.client.api :as d]
   [intuition.gateway.orchestrator :as orchestrator]
   [support.datomic :as support]))

(def mission-id "M-20251121-814")
(def bundle-path "missions/logs/M-20251121-814/agent-edit-context-bundle.edn")
(def proposals-path "missions/logs/M-20251121-814/agent-proposals.edn")
(def manifest-path "missions/logs/M-20251121-814/edit-flow/manifest.edn")
(def run-log-path "missions/logs/M-20251121-814/edit-flow/run.log")
(def default-materialization-log "missions/logs/m-20251121-814/code-materialization.edn")
(def sandbox-root "tmp/missions/M-20251121-814/edit-sandbox")

(defn- delete-tree!
  [^java.io.File file]
  (when (and file (.exists file))
    (doseq [child (.listFiles file)]
      (delete-tree! child))
    (io/delete-file file true)))

(defn- canonical
  [path]
  (.getCanonicalPath (io/file path)))

(deftest agent-edit-flow-end-to-end
  (testing "edit-graph validates proposals, applies Datomic-only changes, and materializes code in the sandbox"
    (support/with-test-conn
     (fn [conn]
       (let [manifest-file (io/file manifest-path)
             run-log-file (io/file run-log-path)
             sandbox (io/file sandbox-root)]
         (doseq [f [manifest-file run-log-file (io/file default-materialization-log)]]
           (when (.exists f) (io/delete-file f true)))
         (delete-tree! sandbox)
         (is (.exists (io/file bundle-path)) "context bundle should be present for edit-graph smoke")
         (is (.exists (io/file proposals-path)) "agent proposals fixture should be present")
         (let [result (orchestrator/edit-graph! {:mission/id mission-id
                                                 :context/bundle-path bundle-path
                                                 :agent/id "test-agent"
                                                 :conn conn})
              manifest (edn/read-string (slurp manifest-file))
              artifact-labels (set (map :artifact/label (:artifacts manifest)))
              artifacts-by-label (into {} (map (juxt :artifact/label :artifact/path))
                                       (:artifacts manifest))
              materialize-path (or (artifacts-by-label "Code materialization log")
                                   default-materialization-log)
              materialize-file (io/file materialize-path)
              db (d/db conn)
              proposal-rows (d/q '[:find ?ident ?status
                                   :where [?e :code.proposal/ident ?ident]
                                          [?e :code.proposal/status ?status]]
                                  db)
               definition-rows (d/q '[:find ?ident
                                      :where [?e :code.definition/ident ?ident]]
                                    db)
              materialize-log-edn (edn/read-string (slurp materialize-file))
              files (->> (:code.materialize/runs materialize-log-edn)
                         (mapcat :code.materialize/definitions)
                         (mapcat :code.materialize/files))]
           (is (= :status/ok (:action/status result)))
           (is (= :status/ok (:action/status manifest)))
           (is (.exists manifest-file) "manifest should be written for edit-graph")
           (is (.exists run-log-file) "run log should be written for edit-graph")
           (is (.exists materialize-file) "code-materialization log should exist")
           (is (contains? artifact-labels "Proposal validation"))
           (is (contains? artifact-labels "Proposal apply"))
           (is (contains? artifact-labels "Code materialization log"))
           (is (some (fn [[ident status]]
                       (and (= "code/agent-edit.demo" (str ident))
                            (= :code.proposal.status/applied status)))
                     proposal-rows)
               "applied proposal should be persisted in Datomic")
           (is (some (fn [[ident]] (= :code/agent-edit.demo ident)) definition-rows)
               "code definition should be transacted for generation")
           (is (seq files) "code materialization should emit sandbox files")
           (is (every? #(str/starts-with? (:code.materialize/file %) (canonical sandbox-root)) files)
               "generated files must remain inside the sandbox")
           (is (= [:code/agent-edit.demo]
                  (vec (:code.definition/transacted manifest)))
               "manifest should surface the transacted code definitions")
           (delete-tree! sandbox)
           (is (not (.exists sandbox))
               "sandbox cleaned after assertions")))))))
