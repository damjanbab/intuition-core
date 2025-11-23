(ns intuition.recipes.runtime
  "Recipe contract runtime that loads the governed catalog from
   resources/dictionary/recipes.edn, enforces the schema described in
   SYSTEM_SPEC §§2.1–2.2, §§3.3–3.6, §4.7, §5.1, §5.3, §8.1, §9, and §11,
   and provides helpers for planner/router components."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str])
  (:import
   (java.io PushbackReader)))

(def catalog-resource "dictionary/recipes.edn")

(def allowed-ops
  "Allowed plan operations per the recipe contract."
  #{:op/llm-call
    :op/tool-call
    :op/map
    :op/reduce
    :op/branch
    :op/eval-code
    :op/retry
    :op/select-best})

(def ^:private allowed-stability
  #{:stability/experimental :stability/beta :stability/ga :stability/frozen})

(def ^:private promotion-edges
  #{[:stability/experimental :stability/beta]
    [:stability/beta :stability/ga]
    [:stability/ga :stability/frozen]})

(def ^:private allowed-layers
  #{:layer/l0 :layer/l1 :layer/l2 :layer/l3})

(def ^:private allowed-sandbox
  #{:sandbox/read-only :sandbox/workspace-write :sandbox/full})

(def ^:private default-ephemeral-limits
  {:tokens 2048
   :wall-clock-ms 60000
   :files-max 8})

(defn- canonical-path
  [path]
  (some-> path io/file .getCanonicalPath))

(defn- resolve-resource
  [path]
  (or (io/resource path)
      (io/resource (str "resources/" path))
      (let [f (io/file path)]
        (when (.exists f) f))
      (let [f (io/file "resources" path)]
        (when (.exists f) f))))

(defn load-catalog
  "Loads the recipe catalog EDN. Returns {:entries [...] :meta {...} :resource <string>}."
  ([] (load-catalog catalog-resource))
  ([path]
   (let [url (resolve-resource path)]
     (when-not url
       (throw (ex-info "Missing recipe catalog resource" {:resource path})))
     (with-open [r (PushbackReader. (io/reader url))]
       (let [data (edn/read {:eof nil} r)
             entries (cond
                       (map? data) (:recipe.catalog/entries data)
                       (sequential? data) data
                       :else nil)
             meta (when (map? data) (dissoc data :recipe.catalog/entries))]
         (when (empty? entries)
           (throw (ex-info "Recipe catalog is empty" {:resource path})))
         {:entries (vec entries)
          :meta meta
          :resource (or (canonical-path (str url))
                        (str url))})))))

(defn- keyword-coll
  [value]
  (->> value
       (keep (fn [entry]
               (cond
                 (keyword? entry) entry
                 (string? entry) (keyword (str/replace (str/trim entry) #"^:" ""))
                 :else nil)))
       vec))

(defn- positive-long?
  [v]
  (and (integer? v) (pos? v)))

(defn- require-present!
  [pred value message ctx]
  (when-not (pred value)
    (throw (ex-info message ctx))))

(defn- validate-limits!
  [ident {:recipe/keys [limits]} {:keys [ephemeral?]}]
  (require-present! map? limits "Recipe limits are required" {:recipe/ident ident})
  (let [{:keys [sandbox tokens wall-clock-ms files-max allowed-tools]} limits]
    (require-present! #(allowed-sandbox %) sandbox "Sandbox profile is required and must be governed" {:recipe/ident ident})
    (require-present! positive-long? tokens "Token budget must be a positive integer" {:recipe/ident ident})
    (require-present! positive-long? wall-clock-ms "Wall clock budget must be a positive integer" {:recipe/ident ident})
    (require-present! (fn [v] (and (integer? v) (<= 0 v))) files-max "files-max must be non-negative" {:recipe/ident ident})
    (when-not (every? keyword? (or allowed-tools []))
      (throw (ex-info "allowed-tools must be keywords" {:recipe/ident ident})))
    (when (empty? allowed-tools)
      (throw (ex-info "allowed-tools cannot be empty" {:recipe/ident ident})))
    (when ephemeral?
      (doseq [[k threshold] default-ephemeral-limits]
        (let [actual (get limits k Long/MAX_VALUE)]
          (when (pos? threshold)
            (when (> actual threshold)
              (throw (ex-info "Ephemeral recipe exceeds tight budget"
                              {:recipe/ident ident
                               :budget/key k
                               :budget/threshold threshold
                               :budget/actual actual}))))))))
  limits)

(defn- validate-match!
  [ident match]
  (require-present! map? match "Recipe match block required" {:recipe/ident ident})
  (let [tags (keyword-coll (:tags match))
        constraints (:constraints match)
        classifier (:classifier match)]
    (require-present! seq tags "Recipe match tags cannot be empty" {:recipe/ident ident})
    (when constraints
      (when-let [sandbox (:sandbox constraints)]
        (when-not (every? allowed-sandbox (if (coll? sandbox) sandbox [sandbox]))
          (throw (ex-info "Recipe match sandbox constraints invalid"
                          {:recipe/ident ident
                           :constraints constraints}))))
      (when-let [langs (:languages constraints)]
        (when-not (every? keyword? langs)
          (throw (ex-info "Recipe match languages must be keywords"
                          {:recipe/ident ident
                           :constraints constraints}))))
      (when-let [forbidden (:tools/disallowed constraints)]
        (when-not (every? keyword? forbidden)
          (throw (ex-info "Recipe match disallowed tools must be keywords"
                          {:recipe/ident ident
                           :constraints constraints}))))
      (when-let [required (:tools/required constraints)]
        (when-not (every? keyword? required)
          (throw (ex-info "Recipe match required tools must be keywords"
                          {:recipe/ident ident
                           :constraints constraints})))))
    (when classifier
      (when-not (map? classifier)
        (throw (ex-info "Classifier config must be a map"
                        {:recipe/ident ident :classifier classifier})))
      (when-let [threshold (:threshold classifier)]
        (when-not (number? threshold)
          (throw (ex-info "Classifier threshold must be numeric"
                          {:recipe/ident ident :classifier classifier})))))
    (assoc match :tags tags)))

(defn- validate-io-schema!
  [ident field schema]
  (require-present! map? schema (str (name field) " must be a map") {:recipe/ident ident})
  (let [required (keyword-coll (:required schema))
        produces (keyword-coll (:produces schema))]
    (when (and (= field :recipe/input-schema) (empty? required))
      (throw (ex-info "Input schema must declare required fields" {:recipe/ident ident})))
    (when (and (= field :recipe/output-schema) (empty? produces))
      (throw (ex-info "Output schema must declare produced fields" {:recipe/ident ident})))
    (-> schema
        (cond-> required (assoc :required required)
                produces (assoc :produces produces)))))

(defn- validate-step!
  [ident limits ops allowed-tools step]
  (require-present! keyword? (:recipe.step/id step) "Step id is required" {:recipe/ident ident :step step})
  (require-present! #(contains? allowed-ops %) (:recipe.step/op step) "Step op must be in allowed op set" {:recipe/ident ident :step step})
  (when-not (contains? ops (:recipe.step/op step))
    (throw (ex-info "Step op not declared in recipe plan ops"
                    {:recipe/ident ident
                     :recipe/ops ops
                     :recipe.step/op (:recipe.step/op step)})))
  (doseq [[field value] [[:recipe.step/inputs (:recipe.step/inputs step)]
                         [:recipe.step/outputs (:recipe.step/outputs step)]]]
    (require-present! #(or (nil? %) (vector? %)) value "Step IO must be vectors" {:recipe/ident ident :step step :field field})
    (when value
      (when-not (every? keyword? value)
        (throw (ex-info "Step IO entries must be keywords"
                        {:recipe/ident ident :step step :field field})))))
  (when-let [tools (:recipe.step/tools step)]
    (when-not (every? keyword? tools)
      (throw (ex-info "Step tools must be keywords"
                      {:recipe/ident ident :step step}))))
  (when-let [tools (:recipe.step/tools step)]
    (when (and (seq allowed-tools)
               (not (set/subset? (set tools) allowed-tools)))
      (throw (ex-info "Step uses tools not declared in recipe limits"
                      {:recipe/ident ident
                       :recipe.step/id (:recipe.step/id step)
                       :recipe.step/tools tools
                       :recipe/allowed-tools allowed-tools}))))
  (when-let [bindings (:recipe.step/bindings step)]
    (when-not (map? bindings)
      (throw (ex-info "Step bindings must be a map"
                      {:recipe/ident ident :step step}))))
  (when-let [validations (:recipe.step/validations step)]
    (when-not (every? keyword? validations)
      (throw (ex-info "Step validations must be keywords"
                      {:recipe/ident ident :step step}))))
  (when-let [budget (:recipe.step/limits step)]
    (when-not (map? budget)
      (throw (ex-info "Step limits must be a map"
                      {:recipe/ident ident :step step})))
    (when-let [tokens (:tokens budget)]
      (require-present! positive-long? tokens "Step token budget must be positive"
                        {:recipe/ident ident :step step}))
    (when-let [time-ms (:time-ms budget)]
      (require-present! positive-long? time-ms "Step time budget must be positive"
                        {:recipe/ident ident :step step}))
    (when-let [files (:files-max budget)]
      (require-present! (fn [v] (and (integer? v) (<= 0 v))) files "Step files-max must be non-negative"
                        {:recipe/ident ident :step step}))
    (let [{:keys [tokens wall-clock-ms files-max]} limits]
      (when (and tokens (:tokens budget) (> (:tokens budget) tokens))
        (throw (ex-info "Step token budget exceeds recipe limit"
                        {:recipe/ident ident :step (:recipe.step/id step)})))
      (when (and wall-clock-ms (:time-ms budget) (> (:time-ms budget) wall-clock-ms))
        (throw (ex-info "Step time budget exceeds recipe limit"
                        {:recipe/ident ident :step (:recipe.step/id step)})))
      (when (and files-max (:files-max budget) (> (:files-max budget) files-max))
        (throw (ex-info "Step file budget exceeds recipe limit"
                        {:recipe/ident ident :step (:recipe.step/id step)})))))
  step)

(defn- validate-plan!
  [ident {:recipe/keys [limits] :as recipe}]
  (let [plan (:recipe/plan recipe)]
    (require-present! map? plan "Recipe plan is required" {:recipe/ident ident})
    (let [ops (set (:ops plan))
          _ (when (empty? ops)
              (throw (ex-info "Recipe plan ops are required" {:recipe/ident ident})))
          _ (when-not (set/subset? ops allowed-ops)
              (throw (ex-info "Recipe plan ops contain unsupported entries"
                              {:recipe/ident ident :ops ops})))
          steps (vec (:steps plan))
          _ (when (empty? steps)
              (throw (ex-info "Recipe plan steps are required" {:recipe/ident ident})))
          allowed-tools (set (get limits :allowed-tools))
          validated-steps (mapv #(validate-step! ident limits ops allowed-tools %) steps)
          edges (vec (or (:edges plan) []))]
      (doseq [edge edges]
        (when-not (and (vector? edge) (= 2 (count edge)))
          (throw (ex-info "Plan edges must be pairs" {:recipe/ident ident :edge edge}))))
      (let [used-tools (set (mapcat (fn [step] (or (:recipe.step/tools step) [])) validated-steps))]
        (-> recipe
            (assoc :recipe/plan {:ops ops
                                 :steps validated-steps
                                 :edges edges})
            (assoc :recipe/used-tools used-tools))))))

(defn- validate-validations!
  [ident validations]
  (require-present! map? validations "Recipe validations must be a map" {:recipe/ident ident})
  (doseq [k [:pre :post :invariants]]
    (when-let [entries (get validations k)]
      (when-not (every? keyword? entries)
        (throw (ex-info "Recipe validation entries must be keywords"
                        {:recipe/ident ident :kind k})))))
  validations)

(defn- validate-audit!
  [ident audit]
  (require-present! map? audit "Recipe audit block must be a map" {:recipe/ident ident})
  (when-let [trace (:trace audit)]
    (when-not (every? keyword? trace)
      (throw (ex-info "Audit trace entries must be keywords"
                      {:recipe/ident ident}))))
  (when-let [hash-fields (:hash-fields audit)]
    (when-not (every? keyword? hash-fields)
      (throw (ex-info "Audit hash fields must be keywords"
                      {:recipe/ident ident}))))
  audit)

(defn- validate-side-effects!
  [ident limits side-effects]
  (require-present! map? side-effects "Recipe side-effects block must be a map" {:recipe/ident ident})
  (let [{:keys [writes commands audit]} side-effects
        sandbox (:sandbox limits)]
    (doseq [[field value] [[:writes writes] [:commands commands] [:audit audit]]]
      (when value
        (when-not (vector? value)
          (throw (ex-info "Side-effect entries must be vectors"
                          {:recipe/ident ident :field field :value value})))
        (when-not (every? keyword? value)
          (throw (ex-info "Side-effect entries must be keywords"
                          {:recipe/ident ident :field field :value value})))))
    (when (and (seq writes) (= :sandbox/read-only sandbox))
      (throw (ex-info "Writes are forbidden under read-only sandbox"
                      {:recipe/ident ident
                       :recipe/side-effects side-effects
                       :sandbox sandbox})))
    (cond-> side-effects
      writes (assoc :writes (vec writes))
      commands (assoc :commands (vec commands))
      audit (assoc :audit (vec audit)))))

(defn- validate-policies!
  [ident policies]
  (require-present! map? policies "Recipe policy injection block must be a map" {:recipe/ident ident})
  (let [approvals (:policy/approvals policies)
        tests (:policy/tests policies)
        gates (:policy/gates policies)]
    (require-present! #(and (vector? %) (seq %) (every? keyword? %))
                      approvals "policy/approvals must be a non-empty vector of keywords"
                      {:recipe/ident ident})
    (require-present! #(and (vector? %) (seq %) (every? keyword? %))
                      tests "policy/tests must be a non-empty vector of keywords"
                      {:recipe/ident ident})
    (when gates
      (when-not (map? gates)
        (throw (ex-info "policy/gates must be a map"
                        {:recipe/ident ident :policy/gates gates})))
      (doseq [[k v] gates]
        (when (and (coll? v) (not (every? keyword? v)))
          (throw (ex-info "policy/gates values must be keywords or collections of keywords"
                          {:recipe/ident ident :policy/gates gates :key k :value v})))))
    (-> policies
        (assoc :policy/approvals (vec approvals)
               :policy/tests (vec tests)))))

(defn- validate-promotion!
  [ident promotion]
  (require-present! map? promotion "Recipe promotion metadata must be a map" {:recipe/ident ident})
  (let [rules (:promotion/rules promotion)]
    (require-present! #(and (vector? %) (seq %)) rules "Promotion rules must be a non-empty vector" {:recipe/ident ident})
    (doseq [rule rules]
      (require-present! keyword? (:from rule) "Promotion rule requires :from stability" {:recipe/ident ident :rule rule})
      (require-present! keyword? (:to rule) "Promotion rule requires :to stability" {:recipe/ident ident :rule rule})
      (when-not (promotion-edges [(:from rule) (:to rule)])
        (throw (ex-info "Promotion rule transition not allowed"
                        {:recipe/ident ident
                         :rule rule
                         :allowed promotion-edges})))
      (when-let [requirements (:requirements rule)]
        (require-present! #(and (vector? %) (every? keyword? %)) requirements
                          "Promotion requirements must be keywords" {:recipe/ident ident :rule rule}))
      (when-let [policies (:policies rule)]
        (when-not (map? policies)
          (throw (ex-info "Promotion policies must be a map"
                          {:recipe/ident ident :rule rule})))
        (doseq [[k v] policies]
          (when (and (coll? v) (not (every? keyword? v)))
            (throw (ex-info "Promotion policy entries must be keywords"
                            {:recipe/ident ident :rule rule :key k :value v}))))))
    (let [edges (set (map (juxt :from :to) rules))]
      (doseq [edge promotion-edges]
        (when-not (edges edge)
          (throw (ex-info "Promotion rules must cover experimental→beta→ga→frozen"
                          {:recipe/ident ident
                           :missing edge
                           :have edges})))))
    promotion))

(defn- normalize-boolean
  [v]
  (if (instance? Boolean v) v (boolean v)))

(defn validate-recipe!
  "Validates and normalizes a single recipe entry."
  ([recipe] (validate-recipe! recipe {}))
  ([recipe opts]
   (let [ident (:recipe/ident recipe)]
     (require-present! keyword? ident "recipe/ident is required" {:recipe recipe})
     (require-present! positive-long? (:recipe/version recipe) "recipe/version must be positive" {:recipe/ident ident})
     (require-present! keyword? (:recipe/owner recipe) "recipe/owner is required" {:recipe/ident ident})
     (require-present! #(contains? allowed-stability %) (:recipe/stability recipe) "recipe/stability invalid" {:recipe/ident ident})
     (require-present! #(contains? allowed-layers %) (:recipe/layer recipe) "recipe/layer invalid" {:recipe/ident ident})
     (let [intent-tags (keyword-coll (:recipe/intent-tags recipe))
           required-caps (keyword-coll (:recipe/required-capabilities recipe))
           match (validate-match! ident (:recipe/match recipe))
           input-schema (validate-io-schema! ident :recipe/input-schema (:recipe/input-schema recipe))
           output-schema (validate-io-schema! ident :recipe/output-schema (:recipe/output-schema recipe))
           limits (validate-limits! ident recipe opts)
           side-effects (validate-side-effects! ident limits (:recipe/side-effects recipe))
           policies (validate-policies! ident (:recipe/policies recipe))
           promotion (validate-promotion! ident (:recipe/promotion recipe))
           validations (validate-validations! ident (:recipe/validations recipe))
           audit (validate-audit! ident (:recipe/audit recipe))
           errors (:recipe/errors recipe)
           normalized (-> recipe
                          (assoc :recipe/intent-tags intent-tags
                                 :recipe/required-capabilities required-caps
                                 :recipe/match match
                                 :recipe/input-schema input-schema
                                 :recipe/output-schema output-schema
                                 :recipe/limits limits
                                 :recipe/side-effects side-effects
                                 :recipe/policies policies
                                 :recipe/promotion promotion
                                 :recipe/validations validations
                                 :recipe/audit audit
                                 :recipe/ephemeral? (normalize-boolean (:recipe/ephemeral? recipe)))
                          (cond-> errors (assoc :recipe/errors errors)))
           with-plan (validate-plan! ident normalized)
           used-tools (:recipe/used-tools with-plan)
           llm? (contains? used-tools :tool/llm)
           audit-trace (set (or (get-in with-plan [:recipe/audit :trace]) []))
           audit-side-effects (set (or (get-in with-plan [:recipe/side-effects :audit]) []))]
       (when (and llm?
                  (empty? (set/intersection #{:meta/self-report}
                                            (set/union audit-trace audit-side-effects))))
         (throw (ex-info "LLM tools require meta/self-report capture"
                         {:recipe/ident ident
                          :recipe/used-tools used-tools})))
       with-plan))))

(defn validate-catalog!
  "Validates a catalog payload or vector of recipes. Returns
   {:recipes [...] :by-ident {...} :resource <string> :meta {...}}."
  [catalog]
  (let [resource (when (map? catalog) (:resource catalog))
        meta (when (map? catalog) (:meta catalog))
        entries (cond
                  (map? catalog) (cond
                                   (:entries catalog) (:entries catalog)
                                   (:recipes catalog) (:recipes catalog)
                                   :else catalog)
                  :else catalog)]
    (when-not (seq entries)
      (throw (ex-info "Recipe catalog is empty" {:catalog catalog})))
    (let [recipes (mapv validate-recipe! entries)
          idents (map :recipe/ident recipes)]
      (when-not (= (count idents) (count (distinct idents)))
        (throw (ex-info "Recipe idents must be unique" {:recipe/idents idents})))
      {:recipes recipes
       :by-ident (into {} (map (juxt :recipe/ident identity) recipes))
       :resource resource
       :meta meta})))

(defn recipes
  "Convenience helper that loads and validates the default catalog."
  []
  (:recipes (validate-catalog! (load-catalog))))

(defn recipe-by-ident
  [ident]
  (get (:by-ident (validate-catalog! (load-catalog))) ident))

(defn synthesize-ephemeral
  "Builds an ephemeral recipe respecting tight budgets and allowed ops."
  [request]
  (let [{:intent/keys [tags] :keys [constraints]} request
        tags (vec (or tags []))
        caps (set (or (get constraints :capabilities) []))
        sandbox :sandbox/read-only
        base {:recipe/ident (keyword (str "recipe/ephemeral." (if (seq tags) (name (first tags)) "generic")))
              :recipe/version 1
              :recipe/owner :role/mission-generator
              :recipe/stability :stability/experimental
              :recipe/layer :layer/l1
              :recipe/intent-tags (if (seq tags) tags [:intent/unknown])
              :recipe/required-capabilities (vec (if (seq caps) caps [:capability/llm]))
              :recipe/match {:tags (if (seq tags) tags [:intent/unknown])
                             :constraints {:sandbox [sandbox]
                                           :capabilities (vec caps)}}
              :recipe/input-schema {:required [:request/payload]
                                    :optional [:context/summary]
                                    :constraints {:secrets? false}}
              :recipe/output-schema {:produces [:plan/nodes :plan/coverage]
                                     :status [:status/ok :status/failed]
                                     :side-effects {:writes [] :commands []}
                                     :summary "Ephemeral one-shot plan sketch"}
              :recipe/plan {:ops #{:op/llm-call :op/branch}
                            :steps [{:recipe.step/id :llm-plan-draft
                                     :recipe.step/name "LLM draft"
                                     :recipe.step/op :op/llm-call
                                     :recipe.step/description "One-shot draft with bounded input."
                                     :recipe.step/inputs [:request/payload :context/summary]
                                     :recipe.step/outputs [:plan/nodes :plan/coverage]
                                     :recipe.step/tools [:tool/llm]
                                     :recipe.step/limits {:tokens 800 :time-ms 5000}
                                     :recipe.step/validations [:validator/plan-shape]}
                                    {:recipe.step/id :guard-coverage
                                     :recipe.step/op :op/branch
                                     :recipe.step/description "Stop when coverage is empty."
                                     :recipe.step/inputs [:plan/coverage]
                                     :recipe.step/outputs [:plan/coverage]
                                     :recipe.step/limits {:time-ms 500}
                                     :recipe.step/validations [:validator/coverage]}]
                            :edges [[:llm-plan-draft :guard-coverage]]}
              :recipe/limits {:sandbox sandbox
                              :tokens (:tokens default-ephemeral-limits)
                              :wall-clock-ms (:wall-clock-ms default-ephemeral-limits)
                              :files-max (:files-max default-ephemeral-limits)
                              :allowed-tools [:tool/llm]}
              :recipe/validations {:pre [:validator/input-schema]
                                   :invariants [:validator/allowed-ops]
                                   :post [:validator/output-schema]}
              :recipe/audit {:trace [:inputs :decisions :tool-calls :meta/self-report]
                             :hash-fields [:plan/nodes :plan/coverage]}
              :recipe/errors {:retry-policy {:max-attempts 1 :backoff-ms 0}
                              :fallback [:recipe/pattern.plan-skeleton]}
              :recipe/policies {:policy/approvals [:approval/offline-validation]
                                :policy/tests [:test/lint :test/recipe-conformance]
                                :policy/gates {:stability/min :stability/experimental
                                               :sandbox/profile sandbox}}
              :recipe/promotion {:promotion/spec-sections ["2.1" "2.2" "3.3" "3.4" "3.5" "3.6" "4.7" "5" "6" "8.1" "9" "11"]
                                 :promotion/rules [{:from :stability/experimental
                                                    :to :stability/beta
                                                    :requirements [:policy/approvals :policy/tests]
                                                    :policies {:policy/approvals [:approval/offline-validation]
                                                               :policy/tests [:test/lint :test/recipe-conformance]}}
                                                   {:from :stability/beta
                                                    :to :stability/ga
                                                    :requirements [:evidence/repeatable-runs :policy/approvals :policy/tests]}
                                                   {:from :stability/ga
                                                    :to :stability/frozen
                                                    :requirements [:steward/freeze :policy/approvals]}]}
              :recipe/side-effects {:writes [] :commands [] :audit [:meta/self-report]}
              :recipe/ephemeral? true}]
    (validate-recipe! base {:ephemeral? true})))

(defn validate-catalog-action
  "Action handler that validates the recipe catalog and emits a summary."
  [{:keys [config log!]}]
  (let [path (or (:recipe/catalog-path config) catalog-resource)
        catalog (load-catalog path)
        {:keys [recipes resource]} (validate-catalog! catalog)
        idents (mapv :recipe/ident recipes)]
    (when log!
      (log! :recipe/validate {:resource resource
                              :recipe/count (count recipes)}))
    {:action/status :status/ok
     :recipe/catalog-path resource
     :recipe/count (count recipes)
     :recipe/idents idents}))
