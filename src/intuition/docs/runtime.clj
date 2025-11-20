(ns intuition.docs.runtime
  "Doc generation runtime for TemplateDefinitions declared in resources/dictionary/doc_templates.edn.
   Renders TypeDefinition and Mission documentation into Markdown/EDN artifacts under docs/generated/*."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [intuition.code.runtime :as code])
  (:import
   (java.io File)))

(def ^:private repo-root (.getCanonicalFile (io/file ".")))
(def ^:private doc-root (doto (io/file repo-root "docs" "generated") .mkdirs))
(def ^:private doc-templates-resource "dictionary/doc_templates.edn")
(def ^:private meta-types-resource "dictionary/meta-types.edn")
(def ^:private missions-resource "dictionary/missions.edn")

(defn- read-edn-resource
  [path]
  (if-let [res (io/resource path)]
    (-> res slurp edn/read-string)
    (let [file (io/file "resources" path)]
      (if (.exists file)
        (-> file slurp edn/read-string)
        (throw (ex-info (str "Unable to load EDN resource " path)
                        {:resource path}))))))

(def ^:private templates* (delay (read-edn-resource doc-templates-resource)))
(def ^:private meta-types* (delay (read-edn-resource meta-types-resource)))
(def ^:private missions* (delay (read-edn-resource missions-resource)))

(defn- entries-by
  [pred coll keyfn]
  (->> coll
       (filter pred)
       (map (juxt keyfn identity))
       (into {})))

(defn- template-definitions
  []
  (entries-by #(= :template/definition (:entity/type %))
              @templates*
              :template/ident))

(defn- template-instances
  []
  (entries-by #(= :template/instance (:entity/type %))
              @templates*
              :template.instance/ident))

(defn- type-definitions
  []
  (entries-by #(= :meta/type (:entity/type %))
              @meta-types*
              :type/ident))

(defn- attributes-by-ident
  []
  (entries-by #(= :meta/attribute (:entity/type %))
              @meta-types*
              :attribute/ident))

(defn- mission-records
  []
  (entries-by :mission/id @missions* :mission/id))

(defn- code-definition->doc
  [definition]
  (let [type (code/type-for-definition definition)
        type-doc (when type
                   {:code.type/ident (:code.type/ident type)
                    :code.type/name (:entity/name type)
                    :code.type/category (:code.type/category type)
                    :code.type/spec-sections (:code.type/spec-sections type)})]
    {:code.definition/ident (:code.definition/ident definition)
     :code.definition/name (:code.definition/name definition)
     :code.definition/type (:code.definition/type definition)
     :code.definition/spec-sections (vec (or (:code.definition/spec-sections definition) []))
     :code.definition/dependencies (vec (or (:code.definition/dependencies definition) []))
     :code.definition/tests (vec (or (:code.definition/tests definition) []))
     :code.definition/missions (vec (or (:code.definition/missions definition) []))
     :code.definition/validators (vec (or (:code.definition/validators definition) []))
     :code.definition/description (:entity/description definition)
     :code.definition/type-meta type-doc}))

(defn- doc-code-definitions
  [spec-sections]
  (->> (code/definitions-by-spec spec-sections)
       (map code-definition->doc)
       vec))

(defn- canonical-path
  [^File file]
  (.getCanonicalPath file))

(defn- slug->file
  [slug extension]
  (when (str/blank? slug)
    (throw (ex-info "doc slug is required" {:doc/slug slug})))
  (when (or (str/includes? slug "..")
            (str/starts-with? slug "/"))
    (throw (ex-info "doc slug must be repo-relative" {:doc/slug slug})))
  (let [file (io/file doc-root (str slug extension))]
    (doto (.getParentFile file) .mkdirs)
    file))

(defn- join-sections
  [sections]
  (->> sections
       (map #(str "§" %))
       (str/join ", ")))

(defn- kw->string
  [value]
  (cond
    (keyword? value) (name value)
    (nil? value) "n/a"
    :else (str value)))

(defn- code
  [value]
  (format "`%s`" (kw->string value)))

(defn- format-line
  [label value]
  (str "- **" label ":** " value "\n"))

(defn- type-attribute->doc
  [attribute]
  {:attribute/ident (:attribute/ident attribute)
   :attribute/value-type (:attribute/value-type attribute)
   :attribute/cardinality (:attribute/cardinality attribute)
   :attribute/required? (boolean (:attribute/required? attribute))
   :attribute/description (:entity/description attribute)})

(defn- attribute->markdown-row
  [attribute]
  (let [ident (:attribute/ident attribute)
        value-type (:attribute/value-type attribute)
        cardinality (:attribute/cardinality attribute)
        required? (:attribute/required? attribute)
        description (:attribute/description attribute)]
    (format "| `%s` | `%s` | `%s` | %s | %s |\n"
            (name ident)
            (name value-type)
            (name cardinality)
            (if required? "yes" "no")
            (or description ""))))

(defn- list-line
  [value]
  (str "- " (kw->string value) "\n"))

(defn- track-line
  [value]
  (if (keyword? value)
    (str "- `" (name value) "`\n")
    (list-line value)))

(defn- ensure-type
  [type-ident]
  (or ((type-definitions) type-ident)
      (throw (ex-info "Unknown TypeDefinition for doc template"
                      {:type/ident type-ident}))))

(defn- ensure-attribute
  [attr-ident]
  (or ((attributes-by-ident) attr-ident)
      (throw (ex-info "Unknown AttributeDefinition for doc template"
                      {:attribute/ident attr-ident}))))

(defn- ensure-mission
  [mission-id]
  (or ((mission-records) mission-id)
      (throw (ex-info "Unknown mission for doc template"
                      {:mission/id mission-id}))))

(defn- ensure-template-instance
  [ident definition]
  (let [instance ((template-instances) ident)]
    (when-not instance
      (throw (ex-info "Unknown doc template instance"
                      {:template.instance/ident ident})))
    (when (and definition
               (not= definition (:template.instance/definition instance)))
      (throw (ex-info "Template instance does not match definition"
                      {:template.instance/ident ident
                       :expected definition
                       :actual (:template.instance/definition instance)})))
    instance))

(defn- doc-base
  [definition instance]
  (let [definitions (template-definitions)
        template-def (definitions definition)]
    (when-not template-def
      (throw (ex-info "Unknown template definition"
                      {:template/ident definition})))
    (let [config (:template.instance/config instance)
          title (:doc/title config)
          slug (:doc/slug config)
          spec-sections (vec (or (:doc/spec-sections config) []))]
      (when (str/blank? title)
        (throw (ex-info "doc title is required" {:template.instance/ident (:template.instance/ident instance)})))
      (when (str/blank? slug)
        (throw (ex-info "doc slug is required" {:template.instance/ident (:template.instance/ident instance)})))
      {:doc/template definition
       :doc/template-instance (:template.instance/ident instance)
       :doc/categories (vec (or (get-in template-def [:template/meta :doc/categories]) []))
       :doc/spec-sections spec-sections
       :doc/title title
       :doc/slug slug
       :doc/template-meta (:template/meta template-def)
       :doc/code-definitions (doc-code-definitions spec-sections)})))

(defn- type-doc-data
  [instance]
  (let [config (:template.instance/config instance)
        type-ident (:doc/type-ident config)
        type-def (ensure-type type-ident)
        attr-order (vec (or (:doc/attribute-order config) []))
        attr-idents (vec (:type/attributes type-def))
        _ (doseq [attr attr-order]
            (when-not (some #{attr} attr-idents)
              (throw (ex-info "Attribute order references attribute not declared on type"
                              {:type/ident type-ident
                               :attribute attr}))))
        ordered (distinct (concat attr-order attr-idents))
        attributes (map (comp type-attribute->doc ensure-attribute) ordered)]
    (assoc (doc-base :template/doc.type instance)
           :doc/spec-sections (vec (or (:doc/spec-sections config) []))
           :type {:ident type-ident
                  :name (:entity/name type-def)
                  :path (:entity/path type-def)
                  :category (:type/category type-def)
                  :description (:entity/description type-def)
                  :spec/sections (get-in type-def [:type/meta :spec/sections])}
           :attributes (vec attributes))))

(defn- mission-doc-data
  [instance]
  (let [config (:template.instance/config instance)
        mission-id (:doc/mission-id config)
        mission (ensure-mission mission-id)]
    (assoc (doc-base :template/doc.mission instance)
           :doc/spec-sections (vec (or (:doc/spec-sections config) []))
           :mission {:id mission-id
                     :title (:mission/title mission)
                     :summary (:mission/summary mission)
                     :category (:mission/category mission)
                     :priority (:mission/priority mission)
                     :status (:mission/status mission)
                     :protocol (:mission/protocol mission)
                     :scope (:mission/scope mission)
                     :prerequisites (vec (or (:mission/prerequisites mission) []))
                     :deliverables (vec (or (:mission/deliverables mission) []))
                     :work-tracks (vec (or (:mission/work-tracks mission) []))
                     :tests (vec (or (:mission/tests mission) []))
                     :spec-section (:mission/spec-section mission)
                     :owner (:mission/owner mission)})))

(defn- type-doc->markdown
  [{:doc/keys [title spec-sections categories template template-instance]
    :keys [type attributes]}]
  (let [{:keys [ident path category description]
         type-name :name} type]
    (str "# " title "\n\n"
         (when (seq spec-sections)
           (str "*Spec sections:* " (join-sections spec-sections) "\n\n"))
         (when (seq categories)
           (str "*Doc categories:* "
                (str/join ", " (map name categories)) "\n\n"))
         "*Doc template:* `" (name template) "`\n\n"
         "*Template instance:* `" (name template-instance) "`\n\n"
         "## Type Summary\n\n"
         (format-line "Name" (or type-name "n/a"))
         (format-line "Ident" (code ident))
         (format-line "Path" (or path "n/a"))
         (format-line "Category" (code category))
         (format-line "Description" (or description ""))
         "\n## Attributes\n\n"
         "| Ident | Value Type | Cardinality | Required? | Description |\n"
         "|-------|------------|-------------|-----------|-------------|\n"
         (apply str (map attribute->markdown-row attributes)))))

(defn- mission-doc->markdown
  [{:doc/keys [title spec-sections categories template template-instance]
    :keys [mission]}]
  (let [{:keys [id summary scope prerequisites deliverables work-tracks tests category priority status protocol]} mission]
    (str "# " title "\n\n"
         (when (seq spec-sections)
           (str "*Spec sections:* " (join-sections spec-sections) "\n\n"))
         (when (seq categories)
           (str "*Doc categories:* "
                (str/join ", " (map name categories)) "\n\n"))
         "*Doc template:* `" (name template) "`\n\n"
         "*Template instance:* `" (name template-instance) "`\n\n"
         (format-line "Mission ID" (code id))
         (format-line "Summary" (or summary ""))
         (format-line "Category" (code category))
         (format-line "Priority" (code priority))
         (format-line "Status" (code status))
         (format-line "Protocol" (code protocol))
         "\n## Scope\n\n```\n" (or scope "") "\n```\n\n"
         "## Prerequisites\n\n"
         (if (seq prerequisites)
           (apply str (map list-line prerequisites))
           "- None\n")
         "\n## Deliverables\n\n"
         (if (seq deliverables)
           (apply str (map list-line deliverables))
           "- None\n")
         "\n## Work Tracks\n\n"
         (if (seq work-tracks)
             (apply str (map track-line work-tracks))
             "- None\n")
         "\n## Tests\n\n"
         (if (seq tests)
           (apply str (map list-line tests))
           "- None\n"))))

(defn- markdown-writer
  [definition doc-data]
  (case definition
    :template/doc.type (type-doc->markdown doc-data)
    :template/doc.mission (mission-doc->markdown doc-data)
    (throw (ex-info "Unsupported doc template definition" {:definition definition}))))

(defn- write-doc!
  [doc-data]
  (let [slug (:doc/slug doc-data)
        markdown-file (slug->file slug ".md")
        edn-file (slug->file slug ".edn")
        markdown (markdown-writer (:doc/template doc-data) doc-data)]
    (spit markdown-file markdown)
    (spit edn-file (pr-str doc-data))
    (let [doc (assoc doc-data
                     :markdown/path (canonical-path markdown-file)
                     :edn/path (canonical-path edn-file))]
      (select-keys doc [:doc/template
                        :doc/template-instance
                        :doc/title
                        :doc/slug
                        :doc/categories
                        :doc/spec-sections
                        :markdown/path
                        :edn/path]))))

(defn- definition->instances
  [definition template-idents]
  (let [instances (template-instances)
        filtered (->> instances
                      (filter (fn [[_ instance]]
                                (= definition (:template.instance/definition instance))))
                      (map second)
                      vec)]
    (cond
      (and template-idents (not (seq template-idents)))
      []

      template-idents
      (mapv #(ensure-template-instance % definition) template-idents)

      :else
      filtered)))

(defn generate-docs!
  "Generate docs for template instances matching `:definition`.
   Options:
   - :definition – :template/doc.type or :template/doc.mission (required)
   - :template-idents – vector of specific TemplateInstance idents (optional).

   Returns a vector of doc metadata maps with :markdown/path and :edn/path."
  [{:keys [definition template-idents]}]
  (when-not definition
    (throw (ex-info "doc definition is required" {})))
  (let [instances (definition->instances definition template-idents)]
    (when-not (seq instances)
      (throw (ex-info "No template instances found for docgen"
                      {:definition definition
                       :template-idents template-idents})))
    (->> instances
         (map (fn [instance]
                (let [doc-data (case definition
                                 :template/doc.type (type-doc-data instance)
                                 :template/doc.mission (mission-doc-data instance)
                                 (throw (ex-info "Unsupported doc definition"
                                                 {:definition definition})))]
                  (write-doc! doc-data))))
         vec)))

(defn generate-type-docs!
  [{:keys [template-idents]}]
  (generate-docs! {:definition :template/doc.type
                   :template-idents template-idents}))

(defn generate-mission-docs!
  [{:keys [template-idents]}]
  (generate-docs! {:definition :template/doc.mission
                   :template-idents template-idents}))
