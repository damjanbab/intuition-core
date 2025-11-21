(ns plan-generator-integration-test
  "Integration coverage for the spec→plan generator: generation log, validation, locks, and snapshots."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [intuition.sfs.actions.handlers :as handlers]
   [intuition.versioning.runtime :as versioning])
  (:import
   (java.io File)
   (java.util UUID)))

(def heuristics-source "missions/logs/M-20251121-701/planner-heuristics.edn")
(def code-types-source "resources/dictionary/code_types.edn")

(defn- temp-dir
  []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "plan-generator-integration-" (UUID/randomUUID)))
    .mkdirs))

(defn- delete-tree
  [^File path]
  (when (and path (.exists path))
    (doseq [child (.listFiles path)]
      (delete-tree child))
    (io/delete-file path true)))

(defn- write-edn!
  [repo-root relative-path data]
  (let [file (io/file repo-root relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file (pr-str data))
    (.getCanonicalPath file)))

(defn- copy-heuristics!
  [repo-root]
  (let [target (io/file repo-root "missions" "logs" "M-20251121-702" "planner-heuristics.edn")]
    (.mkdirs (.getParentFile target))
    (spit target (slurp (io/file heuristics-source)))
    target))

(defn- copy-code-types!
  [repo-root]
  (let [target (io/file repo-root "resources" "dictionary" "code_types.edn")]
    (.mkdirs (.getParentFile target))
    (spit target (slurp (io/file code-types-source)))
    target))

(defn- with-temp-repo
  [f]
  (let [root (temp-dir)
        repo-path (.getCanonicalPath root)
        specs-root (doto (io/file root "resources" "specs") .mkdirs)
        plans-root (doto (io/file root "resources" "work-plans") .mkdirs)
        repo-var #'handlers/repo-root
        specs-var #'handlers/specs-dir
        plans-var #'handlers/work-plans-dir
        versioning-var #'versioning/repo-root
        originals {repo-var @repo-var
                   specs-var @specs-var
                   plans-var @plans-var
                   versioning-var @versioning-var}]
    (try
      (alter-var-root repo-var (constantly repo-path))
      (alter-var-root specs-var (constantly specs-root))
      (alter-var-root plans-var (constantly plans-root))
      (alter-var-root versioning-var (constantly repo-path))
      (copy-code-types! repo-path)
      (f {:repo-root repo-path
          :heuristics-path (.getCanonicalPath (copy-heuristics! repo-path))})
      (finally
        (doseq [[var value] originals]
          (alter-var-root var (constantly value)))
        (delete-tree root)))))

(deftest spec-plan-generate-runs-validation-and-snapshots
  (with-temp-repo
    (fn [{:keys [repo-root heuristics-path]}]
      (let [spec-mission :mission/spec-planner
            plan-mission :mission/plan-generator
            planner-agent "planner"
            spec-id :spec/planner.integration
            spec-input (write-edn! repo-root "tmp/spec.edn"
                                   {:spec/id spec-id
                                    :spec/title "Planner integration spec"
                                    :spec/summary "Exercise the planner end-to-end."
                                    :spec/requirements ["REQ-plan-a" "REQ-plan-b"]
                                    :spec/acceptance-criteria ["Accept-A" "Accept-B"]
                                    :spec/constraints []
                                    :spec/status :spec.status/draft
                                    :spec/spec-sections ["3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "9"]
                                    :spec/artifacts []})
            capture (handlers/spec-capture {:config {:mission/id spec-mission
                                                     :agent/id planner-agent
                                                     :spec/input-path spec-input}})
            validate (handlers/spec-validate {:config {:mission/id spec-mission
                                                       :agent/id planner-agent
                                                       :spec/id (:spec/id capture)
                                                       :spec/resource-path (:spec/resource-path capture)}})
            publish (handlers/spec-publish {:config {:mission/id spec-mission
                                                     :agent/id planner-agent
                                                     :spec/id (:spec/id capture)}})
            spec-snapshot (handlers/version-snapshot-spec
                           {:config {:mission/id spec-mission
                                     :agent/id planner-agent
                                     :spec/id (:spec/id capture)
                                     :spec/resource-path (:spec/resource-path capture)
                                     :spec/validation-path (:spec/validation-path validate)
                                     :spec/publish-log (:spec/publish-log publish)}})
            plan-output (io/file repo-root "missions" "logs" "M-20251121-702" "generated-plan.edn")
            generation-log (io/file repo-root "missions" "logs" "M-20251121-702" "planner-generation-log.edn")
            result (handlers/spec-plan-generate
                    {:config {:mission/id plan-mission
                              :agent/id planner-agent
                              :spec/id spec-id
                              :spec/version 1
                              :spec/resource-path (:spec/resource-path capture)
                              :planner/heuristics-path heuristics-path
                              :plan/output-path (.getCanonicalPath plan-output)
                              :planner/generation-log-path (.getCanonicalPath generation-log)}})
            plan-data (edn/read-string (slurp (:work-plan/resource-path result)))
            generation-log-data (edn/read-string (slurp (:plan.generation/log-path result)))
            plan-snapshot (first (versioning/snapshot-history {:subject-type :version.snapshot/plan
                                                                :subject-id (:work.plan/id plan-data)
                                                                :logs-root repo-root}))]
        (testing "artifacts are written to mission log"
          (is (.exists (io/file (:plan.generation/log-path result))))
          (is (.exists (io/file (:work-plan/resource-path result))))
          (is (.exists (io/file (:work-plan/log-path result))))
          (is (.exists (io/file (:work-plan/validation-path result))))
          (is (.exists (io/file (:work-plan/publish-log result))))
          (is (.exists (io/file (:version.snapshot/path result)))))
        (testing "generation log includes heuristics and coverage"
          (is (= :planner/default-v1 (:plan.generation/heuristics-id generation-log-data)))
          (is (= (:plan.generation/work-plan-id result)
                 (:plan.generation/work-plan-id generation-log-data)))
          (is (seq (:plan.generation/coverage generation-log-data))))
        (testing "plan nodes/edges/coverage cover requirements and form DAG"
          (is (= #{"REQ-plan-a" "REQ-plan-b"}
                 (set (map :coverage.row/requirement-id (:work.plan/coverage plan-data)))))
          (is (= (dec (count (:work.plan/nodes plan-data)))
                 (count (:work.plan/edges plan-data))))
          (is (every? #(str/starts-with? % "src/")
                      (mapcat :plan.node/resources (:work.plan/nodes plan-data)))))
        (testing "code types inferred from heuristics without spec hints"
          (is (every? (comp seq :plan.node/code-types) (:work.plan/nodes plan-data)))
          (is (= #{:code.type/runtime}
                 (set (mapcat :plan.node/code-types (:work.plan/nodes plan-data)))))
          (is (= #{:code/sample.validator}
                 (set (mapcat :coverage.row/test-contracts (:work.plan/coverage plan-data))))))
        (testing "plan validation + snapshot link to spec snapshot"
          (is (= (:version.snapshot/id (:version/snapshot spec-snapshot))
                 (:version.link/source-snapshot-id (first (:version.snapshot/links plan-snapshot)))))
          (is (= spec-id (:version.snapshot/spec-id plan-snapshot))))
        (testing "mission locks derive from generated resources"
          (let [first-node (-> plan-data :work.plan/nodes first :plan.node/id)
                binding (handlers/mission-from-plan {:config {:mission/id :mission/locks-check
                                                              :agent/id planner-agent
                                                              :work.plan/id (:work.plan/id plan-data)
                                                              :plan.node/id first-node
                                                              :work-plan/resource-path (:work-plan/resource-path result)}})
                locks (handlers/mission-lock-resolve {:config {:mission/id :mission/locks-check
                                                               :agent/id planner-agent
                                                               :mission/resources (:mission/resources binding)}})]
            (is (seq (:locks/requested locks)))
            (is (= (set (:locks/requested locks))
                   (set (map :mission.resource/path (:mission/resources binding)))))))))))
