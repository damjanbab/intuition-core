(ns docgen-type-app-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [intuition.sfs.actions.runtime :as actions]
   [support.datomic :as support]))

(defn- read-edn
  [path]
  (-> path slurp edn/read-string))

(defn- assert-doc-output
  [doc]
  (let [markdown-file (io/file (:markdown/path doc))
        edn-file (io/file (:edn/path doc))]
    (is (.exists markdown-file) (str "Markdown file missing: " markdown-file))
    (is (.exists edn-file) (str "EDN file missing: " edn-file))
    (let [payload (read-edn edn-file)]
      (is (= (:doc/title doc) (:doc/title payload)))
      (is (= (:doc/template doc) (:doc/template payload)))
      (is (= (:doc/template-instance doc) (:doc/template-instance payload))))))

(deftest docgen-type-docs-produce-artifacts
  (support/with-test-conn
   (fn [conn]
     (testing "type doc action emits Markdown/EDN outputs"
       (let [{:keys [result]} (actions/execute!
                               {:conn conn
                                :action/ident :action/docgen.types
                                :config {:mission/id "M-DOCGEN"}
                                :permissions #{:permission/docs.write}})]
         (is (= :status/ok (:action/status result)))
         (is (seq (:docs/generated result)))
         (doseq [doc (:docs/generated result)]
           (assert-doc-output doc)))))))

(deftest docgen-mission-docs-produce-artifacts
  (support/with-test-conn
   (fn [conn]
     (testing "mission doc action emits Markdown/EDN outputs"
       (let [{:keys [result]} (actions/execute!
                               {:conn conn
                                :action/ident :action/docgen.missions
                                :config {:mission/id "M-DOCGEN"}
                                :permissions #{:permission/docs.write}})]
         (is (= :status/ok (:action/status result)))
         (is (seq (:docs/generated result)))
         (doseq [doc (:docs/generated result)]
           (assert-doc-output doc)))))))
