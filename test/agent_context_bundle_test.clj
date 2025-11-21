(ns agent-context-bundle-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [intuition.gateway.context-bundle :as context-bundle]
   [support.datomic :as support]))

(def ^:private mission-id "M-20251121-805")
(def ^:private focus-ident :mission/M-20251121-805)
(def ^:private bundle-path "tmp/agent-context-bundle.edn")

(defn- canonical
  [path]
  (.getCanonicalPath (io/file path)))

(defn- load-edn
  [path]
  (edn/read-string (slurp path)))

(defn- assert-keys!
  [m ks label]
  (doseq [k ks]
    (is (contains? m k) (str label " missing key " k))))

(deftest agent-context-bundle-shape-and-stability
  (testing "context bundle captures graph neighborhood, artifacts, and stays stable"
    (support/with-test-conn
      (fn [conn]
        (let [file (io/file bundle-path)]
          (when (.exists file)
            (io/delete-file file true))
          (let [result (context-bundle/build! {:mission/id mission-id
                                               :focus/node focus-ident
                                               :conn conn
                                               :log/root "missions/logs"
                                               :output/path bundle-path})
                data (load-edn bundle-path)
                required [:bundle/id :bundle/version :mission/id :mission/record
                          :spec/fragment :plan/nodes :graph/slice :code/definitions
                          :code/types :artifacts/validation :system-spec/sections]]
            (is (.exists file) "bundle file should be written")
            (is (= (canonical bundle-path) (:bundle/path result)))
            (assert-keys! data required "agent context bundle")
            (is (= "agent-context-bundle/v1" (:bundle/id data)))
            (is (= mission-id (:mission/id data)))
            (is (seq (:plan/nodes data)) "plan nodes should be present")
            (is (seq (:code/definitions data)) "code definitions should be collected")
            (is (seq (:code/types data)) "code types should be collected")
            (is (seq (get-in data [:graph/slice :nodes])) "graph slice should include nodes")
            (is (seq (get-in data [:artifacts/validation])) "validation artifacts should be captured")
            (is (every? :artifact/present? (:artifacts/validation data)) "artifacts must be present on disk")
            (testing "bundle is stable across runs for the same mission state"
              (let [again (context-bundle/build! {:mission/id mission-id
                                                  :focus/node focus-ident
                                                  :conn conn
                                                  :log/root "missions/logs"
                                                  :output/path bundle-path})]
                (is (= (dissoc data :bundle/path :bundle/sha256)
                       (dissoc again :bundle/path :bundle/sha256)))))))))))
