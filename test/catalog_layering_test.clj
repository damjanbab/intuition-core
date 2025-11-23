(ns catalog-layering-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [intuition.code.runtime :as code]))

(def ^:private allowed-layers #{:layer/l0 :layer/l1 :layer/l2 :layer/l3})
(def ^:private allowed-stability #{:stability/experimental :stability/beta :stability/ga :stability/frozen})
(def ^:private sandbox-profiles #{:sandbox/read-only :sandbox/workspace-write :sandbox/full})

(defn- read-llm-surfaces
  []
  (-> "resources/dictionary/llm_surfaces.edn"
      io/file
      slurp
      edn/read-string))

(deftest code-type-catalog-has-layering-and-stability
  (let [types (code/code-types)]
    (is (seq types) "CodeType catalog should not be empty")
    (testing "every CodeType declares governance metadata (SYSTEM_SPEC §§2.1–2.2, §§3.3–3.6, §4.7, §5, §8.1, §9, §11)"
      (doseq [entry types]
        (is (allowed-layers (:code.type/layer entry)) (str "Missing or invalid layer on " (:code.type/ident entry)))
        (is (allowed-stability (:code.type/stability entry)) (str "Missing stability on " (:code.type/ident entry)))
        (is (seq (:code.type/required-tools entry)) (str "Missing required-tools on " (:code.type/ident entry)))
        (is (sandbox-profiles (:code.type/sandbox-profile entry)) (str "Missing sandbox-profile on " (:code.type/ident entry)))
        (is (map? (:code.type/input-schema entry)) (str "Missing input-schema on " (:code.type/ident entry)))
        (is (map? (:code.type/output-schema entry)) (str "Missing output-schema on " (:code.type/ident entry)))
        (is (map? (:code.type/side-effects entry)) (str "Missing side-effects on " (:code.type/ident entry)))))
    (testing "live catalog excludes drafts/fixtures"
      (doseq [entry types]
        (is (not= :entity.status/draft (:entity/status entry))
            (str "Draft CodeType should be quarantined: " (:code.type/ident entry)))))))

(deftest llm-surfaces-carry-recipe-contract
  (let [surfaces (:llm.surfaces/entries (read-llm-surfaces))]
    (is (seq surfaces) "LLM surfaces catalog should not be empty")
    (doseq [surface surfaces]
      (is (allowed-layers (:llm.surface/layer surface)) (str "Missing layer on " (:llm.surface/ident surface)))
      (is (allowed-stability (:llm.surface/stability surface)) (str "Missing stability on " (:llm.surface/ident surface)))
      (is (seq (:llm.surface/required-tools surface)) (str "Missing required-tools on " (:llm.surface/ident surface)))
      (is (sandbox-profiles (:llm.surface/sandbox-profile surface)) (str "Missing sandbox-profile on " (:llm.surface/ident surface)))
      (is (map? (:llm.surface/input-schema surface))
          (str "Missing input-schema on " (:llm.surface/ident surface)))
      (is (map? (:llm.surface/output-schema surface))
          (str "Missing output-schema on " (:llm.surface/ident surface)))
      (is (map? (:llm.surface/side-effects surface))
          (str "Missing side-effects on " (:llm.surface/ident surface))))
    ))
