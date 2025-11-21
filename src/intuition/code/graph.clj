(ns intuition.code.graph
  "Builds a normalized code graph over Datomic so agents can traverse specs,
  plans, missions, code definitions, tests, docs, and system-map projections.
  Edges capture spec→plan→mission lineage plus validation/doc coverage so
  SYSTEM_SPEC §§3.3–3.6, §4.7, §5.1, §8.1, and §9 queries stay graph-native."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [datomic.client.api :as d]
   [intuition.code.runtime :as code]
   [intuition.datomic :as datomic])
  (:import
   (java.io File PushbackReader)
   (java.util UUID)))

(def ^:private node-schema
  [{:db/ident :code.graph.node/ident
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :code.graph.node/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.graph.node/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.graph.node/ref
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.graph.node/spec-id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.graph.node/plan-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.graph.node/mission-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.graph.node/spec-sections
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.graph.node/requirements
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.graph.node/missions
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.graph.node/version-snapshots
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/many}])

(def ^:private edge-schema
  [{:db/ident :code.graph.edge/ident
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :code.graph.edge/from
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.graph.edge/to
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.graph.edge/relation
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.graph.edge/requirements
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.graph.edge/spec-sections
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.graph.edge/version-snapshots
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/many}])

(def ^:private schema
  (concat node-schema edge-schema))

(def ^:private node-pull [:code.graph.node/ident
                          :code.graph.node/type
                          :code.graph.node/name
                          :code.graph.node/ref
                          :code.graph.node/spec-id
                          :code.graph.node/plan-id
                          :code.graph.node/mission-id
                          :code.graph.node/spec-sections
                          :code.graph.node/requirements
                          :code.graph.node/missions
                          :code.graph.node/version-snapshots])

(def ^:private edge-pull [:code.graph.edge/ident
                          :code.graph.edge/from
                          :code.graph.edge/to
                          :code.graph.edge/relation
                          :code.graph.edge/requirements
                          :code.graph.edge/spec-sections
                          :code.graph.edge/version-snapshots])

(def ^:private hierarchy-relations
  #{:code.graph.relation/spec->plan
    :code.graph.relation/plan->mission
    :code.graph.relation/mission->code
    :code.graph.relation/code->code-type})

(def ^:private validation-relations
  #{:code.graph.relation/code->test
    :code.graph.relation/code->doc
    :code.graph.relation/code->system-map})

(def ^:private downstream-relations
  (set/union hierarchy-relations validation-relations))

(def ^:private repo-root
  (.getCanonicalPath (io/file ".")))

(defn- ensure-schema!
  [conn]
  (let [db (d/db conn)
        installed? (seq (d/q '[:find ?e
                               :where [?e :db/ident :code.graph.node/ident]]
                             db))]
    (when-not installed?
      (d/transact conn {:tx-data schema})))
  conn)

