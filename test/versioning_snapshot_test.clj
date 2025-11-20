(ns versioning-snapshot-test
  "Ensures spec/plan/mission version snapshots and graph traversals behave per SYSTEM_SPEC §8.1."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [intuition.sfs.actions.handlers :as handlers]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [intuition.versioning.runtime :as versioning])
  (:import
   (java.io File)
   (java.util UUID)))

(defn- temp-dir
  []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "versioning-snapshot-test-" (UUID/randomUUID)))
    .mkdirs))

(defn- delete-tree
  [^File path]
  (when (and path (.exists path))
    (doseq [child (.listFiles path)]
      (delete-tree child))
    (io/delete-file path true)))

(defn- mission-log-dir
  [repo-root mission-id]
  (io/file repo-root "missions" "logs" (bootstrap/sanitize-fragment mission-id)))

(defn- write-edn!
  [file data]
  (when-let [parent (.getParentFile file)]
    (.mkdirs parent))
  (spit file (pr-str data))
  (.getCanonicalPath file))

(defn- with-temp-repo
  [f]
  (let [root (temp-dir)
        repo-path (.getCanonicalPath root)
        specs-dir (doto (io/file root "resources" "specs") .mkdirs)
        work-plans-dir (doto (io/file root "resources" "work-plans") .mkdirs)
        repo-var #'handlers/repo-root
        specs-var #'handlers/specs-dir
        work-plans-var #'handlers/work-plans-dir
        versioning-var #'versioning/repo-root
        originals {repo-var @repo-var
                   specs-var @specs-var
                   work-plans-var @work-plans-var
                   versioning-var @versioning-var}]
    (try
      (alter-var-root repo-var (constantly repo-path))
      (alter-var-root specs-var (constantly specs-dir))
      (alter-var-root work-plans-var (constantly work-plans-dir))
      (alter-var-root versioning-var (constantly repo-path))
      (f {:repo-root repo-path})
      (finally
        (doseq [[var value] originals]
          (alter-var-root var (constantly value)))
        (delete-tree root)))))

