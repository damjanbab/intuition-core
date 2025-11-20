(ns work-plan-validation-test
  "SYSTEM_SPEC §§3.3–3.6, §4.7, §5.1, §9 mandate that WorkPlans prove coverage, DAG order, and resource locks before missions run."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [intuition.sfs.actions.handlers :as handlers]
   [intuition.versioning.runtime :as versioning])
  (:import
   (java.util UUID)))

(defn- temp-dir
  []
  (let [base (System/getProperty "java.io.tmpdir")
        dir (io/file base (str "work-plan-validation-test-" (UUID/randomUUID)))]
    (.mkdirs dir)
    dir))

(def heuristics-path "/home/dami/intuition-core/missions/logs/M-20251121-701/planner-heuristics.edn")

(defn- delete-tree
  [^java.io.File path]
  (when (and path (.exists path))
    (doseq [child (.listFiles path)]
      (delete-tree child))
    (io/delete-file path true)))

(defn- with-temp-handlers
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
      (f {:repo-root repo-path})
      (finally
        (doseq [[var value] originals]
          (alter-var-root var (constantly value)))
        (delete-tree root)))))

(defn- write-edn!
  [repo-root relative-path data]
  (let [file (io/file repo-root relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file (pr-str data))
    (.getCanonicalPath file)))

(deftest work-plan-validation-produces-artifacts
  "SYSTEM_SPEC §§3.3–3.6, §4.7, §5.1, §9: happy-path WorkPlan validation logs coverage/DAG/resource evidence."
  (with-temp-handlers
    (fn [{:keys [repo-root]}]
      (let [spec-id :spec/work-plan-demo
            planner "planner"
            spec-input (write-edn! repo-root "tmp/work-plan-spec.edn"
                                   {:spec/id spec-id
                                    :spec/title "WorkPlan validation demo"
                                    :spec/summary "Covers generator-only WorkPlan validation."
                                    :spec/requirements ["REQ-1" "REQ-2"]
                                    :spec/acceptance-criteria ["AC-1" "AC-2"]
                                    :spec/test-contracts [:code/sample.validator]
                                    :spec/spec-sections ["3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "9"]})
            capture (handlers/spec-capture {:config {:mission/id :mission/work-plan-test
                                                     :agent/id planner
                                                     :spec/input-path spec-input}})
            validate (handlers/spec-validate {:config {:mission/id :mission/work-plan-test
                                                       :agent/id planner
                                                       :spec/id (:spec/id capture)
                                                       :spec/resource-path (:spec/resource-path capture)}})
            publish (handlers/spec-publish {:config {:mission/id :mission/work-plan-test
                                                     :agent/id planner
                                                     :spec/id (:spec/id capture)}})
            _ (handlers/version-snapshot-spec
               {:config {:mission/id :mission/work-plan-test
                         :agent/id planner
                         :spec/id (:spec/id capture)
                         :spec/resource-path (:spec/resource-path capture)
                         :spec/validation-path (:spec/validation-path validate)
                         :spec/publish-log (:spec/publish-log publish)}})
            plan-output (io/file repo-root "missions/logs/M-20251121-703/generated-plan.edn")
            generation-log (io/file repo-root "missions/logs/M-20251121-703/generation-log.edn")
            result (handlers/spec-plan-generate
                    {:config {:mission/id :mission/work-plan-test
                              :agent/id planner
                              :spec/id spec-id
                              :spec/version 1
                              :spec/resource-path (:spec/resource-path capture)
                              :planner/heuristics-path heuristics-path
                              :plan/output-path (.getCanonicalPath plan-output)
                              :planner/generation-log-path (.getCanonicalPath generation-log)}})
            plan-data (edn/read-string (slurp (:work-plan/resource-path result)))
            validation-report (edn/read-string (slurp (:work-plan/validation-path result)))
            generation-log-data (edn/read-string (slurp (:plan.generation/log-path result)))]
        (testing "generation + validation artifacts exist"
          (doseq [path [(:work-plan/resource-path result)
                        (:work-plan/log-path result)
                        (:work-plan/validation-path result)
                        (:work-plan/publish-log result)
                        (:plan.generation/log-path result)]]
            (is (.exists (io/file path))))
          (is (= :plan.generation.status/validated (:plan.generation/status plan-data))))
        (testing "validation report references plan id"
          (is (= (:work.plan/id plan-data)
                 (:work.plan/id validation-report)))
          (is (= :status/passed
                 (:validation/status (first (:work.plan/validation-results validation-report))))))
        (testing "plan carries generation metadata"
          (is (= (:plan.generation/id generation-log-data)
                 (:plan.generation/id plan-data)))
          (is (= (:plan.generation/status plan-data)
                 :plan.generation.status/validated)))))))

(deftest work-plan-validation-detects-missing-coverage-and-conflicts
  "SYSTEM_SPEC §§3.3–3.6, §4.7, §5.1, §9: validation must fail when requirements are uncovered or write scopes conflict."
  (with-temp-handlers
    (fn [{:keys [repo-root]}]
      (let [spec-id :spec/work-plan-demo-bad
            planner "planner"
            spec-input (write-edn! repo-root "tmp/work-plan-bad-spec.edn"
                                   {:spec/id spec-id
                                    :spec/title "WorkPlan validation failure demo"
                                    :spec/summary "Ensure validators catch missing coverage and conflicts."
                                    :spec/requirements ["REQ-A" "REQ-B"]
                                    :spec/acceptance-criteria ["AC-A" "AC-B"]
                                    :spec/test-contracts [:code/sample.validator :code/extra.validator]
                                    :spec/spec-sections ["3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "9"]})
            capture (handlers/spec-capture {:config {:mission/id :mission/work-plan-test-bad
                                                     :agent/id planner
                                                     :spec/input-path spec-input}})
            validate (handlers/spec-validate {:config {:mission/id :mission/work-plan-test-bad
                                                       :agent/id planner
                                                       :spec/id (:spec/id capture)
                                                       :spec/resource-path (:spec/resource-path capture)}})
            publish (handlers/spec-publish {:config {:mission/id :mission/work-plan-test-bad
                                                     :agent/id planner
                                                     :spec/id (:spec/id capture)}})
            _ (handlers/version-snapshot-spec
               {:config {:mission/id :mission/work-plan-test-bad
                         :agent/id planner
                         :spec/id (:spec/id capture)
                         :spec/resource-path (:spec/resource-path capture)
                         :spec/validation-path (:spec/validation-path validate)
                         :spec/publish-log (:spec/publish-log publish)}})
            plan-output (io/file repo-root "missions/logs/M-20251121-703/bad-plan.edn")
            generation-log (io/file repo-root "missions/logs/M-20251121-703/bad-generation-log.edn")
            result (handlers/spec-plan-generate
                    {:config {:mission/id :mission/work-plan-test-bad
                              :agent/id planner
                              :spec/id spec-id
                              :spec/version 1
                              :spec/resource-path (:spec/resource-path capture)
                              :planner/heuristics-path heuristics-path
                              :plan/output-path (.getCanonicalPath plan-output)
                              :planner/generation-log-path (.getCanonicalPath generation-log)}})
            plan-data (edn/read-string (slurp (:work-plan/resource-path result)))
            tampered (-> plan-data
                         (assoc :work.plan/edges [])
                         (assoc :work.plan/coverage [(first (:work.plan/coverage plan-data))])
                         (update :work.plan/nodes (fn [nodes]
                                                    (mapv #(assoc % :plan.node/resources ["src/shared.clj"]) nodes))))]
        (spit (:work-plan/resource-path result) (pr-str tampered))
        (try
          (handlers/work-plan-validate {:config {:mission/id :mission/work-plan-test-bad
                                                :agent/id planner
                                                :work.plan/id (:work.plan/id plan-data)
                                                :work-plan/resource-path (:work-plan/resource-path result)
                                                :spec/resource-path (:spec/resource-path capture)}})
          (is false "Validation should have failed")
          (catch clojure.lang.ExceptionInfo ex
            (let [errors (:errors (ex-data ex))]
              (is (some #(re-find #"Missing coverage" %) errors))
              (is (some #(re-find #"Missing coverage for test contracts" %) errors))
              (is (some #(re-find #"Resource .*shared" %) errors)))))))))
