(ns spec-intake-test
  "SYSTEM_SPEC Section Section 3.3–3.6, Section 4.1, Section 9 mandate spec-to-mission evidence; these tests cover the spec intake protocol."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [intuition.sfs.actions.handlers :as handlers]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [intuition.sfs.protocols.runtime :as protocols]
   [support.datomic :as support])
  (:import
   (java.util UUID)))

(def permissions #{:permission/spec.manage})

(defn- temp-dir
  []
  (let [base (System/getProperty "java.io.tmpdir")
        dir (io/file base (str "spec-intake-test-" (UUID/randomUUID)))]
    (.mkdirs dir)
    dir))

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
        repo-var #'handlers/repo-root
        specs-var #'handlers/specs-dir
        contracts-var #'handlers/known-test-contracts
        originals {repo-var @repo-var
                   specs-var @specs-var
                   contracts-var @contracts-var}]
    (try
      (alter-var-root repo-var (constantly repo-path))
      (alter-var-root specs-var (constantly specs-root))
      (alter-var-root contracts-var (constantly (constantly #{:code/intuition.sfs.actions.handlers})))
      (f {:repo-root repo-path})
      (finally
        (doseq [[var value] originals]
          (alter-var-root var (constantly value)))
        (delete-tree root)))))

(defn- write-spec!
  [repo-root relative-path spec]
  (let [file (io/file repo-root relative-path)]
    (.mkdirs (.getParentFile file))
    (spit file (pr-str spec))
    (.getCanonicalPath file)))

(deftest spec-intake-protocol-produces-validation
  "SYSTEM_SPEC Section Section 3.3–3.6, Section 4.1, Section 9: spec-intake protocol emits validation artifacts."
  (with-temp-handlers
   (fn [{:keys [repo-root]}]
     (let [mission-id :mission/spec-intake-test
           agent-id "spec-agent"
           spec-id :spec/protocol-sample
           spec-fragment (bootstrap/sanitize-fragment (name spec-id))
           spec-input (write-spec! repo-root "tmp/spec-input.edn"
                                   {:spec/id spec-id
                                    :spec/title "Spec intake sample"
                                    :spec/summary "Exercising the spec-intake protocol."
                                    :spec/requirements ["Normalize spec"
                                                        "Validate CodeTypes"
                                                        "Publish status"]
                                    :spec/acceptance-criteria ["Validation artifact exists"
                                                              "Publish log captured"]
                                    :spec/test-contracts [:code/intuition.sfs.actions.handlers]
                                    :spec/constraints ["Write under governed dirs"]
                                    :spec/status :spec.status/captured
                                    :spec/spec-sections ["3.3" "3.4" "3.5" "4.1" "4.2" "4.7" "5.1" "9"]
                                    :spec/artifacts ["tmp/spec-input.edn"]
                                    :spec/owner :role/dictionary-engineer})
           context {:mission/id mission-id
                    :agent/id agent-id
                    :spec/input-path spec-input
                    :spec/id spec-id}
           log-dir (io/file repo-root "missions" "logs" (bootstrap/sanitize-fragment mission-id))]
       (support/with-test-conn
        (fn [conn]
          (let [result (protocols/run!
                        {:conn conn
                         :protocol/ident :protocol/spec-intake
                         :context context
                         :permissions permissions})]
            (is (= :status/succeeded (:status result))))))
       (let [validation-edn (io/file log-dir "spec-validation.edn")
             validation-md (io/file log-dir "spec-validation.md")
             publish-md (io/file log-dir (str spec-fragment "-spec-publish.md"))
             resource-file (io/file repo-root "resources" "specs" (str spec-fragment ".edn"))
             validation-data (edn/read-string (slurp validation-edn))]
         (testing "artifacts exist"
           (is (.exists validation-edn))
           (is (.exists validation-md))
           (is (.exists publish-md))
           (is (.exists resource-file)))
         (testing "validation report status"
           (is (= :spec.status/validated (:spec/status validation-data))))
         (testing "markdown mentions spec"
           (is (re-find #"Spec Validation" (slurp validation-md))))))))
  )

(deftest spec-validation-missing-fields
  "SYSTEM_SPEC Section Section 3.3–3.6, Section 4.1, Section 9: validation fails when required fields are absent."
  (with-temp-handlers
   (fn [{:keys [repo-root]}]
     (let [mission-id :mission/spec-intake-bad
           agent-id "spec-agent"
           spec-id :spec/invalid
           spec-path (write-spec! repo-root "resources/specs/invalid.edn"
                                  {:spec/id spec-id
                                   :spec/title "Broken spec"
                                   :spec/summary "Missing requirements"
                                   :spec/acceptance-criteria []
                                   :spec/test-contracts []
                                   :spec/spec-sections []
                                   :spec/artifacts []
                                   :spec/status :spec.status/captured})]
       (try
         (handlers/spec-validate {:config {:mission/id mission-id
                                           :agent/id agent-id
                                           :spec/id spec-id
                                           :spec/resource-path spec-path}})
         (is false "Validation should fail")
         (catch clojure.lang.ExceptionInfo ex
           (is (re-find #"Spec validation failed" (.getMessage ex)))
           (let [errors (:errors (ex-data ex))]
             (is (some #(re-find #"requirements" %) errors))
             (is (some #(re-find #"test-contracts" %) errors)))))))))
