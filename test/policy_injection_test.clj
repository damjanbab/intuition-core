(ns policy-injection-test
  (:require
   [clojure.test :refer [deftest is]]
   [intuition.recipes.runtime :as recipes]))

(def ^:private catalog
  (:recipes (recipes/validate-catalog! (recipes/load-catalog))))

(deftest catalog-policies-present
  (doseq [recipe catalog
          :let [policies (:recipe/policies recipe)]]
    (is (seq (:policy/approvals policies))
        (str (:recipe/ident recipe) " missing approvals policy"))
    (is (seq (:policy/tests policies))
        (str (:recipe/ident recipe) " missing tests policy"))))

(deftest llm-self-report-required
  (let [base (recipes/synthesize-ephemeral {:intent/tags [:intent/test]})
        stripped (-> base
                     (assoc :recipe/side-effects {:writes [] :commands []})
                     (assoc-in [:recipe/audit :trace] [:inputs]))]
    (is (thrown? Exception (recipes/validate-recipe! stripped))
        "LLM recipes without meta/self-report should be rejected")))

(deftest ephemeral-recipe-is-read-only-and-governed
  (let [recipe (recipes/synthesize-ephemeral {:intent/tags [:intent/test]})
        policies (:recipe/policies recipe)]
    (is (= :sandbox/read-only (get-in recipe [:recipe/limits :sandbox])))
    (is (some #{:meta/self-report} (get-in recipe [:recipe/side-effects :audit])))
    (is (seq (:policy/tests policies)))
    (is (seq (:policy/approvals policies)))))
