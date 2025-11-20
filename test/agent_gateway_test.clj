(ns agent-gateway-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [intuition.sfs.missions.runtime :as missions])
  (:import
   (java.nio.file Files Paths)
   (java.nio.file.attribute FileAttribute)))

(defmacro thrown-with-msg?
  [exception-class pattern body]
  `(try
     ~body
     false
     (catch ~exception-class e#
       (boolean (re-find ~pattern (.getMessage e#))))))

(defn- temp-root
  []
  (let [attrs (make-array FileAttribute 0)
        base (Paths/get (System/getProperty "java.io.tmpdir")
                        (make-array String 0))]
    (-> (Files/createTempDirectory base "gateway-test" attrs)
        .toFile
        .getCanonicalFile)))

(defn- delete-recursively!
  [^java.io.File file]
  (when (.exists file)
    (doseq [f (reverse (file-seq file))]
      (.delete f))))

(defn- with-mission-env
  [opts f]
  (let [root (temp-root)
        mission-id (or (:mission/id opts)
                       (str "M-GATEWAY-" (System/currentTimeMillis)))
        scope-paths (vec (or (:scope-paths opts) ["src"]))]
    (doseq [relative scope-paths]
      (.mkdirs (io/file root relative)))
    (let [mission {:mission/id mission-id
                   :mission/scope (pr-str {:paths scope-paths})}]
      (try
        (with-redefs-fn {#'missions/repo-root root
                         #'missions/repo-root-path (.getCanonicalPath root)
                         #'missions/ensure-base! (fn [] :stub-conn)
                         #'missions/fetch-mission! (fn [_ _] mission)}
          #(f {:root root
               :mission mission}))
        (finally
          (delete-recursively! root))))))

(deftest mission-plan-step-records-requirements
  (with-mission-env {}
    (fn [{:keys [mission]}]
      (let [result (missions/plan-step! {:mission/id (:mission/id mission)
                                         :agent/id "coder"
                                         :plan/requirements [:req/trace]
                                         :plan/notes "Break work"})
            payload (-> (:mission.step/edn result) slurp edn/read-string)
            inputs (edn/read-string (:mission.step/inputs payload))]
        (is (= :status/ok (:action/status result)))
        (is (= [:req/trace] (:plan/requirements inputs)))))))

(deftest edit-step-rejects-outside-scope
  (with-mission-env {:scope-paths ["src"]}
    (fn [{:keys [mission]}]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"outside locked mission scope"
           (missions/edit-step! {:mission/id (:mission/id mission)
                                  :agent/id "coder"
                                  :edit/files ["docs/manual.md"]
                                  :edit/summary "touch docs"}))))))

(deftest tool-run-step-writes-tool-run-artifact
  (with-mission-env {}
    (fn [{:keys [root mission]}]
      (let [log-file (io/file root "lint.txt")
            log-path (.getCanonicalPath log-file)]
        (spit log-file "lint ok")
        (with-redefs-fn {#'missions/run-lint! (fn [_ _ _ _]
                                                (spit log-file "lint ok")
                                                {:action/status :status/ok
                                                 :lint/log log-path})}
          (fn []
            (let [result (missions/tool-run-step! {:mission/id (:mission/id mission)
                                                   :agent/id "coder"
                                                   :tool/id :tool/lint
                                                   :tool/params {:paths ["src"]}})
                  sanitized (bootstrap/sanitize-fragment (:mission/id mission))
                  tool-run-file (io/file root "missions" "logs" sanitized "tool-run.edn")
                  tool-run (edn/read-string (slurp tool-run-file))]
              (is (= :status/ok (:action/status result)))
              (is (= :tool/lint (:mission.tool-run/tool-id tool-run)))
              (is (= log-path (:mission.tool-run/log-path tool-run)))))))))) 

(deftest decision-step-links-requirements
  (with-mission-env {}
    (fn [{:keys [mission]}]
      (let [result (missions/decision-step! {:mission/id (:mission/id mission)
                                             :agent/id "coder"
                                             :decision/summary "Chose plan"
                                             :decision/requirements [:req/a :req/b]
                                             :decision/tests [:test/a]})
            payload (-> (:mission.step/edn result) slurp edn/read-string)
            inputs (edn/read-string (:mission.step/inputs payload))]
        (is (= :status/ok (:action/status result)))
        (is (= [:req/a :req/b] (:decision/requirements inputs)))))))
