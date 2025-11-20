(ns git-branch-prepare-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [intuition.sfs.actions.handlers :as handlers]
   [intuition.sfs.env.bootstrap :as bootstrap]))

(def mission-id "M-20251120-101")
(def agent-id "sandbox-agent")

(defn- delete-tree!
  [^java.io.File file]
  (when (and file (.exists file))
    (doseq [child (.listFiles file)]
      (delete-tree! child))
    (io/delete-file file true)))

(defn- cleanup!
  []
  (delete-tree! (io/file "missions" "logs" (bootstrap/sanitize-fragment mission-id))))

(deftest git-branch-prepare-writes-artifacts
  (cleanup!)
  (let [result (handlers/prepare-git-branch {:config {:mission/id mission-id
                                                      :agent/id agent-id
                                                      :branch/prefix "mission"}})
        edn-path (:branch/edn-path result)
        md-path (:branch/markdown-path result)
        edn-file (io/file edn-path)
        md-file (io/file md-path)
        metadata (edn/read-string (slurp edn-file))
        repo-root (.getCanonicalPath (io/file "."))]
    (testing "artifacts exist inside the repo"
      (is (.exists edn-file))
      (is (.exists md-file))
      (is (str/starts-with? edn-path repo-root))
      (is (str/starts-with? md-path repo-root)))
    (testing "branch metadata references §6.2"
      (is (= "SYSTEM_SPEC §6.2" (:branch/spec-reference result)))
      (is (= (:branch/name result) (:branch/name metadata)))
      (is (= (:branch/edn-path metadata) edn-path))
      (is (= (:branch/markdown-path metadata) md-path))
      (is (str/includes? (slurp md-file) "SYSTEM_SPEC §6.2")))
    (testing "branch naming convention"
      (is (re-find (re-pattern (str "mission/" (bootstrap/sanitize-fragment mission-id)
                                    "/\\d{8}-\\d{6}Z-" (bootstrap/sanitize-fragment agent-id)))
                   (:branch/name result))))))