(deftest snapshot-actions-produce-linked-graph
  (with-temp-repo
    (fn [{:keys [repo-root]}]
      (let [spec-id :spec/versioning.graph
            plan-id (UUID/randomUUID)
            spec-mission :mission/spec-versioning
            plan-mission :mission/plan-versioning
            merge-mission :mission/merge-versioning
            spec-path (io/file repo-root "resources" "specs" "versioning.edn")
            plan-path (io/file repo-root "resources" "work-plans" "versioning-plan.edn")
            spec-log (mission-log-dir repo-root spec-mission)
            plan-log (mission-log-dir repo-root plan-mission)
            merge-log (mission-log-dir repo-root merge-mission)
            validation-path (io/file spec-log "spec-validation.edn")
            publish-log (io/file spec-log (str (bootstrap/sanitize-fragment (name spec-id)) "-spec-publish.md"))
            plan-validation-path (io/file plan-log "work-plan-validation.edn")
            plan-publish-log (io/file plan-log (str (bootstrap/sanitize-fragment plan-id) "-work-plan-publish.md"))
            mission-report (io/file merge-log "report.edn")
            merge-log-file (io/file merge-log "merge" "20250101" "merge-log.edn")]
        (.mkdirs (.getParentFile merge-log-file))
        (write-edn! spec-path {:spec/id spec-id
                               :spec/title "Graph versioning"
                               :spec/summary "Trace requirements"
                               :spec/requirements ["REQ-1" "REQ-2"]
                               :spec/acceptance-criteria ["Graph recorded"]
                               :spec/test-contracts [:code/test.actions-contract]
                               :spec/constraints []
                               :spec/status :spec.status/validated
                               :spec/spec-sections ["3.3" "4.1" "8.1"]})
        (write-edn! validation-path {:spec/id spec-id
                                     :spec/requirements ["REQ-1" "REQ-2"]})
        (spit publish-log "spec publish log")
        (write-edn! plan-path {:work.plan/id plan-id
                               :work.plan/spec-id spec-id
                               :work.plan/spec-version 1
                               :work.plan/status :work.plan.status/draft
                               :work.plan/nodes [{:plan.node/id "node-1"
                                                  :plan.node/name "Trace requirement"
                                                  :plan.node/scope-requirements ["REQ-1"]
                                                  :plan.node/resources ["src/app.clj"]}]})
        (write-edn! plan-validation-path {:work.plan/id plan-id})
        (spit plan-publish-log "plan publish log")
        (write-edn! mission-report {:mission/id merge-mission
                                    :summary "Merge done"
                                    :artifacts [{:label "spec-validation" :path "missions/logs/M-20251121-407/spec-validation.edn"}]})
        (write-edn! merge-log-file {:mission/id merge-mission})
        (let [spec-result (handlers/version-snapshot-spec
                           {:config {:mission/id spec-mission
                                     :agent/id "spec-agent"
                                     :spec/id spec-id
                                     :spec/resource-path (.getCanonicalPath spec-path)
                                     :spec/validation-path (.getCanonicalPath validation-path)
                                     :spec/publish-log (.getCanonicalPath publish-log)}})
              plan-result (handlers/version-snapshot-plan
                           {:config {:mission/id plan-mission
                                     :agent/id "plan-agent"
                                     :work.plan/id plan-id
                                     :work.plan/spec-id spec-id
                                     :work-plan/resource-path (.getCanonicalPath plan-path)
                                     :work-plan/validation-path (.getCanonicalPath plan-validation-path)
                                     :work-plan/publish-log (.getCanonicalPath plan-publish-log)}})
              mission-result (handlers/version-snapshot-mission
                              {:config {:mission/id merge-mission
                                        :agent/id "merge-agent"
                                        :work.plan/id plan-id
                                        :merge/log-path (.getCanonicalPath merge-log-file)}})
              spec-snapshot (:version/snapshot spec-result)
              plan-snapshot (:version/snapshot plan-result)
              mission-snapshot (:version/snapshot mission-result)
              spec-history (versioning/snapshot-history {:subject-type :version.snapshot/spec
                                                         :subject-id spec-id
                                                         :logs-root repo-root})
              plan-history (versioning/snapshot-history {:subject-type :version.snapshot/plan
                                                         :subject-id plan-id
                                                         :logs-root repo-root})
              mission-history (versioning/snapshot-history {:subject-type :version.snapshot/mission
                                                            :subject-id merge-mission
                                                            :logs-root repo-root})
              trace (versioning/trace-requirement->snapshots {:requirement-id "REQ-1"
                                                               :logs-root repo-root})]
          (testing "snapshot outputs contain canonical paths"
            (is (.exists (io/file (:version.snapshot/path spec-result)))))
          (testing "spec snapshot artifacts recorded"
            (is (= #{:artifact/spec-validation
                     :artifact/spec-publish
                     :artifact/spec-resource}
                   (set (map :version.artifact/id (:version.snapshot/artifacts spec-snapshot))))))
          (testing "plan snapshot links to spec snapshot"
            (is (= (:version.snapshot/id spec-snapshot)
                   (:version.link/source-snapshot-id (first (:version.snapshot/links plan-snapshot))))))
          (testing "mission snapshot links to plan snapshot"
            (is (= (:version.snapshot/id plan-snapshot)
                   (:version.link/source-snapshot-id (first (:version.snapshot/links mission-snapshot))))))
          (testing "history lookups return recorded snapshots"
            (is (= [(:version.snapshot/id spec-snapshot)]
                   (map :version.snapshot/id spec-history)))
            (is (= [(:version.snapshot/id plan-snapshot)]
                   (map :version.snapshot/id plan-history)))
            (is (= [(:version.snapshot/id mission-snapshot)]
                   (map :version.snapshot/id mission-history))))
          (testing "requirement trace walks spec→plan→mission"
            (is (= [[(:version.snapshot/id spec-snapshot)
                     (:version.snapshot/id plan-snapshot)
                     (:version.snapshot/id mission-snapshot)]]
                   (:paths trace)))))))))
