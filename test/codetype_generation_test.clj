(ns codetype-generation-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [intuition.sfs.actions.handlers :as handlers]
   [intuition.sfs.actions.runtime :as actions]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [support.datomic :as support])
  (:import
   (java.util UUID)))

(defn- temp-dir []
  (let [dir (io/file (System/getProperty "java.io.tmpdir")
                     (str "codetype-generation-test-" (UUID/randomUUID)))]
    (.mkdirs dir)
    dir))

(defn- delete-tree [^java.io.File path]
  (when (and path (.exists path))
    (doseq [child (.listFiles path)]
      (delete-tree child))
    (io/delete-file path true)))

(defn- with-temp-repo [f]
  (let [root (temp-dir)
        canonical (.getCanonicalPath root)
        repo-var #'handlers/repo-root
        original @repo-var]
    (try
      (alter-var-root repo-var (constantly canonical))
      (f canonical)
      (finally
        (alter-var-root repo-var (constantly original))
        (delete-tree root)))))

(defn- with-repo-and-conn [f]
  (with-temp-repo
    (fn [repo-root]
      (support/with-test-conn
        (fn [conn]
          (f repo-root conn))))))

(defn- sandbox-path [repo-root]
  (let [dir (io/file repo-root "tmp" "sandbox")]
    (.mkdirs dir)
    (.getCanonicalPath dir)))

(defn- log-dir [repo-root mission-id]
  (io/file repo-root "missions" "logs" (bootstrap/sanitize-fragment mission-id)))

(defn- generation-log [repo-root mission-id]
  (let [file (io/file (log-dir repo-root mission-id) "codetype-generation.edn")]
    (when (.exists file)
      (edn/read-string (slurp file)))))

(deftest codetype-generation-produces-artifacts
  (with-repo-and-conn
    (fn [repo-root conn]
      (let [mission-id "M-GEN-001"
            sandbox (sandbox-path repo-root)
            {:keys [result]} (actions/execute!
                              {:conn conn
                               :action/ident :action/codetype.generate
                               :config {:mission/id mission-id
                                        :agent/id "tester"
                                        :sandbox/root sandbox
                                        :codetype/ident :code.type/codex.sample}
                               :permissions #{:permission/env.bootstrap}})
            artifact (io/file sandbox "src" "generated" "sample_runtime.clj")
            stamp (io/file sandbox ".codetype"
                           (str (bootstrap/sanitize-fragment (name :code.type/codex.sample))
                                ".edn"))]
        (is (= :status/ok (:action/status result)))
        (is (.exists artifact) "Generated file should exist")
        (is (.exists stamp) "Stamp file should exist")
        (is (= (:codetype/ident result) :code.type/codex.sample))
        (is (seq (:codetype/generated-files result)))
        (let [log (generation-log repo-root mission-id)]
          (is (seq (:codetype/generations log)))
          (is (= [mission-id]
                 (->> (:codetype/generations log)
                      (map :mission/id)
                      set
                      vec))))))))

(deftest codetype-generation-skips-duplicates
  (with-repo-and-conn
    (fn [repo-root conn]
      (let [mission-id "M-GEN-002"
            sandbox (sandbox-path repo-root)
            run! (fn []
                   (:result (actions/execute!
                             {:conn conn
                              :action/ident :action/codetype.generate
                              :config {:mission/id mission-id
                                       :agent/id "tester"
                                       :sandbox/root sandbox
                                       :codetype/ident :code.type/codex.sample}
                              :permissions #{:permission/env.bootstrap}})))
            first-run (run!)
            second-run (run!)]
        (is (= :status/ok (:action/status first-run)))
        (is (= :status/ok (:action/status second-run)))
        (is (= (:codetype/generated-at first-run)
               (:codetype/generated-at second-run))
            "Skip reuses original timestamp")
        (is (:codetype/skipped? second-run))
        (let [log (generation-log repo-root mission-id)]
          (is (= 2 (count (:codetype/generations log))))
          (is (true? (-> log :codetype/generations second :codetype/skipped?))))))))

(deftest codetype-generation-errors-when-template-missing
  (with-repo-and-conn
    (fn [repo-root conn]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Generator template not found"
           (actions/execute!
            {:conn conn
             :action/ident :action/codetype.generate
             :config {:mission/id "M-GEN-003"
                      :agent/id "tester"
                      :sandbox/root (sandbox-path repo-root)
                      :codetype/ident :code.type/codex.sample-missing-template}
             :permissions #{:permission/env.bootstrap}}))))))

(deftest codetype-generation-errors-when-handler-missing
  (with-repo-and-conn
    (fn [repo-root conn]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Unable to resolve generator function"
           (actions/execute!
            {:conn conn
             :action/ident :action/codetype.generate
             :config {:mission/id "M-GEN-004"
                      :agent/id "tester"
                      :sandbox/root (sandbox-path repo-root)
                      :codetype/ident :code.type/codex.sample-missing-handler}
             :permissions #{:permission/env.bootstrap}}))))))
