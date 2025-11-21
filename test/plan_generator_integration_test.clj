(ns plan-generator-integration-test
  "Integration coverage for the spec→plan generator: generation log, validation, locks, and snapshots."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [intuition.sfs.actions.handlers :as handlers]
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

(defn- perform-plan-generation
  [{:keys [repo-root heuristics-path llm-config]}]
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
        base-config {:mission/id plan-mission
                     :agent/id planner-agent
                     :spec/id spec-id
                     :spec/version 1
                     :spec/resource-path (:spec/resource-path capture)
                     :planner/heuristics-path heuristics-path
                     :plan/output-path (.getCanonicalPath plan-output)
                     :planner/generation-log-path (.getCanonicalPath generation-log)}
        plan-config (if (seq llm-config)
                      (assoc base-config :llm.plan-draft llm-config)
                      base-config)
        result (handlers/spec-plan-generate {:config plan-config})
        plan-data (edn/read-string (slurp (:work-plan/resource-path result)))
        generation-log-data (edn/read-string (slurp (:plan.generation/log-path result)))
        plan-snapshot (edn/read-string (slurp (:version.snapshot/path result)))]
    {:capture capture
     :validate validate
     :publish publish
     :spec-snapshot spec-snapshot
     :result result
     :plan-data plan-data
     :generation-log generation-log-data
     :plan-snapshot plan-snapshot}))

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
      (let [planner-agent "planner"
            {:keys [spec-snapshot result plan-data generation-log plan-snapshot]}
            (perform-plan-generation {:repo-root repo-root
                                      :heuristics-path heuristics-path})
            generation-log-data generation-log
            spec-id :spec/planner.integration]
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
        (testing "LLM plan-draft log records disabled mode"
          (is (= :off (get-in generation-log-data [:plan.generation/llm :llm.plan-draft/mode])))
          (is (= :llm.status/disabled (get-in generation-log-data [:plan.generation/llm :llm.plan-draft/status])))
          (is (empty? (:plan.generation/warnings generation-log-data))))
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

(deftest llm-plan-draft-applies-when-enabled
  (support/with-test-conn
    (fn [conn]
      (with-temp-repo
        (fn [{:keys [repo-root heuristics-path]}]
          (let [self-report {:confidence :high :reason "test" :assumptions [] :uncertainties []}
                fake (fn [_]
                       {:status :response.status/ok
                        :payload {:plan/nodes [{:plan.node/id "planner.integration-LLM"
                                                :plan.node/name "LLM extra"
                                                :plan.node/scope-requirements ["REQ-plan-a"]
                                                :plan.node/resources ["src/planner.integration/llm-extra.clj"]
                                                :plan.node/test-contracts [:code/sample.validator]
                                                :plan.node/code-types [:code.type/runtime]}]
                                  :plan/edges []
                                  :plan/coverage [{:coverage.row/requirement-id "REQ-plan-a"
                                                   :coverage.row/nodes ["planner.integration-LLM"]
                                                   :coverage.row/code-targets ["src/planner.integration/llm-extra.clj"]
                                                   :coverage.row/test-contracts [:code/sample.validator]
                                                   :coverage.row/code-types [:code.type/runtime]
                                                   :coverage.row/acceptance-id "LLM-coverage"}]}
                        :self-report self-report})
                {:keys [plan-data generation-log]}
                (perform-plan-generation {:repo-root repo-root
                                          :heuristics-path heuristics-path
                                          :llm-config {:mode :apply
                                                       :conn conn
                                                       :fake-response-fn fake}})
                node-ids (set (map :plan.node/id (:work.plan/nodes plan-data)))
                llm-log (:plan.generation/llm generation-log)
                decisions (:plan.generation/decisions generation-log)]
            (testing "LLM node appended"
              (is (contains? node-ids "planner.integration-LLM")))
            (testing "LLM metadata recorded"
              (is (= :apply (:llm.plan-draft/mode llm-log)))
              (is (= :llm.status/applied (:llm.plan-draft/status llm-log)))
              (is (true? (:llm.plan-draft/applied? llm-log)))
              (is (= :planner+llm.plan-draft
                     (:decision/source (last decisions)))))
            (testing "warnings remain empty"
              (is (empty? (:plan.generation/warnings generation-log))))))))))

(deftest llm-plan-draft-aborts-and-falls-back
  (support/with-test-conn
    (fn [conn]
      (with-temp-repo
        (fn [{:keys [repo-root heuristics-path]}]
          (let [self-report {:confidence :medium :reason "abort" :assumptions [] :uncertainties []}
                fake (fn [_]
                       {:status :response.status/ok
                        :payload {:llm.plan/status :abort
                                  :reason "insufficient context"}
                        :self-report self-report})
                {:keys [plan-data generation-log]}
                (perform-plan-generation {:repo-root repo-root
                                          :heuristics-path heuristics-path
                                          :llm-config {:mode :apply
                                                       :conn conn
                                                       :fake-response-fn fake}})
                node-ids (set (map :plan.node/id (:work.plan/nodes plan-data)))
                llm-log (:plan.generation/llm generation-log)
                decisions (:plan.generation/decisions generation-log)]
            (testing "deterministic nodes preserved"
              (is (not (contains? node-ids "planner.integration-LLM"))))
            (testing "abort recorded with warning"
              (is (= :llm.status/abort (:llm.plan-draft/status llm-log)))
              (is (some #(str/includes? % "LLM plan-draft aborted")
                        (:plan.generation/warnings generation-log)))
              (is (= :llm.status/abort (:decision/status (last decisions)))))))))))
