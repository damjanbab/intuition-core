(ns recipe-contract-test
  (:require
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [intuition.dictionary.meta-types :as meta]
   [intuition.recipes.runtime :as recipes]))

(deftest recipe-types-are-seeded
  (let [{:keys [types]} (meta/validate-bundle! (meta/load-bundle))
        definition (:recipe/definition types)
        step (:recipe/step types)]
    (is definition "Recipe TypeDefinition should be present")
    (is step "Recipe step TypeDefinition should be present")
    (is (set/subset? #{:recipe/plan-ops :recipe/validations :recipe/audit}
                     (set (:type/attributes definition)))
        "Recipe definition must cite plan ops/validations/audit attributes")
    (is (set/subset? #{:recipe.step/op :recipe.step/inputs :recipe.step/limits}
                     (set (:type/attributes step)))
        "Recipe step TypeDefinition should expose op/io/limits")))

(deftest catalog-respects-contract
  (let [{:keys [recipes]} (recipes/validate-catalog! (recipes/load-catalog))
        idents (set (map :recipe/ident recipes))]
    (is (seq recipes) "Catalog should not be empty")
    (is (contains? idents :recipe/plan.llm-draft))
    (is (contains? idents :recipe/pattern.map-plan))
    (is (contains? idents :recipe/domain.code-refactor))
    (is (contains? idents :recipe/extension.code-refactor-reviewed))
    (testing "plan ops remain within allowed set"
      (doseq [recipe recipes
              :let [ops (get-in recipe [:recipe/plan :ops])]]
        (is (set/subset? ops recipes/allowed-ops)
            (str "Unexpected op for " (:recipe/ident recipe)))))
    (testing "required capabilities are declared"
      (doseq [recipe recipes]
        (is (seq (:recipe/required-capabilities recipe))
            (str "Missing capabilities on " (:recipe/ident recipe)))))))
