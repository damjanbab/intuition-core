(ns intuition.planner.router
  "Router that selects a governed recipe from the catalog based on tags and
   constraints, optionally applying a classifier, and synthesizing an
   ephemeral recipe when no candidate fits. Aligns with SYSTEM_SPEC
   §§2.1–2.2, §§3.3–3.6, §4.7, §5.1, §5.3, §8.1, §9, and §11."
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [intuition.recipes.runtime :as recipes]))

(def ^:private sandbox-rank
  {:sandbox/read-only 0
   :sandbox/workspace-write 1
   :sandbox/full 2})

(def ^:private stability-rank
  {:stability/experimental 0
   :stability/beta 1
   :stability/ga 2
   :stability/frozen 3})

(def ^:private layer-rank
  {:layer/l0 0
   :layer/l1 1
   :layer/l2 2
   :layer/l3 3})

(defn- kw-vec
  [values]
  (->> values
       (keep (fn [entry]
               (cond
                 (keyword? entry) entry
                 (string? entry) (keyword (str/replace (str/trim entry) #"^:" ""))
                 :else nil)))
       vec))

(defn- normalize-request
  [request]
  (let [tags (kw-vec (:intent/tags request))
        constraints (:constraints request)
        sandbox (:sandbox constraints)
        capabilities (set (kw-vec (:capabilities constraints)))
        required-tools (set (kw-vec (:tools/required constraints)))
        forbidden-tools (set (kw-vec (:tools/disallowed constraints)))
        languages (set (kw-vec (:languages constraints)))
        min-stability (:min-stability constraints)
        allow-unpromoted? (boolean (:allow-unpromoted-writes? constraints))]
    {:intent/tags tags
     :constraints {:sandbox sandbox
                   :capabilities capabilities
                   :tools/required required-tools
                   :tools/disallowed forbidden-tools
                   :languages languages
                   :min-stability min-stability
                   :allow-unpromoted-writes? allow-unpromoted?}
     :context (:context request)}))

(defn- sandbox-allowed?
  [{:keys [constraints]} recipe]
  (let [requested (:sandbox constraints)
        recipe-sandbox (get-in recipe [:recipe/limits :sandbox])
        recipe-constraint (get-in recipe [:recipe/match :constraints :sandbox])]
    (and (or (nil? requested)
             (<= (get sandbox-rank recipe-sandbox 99)
                 (get sandbox-rank requested 99)))
         (or (nil? recipe-constraint)
             (some #(= % recipe-sandbox) recipe-constraint)))))

(defn- tags-match?
  [{:keys [intent/tags]} recipe]
  (let [recipe-tags (set (or (:recipe/intent-tags recipe) []))
        match-tags (set (or (get-in recipe [:recipe/match :tags]) recipe-tags))]
    (seq (set/intersection (set tags) (set/intersection recipe-tags match-tags)))))

(defn- capabilities-allowed?
  [{:keys [constraints]} recipe]
  (let [available (:capabilities constraints)
        recipe-required (set (:recipe/required-capabilities recipe))
        match-required (set (get-in recipe [:recipe/match :constraints :capabilities]))]
    (and (or (empty? available)
             (set/subset? recipe-required available))
         (or (empty? match-required)
             (empty? available)
             (set/subset? match-required available)))))

(defn- tools-allowed?
  [{:keys [constraints]} recipe]
  (let [allowed (set (or (get-in recipe [:recipe/limits :allowed-tools]) []))
        required (:tools/required constraints)
        disallowed (:tools/disallowed constraints)]
    (and (or (empty? required) (set/subset? required allowed))
         (or (empty? disallowed) (empty? (set/intersection disallowed allowed))))))

(defn- languages-allowed?
  [{:keys [constraints]} recipe]
  (let [required (set (or (:languages constraints) []))
        recipe-langs (set (or (get-in recipe [:recipe/match :constraints :languages]) []))]
    (cond
      (and (seq recipe-langs) (seq required)) (seq (set/intersection recipe-langs required))
      (seq recipe-langs) true
      :else true)))

(defn- high-side-effect?
  [recipe]
  (let [side-effects (or (:recipe/side-effects recipe) {})
        writes (:writes side-effects)
        commands (:commands side-effects)
        sandbox (get-in recipe [:recipe/limits :sandbox])]
    (or (seq writes)
        (and (not= :sandbox/read-only sandbox)
             (seq commands)))))

(defn- stability-allowed?
  [{:keys [constraints]} recipe]
  (let [min-stability (:min-stability constraints)]
    (if min-stability
      (>= (get stability-rank (:recipe/stability recipe) 0)
          (get stability-rank min-stability 0))
      true)))

(defn- promotion-allowed?
  [request recipe]
  (let [allow-unpromoted? (true? (get-in request [:constraints :allow-unpromoted-writes?]))]
    (and (stability-allowed? request recipe)
         (if (and (= :stability/experimental (:recipe/stability recipe))
                  (high-side-effect? recipe))
           allow-unpromoted?
           true))))

(defn- rejection-reason
  [request recipe]
  (cond
    (not (tags-match? request recipe)) :intent/tags
    (not (sandbox-allowed? request recipe)) :constraints/sandbox
    (not (capabilities-allowed? request recipe)) :constraints/capabilities
    (not (tools-allowed? request recipe)) :constraints/tools
    (not (languages-allowed? request recipe)) :constraints/language
    (not (promotion-allowed? request recipe)) :constraints/stability
    :else nil))

(defn- score-candidate
  [classifier request recipe]
  (let [score-fn (or (:score-fn classifier) (:fn classifier))]
    (when (fn? score-fn)
      (try
        (let [score (score-fn request recipe)]
          (when (number? score) score))
        (catch Exception _
          nil)))))

(defn- apply-classifier
  [classifier request candidates]
  (let [threshold (:threshold classifier)]
    (mapv (fn [recipe]
            (let [score (score-candidate classifier request recipe)
                  recipe-threshold (or threshold
                                        (get-in recipe [:recipe/match :classifier :threshold])
                                        0.0)
                  passes? (or (nil? score) (>= score recipe-threshold))]
              {:recipe recipe
               :score score
               :threshold recipe-threshold
               :passes? passes?}))
          candidates)))

(defn- select-best
  [candidates]
  (->> candidates
       (filter :passes?)
       (sort-by (fn [{:keys [score recipe]}]
                  [(- (or score -1.0))
                   (- (get stability-rank (:recipe/stability recipe) 0))
                   (- (get layer-rank (:recipe/layer recipe) 0))
                   (- (:recipe/version recipe 0))]))
       first))

(defn- candidate-summary
  [{:keys [recipe score threshold passes?]}]
  {:recipe/ident (:recipe/ident recipe)
   :recipe/layer (:recipe/layer recipe)
   :recipe/stability (:recipe/stability recipe)
   :recipe/version (:recipe/version recipe)
   :score score
   :threshold threshold
   :passes? passes?})

(defn route
  "Routes a request to a recipe catalog. Options:
   {:request {...} :recipes [...] :classifier {:score-fn f :threshold 0.5}}"
  [{:keys [request recipes classifier catalog]}]
  (let [normalized (normalize-request request)
        catalog-input (cond
                        recipes {:entries recipes}
                        catalog catalog
                        :else (recipes/load-catalog))
        validation (recipes/validate-catalog! catalog-input)
        validated (:recipes validation)
        evaluation (reduce (fn [acc recipe]
                             (if-let [reason (rejection-reason normalized recipe)]
                               (update acc :rejected conj {:recipe/ident (:recipe/ident recipe)
                                                           :reason reason})
                               (update acc :candidates conj recipe)))
                           {:candidates [] :rejected []}
                           validated)
        classified (apply-classifier classifier normalized (:candidates evaluation))
        selection (select-best classified)
        chosen (or (:recipe selection)
                   (recipes/synthesize-ephemeral normalized))
        decision (if (:recipe selection)
                   :planner/decision.match
                   :planner/decision.ephemeral)]
    {:planner/decision decision
     :planner/recipe chosen
     :planner/candidates (mapv candidate-summary classified)
     :planner/trace {:rejected (:rejected evaluation)
                     :classifier (when classifier (dissoc classifier :score-fn))
                     :resource (:resource validation)}}))

(defn route-action
  "Action handler wrapper for route."
  [{:keys [config log!]}]
  (let [result (route {:request (:planner/request config)
                       :recipes (:planner/recipes config)
                       :classifier (:planner/classifier config)
                       :catalog (:planner/catalog config)})
        recipe-ident (:recipe/ident (:planner/recipe result))]
    (when log!
      (log! :planner/route {:decision (:planner/decision result)
                            :recipe recipe-ident}))
    {:action/status :status/ok
     :planner/decision (:planner/decision result)
     :planner/recipe recipe-ident
     :planner/route result}))
