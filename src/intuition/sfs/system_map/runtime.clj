(ns intuition.sfs.system-map.runtime
  "Creates and validates the governed system-map graph. Phase-2 M-05 requires
  every structural entity to have a node (§§4.1, 4.5) and forbids dangling
  edges (§4.10)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
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

(def ^:private default-code-graph-path
  "docs/code-types/code-graph.edn")

(def ^:private code-graph-ident-keys
  ["code.graph.node/ident"
   "code.graph.edge/ident"
   "code.graph.edge/from"
   "code.graph.edge/to"])

(def ^:private code-graph-type->entity-kind
  {:code.graph.node.type/code {:entity-kind :system-map.entity/code
                               :tags [:system-map.tag/code]}
   :code.graph.node.type/test {:entity-kind :system-map.entity/test
                               :tags [:system-map.tag/tests]}
   :code.graph.node.type/doc {:entity-kind :system-map.entity/doc
                              :tags [:system-map.tag/docs]}
   :code.graph.node.type/mission {:entity-kind :system-map.entity/mission
                                  :tags [:system-map.tag/mission]}
   :code.graph.node.type/code-type {:entity-kind :system-map.entity/code-type
                                    :tags [:system-map.tag/code :system-map.tag/codetype]}})

(def ^:private code-graph-relation->system-relation
  {:code.graph.relation/mission->code :system-map.relation/implements
   :code.graph.relation/code->test :system-map.relation/validated-by
   :code.graph.relation/code->doc :system-map.relation/documented-by})

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

(defn- as-ident
  [v]
  (cond
    (keyword? v) v
    (string? v) (let [trimmed (-> v
                                  str/trim
                                  (str/replace #"[,]+$" ""))
                      token (if (str/starts-with? trimmed ":")
                              (subs trimmed 1)
                              trimmed)]
                  (when-not (str/blank? token)
                    (keyword token)))
    :else nil))

(defn- sanitize-fragment
  [value]
  (-> (name value)
      (str/replace #"[^A-Za-z0-9_.-]" "-")
      (str/replace #"--+" "-")
      (str/replace #"(^-|-$)" "")))

(defn- system-edge-ident
  [relation from to]
  (keyword (str "system-map.edge/"
                (name relation) "/"
                (sanitize-fragment from) "->"
                (sanitize-fragment to))))

(defn- quote-ident-token
  [text key]
  (let [pattern (re-pattern (str ":" key "\\s+:([^\\s\\}\\]]+)"))]
    (str/replace text pattern (fn [[_ v]]
                                (str ":" key " \"" v "\"")))))

(defn- read-code-graph-text
  [text]
  (let [patched (reduce quote-ident-token text code-graph-ident-keys)
        data (edn/read-string patched)]
    (-> data
        (update :nodes #(vec (or % [])))
        (update :edges #(vec (or % []))))))

(defn- normalize-code-graph-node
  [node]
  (-> node
      (update :code.graph.node/ident as-ident)
      (update :code.graph.node/spec-id as-ident)
      (update :code.graph.node/spec-sections #(vec (or % [])))
      (update :code.graph.node/requirements #(vec (or % [])))
      (update :code.graph.node/missions #(vec (or % [])))
      (update :code.graph.node/version-snapshots #(vec (or % [])))
      (update :code.graph.node/plan-id #(when % (str %)))
      (update :code.graph.node/mission-id #(when % (str %)))))

(defn- normalize-code-graph-edge
  [edge]
  (-> edge
      (update :code.graph.edge/ident as-ident)
      (update :code.graph.edge/from as-ident)
      (update :code.graph.edge/to as-ident)
      (update :code.graph.edge/spec-sections #(vec (or % [])))
      (update :code.graph.edge/requirements #(vec (or % [])))
      (update :code.graph.edge/version-snapshots #(vec (or % [])))))

(defn- normalize-code-graph
  [{:keys [nodes edges] :as graph}]
  (when graph
    {:nodes (mapv normalize-code-graph-node nodes)
     :edges (mapv normalize-code-graph-edge edges)}))

(defn- load-code-graph
  [{:keys [log! path graph]}]
  (cond
    (map? graph) (normalize-code-graph graph)
    :else (let [file (io/file (or path default-code-graph-path))]
            (cond
              (.exists file)
              (try
                (-> (slurp file)
                    read-code-graph-text
                    normalize-code-graph)
                (catch Exception ex
                  (when log!
                    (log! :system-map/code-graph-parse-failed
                          {:path (.getAbsolutePath file)
                           :error (.getMessage ex)}))
                  nil))

              log!
              (do (log! :system-map/code-graph-missing
                        {:path (.getAbsolutePath file)})
                  nil)

              :else nil))))

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

(defn- code-graph-node->system-node
  [node]
  (let [ident (:code.graph.node/ident node)
        {:keys [entity-kind tags]} (get code-graph-type->entity-kind (:code.graph.node/type node))]
    (when (and ident entity-kind)
      {:entity ident
       :entity-kind entity-kind
       :name (or (:code.graph.node/name node) (name ident))
       :description (or (:code.graph.node/ref node)
                        (str (name ident) " code graph node"))
       :spec-refs (->> (concat (:code.graph.node/spec-sections node)
                               (:code.graph.node/requirements node))
                       dedupe-preserving-order
                       vec)
       :tags tags})))

(defn- code-graph-edge->system-edge
  [edge]
  (let [relation (get code-graph-relation->system-relation
                      (:code.graph.edge/relation edge))
        from (:code.graph.edge/from edge)
        to (:code.graph.edge/to edge)]
    (when (and relation from to)
      {:ident (system-edge-ident relation from to)
       :from from
       :to to
       :relation relation
       :spec-refs (->> (concat (:code.graph.edge/spec-sections edge)
                               (:code.graph.edge/requirements edge))
                       dedupe-preserving-order
                       vec)
       :tags (case relation
               :system-map.relation/validated-by [:system-map.tag/tests]
               :system-map.relation/documented-by [:system-map.tag/docs]
               :system-map.relation/implements [:system-map.tag/mission]
               nil)})))

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

(defn- edge-tx
  [{:keys [ident from to relation description spec-refs tags]}]
  (cond-> {:system-map.edge/ident ident
           :system-map.edge/from from
           :system-map.edge/to to
           :system-map.edge/relation relation
           :system-map.edge/status :system-map.edge.status/active}
    description (assoc :system-map.edge/description description)
    (seq spec-refs) (assoc :system-map.edge/spec-refs (vec spec-refs))
    (seq tags) (assoc :system-map.edge/tags (vec tags))))

(defn- merge-node-details
  [existing incoming]
  (let [spec-refs (dedupe-preserving-order (concat (:spec-refs existing)
                                                   (:spec-refs incoming)))
        tags (dedupe-preserving-order (concat (:tags existing)
                                              (:tags incoming)))]
    (-> existing
        (merge incoming)
        (assoc :spec-refs spec-refs
               :tags tags))))

(defn- dangling-nodes
  [db nodes allowed-entities]
  (keep (fn [node]
          (let [entity (:system-map.node/entity node)]
            (when-not (or (contains? allowed-entities entity)
                          (resolve-dictionary-entity db entity))
              (select-keys node [:system-map.node/ident :system-map.node/entity]))))
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
  [{:keys [conn entities log!] :as opts}]
  (if (:system-map/skip? opts)
    (let [entities' (vec (or entities []))]
      (log/infof "[system-map] skip requested; entities=%s" entities')
      {:action/status :status/ok
       :system-map/entities entities'
       :system-map/skipped? true})
    (do
      (when-not conn
        (throw (ex-info "Missing Datomic connection" {:type :system-map/missing-conn})))
      (let [log! (or log! default-log)
            conn (ensure-schema! conn)
            db (d/db conn)
            include-code-graph? (if (contains? opts :code-graph/enabled?)
                                  (:code-graph/enabled? opts)
                                  true)
            code-graph (when include-code-graph?
                         (load-code-graph {:log! log!
                                           :path (:code-graph/path opts)
                                           :graph (:code-graph/graph opts)}))
            code-nodes (vec (keep code-graph-node->system-node (:nodes code-graph)))
            doc-source (some #(when (= :code/intuition.docs.runtime (:entity %))
                                (:entity %))
                             code-nodes)
            doc-targets (map :entity (filter #(= :system-map.entity/doc (:entity-kind %)) code-nodes))
            graph-edges (keep code-graph-edge->system-edge (:edges code-graph))
            synthetic-doc-edges (if (and doc-source (seq doc-targets))
                                  (map (fn [doc-ident]
                                         {:ident (system-edge-ident :system-map.relation/documented-by
                                                                    doc-source
                                                                    doc-ident)
                                          :from doc-source
                                          :to doc-ident
                                          :relation :system-map.relation/documented-by
                                          :spec-refs ["4.10" "7" "9"]
                                          :tags [:system-map.tag/docs]})
                                       doc-targets)
                                  [])
            code-edges (vec (concat graph-edges synthetic-doc-edges))
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
          (let [merged-nodes (->> (concat (map second details) code-nodes)
                                  (remove nil?)
                                  (reduce (fn [acc node]
                                            (let [entity (:entity node)]
                                              (assoc acc entity (merge-node-details (or (get acc entity)
                                                                                         {:entity entity})
                                                                                    node))))
                                          {})
                                  vals)
                tx-data (->> merged-nodes
                             (map node-tx)
                             (remove nil?)
                             vec)
                tx-edges (->> code-edges
                              (map edge-tx)
                              (remove nil?)
                              vec)
                tx (vec (concat tx-data tx-edges))]
            (when (seq tx)
              (d/transact conn {:tx-data tx}))
            (let [db' (d/db conn)
                  nodes (load-nodes db')
                  edges (load-edges db')
                  node-idents (set (map :system-map.node/ident nodes))
                  dangling-nodes (vec (dangling-nodes db' nodes (set (map :entity code-nodes))))
                  dangling-edges (vec (dangling-edges node-idents edges))]
              (when (seq dangling-nodes)
                (throw (ex-info "System-map nodes reference dictionary entries that do not exist."
                                {:type :system-map/dangling-nodes
                                 :system-map/nodes dangling-nodes})))
              (when (seq dangling-edges)
                (throw (ex-info "System-map edges reference unknown nodes."
                                {:type :system-map/dangling-edges
                                 :system-map/edges dangling-edges})))
              (let [reported-entities (dedupe-preserving-order
                                       (concat target-entities (map :entity code-nodes)))
                    result {:action/status :status/ok
                            :system-map/entities (vec reported-entities)
                            :system-map/nodes (count nodes)
                            :system-map/edges (count edges)}]
                (when code-graph
                  (log! :system-map/code-graph {:path (or (:code-graph/path opts)
                                                          default-code-graph-path)
                                                :nodes (count code-nodes)
                                                :edges (count code-edges)}))
                (log! :system-map/success result)
                result))))))))

(defn refresh-action
  "Action handler wrapper so `:action/system-map.refresh` can call `refresh!`."
  [{:keys [conn config log!]}]
  (let [env-skip (let [env (System/getenv "SYSTEM_MAP_SKIP")]
                   (some #(= env %) ["true" "1" "yes" "on"]))
        skip? (boolean (or (:system-map/skip? config) env-skip))]
    (refresh! {:conn conn
               :entities (:system-map/entities config)
               :code-graph/enabled? (:code-graph/enabled? config)
               :code-graph/path (:code-graph/path config)
               :code-graph/graph (:code-graph/graph config)
               :system-map/skip? skip?
               :log! log!})))
