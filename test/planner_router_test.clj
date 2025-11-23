(ns planner-router-test
  (:require
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [intuition.planner.router :as router]
   [intuition.recipes.runtime :as recipes]))

(def ^:private catalog
  (:recipes (recipes/validate-catalog! (recipes/load-catalog))))

(deftest selects-matching-domain-recipe
  (let [result (router/route {:request {:intent/tags [:intent/code-change :intent/refactor]
                                        :constraints {:sandbox :sandbox/workspace-write
                                                      :capabilities #{:capability/code :capability/llm :capability/fs}
                                                      :tools/required #{:tool/fs :tool/clojure}
                                                      :languages #{:lang/clojure}}}
                              :recipes catalog})
        selected (:planner/recipe result)]
    (is (= :planner/decision.match (:planner/decision result)))
    (is (= :recipe/domain.code-refactor (:recipe/ident selected)))
    (is (map? (:planner/trace result))))
  (testing "plan ops are returned in candidate summaries"
    (let [result (router/route {:request {:intent/tags [:intent/plan]
                                          :constraints {:sandbox :sandbox/read-only
                                                        :capabilities #{:capability/llm :capability/map}}}
                                :recipes catalog})
          ids (set (map :recipe/ident (:planner/candidates result)))]
      (is (contains? ids :recipe/plan.llm-draft))
      (is (contains? ids :recipe/pattern.map-plan)))))

(deftest classifier-steers-selection
  (let [score-fn (fn [_ recipe]
                   (if (= :recipe/pattern.map-plan (:recipe/ident recipe))
                     0.9
                     0.1))
        result (router/route {:request {:intent/tags [:intent/plan]
                                        :constraints {:sandbox :sandbox/read-only
                                                      :capabilities #{:capability/llm :capability/map}}}
                              :recipes catalog
                              :classifier {:score-fn score-fn
                                           :threshold 0.5}})
        selected (:recipe/ident (:planner/recipe result))]
    (is (= :recipe/pattern.map-plan selected))
    (is (some #(= selected (:recipe/ident %)) (:planner/candidates result)))))

(deftest synthesizes-ephemeral-when-no-match
  (let [result (router/route {:request {:intent/tags [:intent/unknown]
                                        :constraints {:sandbox :sandbox/read-only}}})
        recipe (:planner/recipe result)]
    (is (= :planner/decision.ephemeral (:planner/decision result)))
    (is (:recipe/ephemeral? recipe))
    (is (<= (get-in recipe [:recipe/limits :tokens]) 2048))
    (is (set/subset? (get-in recipe [:recipe/plan :ops]) recipes/allowed-ops))))
