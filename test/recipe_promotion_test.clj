(ns recipe-promotion-test
  (:require
   [clojure.test :refer [deftest is]]
   [intuition.planner.router :as router]
   [intuition.recipes.runtime :as recipes]))

(def ^:private catalog
  (:recipes (recipes/validate-catalog! (recipes/load-catalog))))

(def ^:private promotion-edges
  #{[:stability/experimental :stability/beta]
    [:stability/beta :stability/ga]
    [:stability/ga :stability/frozen]})

(deftest promotion-paths-cover-stability
  (doseq [recipe catalog
          :let [edges (set (map (juxt :from :to)
                                (get-in recipe [:recipe/promotion :promotion/rules])))]]
    (doseq [edge promotion-edges]
      (is (contains? edges edge)
          (str "Missing promotion edge " edge " for " (:recipe/ident recipe))))))

(deftest high-side-effect-experimental-blocked-when-beta-required
  (let [result (router/route {:request {:intent/tags [:intent/code-change :intent/refactor]
                                        :constraints {:sandbox :sandbox/workspace-write
                                                      :capabilities #{:capability/code :capability/llm :capability/fs}
                                                      :tools/required #{:tool/fs :tool/clojure}
                                                      :min-stability :stability/beta}}
                              :recipes catalog})]
    (is (= :planner/decision.ephemeral (:planner/decision result)))
    (is (= :sandbox/read-only (get-in (:planner/recipe result) [:recipe/limits :sandbox])))))
