(ns code-type-inference-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [intuition.code.runtime :as code]
   [intuition.sfs.actions.handlers :as handlers]
   [intuition.sfs.actions.runtime :as actions]
   [intuition.versioning.runtime :as versioning]
   [support.datomic :as support])
  (:import
   (java.io File)
   (java.util UUID)))

(def heuristics-source "missions/logs/M-20251121-701/planner-heuristics.edn")
(def code-types-source "resources/dictionary/code_types.edn")

(defn- temp-dir
  []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "code-type-inference-" (UUID/randomUUID)))
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

(defn- touch!
  [repo-root relative-path content]
  (let [file (io/file repo-root relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file content)
    (.getCanonicalPath file)))

(defn- copy-heuristics!
  [repo-root]
  (let [target (io/file repo-root "missions" "logs" "M-20251121-804" "planner-heuristics.edn")]
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

(deftest planner-infers-codetypes-and-generation-runs
  (with-temp-repo
    (fn [{:keys [repo-root heuristics-path]}]
      (support/with-test-conn
        (fn [conn]
          (let [spec-id :spec/codetype.inference
                mission-id :mission/codetype.inference
                planner "planner"
                doc-artifact (touch! repo-root "docs/inference.md" "# doc placeholder")
                resource-artifact (touch! repo-root "resources/dictionary/inference.edn" "{}")
                spec-input (write-edn! repo-root "tmp/codetype-inference-spec.edn"
                                       {:spec/id spec-id
                                        :spec/title "CodeType inference"
                                        :spec/summary "Validate CodeType inference and generation."
                                        :spec/requirements ["Infer CodeTypes"]
                                        :spec/acceptance-criteria ["Artifacts generated"]
                                        :spec/constraints [:risk/security]
                                        :spec/spec-sections ["3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "9"]
                                        :spec/artifacts ["docs/inference.md"
                                                         "resources/dictionary/inference.edn"]})
                capture (handlers/spec-capture {:config {:mission/id mission-id
                                                         :agent/id planner
                                                         :spec/input-path spec-input}})
                validate (handlers/spec-validate {:config {:mission/id mission-id
                                                           :agent/id planner
                                                           :spec/id (:spec/id capture)
                                                           :spec/resource-path (:spec/resource-path capture)}})
                publish (handlers/spec-publish {:config {:mission/id mission-id
                                                         :agent/id planner
                                                         :spec/id (:spec/id capture)}})
                _ (handlers/version-snapshot-spec
                   {:config {:mission/id mission-id
                             :agent/id planner
                             :spec/id (:spec/id capture)
                             :spec/resource-path (:spec/resource-path capture)
                             :spec/validation-path (:spec/validation-path validate)
                             :spec/publish-log (:spec/publish-log publish)}})
                plan-output (io/file repo-root "missions/logs/M-20251121-804/generated-plan.edn")
                generation-log (io/file repo-root "missions/logs/M-20251121-804/planner-generation-log.edn")
                plan (handlers/spec-plan-generate
                      {:config {:mission/id mission-id
                                :agent/id planner
                                :spec/id spec-id
                                :spec/version 1
                                :spec/resource-path (:spec/resource-path capture)
                                :planner/heuristics-path heuristics-path
                                :plan/output-path (.getCanonicalPath plan-output)
                                :planner/generation-log-path (.getCanonicalPath generation-log)}})
                plan-data (edn/read-string (slurp (:work-plan/resource-path plan)))
                node (-> plan-data :work.plan/nodes first)
                mission (handlers/mission-from-plan {:config {:mission/id mission-id
                                                               :agent/id planner
                                                               :work.plan/id (:work.plan/id plan-data)
                                                               :plan.node/id (:plan.node/id node)
                                                               :work-plan/resource-path (:work-plan/resource-path plan)}})
                mission-record (:mission/record mission)
                scope (edn/read-string (:mission/scope mission-record))
                code-types (vec (or (:code-types scope)
                                    (:mission/code-types mission-record)))
                sandbox (doto (io/file repo-root "tmp" "sandbox") .mkdirs)
                generation-results (mapv #(-> (actions/execute!
                                               {:conn conn
                                                :action/ident :action/codetype.generate
                                                :config {:mission/id mission-id
                                                         :agent/id planner
                                                         :sandbox/root (.getCanonicalPath sandbox)
                                                         :codetype/ident %}
                                                :permissions #{:permission/env.bootstrap}})
                                              :result)
                                         code-types)]
            (testing "planner infers CodeTypes from artifacts and risk"
              (is (= #{:code.type/runtime :code.type/resource :code.type/doc :code.type/test}
                     (set (:plan.node/code-types node)))))
            (testing "mission scope carries inferred paths for validation"
              (is (= (set (:resources scope))
                     (set (:paths scope)))))
            (testing "generation writes expected artifacts inside sandbox"
              (is (seq code-types))
              (is (seq generation-results))
              (doseq [run generation-results
                      file (:codetype/generated-files run)]
                (is (.exists (io/file sandbox (:codetype/relative-path file)))
                    (str "missing generated file " (:codetype/relative-path file)))))))))))

(deftest dedupe-guard-blocks-near-duplicate
  (let [sample {:code.type/ident :code.type/runtime
                :code.type/category :code.category/runtime
                :code.type/default-validators [:validator/spec-trace]
                :code.type/generator "intuition.codetype.generators/templated-scaffold"
                :code.type/generator-templates ["resources/codetype/runtime_stub.clj.tpl"]
                :code.type/generated-artifacts ["src/generated/{{IDENT}}/core.clj"]}]
    (with-redefs [code/code-types (constantly [sample (assoc sample :code.type/ident :code.type/runtime-duplicate)])]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"near-duplicates"
           (code/assert-no-near-duplicates!))))))
