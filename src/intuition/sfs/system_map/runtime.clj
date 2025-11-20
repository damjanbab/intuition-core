(ns intuition.sfs.system-map.runtime
  "Creates and validates the governed system-map graph. Phase-2 M-05 requires
  every structural entity to have a node (§§4.1, 4.5) and forbids dangling
  edges (§4.10)."
  (:require
   [clojure.tools.logging :as log]
   [datomic.client.api :as d]
   [intuition.code.runtime :as code]))

(def ^:private node-schema
  [{:db/ident :system-map.node/ident
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :system-map.node/entity
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :system-map.node/entity-kind
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :system-map.node/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :system-map.node/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :system-map.node/spec-refs
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :system-map.node/tags
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :system-map.node/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])

(def ^:private edge-schema
  [{:db/ident :system-map.edge/ident
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :system-map.edge/from
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :system-map.edge/to
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :system-map.edge/relation
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :system-map.edge/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :system-map.edge/spec-refs
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :system-map.edge/tags
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :system-map.edge/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}])

(def ^:private schema
  (concat node-schema edge-schema))

(def ^:private node-pull-pattern
  [:system-map.node/ident
   :system-map.node/entity
   :system-map.node/entity-kind
   :system-map.node/spec-refs
   :system-map.node/tags])

(def ^:private edge-pull-pattern
  [:system-map.edge/ident
   :system-map.edge/from
   :system-map.edge/to
   :system-map.edge/relation])

(def ^:private dictionary-entity-sources
  [{:entity-kind :system-map.entity/action
    :attr :action/ident
    :pull [:action/ident :action/name :action/description :action/invariants]
    :name-key :action/name
    :description-key :action/description
    :spec-key :action/invariants
    :tags [:system-map.tag/action]}
   {:entity-kind :system-map.entity/protocol
    :attr :protocol/ident
    :pull [:protocol/ident :protocol/name :protocol/description :protocol/invariants]
    :name-key :protocol/name
    :description-key :protocol/description
    :spec-key :protocol/invariants
    :tags [:system-map.tag/protocol]}])

(defn- code-tags
  [definition]
  (let [base [:system-map.tag/code]
        type-ident (:code.definition/type definition)]
    (if type-ident
      (conj base (keyword "system-map.tag" (name type-ident)))
      base)))

(defn- code-entity
  [ident]
  (when-let [definition (code/by-ident ident)]
    {:entity ident
     :entity-kind :system-map.entity/code
     :name (:code.definition/name definition)
     :description (or (:entity/description definition)
                      (str (name ident) " code node"))
     :spec-refs (vec (or (:code.definition/spec-sections definition) []))
     :tags (code-tags definition)}))

(defn- ensure-schema!
  [conn]
  (let [db (d/db conn)
        installed? (seq (d/q '[:find ?e
                               :where [?e :db/ident :system-map.node/ident]]
                             db))]
    (when-not installed?
      (d/transact conn {:tx-data schema})))
  conn)

(defn- default-log
  [event payload]
  (log/infof "[system-map] %s %s" event (pr-str payload)))

(defn- dedupe-preserving-order
  [coll]
  (loop [xs coll
         seen #{}
         acc []]
    (if-let [x (first xs)]
      (if (contains? seen x)
        (recur (rest xs) seen acc)
        (recur (rest xs) (conj seen x) (conj acc x)))
      acc)))

(defn- entity-idents-for-attr
  [db attr]
  (map first (d/q '[:find ?ident
                    :in $ ?attr
                    :where [?e ?attr ?ident]]
                  db attr)))

(defn- all-entity-idents
  [db]
  (let [dictionary-ids (mapcat #(entity-idents-for-attr db (:attr %))
                               dictionary-entity-sources)
        code-ids (sort-by str (code/definition-idents))]
    (concat dictionary-ids code-ids)))

(defn- resolve-dictionary-entity
  [db ident]
  (or (code-entity ident)
      (some (fn [{:keys [entity-kind attr pull name-key description-key spec-key tags]}]
              (when-let [entity (d/pull db pull [attr ident])]
                {:entity ident
                 :entity-kind entity-kind
                 :name (or (get entity name-key) (name ident))
                 :description (or (get entity description-key)
                                  (str (name ident) " node"))
                 :spec-refs (->> (get entity spec-key)
                                 (keep identity)
                                 (map name)
                                 vec)
                 :tags tags}))
            dictionary-entity-sources)))

