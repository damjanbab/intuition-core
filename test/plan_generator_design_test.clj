(ns plan-generator-design-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(def heuristics-path "missions/logs/M-20251121-701/planner-heuristics.edn")
(def generation-log-schema-path "missions/logs/M-20251121-701/planner-generation-log-schema.edn")

(defn- load-edn [path]
  (edn/read-string (slurp path)))

(defn- assert-required-keys!
  [m ks label]
  (doseq [k ks]
    (is (contains? m k) (str label " missing key " k))
    (is (some? (get m k)) (str label " key " k " is nil"))))

(deftest heuristics-edn-present-and-shaped
  (let [file (io/file heuristics-path)]
    (is (.exists file) "planner-heuristics.edn is missing")
    (let [data (load-edn file)
          required [:heuristics/id
                    :heuristics/version
                    :mission/id
                    :source
                    :scope-grouping
                    :risk-splitting
                    :track-inference
                    :test-inference
                    :edges
                    :locks
                    :templates
                    :coverage
                    :generation-log]]
      (is (map? data) "planner-heuristics.edn should contain a map")
      (assert-required-keys! data required "planner-heuristics.edn")
      (testing "nested heuristic maps are non-empty"
        (is (seq (:source data)))
        (is (seq (:scope-grouping data)))
        (is (seq (:risk-splitting data)))
        (is (seq (:templates data)))
        (is (seq (:generation-log data))))
      (testing "generation log section lists required keys"
        (let [gl (:generation-log data)]
          (assert-required-keys! gl [:required :optional] "generation-log block")
          (is (seq (:required gl)) "generation-log.required should list keys"))))))

(deftest generation-log-schema-present-and-shaped
  (let [file (io/file generation-log-schema-path)]
    (is (.exists file) "planner-generation-log-schema.edn is missing")
    (let [data (load-edn file)
          required [:schema/id :schema/version :mission/id :required-keys :log-fields :invariants :relations]]
      (is (map? data) "planner-generation-log-schema.edn should contain a map")
      (assert-required-keys! data required "planner-generation-log-schema.edn")
      (testing "log-fields are populated"
        (is (seq (:log-fields data)))
        (is (every? some? (vals (:log-fields data))) "log-fields entries should be non-nil"))
      (testing "required-keys are declared"
        (is (seq (:required-keys data)))))))
