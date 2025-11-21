(ns intuition.gateway.context-bundle
  "Builds deterministic agent context bundles that package the graph slice (spec → plan → mission → code/test/doc),
  relevant CodeDefinitions/CodeTypes, and validation artifacts for a mission. Supports SYSTEM_SPEC §§3.3–3.6, §4.7,
  §5.1, §8.1, §9, and §11 traceability by hashing referenced artifacts and trimming the bundle to the focused graph
  neighborhood."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.set :as set]
   [clojure.string :as str]
   [intuition.code.graph :as code.graph]
   [intuition.code.runtime :as code]
   [intuition.datomic :as datomic]
   [intuition.sfs.missions.runtime :as missions])
  (:import
   (java.io File PushbackReader)
   (java.math BigInteger)
   (java.security MessageDigest)))

(def ^:private bundle-id "agent-context-bundle/v1")
(def ^:private bundle-version 1)
(def ^:private system-spec-watermark ["3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "8.1" "9" "11"])

(def ^:private default-log-root "missions/logs")

(defn- canonical-path
  [path]
  (some-> path io/file .getCanonicalPath))

(defn- mission-dir
  [log-root mission-id]
  (let [root (io/file log-root)]
    (if (= (.getName root) (str mission-id))
      root
      (io/file root mission-id))))

(defn- mission-ident-keyword
  [mission-id]
  (keyword (str "mission/" (str mission-id))))

(defn- ensure-parent!
  [path]
  (when path
    (io/make-parents (io/file path))))

(defn- read-edn
  [^File file]
  (with-open [r (PushbackReader. (io/reader file))]
    (edn/read {:eof nil} r)))

(defn- sha256-file
  ^String [^File file]
  (with-open [input (io/input-stream file)]
    (let [digest (MessageDigest/getInstance "SHA-256")
          buffer (byte-array 8192)]
      (loop []
        (let [read-bytes (.read input buffer)]
          (when (pos? read-bytes)
            (.update digest buffer 0 read-bytes)
            (recur))))
      (format "%064x" (BigInteger. 1 (.digest digest))))))

(defn- ensure-conn!
  [conn]
  (missions/prepare-conn! (or conn (datomic/ensure-db!))))

(defn- maybe-existing-bundle
  [log-root mission-id]
  (let [file (io/file (mission-dir log-root mission-id) "context-bundle.edn")]
    (when (.exists file)
      (read-edn file))))

(defn- coerce-ident
  [v]
  (cond
    (keyword? v) v
    (string? v)
    (let [trimmed (str/trim v)]
      (when-not (str/blank? trimmed)
        (if (str/starts-with? trimmed ":")
          (keyword (subs trimmed 1))
          (keyword trimmed))))
    :else nil))

(defn- sanitize-mission-record
  [record]
  (when record
    (-> record
        (dissoc :db/id)
        (update :mission/queue-tags #(vec (or % [])))
        (update :mission/work-tracks #(vec (or % [])))
        (update :mission/tests #(vec (or % [])))
        (update :mission/deliverables #(vec (or % []))))))

(defn- mission-record
  [{:keys [conn mission-id bundle log-root]}]
  (let [existing (maybe-existing-bundle log-root mission-id)]
    (or (when mission-id
          (try
            (missions/get-mission {:mission/id mission-id :conn conn})
            (catch Exception _
              nil)))
        (:mission/record bundle)
        (:mission/record existing)
        {:mission/id mission-id
         :mission/title (str mission-id " (context bundle)")})))

(defn- load-spec-file
  [log-root mission-id]
  (let [candidate (io/file (mission-dir log-root mission-id) "spec.edn")]
    (when (.exists candidate)
      {:path (.getCanonicalPath candidate)
       :data (read-edn candidate)})))

(defn- spec-fragment
  [{:keys [bundle log-root mission-id graph-nodes]}]
  (let [existing (maybe-existing-bundle log-root mission-id)
        spec-file (load-spec-file log-root mission-id)
        spec-node (first (filter #(= :code.graph.node.type/spec (:code.graph.node/type %))
                                 graph-nodes))
        source (or (:spec/source bundle)
                   (:spec/source existing)
                   (:data spec-file))
        spec-id (or (:spec/id source)
                    (:code.graph.node/ident spec-node)
                    (keyword (str "spec/" (str/lower-case (str mission-id)))))
        sections (or (:spec/spec-sections source)
                     (:code.graph.node/spec-sections spec-node)
                     system-spec-watermark)
        requirements (or (:spec/requirements source)
                         (:code.graph.node/requirements spec-node))
        path (or (:spec/input-path source)
                 (:path spec-file))
        hash (when path
               (sha256-file (io/file path)))]
    (cond-> {:spec/id spec-id
             :spec/title (or (:spec/title source) (some-> spec-id name))
             :spec/summary (or (:spec/summary source) "Spec fragment for context bundle")
             :spec/requirements (vec (or requirements []))
             :spec/spec-sections (vec (or sections []))}
      path (assoc :spec/path (canonical-path path)
                  :spec/hash hash)
      (:spec/test-contracts source) (assoc :spec/test-contracts (vec (:spec/test-contracts source))))))

(defn- plan-path
  [log-root mission-id bundle]
  (or (get-in bundle [:plan/snapshot :plan/path])
      (get-in bundle [:plan/path])
      (some-> (io/file (mission-dir log-root mission-id) "plan-snapshot.edn") .getCanonicalPath)
      (some-> (io/file (mission-dir log-root mission-id) "plan.edn") .getCanonicalPath)))

(defn- load-plan
  [path]
  (when-let [file (some-> path io/file)]
    (when (.exists file)
      {:path (.getCanonicalPath file)
       :data (read-edn file)})))

(defn- plan-fragment
  [{:keys [log-root mission-id bundle graph-nodes]}]
  (let [path (plan-path log-root mission-id bundle)
        plan (load-plan path)
        plan-nodes (or (some-> plan :data :work.plan/nodes)
                       (some-> plan :data :plan/nodes)
                       [])
        graph-plan-nodes (filter #(= :code.graph.node.type/plan (:code.graph.node/type %)) graph-nodes)
        nodes (cond
                (seq plan-nodes) plan-nodes
                (seq graph-plan-nodes) (map (fn [node]
                                              {:plan.node/id (:code.graph.node/ident node)
                                               :plan.node/name (:code.graph.node/name node)
                                               :plan.node/requirements (:code.graph.node/requirements node)})
                                            graph-plan-nodes)
                :else [])]
    (cond-> {:plan/id (or (:plan/id bundle)
                          (:work.plan/id (:data plan))
                          (:plan.generation/id (:data plan))
                          (keyword (str "plan/" (str/lower-case (str mission-id)))))
             :plan/nodes (vec nodes)}
      (:path plan) (assoc :plan/path (:path plan)
                          :plan/hash (sha256-file (io/file (:path plan))))
      (seq (:work.plan/coverage (:data plan))) (assoc :plan/coverage (:work.plan/coverage (:data plan)))
      (seq (:plan/validation bundle)) (assoc :plan/validation (:plan/validation bundle)))))

(defn- graph-slice
  [{:keys [conn mission-id graph-seed?] :as opts}]
  (when graph-seed?
    (code.graph/seed-from-repo! conn))
  (let [focus-ident (coerce-ident (:focus/logical opts))
        graph (code.graph/graph {:conn conn
                                 :mission-id mission-id
                                 :node-idents (when focus-ident #{focus-ident})})
        nodes (:nodes graph)
        default-focus (or focus-ident
                          (some-> nodes first :code.graph.node/ident))
        upstream (when default-focus
                   (code.graph/upstream {:graph graph :from default-focus}))
        downstream (when default-focus
                     (code.graph/downstream {:graph graph :from default-focus}))
        selected-idents (set (concat (map :code.graph.node/ident upstream)
                                     (map :code.graph.node/ident downstream)))
        selected? (if (seq selected-idents)
                    (fn [node] (contains? selected-idents (:code.graph.node/ident node)))
                    (constantly true))
        filtered-nodes (->> nodes
                            (filter selected?)
                            (sort-by (comp name :code.graph.node/ident))
                            vec)
        filtered-edges (->> (:edges graph)
                            (filter (fn [{:code.graph.edge/keys [from to]}]
                                      (and (some? from) (some? to)
                                           (or (empty? selected-idents)
                                               (and (selected-idents from)
                                                    (selected-idents to))))))
                            (sort-by (comp name :code.graph.edge/ident))
                            vec)]
    {:focus/node default-focus
     :nodes filtered-nodes
     :edges filtered-edges
     :neighbors {:upstream (vec upstream)
                 :downstream (vec downstream)}}))

(defn- edn-safe-ident
  [v]
  (if (keyword? v)
    (let [nm (name v)]
      (if (and (namespace v)
               (or (str/includes? nm "/")
                   (some-> nm seq first Character/isDigit)))
        (str v)
        v))
    v))

(defn- sanitize-node
  [node]
  (-> node
      (update :code.graph.node/ident edn-safe-ident)
      (update :code.graph.node/spec-id edn-safe-ident)
      (update :code.graph.node/plan-id edn-safe-ident)
      (update :code.graph.node/mission-id edn-safe-ident)
      (update :code.graph.node/requirements #(vec (map edn-safe-ident (or % []))))
      (update :code.graph.node/spec-sections #(vec (map edn-safe-ident (or % []))))
      (update :code.graph.node/missions #(vec (map edn-safe-ident (or % []))))))

(defn- sanitize-edge
  [edge]
  (-> edge
      (update :code.graph.edge/ident edn-safe-ident)
      (update :code.graph.edge/from edn-safe-ident)
      (update :code.graph.edge/to edn-safe-ident)
      (update :code.graph.edge/requirements #(vec (map edn-safe-ident (or % []))))
      (update :code.graph.edge/spec-sections #(vec (map edn-safe-ident (or % []))))))

(defn- edn-safe-graph
  [graph]
  (-> graph
      (update :nodes #(vec (map sanitize-node (or % []))))
      (update :edges #(vec (map sanitize-edge (or % []))))
      (update-in [:neighbors :upstream] #(vec (map sanitize-node (or % []))))
      (update-in [:neighbors :downstream] #(vec (map sanitize-node (or % []))))))

(defn- relevant-definition-idents
  [graph-nodes]
  (->> graph-nodes
       (filter #(= :code.graph.node.type/code (:code.graph.node/type %)))
       (map :code.graph.node/ident)
       set))

(defn- relevant-test-idents
  [graph-nodes]
  (->> graph-nodes
       (filter #(= :code.graph.node.type/test (:code.graph.node/type %)))
       (map :code.graph.node/ident)
       set))

(defn- relevant-doc-idents
  [graph-nodes]
  (->> graph-nodes
       (filter #(= :code.graph.node.type/doc (:code.graph.node/type %)))
       (map :code.graph.node/ident)
       set))

(defn- definitions-block
  [graph-nodes mission-id spec-fragment]
  (let [graph-idents (relevant-definition-idents graph-nodes)
        mission-ident (mission-ident-keyword mission-id)
        spec-sections (set (or (:spec/spec-sections spec-fragment) []))
        definitions (code/definitions)
        section-idents (->> definitions
                            (filter (fn [definition]
                                      (seq (set/intersection spec-sections
                                                             (set (or (:code.definition/spec-sections definition) []))))))
                            (map :code.definition/ident))
        mission-idents (->> definitions
                            (filter (fn [definition]
                                      (some #{mission-ident} (or (:code.definition/missions definition) []))))
                            (map :code.definition/ident))
        idents (set (concat graph-idents section-idents mission-idents))]
    (->> idents
         (map (fn [ident]
                (let [definition (code/by-ident ident)]
                  (or (some-> definition
                              (select-keys [:code.definition/ident
                                            :code.definition/name
                                            :code.definition/type
                                            :code.definition/paths
                                            :code.definition/dependencies
                                            :code.definition/tests
                                            :code.definition/spec-sections]))
                      {:code.definition/ident ident
                       :code.definition/name (name ident)}))))
         (sort-by (comp name :code.definition/ident))
         vec)))

(defn- code-types-block
  [definitions graph-nodes]
  (let [from-defs (keep :code.definition/type definitions)
        from-edges (->> graph-nodes
                        (filter #(= :code.graph.node.type/code-type (:code.graph.node/type %)))
                        (map :code.graph.node/ident))
        idents (set (concat from-defs from-edges))]
    (->> idents
         (keep (fn [ident]
                 (let [entry (code/type-by-ident ident)]
                   (or (some-> entry
                               (select-keys [:code.type/ident
                                             :code.type/category
                                             :code.type/default-validators
                                             :code.type/generator
                                             :code.type/generator-templates
                                             :code.type/generated-artifacts
                                             :code.type/spec-sections]))
                       {:code.type/ident ident}))))
         (sort-by (comp name :code.type/ident))
         vec)))

(defn- hash-if-exists
  [path]
  (let [file (some-> path io/file)]
    (when (and file (.exists file))
      (sha256-file file))))

(defn- artifact-entry
  [label kind path]
  (let [canonical (canonical-path path)
        file (some-> canonical io/file)]
    (when canonical
      (cond-> {:artifact/label label
               :artifact/kind kind
               :artifact/path canonical
               :artifact/present? (boolean (and file (.exists file)))}
        (and file (.exists file))
        (assoc :artifact/sha256 (sha256-file file)
               :artifact/bytes (.length file))))))

(def ^:private validation-artifact-hints
  [{:kind :artifact.kind/spec :labels ["Spec" "Spec validation" "Spec publish log"]
    :paths ["spec.edn" "spec-validation.edn" "spec-validation.md"]}
   {:kind :artifact.kind/plan :labels ["Plan snapshot" "Plan validation" "Work plan validation"]
    :paths ["plan-snapshot.edn" "plan-validation.edn" "work-plan-validation.edn" "work-plan-validation.md"]}
   {:kind :artifact.kind/mission :labels ["Run manifest" "Run log" "Branch" "Sandbox manifest" "Mission plan binding"]
    :paths ["run-manifest.edn" "run.log" "branch.edn" "sandbox-manifest.edn" "mission-plan-binding.edn"]}
   {:kind :artifact.kind/merge :labels ["Merge prepare" "Merge log"]
    :paths ["merge/merge-prepare.edn" "merge/merge-log.edn" "merge/merge-failure.edn"]}
   {:kind :artifact.kind/analytics :labels ["Analytics (edn)" "Analytics (md)" "Analysis report"]
    :paths ["analytics.edn" "analysis/analytics.edn" "analysis/analytics.md" "analysis/report.edn"]}])

(defn- collect-validation-artifacts
  [log-root mission-id]
  (let [root (mission-dir log-root mission-id)]
    (->> validation-artifact-hints
         (mapcat (fn [{:keys [kind paths labels]}]
                   (map-indexed (fn [idx path]
                                  (let [label (or (nth labels idx nil)
                                                  (str (name kind) " " (inc idx)))]
                                    (artifact-entry label kind (io/file root path))))
                                paths)))
         (remove nil?)
         (filter :artifact/present?)
         (sort-by :artifact/path)
         vec)))

(defn- write-bundle!
  [path data]
  (ensure-parent! path)
  (with-open [w (io/writer path)]
    (binding [*print-namespace-maps* false]
      (pprint/pprint data w)))
  (canonical-path path))

(defn build!
  "Builds and writes an agent context bundle for the provided mission. Options:
   - :mission/id (required)
   - :focus/node – optional graph node ident to anchor the neighborhood
   - :bundle – optional existing bundle map used for hints
   - :log/root – mission log root (defaults to missions/logs)
   - :output/path – override output path
   - :conn – Datomic connection (prepared when omitted)
   Returns the in-memory bundle with :bundle/path and :bundle/sha256 populated when written."
  [{:mission/keys [id]
    :focus/keys [node]
    :as opts}]
  (when (str/blank? (str id))
    (throw (ex-info "mission/id is required" {:field :mission/id})))
  (let [log-root (or (:log/root opts) default-log-root)
        mission-root (mission-dir log-root id)
        output (or (:output/path opts)
                   (canonical-path (io/file mission-root "agent-context-bundle.edn")))
        conn (ensure-conn! (:conn opts))
        mission-rec (sanitize-mission-record
                     (mission-record {:conn conn
                                      :mission-id id
                                      :bundle (:bundle opts)
                                      :log-root log-root}))
        graph (-> (graph-slice {:conn conn
                                :mission-id id
                                :focus/logical node
                                :graph-seed? true})
                  edn-safe-graph
                  (update :focus/node edn-safe-ident))
        graph-nodes (:nodes graph)
        spec (spec-fragment {:bundle (:bundle opts)
                             :log-root log-root
                             :mission-id id
                             :graph-nodes graph-nodes})
        plan (plan-fragment {:bundle (:bundle opts)
                             :log-root log-root
                             :mission-id id
                             :graph-nodes graph-nodes})
        definitions (definitions-block graph-nodes id spec)
        code-types (code-types-block definitions graph-nodes)
        tests (-> (relevant-test-idents graph-nodes)
                  (into (set (mapcat :code.definition/tests definitions)))
                  (set))
        docs (relevant-doc-idents graph-nodes)
        artifacts (collect-validation-artifacts log-root id)
        bundle-map {:bundle/id bundle-id
                    :bundle/version bundle-version
                    :mission/id id
                    :focus/node (:focus/node graph)
                    :mission/record mission-rec
                    :spec/fragment spec
                    :plan/nodes (:plan/nodes plan)
                    :plan/coverage (:plan/coverage plan)
                    :graph/slice (select-keys graph [:nodes :edges :neighbors])
                    :code/definitions definitions
                    :code/types code-types
                    :tests/related (-> tests sort vec)
                    :docs/related (-> docs sort vec)
                    :artifacts/validation artifacts
                    :system-spec/sections system-spec-watermark
                    :context/source {:log/root (canonical-path log-root)
                                     :mission/root (canonical-path mission-root)
                                     :bundle/hints (boolean (:bundle opts))
                                     :bundle/existing (boolean (maybe-existing-bundle log-root id))}}
        enriched (cond-> bundle-map
                   (:plan/path plan) (assoc :plan/path (:plan/path plan)
                                            :plan/hash (:plan/hash plan))
                   output (assoc :bundle/path (canonical-path output)))]
    (when output
      (write-bundle! output enriched))
    (cond-> enriched
      output (assoc :bundle/sha256 (hash-if-exists output)))))