(defn- entity-ids
  [db attr]
  (map first (d/q '[:find ?e
                    :in $ ?attr
                    :where [?e ?attr _]]
                  db attr)))

(defn- load-nodes
  [db]
  (map #(d/pull db node-pull-pattern %)
       (entity-ids db :system-map.node/ident)))

(defn- load-edges
  [db]
  (map #(d/pull db edge-pull-pattern %)
       (entity-ids db :system-map.edge/ident)))

(defn- node-tx
  [{:keys [entity entity-kind name description spec-refs tags]}]
  (cond-> {:system-map.node/ident entity
           :system-map.node/entity entity
           :system-map.node/entity-kind entity-kind
           :system-map.node/name name
           :system-map.node/description description
           :system-map.node/status :system-map.node.status/active}
    (seq spec-refs) (assoc :system-map.node/spec-refs (vec spec-refs))
    (seq tags) (assoc :system-map.node/tags (vec tags))))

(defn- dangling-nodes
  [db nodes]
  (keep (fn [node]
          (when-not (resolve-dictionary-entity db (:system-map.node/entity node))
            (select-keys node [:system-map.node/ident :system-map.node/entity])))
        nodes))

(defn- dangling-edges
  [node-idents edges]
  (keep (fn [edge]
          (let [missing (->> [(:system-map.edge/from edge)
                              (:system-map.edge/to edge)]
                             (remove #(contains? node-idents %))
                             vec)]
            (when (seq missing)
              {:edge (:system-map.edge/ident edge)
               :missing missing})))
        edges))

(defn refresh!
  "Reconciles dictionary entities -> system-map nodes and enforces the
  no-dangling-nodes/edges invariant. Returns the action-style payload used by
  Mission protocols."
  [{:keys [conn entities log!]}]
  (when-not conn
    (throw (ex-info "Missing Datomic connection" {:type :system-map/missing-conn})))
  (let [log! (or log! default-log)
        conn (ensure-schema! conn)
        db (d/db conn)
        requested (dedupe-preserving-order (seq entities))
        default-entities (->> (all-entity-idents db)
                              set
                              (sort-by str)
                              vec)
        target-entities (if (seq requested)
                          requested
                          default-entities)]
    (when-not (seq target-entities)
      (throw (ex-info "No dictionary entities available for system-map refresh."
                      {:type :system-map/no-entities})))
    (log! :system-map/refresh {:entities target-entities})
    (let [details (map (fn [ident]
                         [ident (resolve-dictionary-entity db ident)])
                       target-entities)
          missing (map first (filter (comp nil? second) details))]
      (when (seq missing)
        (throw (ex-info "Unknown dictionary entities referenced by system-map refresh."
                        {:type :system-map/missing-dictionary-entities
                         :system-map/entities (vec missing)})))
      (let [tx-data (->> details (map (comp node-tx second)) (remove nil?) vec)]
        (when (seq tx-data)
          (d/transact conn {:tx-data tx-data}))
        (let [db' (d/db conn)
              nodes (load-nodes db')
              edges (load-edges db')
              node-idents (set (map :system-map.node/ident nodes))
              dangling-nodes (vec (dangling-nodes db' nodes))
              dangling-edges (vec (dangling-edges node-idents edges))]
          (when (seq dangling-nodes)
            (throw (ex-info "System-map nodes reference dictionary entries that do not exist."
                            {:type :system-map/dangling-nodes
                             :system-map/nodes dangling-nodes})))
          (when (seq dangling-edges)
            (throw (ex-info "System-map edges reference unknown nodes."
                            {:type :system-map/dangling-edges
                             :system-map/edges dangling-edges})))
          (let [result {:action/status :status/ok
                        :system-map/entities (vec target-entities)
                        :system-map/nodes (count nodes)
                        :system-map/edges (count edges)}]
            (log! :system-map/success result)
            result))))))

(defn refresh-action
  "Action handler wrapper so `:action/system-map.refresh` can call `refresh!`."
  [{:keys [conn config log!]}]
  (refresh! {:conn conn
             :entities (:system-map/entities config)
             :log! log!}))