(defn- sanitize-fragment
  [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_.-]" "-")
      (str/replace #"--+" "-")
      (str/replace #"(^-|-$)" "")))

(defn- spec-ident
  [spec-id]
  (when spec-id
    (if (keyword? spec-id)
      spec-id
      (keyword (str "spec/" (sanitize-fragment spec-id))))))

(defn- plan-ident
  [plan-id]
  (when plan-id
    (keyword (str "plan/" (sanitize-fragment plan-id)))))

(defn- mission-ident
  [mission-id]
  (when-not (str/blank? (str mission-id))
    (keyword (str "mission/" (sanitize-fragment mission-id)))))

(defn- ident->db
  [v]
  (cond
    (keyword? v) (str v)
    (string? v) (str/trim v)
    (nil? v) nil
    :else (str/trim (str v))))

(defn- hydrate-ident
  [v]
  (cond
    (keyword? v) v
    (string? v) (let [trimmed (str/trim v)
                      value (if (str/starts-with? trimmed ":")
                              (subs trimmed 1)
                              trimmed)]
                  (when-not (str/blank? value)
                    (keyword value)))
    :else nil))

(defn- edge-ident
  [relation from to]
  (keyword (str "edge/" (name relation) "/"
                (sanitize-fragment (name from)) "->"
                (sanitize-fragment (name to)))))

(defn- read-edn-file
  [^File file]
  (with-open [r (PushbackReader. (io/reader file))]
    (edn/read {:eof nil} r)))

(defn- clean-vec
  [xs]
  (->> xs
       (keep identity)
       (map str)
       (remove str/blank?)
       set
       (sort)
       vec))

(defn- prepare-node
  [node]
  (-> node
      (update :code.graph.node/ident ident->db)
      (cond-> (:code.graph.node/plan-id node)
        (update :code.graph.node/plan-id #(str %)))
      (cond-> (:code.graph.node/mission-id node)
        (update :code.graph.node/mission-id #(str %)))
      (update :code.graph.node/requirements clean-vec)
      (update :code.graph.node/spec-sections clean-vec)
      (update :code.graph.node/missions clean-vec)
      (update :code.graph.node/version-snapshots #(vec (or % [])))))

(defn- prepare-edge
  [edge]
  (-> edge
      (update :code.graph.edge/ident ident->db)
      (update :code.graph.edge/from ident->db)
      (update :code.graph.edge/to ident->db)
      (update :code.graph.edge/requirements clean-vec)
      (update :code.graph.edge/spec-sections clean-vec)
      (update :code.graph.edge/version-snapshots #(vec (or % [])))))

(defn- hydrate-node
  [node]
  (-> node
      (update :code.graph.node/ident hydrate-ident)
      (update :code.graph.node/missions #(vec (or % [])))
      (update :code.graph.node/spec-sections #(vec (or % [])))
      (update :code.graph.node/requirements #(vec (or % [])))
      (update :code.graph.node/version-snapshots #(vec (or % [])))))

(defn- hydrate-edge
  [edge]
  (-> edge
      (update :code.graph.edge/ident hydrate-ident)
      (update :code.graph.edge/from hydrate-ident)
      (update :code.graph.edge/to hydrate-ident)
      (update :code.graph.edge/requirements #(vec (or % [])))
      (update :code.graph.edge/spec-sections #(vec (or % [])))
      (update :code.graph.edge/version-snapshots #(vec (or % [])))))

(defn- dedupe-by-ident
  [items key-fn]
  (->> items
       (reduce (fn [acc item]
                 (let [k (key-fn item)]
                   (if-let [existing (get acc k)]
                     (assoc acc k (merge existing item))
                     (assoc acc k item))))
               {})
       vals))

(defn- add-node
  [nodes {:code.graph.node/keys [ident] :as node}]
  (let [existing (get nodes ident)
        merged (merge-with (fn [a b]
                             (cond
                               (and (vector? a) (vector? b)) (clean-vec (concat a b))
                               :else (or b a)))
                           existing
                           node)]
    (assoc nodes ident merged)))

(defn- add-edge
  [edges {:code.graph.edge/keys [ident] :as edge}]
  (assoc edges ident edge))

(defn- empty-acc
  []
  {:nodes {}
   :edges {}})

(defn- merge-acc
  [& accs]
  (reduce (fn [acc {:keys [nodes edges]}]
            {:nodes (merge (:nodes acc) nodes)
             :edges (merge (:edges acc) edges)})
          (empty-acc)
          accs))

(defn- spec-entries
  []
  (->> (file-seq (io/file repo-root "resources" "specs"))
       (filter #(and (.isFile ^File %)
                     (str/ends-with? (.getName ^File %) ".edn")))
       (keep (fn [^File file]
               (try
                 (read-edn-file file)
                 (catch Exception _
                   nil))))))

(defn- work-plan-entries
  []
  (->> (file-seq (io/file repo-root "resources" "work-plans"))
       (filter #(and (.isFile ^File %)
                     (str/ends-with? (.getName ^File %) ".edn")))
       (keep (fn [^File file]
               (try
                 (read-edn-file file)
                 (catch Exception _
                   nil))))))

(defn- doc-template-entries
  []
  (let [file (io/file repo-root "resources" "dictionary" "doc_templates.edn")]
    (when (.exists file)
      (try
        (read-edn-file file)
        (catch Exception _
          nil)))))

(defn- derive-specs
  []
  (reduce
   (fn [acc {:spec/keys [id title requirements spec-sections]}]
     (let [ident (spec-ident id)
           node {:code.graph.node/ident ident
                 :code.graph.node/type :code.graph.node.type/spec
                 :code.graph.node/name (or title (name ident))
                 :code.graph.node/ref (str id)
                 :code.graph.node/spec-id ident
                 :code.graph.node/requirements (clean-vec requirements)
                 :code.graph.node/spec-sections (clean-vec spec-sections)}]
       (update acc :nodes add-node node)))
   (empty-acc)
   (spec-entries)))

(defn- plan-mission-id
  [plan]
  (let [candidates [(get plan :work.plan/source-path)
                    (get plan :plan.generation/log-path)]]
    (some (fn [text]
            (when-let [match (some->> text (re-find #"M-[0-9A-Za-z-]+"))]
              match))
          candidates)))

(defn- derive-plans
  []
  (reduce
   (fn [acc plan]
           (let [plan-id (:work.plan/id plan)
                 spec-id (:work.plan/spec-id plan)
                 requirements (->> (:work.plan/coverage plan)
                                    (map :coverage.row/requirement-id)
                                    clean-vec)
           plan-ident (plan-ident plan-id)
           spec-ident (spec-ident spec-id)
           mission-id (plan-mission-id plan)
           mission-ident (mission-ident mission-id)
           base-node {:code.graph.node/ident plan-ident
                      :code.graph.node/type :code.graph.node.type/plan
                      :code.graph.node/name (or (some-> plan-id str) (name plan-ident))
                      :code.graph.node/ref (str plan-id)
                      :code.graph.node/spec-id spec-ident
                      :code.graph.node/plan-id (str plan-id)
                      :code.graph.node/requirements requirements}
           plan-edge {:code.graph.edge/ident (edge-ident :code.graph.relation/spec->plan spec-ident plan-ident)
                      :code.graph.edge/from spec-ident
                      :code.graph.edge/to plan-ident
                      :code.graph.edge/relation :code.graph.relation/spec->plan
                      :code.graph.edge/requirements requirements}
           acc (-> acc
                   (update :nodes add-node base-node)
                   (update :edges add-edge plan-edge))
           acc (if mission-ident
                 (let [mission-node {:code.graph.node/ident mission-ident
                                     :code.graph.node/type :code.graph.node.type/mission
                                     :code.graph.node/name (str mission-id)
                                     :code.graph.node/ref (str mission-id)
                                     :code.graph.node/mission-id (str mission-id)}
                       mission-edge {:code.graph.edge/ident (edge-ident :code.graph.relation/plan->mission plan-ident mission-ident)
                                     :code.graph.edge/from plan-ident
                                     :code.graph.edge/to mission-ident
                                     :code.graph.edge/relation :code.graph.relation/plan->mission
                                     :code.graph.edge/requirements requirements}]
                   (-> acc
                       (update :nodes add-node mission-node)
                       (update :edges add-edge mission-edge)))
                 acc)]
       acc))
   (empty-acc)
   (work-plan-entries)))

(defn- doc-nodes-from-paths
  [paths]
  (for [path paths
        :when (str/starts-with? path "docs/")]
    (let [ident (keyword (str "doc/" (sanitize-fragment path)))]
      {:code.graph.node/ident ident
       :code.graph.node/type :code.graph.node.type/doc
       :code.graph.node/name path
       :code.graph.node/ref path})))

(defn- derive-doc-templates
  []
  (reduce
   (fn [acc entry]
     (let [ident (or (:template/ident entry)
                     (:template.instance/ident entry))]
       (if ident
         (update acc :nodes add-node {:code.graph.node/ident ident
                                      :code.graph.node/type :code.graph.node.type/doc
                                      :code.graph.node/name (or (:template/name entry)
                                                                (:template.instance/name entry)
                                                                (name ident))
                                      :code.graph.node/ref (name ident)})
         acc)))
   (empty-acc)
   (doc-template-entries)))

(defn- build-code-graph
  []
  (reduce
   (fn [acc definition]
     (let [ident (:code.definition/ident definition)
           type-ident (:code.definition/type definition)
           spec-sections (clean-vec (:code.definition/spec-sections definition))
           missions (clean-vec (:code.definition/missions definition))
           tests (clean-vec (:code.definition/tests definition))
           paths (vec (or (:code.definition/paths definition) []))
           node {:code.graph.node/ident ident
                 :code.graph.node/type :code.graph.node.type/code
                 :code.graph.node/name (:code.definition/name definition)
                 :code.graph.node/ref (name ident)
                 :code.graph.node/spec-sections spec-sections
                 :code.graph.node/missions missions}
           acc (update acc :nodes add-node node)
           acc (reduce (fn [acc mission-id]
                         (let [mission-ident (mission-ident mission-id)
                               mission-node {:code.graph.node/ident mission-ident
                                             :code.graph.node/type :code.graph.node.type/mission
                                             :code.graph.node/name (str mission-id)
                                             :code.graph.node/ref (str mission-id)
                                             :code.graph.node/mission-id (str mission-id)}
                               edge {:code.graph.edge/ident (edge-ident :code.graph.relation/mission->code mission-ident ident)
                                     :code.graph.edge/from mission-ident
                                     :code.graph.edge/to ident
                                     :code.graph.edge/relation :code.graph.relation/mission->code}]
                           (-> acc
                               (update :nodes add-node mission-node)
                               (update :edges add-edge edge))))
                       acc
                       missions)
           acc (if type-ident
                 (let [edge {:code.graph.edge/ident (edge-ident :code.graph.relation/code->code-type ident type-ident)
                             :code.graph.edge/from ident
                             :code.graph.edge/to type-ident
                             :code.graph.edge/relation :code.graph.relation/code->code-type}]
                   (update acc :edges add-edge edge))
                 acc)
           acc (reduce (fn [acc test-ident]
                         (let [test-node {:code.graph.node/ident test-ident
                                          :code.graph.node/type :code.graph.node.type/test
                                          :code.graph.node/name (name test-ident)
                                          :code.graph.node/ref (name test-ident)}
                               edge {:code.graph.edge/ident (edge-ident :code.graph.relation/code->test ident test-ident)
                                     :code.graph.edge/from ident
                                     :code.graph.edge/to test-ident
                                     :code.graph.edge/relation :code.graph.relation/code->test}]
                           (-> acc
                               (update :nodes add-node test-node)
                               (update :edges add-edge edge))))
                       acc
                       tests)
           acc (reduce (fn [acc doc-node]
                         (let [edge {:code.graph.edge/ident (edge-ident :code.graph.relation/code->doc ident (:code.graph.node/ident doc-node))
                                     :code.graph.edge/from ident
                                     :code.graph.edge/to (:code.graph.node/ident doc-node)
                                     :code.graph.edge/relation :code.graph.relation/code->doc}]
                           (-> acc
                               (update :nodes add-node doc-node)
                               (update :edges add-edge edge))))
                       acc
                       (doc-nodes-from-paths paths))]
       acc))
   (empty-acc)
   (code/definitions)))

(defn seed-from-repo!
  "Transacts a graph derived from specs, work-plans, code definitions, code
  types, and doc templates into the provided Datomic connection."
  [conn]
  (let [code-types (code/code-types)
        type-nodes {:nodes (into {}
                                 (map (fn [entry]
                                        [(:code.type/ident entry)
                                         {:code.graph.node/ident (:code.type/ident entry)
                                          :code.graph.node/type :code.graph.node.type/code-type
                                          :code.graph.node/name (:entity/name entry)
                                          :code.graph.node/ref (name (:code.type/ident entry))
                                          :code.graph.node/spec-sections (clean-vec (:code.type/spec-sections entry))}]))
                                 code-types)
                    :edges {}}
        graph (merge-acc
               type-nodes
               (build-code-graph)
               (derive-specs)
               (derive-plans)
               (derive-doc-templates))
        prepared-nodes (->> graph :nodes vals (map prepare-node))
        prepared-edges (->> graph :edges vals (map prepare-edge))
        nodes (-> (dedupe-by-ident prepared-nodes :code.graph.node/ident) vec)
        edges (-> (dedupe-by-ident prepared-edges :code.graph.edge/ident) vec)]
    (ensure-schema! conn)
    (when (seq nodes)
      (d/transact conn {:tx-data nodes}))
    (when (seq edges)
      (d/transact conn {:tx-data edges}))
    {:nodes nodes
     :edges edges}))

(defn install!
  "Upserts the supplied nodes/edges into Datomic. Data must match the graph
  schema; callers typically compose this with `graph` for tests."
  [{:keys [conn nodes edges]}]
  (ensure-schema! conn)
  (let [prepared-nodes (map prepare-node nodes)
        prepared-edges (map prepare-edge edges)
        deduped-nodes (dedupe-by-ident prepared-nodes :code.graph.node/ident)
        deduped-edges (dedupe-by-ident prepared-edges :code.graph.edge/ident)]
    (when (seq deduped-nodes)
      (d/transact conn {:tx-data deduped-nodes}))
    (when (seq deduped-edges)
      (d/transact conn {:tx-data deduped-edges}))
    {:nodes (count nodes)
     :edges (count edges)}))

(defn- load-nodes
  [conn]
  (->> (d/q '[:find ?e
              :where [?e :code.graph.node/ident _]]
            (d/db conn))
       (map first)
       (map #(hydrate-node (d/pull (d/db conn) node-pull %)))))

(defn- load-edges
  [conn]
  (->> (d/q '[:find ?e
              :where [?e :code.graph.edge/ident _]]
            (d/db conn))
       (map first)
       (map #(hydrate-edge (d/pull (d/db conn) edge-pull %)))))

(defn- reachable
  [seed edges]
  (loop [frontier seed
         visited seed]
    (if (empty? frontier)
      visited
      (let [neighbors (->> edges
                           (filter #(or (visited (:code.graph.edge/from %))
                                        (visited (:code.graph.edge/to %))))
                           (mapcat (juxt :code.graph.edge/from :code.graph.edge/to))
                           set)
            new (set/difference neighbors visited)]
        (recur new (set/union visited new))))))

(defn graph
  "Returns {:nodes [...], :edges [...]} for the current graph. Optional filters:
  :spec-id, :plan-id, :mission-id, or :node-idents (set/seq). When any filter is
  supplied, the result includes all reachable neighbors from the matched nodes."
  [{:keys [conn spec-id plan-id mission-id node-idents]}]
  (let [conn (ensure-schema! (or conn (datomic/ensure-db!)))
        nodes (load-nodes conn)
        edges (load-edges conn)
        base (set (map mission-ident (clean-vec [mission-id])))
        base (into base
                   (map plan-ident (clean-vec [plan-id])))
        base (into base
                   (map spec-ident (clean-vec [spec-id])))
        base (into base (map #(if (keyword? %) % (keyword (str %))))
                         (or node-idents []))
        seed (set
              (concat
               base
               (keep (fn [node]
                       (let [mission-match (set/intersection base (set (map mission-ident (:code.graph.node/missions node))))]
                         (when (or (and spec-id (= spec-id (:code.graph.node/spec-id node)))
                                   (and plan-id (= (str plan-id) (:code.graph.node/plan-id node)))
                                   (seq mission-match))
                           (:code.graph.node/ident node))))
                     nodes)))
        included (if (seq seed)
                   (reachable seed edges)
                   (set (map :code.graph.node/ident nodes)))
        node-map (into {} (map (juxt :code.graph.node/ident identity) nodes))
        filtered-nodes (->> included
                            (keep node-map)
                            (sort-by (comp name :code.graph.node/ident)))
        filtered-edges (->> edges
                            (filter #(and (included (:code.graph.edge/from %))
                                          (included (:code.graph.edge/to %))))
                            (sort-by (comp name :code.graph.edge/ident)))]
    {:nodes (vec filtered-nodes)
     :edges (vec filtered-edges)}))

(defn- adjacency
  [edges]
  (reduce (fn [acc {:code.graph.edge/keys [from to relation]}]
            (-> acc
                (update from (fnil conj []) {:ident to :relation relation})
                (update to (fnil conj []) {:ident from :relation relation})))
          {}
          edges))

(defn- traverse
  [start edges allowed]
  (let [adj (adjacency edges)]
    (loop [frontier (if start [start] [])
           visited #{}]
      (if (empty? frontier)
        visited
        (let [node (first frontier)
              neighbors (->> (get adj node)
                             (filter #(allowed (:relation %)))
                             (map :ident)
                             set)
              unseen (set/difference neighbors visited)]
          (recur (concat (rest frontier) unseen)
                 (conj visited node)))))))

(defn upstream
  "Returns nodes reachable via hierarchy relations (plan/spec/mission/code path)
  starting from the supplied node ident."
  [{:keys [graph from]}]
  (let [edges (:edges graph)
        visited (traverse from edges hierarchy-relations)
        node-map (into {} (map (juxt :code.graph.node/ident identity) (:nodes graph)))]
    (->> (conj visited from)
         (keep node-map)
         (sort-by (comp name :code.graph.node/ident))
         vec)))

(defn downstream
  "Returns nodes reachable via downstream relations (spec→plan→mission→code plus
  validation/doc edges) starting from the supplied node ident."
  [{:keys [graph from relations]}]
  (let [allowed (or relations downstream-relations)
        edges (:edges graph)
        visited (traverse from edges allowed)
        node-map (into {} (map (juxt :code.graph.node/ident identity) (:nodes graph)))]
    (->> (conj visited from)
         (keep node-map)
         (sort-by (comp name :code.graph.node/ident))
         vec)))

(defn export!
  "Seeds the graph from repo data (unless :seed? false) and writes the EDN
  representation to :path (default docs/code-types/code-graph.edn). When no
  conn is supplied, a temp Datomic dev-local database is created and deleted
  around the export."
  [{:keys [conn path spec-id plan-id mission-id seed?]
    :or {path "docs/code-types/code-graph.edn"
         seed? true}}]
  (let [temp? (nil? conn)
        client (when temp? (datomic/client))
        db-name (when temp?
                  (let [name (str "code-graph-" (UUID/randomUUID))]
                    (d/create-database client {:db-name name})
                    name))
        conn (or conn
                 (when temp? (d/connect client {:db-name db-name})))]
    (try
      (when seed?
        (seed-from-repo! conn))
      (let [graph (graph {:conn conn
                          :spec-id spec-id
                          :plan-id plan-id
                          :mission-id mission-id})
            file (io/file path)]
        (io/make-parents file)
        (spit file (with-out-str
                     (binding [*print-namespace-maps* false]
                       (prn (assoc graph
                                   :generated/at (str (java.time.Instant/now))
                                   :filters {:spec-id spec-id
                                             :plan-id (some-> plan-id str)
                                             :mission-id mission-id}))))))
      (finally
        (when (and temp? client db-name)
          (d/delete-database client {:db-name db-name})))))) 
