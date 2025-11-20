(ns intuition.code.runtime
  "Loads the CodeType catalog declared in resources/dictionary/code_types.edn and
  exposes helper functions for lookup + dependency analysis so docgen and the
  system-map runtime can reason about code artifacts as first-class data."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]))

(def ^:private code-types-resource "dictionary/code_types.edn")
(def ^:private actions-resources ["dictionary/actions.edn"
                                  "dictionary/actions_env.edn"
                                  "dictionary/actions_system_map.edn"])
(def ^:private protocols-resource "dictionary/protocols.edn")
(def ^:private doc-templates-resource "dictionary/doc_templates.edn")

(defn- read-edn-resource
  [path]
  (if-let [res (io/resource path)]
    (-> res slurp edn/read-string)
    (let [file (io/file "resources" path)]
      (if (.exists file)
        (-> file slurp edn/read-string)
        (throw (ex-info (str "Unable to load EDN resource " path)
                        {:resource path}))))))

(def ^:private catalog*
  (delay (read-edn-resource code-types-resource)))

(defn- entries-of-type
  [entity-type]
  (->> @catalog*
       (filter #(= entity-type (:entity/type %)))))

(def ^:private code-types*
  (delay (entries-of-type :code/type)))

(def ^:private definitions*
  (delay (entries-of-type :code/definition)))

(def ^:private definitions-by-ident*
  (delay (into {}
               (map (juxt :code.definition/ident identity) @definitions*))))

(def ^:private code-types-by-ident*
  (delay (into {}
               (map (juxt :code.type/ident identity) @code-types*))))

(defn definitions
  "Returns the vector of CodeDefinition entities."
  []
  @definitions*)

(defn code-types
  "Returns the vector of CodeType entities."
  []
  @code-types*)

(defn by-ident
  "Lookup a CodeDefinition by ident."
  [ident]
  (get @definitions-by-ident* ident))

(defn type-by-ident
  "Lookup a CodeType entry by ident."
  [ident]
  (get @code-types-by-ident* ident))

(defn type-for-definition
  "Returns the CodeType entry associated with the given CodeDefinition map."
  [definition]
  (type-by-ident (:code.definition/type definition)))

(defn definition-idents
  []
  (set (keys @definitions-by-ident*)))

(defn definitions-by-spec
  "Returns CodeDefinitions whose :code.definition/spec-sections intersect the
  provided `sections` collection."
  [sections]
  (let [targets (->> sections (keep identity) set)]
    (if (seq targets)
      (->> (definitions)
           (filter (fn [definition]
                     (let [specs (set (:code.definition/spec-sections definition))]
                       (seq (set/intersection targets specs)))))
           (sort-by (comp name :code.definition/ident)))
      [])))

(defn dependency-graph
  "Returns a map of CodeDefinition ident -> set of dependency keywords."
  []
  (into {}
        (map (fn [definition]
               [(:code.definition/ident definition)
                (set (or (:code.definition/dependencies definition) []))]))
        (definitions)))

(defn- read-dictionary
  [path]
  (some-> path read-edn-resource))

(def ^:private action-data*
  (delay (mapcat read-dictionary actions-resources)))

(def ^:private protocol-data*
  (delay (read-dictionary protocols-resource)))

(def ^:private doc-template-data*
  (delay (read-dictionary doc-templates-resource)))

(defn- action-idents
  []
  (->> @action-data*
       (keep :action/ident)
       set))

(defn- protocol-idents
  []
  (->> (or @protocol-data* [])
       (keep :protocol/ident)
       set))

(defn- template-idents
  []
  (let [entries (or @doc-template-data* [])]
    (set (concat (keep :template/ident entries)
                 (keep :template.instance/ident entries)))))

(def ^:private dictionary-ident-set*
  (delay (-> #{}
             (into (action-idents))
             (into (protocol-idents))
             (into (template-idents)))))

(defn dictionary-ident-set
  "Set of known dictionary idents (actions, protocols, doc templates)."
  []
  @dictionary-ident-set*)

(defn dictionary-ident?
  [ident]
  (contains? (dictionary-ident-set) ident))

(defn code-ident?
  [ident]
  (contains? (definition-idents) ident))

(defn known-dependency?
  "True when the keyword references a CodeDefinition or dictionary entity."
  [ident]
  (or (code-ident? ident)
      (dictionary-ident? ident)))

(defn lookup-dependency
  "Returns {:kind :code :definition map} or {:kind :dictionary :ident kw} when the
  keyword resolves to a known dependency."
  [ident]
  (cond
    (code-ident? ident) {:kind :code :definition (by-ident ident)}
    (dictionary-ident? ident) {:kind :dictionary :ident ident}
    :else nil))
