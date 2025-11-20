(ns mission-instantiation-test
  "SYSTEM_SPEC §§3.3–3.6, §4.7, §5.1, §6.2, §9 require WorkPlan→Mission instantiation evidence."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [intuition.sfs.actions.handlers :as handlers]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [intuition.sfs.protocols.runtime :as protocols]
   [intuition.versioning.runtime :as versioning]
   [support.datomic :as support])
  (:import
   (java.util UUID)))

(def permissions
  #{:permission/missions.manage
    :permission/locks.manage
    :permission/env.bootstrap})

(def instantiation-spec-refs
  ["3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "6.2" "9"])

(defn- temp-dir
  []
  (let [base (System/getProperty "java.io.tmpdir")
        dir (io/file base (str "mission-instantiation-test-" (UUID/randomUUID)))]
    (.mkdirs dir)
    dir))

(def heuristics-path "/home/dami/intuition-core/missions/logs/M-20251121-701/planner-heuristics.edn")
(def code-types-source "/home/dami/intuition-core/resources/dictionary/code_types.edn")

(defn- copy-code-types!
  [repo-root]
  (let [target (io/file repo-root "resources" "dictionary" "code_types.edn")]
    (.mkdirs (.getParentFile target))
    (spit target (slurp (io/file code-types-source)))
    target))

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
      (copy-code-types! repo-path)
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

(deftest mission-instantiation-protocol-produces-artifacts
  "SYSTEM_SPEC §§3.3–3.6, §4.7, §5.1, §6.2, §9: protocol writes binding, lock, sandbox evidence."
  (with-temp-handlers
    (fn [{:keys [repo-root]}]
      (support/with-test-conn
        (fn [conn]
          (let [planner "steward"
                spec-id :spec/mission-instantiation
                spec-input (write-edn! repo-root "tmp/mission-instantiation-spec.edn"
                                       {:spec/id spec-id
                                        :spec/title "Mission instantiation spec"
                                        :spec/summary "Validate generator-only mission instantiation path."
                                        :spec/requirements ["Bind plan node" "Lock resources"]
                                        :spec/acceptance-criteria ["Binding captured" "Locks requested"]
                                        :spec/test-contracts [:code/sample.validator]
                                        :spec/spec-sections instantiation-spec-refs})
                capture (handlers/spec-capture {:config {:mission/id :mission/instantiation-plan
                                                         :agent/id planner
                                                         :spec/input-path spec-input}})
                validate (handlers/spec-validate {:config {:mission/id :mission/instantiation-plan
                                                           :agent/id planner
                                                           :spec/id (:spec/id capture)
                                                           :spec/resource-path (:spec/resource-path capture)}})
                publish (handlers/spec-publish {:config {:mission/id :mission/instantiation-plan
                                                         :agent/id planner
                                                         :spec/id (:spec/id capture)}})
                _ (handlers/version-snapshot-spec
                   {:config {:mission/id :mission/instantiation-plan
                             :agent/id planner
                             :spec/id (:spec/id capture)
                             :spec/resource-path (:spec/resource-path capture)
                             :spec/validation-path (:spec/validation-path validate)
                             :spec/publish-log (:spec/publish-log publish)}})
                plan-output (io/file repo-root "missions/logs/M-20251121-703/mission-instantiation-plan.edn")
                generation-log (io/file repo-root "missions/logs/M-20251121-703/mission-instantiation-generation-log.edn")
                plan-result (handlers/spec-plan-generate
                             {:config {:mission/id :mission/instantiation-plan
                                       :agent/id planner
                                       :spec/id spec-id
                                       :spec/version 1
                                       :spec/resource-path (:spec/resource-path capture)
                                       :planner/heuristics-path heuristics-path
                                       :plan/output-path (.getCanonicalPath plan-output)
                                       :planner/generation-log-path (.getCanonicalPath generation-log)}})
                plan-data (edn/read-string (slurp (:work-plan/resource-path plan-result)))
                plan-node-id (-> plan-data :work.plan/nodes first :plan.node/id)
                mission-id "M-INST-TEST"
                context {:mission/id mission-id
                         :agent/id planner
                         :workspace/root (str (io/file repo-root "tmp" (str (UUID/randomUUID))))
                         :work.plan/id (:work.plan/id plan-data)
                         :plan.node/id plan-node-id
                         :work-plan/resource-path (:work-plan/resource-path plan-result)
                         :branch/prefix "mission"}
                branch-calls (atom [])
                original-handler handlers/prepare-git-branch]
            (with-redefs [handlers/prepare-git-branch
                          (fn [opts]
                            (swap! branch-calls conj (:config opts))
                            (original-handler opts))]
              (let [result (protocols/run!
                            {:conn conn
                             :protocol/ident :protocol/mission-instantiation
                             :context context
                             :permissions permissions})]
                (is (= :status/succeeded (:status result)))
                (is (= 1 (count @branch-calls)) "branch action executed once")
                (let [raw-log (io/file repo-root "missions" "logs" mission-id)
                      log-dir (if (.exists raw-log)
                                raw-log
                                (io/file repo-root "missions" "logs" (bootstrap/sanitize-fragment mission-id)))
                      binding-file (io/file log-dir "mission-plan-binding.edn")
                      locks-file (io/file log-dir "locks-request.edn")
                      manifest-file (io/file log-dir "sandbox-manifest.edn")
                      binding (edn/read-string (slurp binding-file))
                      locks (edn/read-string (slurp locks-file))
                      manifest (edn/read-string (slurp manifest-file))]
                  (doseq [file [binding-file locks-file manifest-file]]
                    (is (.exists file) (str (.getName file) " should exist")))
                  (is (= mission-id (:mission/id binding)))
                  (is (= instantiation-spec-refs (:mission.plan-binding/spec-sections binding)))
                  (is (= (get-in binding [:mission.plan-binding :mission.plan-binding/resource-refs])
                         (vec (:plan.node/resources (first (:work.plan/nodes plan-data))))))
                  (is (= instantiation-spec-refs (:mission.locks/spec-sections locks)))
                  (is (= (:locks/requested locks)
                         (vec (:plan.node/resources (first (:work.plan/nodes plan-data))))))
                  (is (= instantiation-spec-refs (:mission.sandbox/spec-sections manifest)))
                  (is (= mission-id (:mission/id manifest)))))))))))
  )

(deftest mission-instantiation-lock-conflict
  "SYSTEM_SPEC §§3.3–3.6, §4.7, §5.1, §6.2, §9: conflicting locks fail early."
  (with-temp-handlers
    (fn [{:keys [repo-root]}]
      (support/with-test-conn
        (fn [conn]
          (let [planner "steward"
                spec-id :spec/mission-instantiation
                spec-input (write-edn! repo-root "tmp/mission-instantiation-conflict-spec.edn"
                                       {:spec/id spec-id
                                        :spec/title "Mission instantiation conflict spec"
                                        :spec/summary "Ensure lock conflicts fail."
                                        :spec/requirements ["Bind plan node" "Lock resources"]
                                        :spec/acceptance-criteria ["Binding captured" "Locks requested"]
                                        :spec/test-contracts [:code/sample.validator]
                                        :spec/spec-sections instantiation-spec-refs})
                capture (handlers/spec-capture {:config {:mission/id :mission/instantiation-plan
                                                         :agent/id planner
                                                         :spec/input-path spec-input}})
                validate (handlers/spec-validate {:config {:mission/id :mission/instantiation-plan
                                                           :agent/id planner
                                                           :spec/id (:spec/id capture)
                                                           :spec/resource-path (:spec/resource-path capture)}})
                publish (handlers/spec-publish {:config {:mission/id :mission/instantiation-plan
                                                         :agent/id planner
                                                         :spec/id (:spec/id capture)}})
                _ (handlers/version-snapshot-spec
                   {:config {:mission/id :mission/instantiation-plan
                             :agent/id planner
                             :spec/id (:spec/id capture)
                             :spec/resource-path (:spec/resource-path capture)
                             :spec/validation-path (:spec/validation-path validate)
                             :spec/publish-log (:spec/publish-log publish)}})
                plan-output (io/file repo-root "missions/logs/M-20251121-703/mission-instantiation-conflict-plan.edn")
                generation-log (io/file repo-root "missions/logs/M-20251121-703/mission-instantiation-conflict-generation-log.edn")
                plan-result (handlers/spec-plan-generate
                             {:config {:mission/id :mission/instantiation-plan
                                       :agent/id planner
                                       :spec/id spec-id
                                       :spec/version 1
                                       :spec/resource-path (:spec/resource-path capture)
                                       :planner/heuristics-path heuristics-path
                                       :plan/output-path (.getCanonicalPath plan-output)
                                       :planner/generation-log-path (.getCanonicalPath generation-log)}})
                plan-data (edn/read-string (slurp (:work-plan/resource-path plan-result)))
                plan-node-id (-> plan-data :work.plan/nodes first :plan.node/id)
                conflicted-resource (first (:plan.node/resources (first (:work.plan/nodes plan-data))))
                mission-id "M-INST-CONFLICT"
                context {:mission/id mission-id
                         :agent/id planner
                         :workspace/root (str (io/file repo-root "tmp" (str (UUID/randomUUID))))
                         :work.plan/id (:work.plan/id plan-data)
                         :plan.node/id plan-node-id
                         :work-plan/resource-path (:work-plan/resource-path plan-result)
                         :locks/current [conflicted-resource]}]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo
                 #"Mission lock conflict"
                 (protocols/run!
                  {:conn conn
                   :protocol/ident :protocol/mission-instantiation
                   :context context
                   :permissions permissions}))))))))
  )
