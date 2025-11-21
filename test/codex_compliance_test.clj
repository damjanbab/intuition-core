(ns codex-compliance-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def mission-id "M-20251121-805")
(def bundle-path "missions/logs/M-20251121-805/context-bundle.edn")
(def manifest-path "missions/logs/M-20251121-805/run-manifest.edn")
(def run-log-path "missions/logs/M-20251121-805/run.log")
(def dummy-artifact "missions/logs/M-20251121-805/dummy-artifact.txt")
(def sandbox-root "tmp/missions/M-20251121-805/codex")
(def token-path "missions/logs/M-20251121-805/auth.token")

(defn- delete-tree!
  [^java.io.File file]
  (when (and file (.exists file))
    (doseq [child (.listFiles file)]
      (delete-tree! child))
    (io/delete-file file true)))

(defn- canonical
  [path]
  (.getCanonicalPath (io/file path)))

(deftest codex-exec-produces-manifest-and-cleans-sandbox
  (testing "codex exec drives run-mission non-interactively and returns expected artifacts"
    (let [bundle-file (io/file bundle-path)
          manifest-file (io/file manifest-path)
          run-log-file (io/file run-log-path)
          dummy-file (io/file dummy-artifact)
          sandbox (io/file sandbox-root)
          token (str/trim (slurp token-path))
          cmd ["codex" "exec" "--sandbox" "workspace-write"
               (format "clojure -M:dev -m dev.agent-gateway run-mission '{:mission/id \"%s\" :context/bundle-path \"%s\" :agent/id \"codex\" :auth/token \"%s\"}'"
                       mission-id (canonical bundle-file) token)]]
      (doseq [f [manifest-file run-log-file dummy-file]]
        (when (.exists f) (io/delete-file f true)))
      (delete-tree! sandbox)
      (is (.exists bundle-file) "context bundle must exist for codex exec")
      (let [{:keys [exit err out]} (apply shell/sh cmd)]
        (is (zero? exit) (str "codex exec should succeed\nerr: " err "\nout: " out))
        (is (.exists manifest-file) "run-manifest.edn should be produced")
        (is (.exists run-log-file) "run log should be produced")
        (is (.exists dummy-file) "dummy smoke artifact should be produced")
        (let [manifest (edn/read-string (slurp manifest-file))
              run-log-bytes (.length run-log-file)
              manifest-artifacts (set (map :artifact/path (:artifacts manifest)))]
          (is (= :status/ok (:action/status manifest)))
          (is (= mission-id (:mission/id manifest)))
          (is (<= run-log-bytes 4096) "run log truncated per contract")
          (is (contains? manifest-artifacts (canonical dummy-file))
              "manifest lists dummy artifact")
          (is (seq (get-in manifest [:trace/watermark :system-spec/sections]))
              "manifest watermark should cite SYSTEM_SPEC sections")))
      (delete-tree! sandbox)
      (is (not (.exists sandbox)) "sandbox cleaned after run per §6.2"))))
