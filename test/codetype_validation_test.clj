(ns codetype-validation-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [intuition.sfs.actions.runtime :as actions]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [support.datomic :as support]))

(def mission-id "M-20251120-102")
(def agent-id "codetype-agent")
(def touched-paths ["src/intuition/sfs/missions/runtime.clj"])

(defn- validation-file
  []
  (io/file "missions" "logs" (bootstrap/sanitize-fragment mission-id) "codetype-validation.edn"))

(deftest codetype-validation-produces-artifact
  (support/with-test-conn
   (fn [conn]
     (let [file (validation-file)]
       (when (.exists file)
         (io/delete-file file true)))
     (let [{:keys [result]} (actions/execute!
                             {:conn conn
                              :action/ident :action/codetype.validate
                              :config {:mission/id mission-id
                                       :agent/id agent-id
                                       :codetype/paths touched-paths}
                              :permissions #{:permission/tests.run}})]
       (is (= :status/ok (:action/status result)))
       (is (= (set touched-paths) (set (:codetype/paths result))))
       (let [file (validation-file)]
         (is (.exists file)))
       (let [payload (edn/read-string (slurp (validation-file)))]
         (is (= mission-id (:mission/id payload)))
         (is (= :status/ok (:codetype/status payload)))
         (is (seq (:codetype/spec-sections payload)))
         (is (seq (:codetype/definitions payload))))))))

(deftest codetype-validation-errors-when-path-missing
  (support/with-test-conn
   (fn [conn]
     (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Touched path does not exist"
          (actions/execute!
           {:conn conn
            :action/ident :action/codetype.validate
            :config {:mission/id mission-id
                     :agent/id agent-id
                     :codetype/paths ["src/unknown/path.clj"]}
            :permissions #{:permission/tests.run}}))))))

(deftest codetype-validation-errors-when-unmapped
  (support/with-test-conn
   (fn [conn]
     (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"No CodeDefinition covers touched path"
          (actions/execute!
           {:conn conn
            :action/ident :action/codetype.validate
            :config {:mission/id mission-id
                     :agent/id agent-id
                     :codetype/paths ["docs/code-types/raw_code_analysis.md"]}
            :permissions #{:permission/tests.run}}))))))
