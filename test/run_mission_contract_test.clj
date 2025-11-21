(ns run-mission-contract-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

(def bundle-path "missions/logs/M-20251121-801/context-bundle.edn")
(def schema-path "missions/logs/M-20251121-801/context-bundle-schema.edn")
(def contract-path "missions/logs/M-20251121-801/run-mission-contract.edn")

(defn- load-edn
  [path]
  (edn/read-string (slurp path)))

(defn- assert-required-keys!
  [m ks label]
  (doseq [k ks]
    (is (contains? m k) (str label " missing key " k))
    (is (some? (get m k)) (str label " key " k " is nil"))))

(deftest context-bundle-present-and-shaped
  (let [file (io/file bundle-path)
        schema (load-edn schema-path)]
    (is (.exists file) "context-bundle.edn is missing")
    (let [data (load-edn bundle-path)
          required (:required-keys schema)]
      (is (map? data) "context-bundle.edn should contain a map")
      (assert-required-keys! data required "context-bundle.edn")
      (testing "stages cover the orchestrator flow"
        (let [stages (set (:run/stages data))
              expected #{:spec/load :plan/validate :plan/snapshot :mission/instantiate :mission/standard :merge/simulate :analytics/emit}]
          (is (every? #(contains? stages %) expected) "run/stages must include the full pipeline")))
      (testing "artifacts are declared with required fields"
        (let [entries (:artifacts/expected data)]
          (is (seq entries) "artifacts/expected must be non-empty")
          (doseq [entry entries]
            (assert-required-keys! entry [:path :label :channel] "artifact entry")))))))

(deftest bundle-schema-present-and-shaped
  (let [file (io/file schema-path)]
    (is (.exists file) "context-bundle-schema.edn is missing")
    (let [data (load-edn schema-path)
          required [:schema/id :schema/version :mission/id :required-keys :field-spec :invariants :references]]
      (is (map? data) "context-bundle-schema.edn should contain a map")
      (assert-required-keys! data required "context-bundle-schema.edn")
      (testing "required fields are listed"
        (is (seq (:required-keys data)) "required-keys should not be empty"))
      (testing "field-spec entries are populated"
        (is (seq (:field-spec data)) "field-spec should not be empty")
        (is (every? some? (vals (:field-spec data))) "field-spec values should be non-nil")))))

(deftest run-mission-contract-present-and-shaped
  (let [file (io/file contract-path)]
    (is (.exists file) "run-mission-contract.edn is missing")
    (let [data (load-edn contract-path)
          required [:contract/id :contract/version :mission/id :lifecycle/stages :logging :idempotency :permissions :gateway :system-spec/sections]]
      (is (map? data) "run-mission-contract.edn should contain a map")
      (assert-required-keys! data required "run-mission-contract.edn")
      (testing "lifecycle stages align with bundle stages"
        (let [stage-ids (set (map :stage/id (:lifecycle/stages data)))
              bundle (load-edn bundle-path)
              bundle-stages (set (:run/stages bundle))]
          (is (every? #(contains? stage-ids %) bundle-stages) "contract stages must cover bundle stages")))
      (testing "gateway contract declares cli and mcp"
        (assert-required-keys! (:gateway data) [:cli :mcp] "gateway block")))))
