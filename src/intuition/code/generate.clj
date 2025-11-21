(ns intuition.code.generate
  "Materializes CodeDefinitions from Datomic into a sandbox using CodeType
  generator metadata declared in resources/dictionary/code_types.edn. The
  generated artifacts stay inside the mission sandbox and are logged with
  checksums for SYSTEM_SPEC §§3.3–3.6, §4.7, §5.1, §6.2, §7, §9 evidence."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [datomic.client.api :as d]
   [intuition.code.graph :as code.graph]
   [intuition.code.runtime :as code]
   [intuition.sfs.env.bootstrap :as bootstrap])
  (:import
   (java.io File PushbackReader)
   (java.math BigInteger)
   (java.security MessageDigest)
   (java.time Instant)))

(def ^:private repo-root (.getCanonicalPath (io/file ".")))

(def ^:private materialization-spec-sections
  ["3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "6.2" "7" "9"])

(def ^:private code-type-schema
  [{:db/ident :code.type/ident
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :code.type/category
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.type/spec-sections
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.type/default-validators
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.type/generator
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.type/generator-templates
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.type/generated-artifacts
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}])

(def ^:private code-definition-schema
  [{:db/ident :code.definition/ident
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :code.definition/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.definition/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.definition/paths
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.definition/dependencies
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.definition/validators
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.definition/tests
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.definition/spec-sections
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.definition/missions
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}])

(defn ensure-schema!
  "Installs the CodeType/CodeDefinition schema when missing. Safe to call
  repeatedly."
  [conn]
  (let [db (d/db conn)
        installed? (fn [ident]
                     (seq (d/q '[:find ?e
                                 :in $ ?ident
                                 :where [?e :db/ident ?ident]]
                               db ident)))]
    (when-not (installed? :code.type/ident)
      (d/transact conn {:tx-data code-type-schema}))
    (when-not (installed? :code.definition/ident)
      (d/transact conn {:tx-data code-definition-schema})))
  conn)

(defn- sanitize-fragment
  [value]
  (-> (str value)
      (str/replace #"[^A-Za-z0-9_.-]" "-")
      (str/replace #"--+" "-")
      (str/replace #"(^-|-$)" "")))

(defn- mission-ident
  [mission-id]
  (cond
    (keyword? mission-id) mission-id
    (string? mission-id) (keyword (str "mission/" (sanitize-fragment mission-id)))
    :else (keyword (str "mission/" (sanitize-fragment mission-id)))))

(defn- ensure-repo-relative
  [path]
  (let [canonical (.getCanonicalPath (io/file path))]
    (when-not (.startsWith canonical repo-root)
      (throw (ex-info "Path is outside the repo" {:path path :repo repo-root})))
    canonical))

(defn- ensure-parent-dirs!
  [^File file]
  (when-let [parent (.getParentFile file)]
    (.mkdirs parent))
  file)

(defn- mission-log-file
  [mission-id filename]
  (let [dir (io/file repo-root "missions" "logs" (bootstrap/sanitize-fragment mission-id))]
    (.mkdirs dir)
    (io/file dir filename)))

(defn- sha256-file
  [^File file]
  (with-open [input (io/input-stream file)]
    (let [digest (MessageDigest/getInstance "SHA-256")
          buffer (byte-array 8192)]
      (loop []
        (let [read-bytes (.read input buffer)]
          (when (pos? read-bytes)
            (.update digest buffer 0 read-bytes)
            (recur))))
      (format "%064x" (BigInteger. 1 (.digest digest))))))

(defn- read-edn-file
  [^File file]
  (with-open [r (PushbackReader. (io/reader file))]
    (edn/read {:eof nil} r)))

(defn- write-edn!
  [^File file payload]
  (ensure-parent-dirs! file)
  (spit file (pr-str payload))
  (.getCanonicalPath file))

(defn- normalize-generated-path
  [path ident]
  (let [slug (bootstrap/sanitize-fragment (name ident))]
    (-> (or path "")
        str
        (str/replace "{{IDENT}}" slug)
        (str/replace #"^\./" "")
        (str/replace #"^/" "")
        (str/replace #"\\+" "/")
        (str/replace #"//+" "/")
        str/trim)))

(defn- sandbox-relative-file
  [sandbox-root relative]
  (let [sandbox (-> sandbox-root io/file .getCanonicalPath)
        file (io/file sandbox-root relative)
        canonical (.getCanonicalPath file)]
    (when-not (.startsWith canonical sandbox)
      (throw (ex-info "Generated artifact escapes sandbox."
                      {:relative relative
                       :sandbox sandbox
                       :path canonical})))
    {:file file
     :canonical canonical
     :relative relative}))

(defn- resolve-template-source
  [template-path]
  (let [value (-> (or template-path "") str str/trim)]
    (when (str/blank? value)
      (throw (ex-info "Template path required" {:template/path template-path})))
    (let [candidate (io/file value)
          file (if (.isAbsolute candidate)
                 candidate
                 (io/file repo-root value))]
      (cond
        (.exists file)
        {:template/path value
         :template/source :file
         :template/file (.getCanonicalPath file)}

        :else
        (if-let [resource (io/resource value)]
          {:template/path value
           :template/source :resource
           :template/resource resource}
          (throw (ex-info "Generator template not found."
                          {:template/path value})))))))

(defn- resolve-templates
  [templates]
  (mapv resolve-template-source templates))

(defn- generator-symbol
  [value]
  (cond
    (symbol? value) value
    (string? value) (symbol value)
    (keyword? value) (symbol (name value))
    :else nil))

(defn- resolve-generator
  [value]
  (let [sym (generator-symbol value)]
    (when-not (and sym (qualified-symbol? sym))
      (throw (ex-info "Generator must be a namespace-qualified symbol."
                      {:codetype/generator value})))
    (try
      (let [resolved (requiring-resolve sym)]
        (when-not resolved
          (throw (ex-info "Unable to resolve generator function"
                          {:codetype/generator value
                           :symbol sym})))
        resolved)
      (catch Exception e
        (throw (ex-info "Unable to resolve generator function"
                        {:codetype/generator value
                         :symbol sym}
                        e))))))

(defn- write-generated-files!
  [sandbox-root file-specs]
  (mapv (fn [spec]
          (let [relative (normalize-generated-path (:relative-path spec)
                                                   (:codetype/ident spec))
                {:keys [file canonical]} (sandbox-relative-file sandbox-root relative)]
            (ensure-parent-dirs! file)
            (cond
              (contains? spec :content)
              (spit file (:content spec))

              (contains? spec :bytes)
              (with-open [output (io/output-stream file)]
                (.write output ^bytes (:bytes spec)))

              :else
              (throw (ex-info "Generated file requires :content or :bytes"
                              {:relative relative})))
            {:code.materialize/relative-path relative
             :code.materialize/file canonical
             :code.materialize/checksum (sha256-file file)}))
        file-specs))

(defn- append-log!
  [mission-id run-entry]
  (let [file (mission-log-file mission-id "code-materialization.edn")
        payload (if (.exists file)
                  (read-edn-file file)
                  {:mission/id mission-id
                   :code.materialize/spec-sections materialization-spec-sections
                   :code.materialize/runs []})
        updated (update payload :code.materialize/runs conj run-entry)
        path (write-edn! file updated)]
    {:file file
     :path path}))

(defn- hydrate-codetype
  [conn ident]
  (let [db (d/db conn)
        rows (d/q '[:find (pull ?e [:code.type/ident
                                    :code.type/category
                                    :code.type/spec-sections
                                    :code.type/default-validators
                                    :code.type/generator
                                    :code.type/generator-templates
                                    :code.type/generated-artifacts])
                    :in $ ?ident
                    :where [?e :code.type/ident ?ident]]
                  db ident)]
    (first (map first rows))))

(defn- hydrate-definition
  [conn ident]
  (let [db (d/db conn)
        rows (d/q '[:find (pull ?e [:code.definition/ident
                                    :code.definition/name
                                    :code.definition/type
                                    :code.definition/paths
                                    :code.definition/spec-sections
                                    :code.definition/missions])
                    :in $ ?ident
                    :where [?e :code.definition/ident ?ident]]
                  db ident)]
    (first (map first rows))))

(defn- definitions-for-mission
  [conn mission-id explicit-idents]
  (let [mission-kw (mission-ident mission-id)
        idents (if explicit-idents
                 (set explicit-idents)
                 (set (map first
                           (d/q '[:find ?ident
                                  :in $ ?mission
                                  :where [?e :code.definition/missions ?mission]
                                         [?e :code.definition/ident ?ident]]
                                (d/db conn) mission-kw))))
        from-graph (when (and (empty? idents) mission-id)
                     (let [graph (code.graph/graph {:conn conn
                                                    :mission-id mission-id})
                           nodes (:nodes graph)
                           edges (:edges graph)
                           type-map (->> edges
                                         (filter #(= :code.graph.relation/code->code-type
                                                     (:code.graph.edge/relation %)))
                                         (map (fn [edge]
                                                [(:code.graph.edge/from edge)
                                                 (:code.graph.edge/to edge)]))
                                         (into {}))]
                       (map (fn [node]
                              {:code.definition/ident (:code.graph.node/ident node)
                               :code.definition/type (get type-map (:code.graph.node/ident node))
                               :code.definition/spec-sections (vec (or (:code.graph.node/spec-sections node) []))})
                            (filter #(= :code.graph.node.type/code (:code.graph.node/type %))
                                    nodes))))
        idents (or (not-empty idents)
                   (when from-graph (set (map :code.definition/ident from-graph))))
        catalog (when (empty? idents)
                  (->> (code/definitions)
                       (filter (fn [definition]
                                 (some #(= mission-kw %)
                                       (:code.definition/missions definition))))
                       (map :code.definition/ident)
                       set))]
    (cond
      (seq idents) (vec idents)
      (seq from-graph) (mapv :code.definition/ident from-graph)
      (seq catalog) (vec catalog)
      :else [])))

(defn- resolved-definition
  [conn ident]
  (or (hydrate-definition conn ident)
      (code/by-ident ident)
      {:code.definition/ident ident}))

(defn- resolved-codetype
  [conn ident]
  (let [from-db (when ident (hydrate-codetype conn ident))
        from-catalog (when ident (code/type-by-ident ident))]
    (merge from-catalog from-db)))

(defn- definition-target-paths
  [definition code-type]
  (let [ident (:code.definition/ident definition)
        slugged (fn [path] (normalize-generated-path path ident))
        paths (or (:code.definition/paths definition)
                  (->> (:code.type/generated-artifacts code-type)
                       (map slugged)))]
    (->> paths
         (map slugged)
         (remove str/blank?)
         distinct
         vec)))

(defn- generator-context
  [mission-id agent-id ident resolved-templates artifacts options]
  {:mission/id mission-id
   :agent/id agent-id
   :codetype/ident ident
   :codetype/generated-artifacts artifacts
   :codetype/templates (mapv :template/path resolved-templates)
   :codetype/resolved-templates resolved-templates
   :codetype/options options})

(defn- materialize-definition
  [{:keys [sandbox-root mission-id agent-id definition code-type]}]
  (let [ident (:code.definition/ident definition)
        type-ident (:code.definition/type definition)
        generator-ref (:code.type/generator code-type)
        _ (when (or (nil? generator-ref) (str/blank? (str generator-ref)))
            (throw (ex-info "CodeType missing generator metadata"
                            {:code.definition/ident ident
                             :code.definition/type type-ident})))
        templates (vec (or (:code.type/generator-templates code-type) []))
        resolved-templates (resolve-templates templates)
        generator (resolve-generator generator-ref)
        artifacts (definition-target-paths definition code-type)
        _ (when-not (seq artifacts)
            (throw (ex-info "No target artifacts declared for CodeDefinition"
                            {:code.definition/ident ident
                             :code.definition/type type-ident})))
        context (generator-context mission-id agent-id ident resolved-templates artifacts
                                   (:code.definition/options definition))
        payload (generator context)
        generated (vec (or (:generated/files payload) []))
        _ (when-not (seq generated)
            (throw (ex-info "Generator returned no files"
                            {:code.definition/ident ident
                             :code.definition/type type-ident})))
        descriptors (write-generated-files! sandbox-root generated)
        missing (set/difference (set artifacts)
                                (set (map :code.materialize/relative-path descriptors)))
        _ (when (seq missing)
            (throw (ex-info "Generator did not emit expected artifacts"
                            {:code.definition/ident ident
                             :missing (vec missing)})))
        now (str (Instant/now))]
    {:code.definition/ident ident
     :code.definition/type type-ident
     :code.definition/spec-sections (vec (or (:code.definition/spec-sections definition) []))
     :code.materialize/files descriptors
     :code.materialize/generated-at now
     :code.type/generator (str generator-ref)
     :code.type/templates templates
     :code.type/generated-artifacts artifacts}))

(defn materialize!
  "Materializes CodeDefinitions for the given mission-id (or an explicit ident
  set) into sandbox-root. Returns a payload suitable for mission-standard
  logging and action output."
  [{:keys [conn mission-id agent-id sandbox-root definition-idents]}]
  (when (str/blank? (str mission-id))
    (throw (ex-info "mission/id required for code materialization" {:field :mission/id})))
  (when (str/blank? (str agent-id))
    (throw (ex-info "agent/id required for code materialization" {:field :agent/id})))
  (when (str/blank? (str sandbox-root))
    (throw (ex-info "sandbox/root required for code materialization" {:field :sandbox/root})))
  (let [conn (ensure-schema! (or conn (throw (ex-info "conn required" {:field :conn}))))
        sandbox (ensure-repo-relative sandbox-root)
        idents (definitions-for-mission conn mission-id definition-idents)
        materialized (mapv (fn [ident]
                             (let [definition (resolved-definition conn ident)
                                   type-ident (:code.definition/type definition)
                                   resolved-type (resolved-codetype conn type-ident)
                                   _ (when-not type-ident
                                       (throw (ex-info "CodeDefinition missing CodeType"
                                                       {:code.definition/ident ident})))
                                   _ (when-not resolved-type
                                       (throw (ex-info "Unknown CodeType for CodeDefinition"
                                                       {:code.definition/ident ident
                                                        :code.definition/type type-ident})))]
                               (materialize-definition {:sandbox-root sandbox
                                                        :mission-id mission-id
                                                        :agent-id agent-id
                                                        :definition definition
                                                        :code-type resolved-type})))
                           idents)
        run-entry {:code.materialize/definitions materialized
                   :code.materialize/run-at (str (Instant/now))
                   :code.materialize/spec-sections materialization-spec-sections}
        {:keys [path]} (append-log! mission-id run-entry)
        files (vec (mapcat :code.materialize/files materialized))
        paths (vec (map :code.materialize/file files))
        skipped? (empty? materialized)]
    {:action/status :status/ok
     :mission/id mission-id
     :agent/id agent-id
     :sandbox/root sandbox
     :code.materialize/log-path path
     :code.materialize/definitions materialized
     :code.materialize/files files
     :code.materialize/paths paths
     :code.materialize/spec-sections materialization-spec-sections
     :code.materialize/skipped? skipped?}))
