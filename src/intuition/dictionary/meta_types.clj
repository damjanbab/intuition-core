(ns intuition.dictionary.meta-types
  "Mission M-01 bootstrap for the Dictionary meta-model. Implements the
   TypeDefinition/AttributeDefinition seed described in SYSTEM_SPEC.md
   §§0–5 so the runtime can transact/query structural metadata."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [datomic.client.api :as d])
  (:import
   (java.io PushbackReader)))

(def meta-types-resource "dictionary/meta-types.edn")

(def ^:private type-spec-justification
  "Mapping of core TypeDefinitions to the SYSTEM_SPEC.md sections that require
  them. These citations keep the Dictionary self-documenting per §4.9."
  {:dictionary/entity {:sections ["4.1"]
                       :summary "Common entity envelope (type/path/status/version/audit)."}
   :meta/type {:sections ["4.2" "11.1"]
               :summary "Describes all structural types."}
   :meta/attribute {:sections ["4.2" "11.1"]
                    :summary "Describes all structural attributes."}
   :meta/action-definition {:sections ["4.6" "5.1" "11.2"]
                            :summary "Actions enforce deterministic change."}
   :meta/protocol-definition {:sections ["4.6" "5.2" "5.3"]
                              :summary "Protocols orchestrate governed sequences."}
   :mission/record {:sections ["1.2" "3.1" "3.3" "3.5" "3.10" "11.3"]
                    :summary "Missions are atomic change units."}
   :role/definition {:sections ["2.1" "2.2"]
                     :summary "Roles define trust boundaries."}
   :permission/definition {:sections ["2.1" "2.2" "5.3"]
                           :summary "Permissions gate all actions/protocols."}
   :user/account {:sections ["2.1" "2.2" "6.2"]
                  :summary "Agents executing SfS flows."}
   :template/definition {:sections ["4.3" "4.10"]
                         :summary "Reusable UI/behavior primitives."}
   :template/instance {:sections ["4.3" "4.5" "4.10"]
                       :summary "Bound template configuration."}
   :app/definition {:sections ["4.5" "4.10"]
                    :summary "Apps composed from templates/navigation."}
   :workflow/definition {:sections ["4.5" "5.1" "5.2"]
                         :summary "Long-running flows referencing templates/actions."}
   :nav/space {:sections ["4.4" "4.10"]
               :summary "Graph-first navigation contexts."}
   :nav/item {:sections ["4.4" "4.10"]
              :summary "Nodes inside navigation spaces."}
   :spec/target {:sections ["3.3" "3.4" "3.5" "3.6" "4.1" "4.2" "4.7" "5.1" "9"]
                 :summary "System Spec targets that missions + tests must satisfy."}
   :meta/code-type {:sections ["4.7" "5.1"]
                    :summary "Code governance + validation primitives."}
   :recipe/definition {:sections ["2.1" "2.2" "3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "5.3" "8.1" "9" "11"]
                       :summary "Contract for cataloged recipes that drive planner/router behavior."}
   :recipe/plan {:sections ["3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "5.3" "9" "11"]
                 :summary "Plan-level schema describing allowed ops, limits, and validations."}
   :recipe/step {:sections ["3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "5.3" "9" "11"]
                 :summary "Step-level contract for deterministic execution primitives."}})

(defn- resolve-resource [path]
  (or (io/resource path)
      (let [f (io/file path)]
        (when (.exists f) f))
      (throw (ex-info (str "Unable to locate EDN resource " path) {:resource path}))))

(defn- read-edn [path]
  (let [url (resolve-resource path)]
    (with-open [r (PushbackReader. (io/reader url))]
      (edn/read {:eof nil} r))))

(defn load-bundle
  "Load the meta-type EDN bundle from the given resource path (defaults to
  `dictionary/meta-types.edn`). Returns a vector of entity maps."
  ([] (load-bundle meta-types-resource))
  ([path]
   (or (seq (read-edn path))
       (throw (ex-info "Meta-type EDN bundle is empty" {:resource path})))))

(def ^:private stringify-keys #{:type/meta :attribute/options})

(def ^:private base-schema
  [{:db/ident :entity/type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :entity/path :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :entity/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :entity/status :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :entity/version :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
   {:db/ident :entity/created-at :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}
   {:db/ident :entity/created-by :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :entity/updated-at :db/valueType :db.type/instant :db/cardinality :db.cardinality/one}
   {:db/ident :entity/updated-by :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :entity/description :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   {:db/ident :type/ident :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :type/category :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :type/attributes :db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   {:db/ident :type/meta :db/valueType :db.type/string :db/cardinality :db.cardinality/one}

   {:db/ident :attribute/ident :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
   {:db/ident :attribute/owner-type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :attribute/value-type :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :attribute/cardinality :db/valueType :db.type/keyword :db/cardinality :db.cardinality/one}
   {:db/ident :attribute/required? :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
   {:db/ident :attribute/options :db/valueType :db.type/string :db/cardinality :db.cardinality/one}
   {:db/ident :attribute/description :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])

(def ^:private value-type-checks
  {:db.type/string string?
   :db.type/keyword keyword?
   :db.type/long integer?
   :db.type/instant #(instance? java.util.Date %)
   :db.type/boolean #(instance? Boolean %)
   :db.type/symbol symbol?})

(defn- ensure-unique!
  [entries key-fn label]
  (let [dupes (->> entries
                   (map key-fn)
                   (remove nil?)
                   frequencies
                   (filter (fn [[_ cnt]] (< 1 cnt))))]
    (when (seq dupes)
      (throw (ex-info (str label " must be unique") {:duplicates dupes})))))

(defn- type-definition? [entity]
  (= :meta/type (:entity/type entity)))

(defn- attribute-definition? [entity]
  (= :meta/attribute (:entity/type entity)))

(defn- stringify-entry [entry]
  (reduce (fn [acc k]
            (if-let [v (get acc k)]
              (assoc acc k (if (string? v) v (pr-str v)))
              acc))
          entry
          stringify-keys))

(defn- validate-enums! [attribute]
  (when-let [enum (get-in attribute [:attribute/options :enum])]
    (when (empty? enum)
      (throw (ex-info "Enum options cannot be empty" {:attribute attribute})))
    (let [value-type (:attribute/value-type attribute)
          pred (value-type-checks value-type)]
      (when-not pred
        (throw (ex-info "Enum validation missing predicate" {:value-type value-type :attribute attribute})))
      (when-not (every? pred enum)
        (throw (ex-info "Enum values must match attribute value type" {:attribute attribute}))))))

(defn- validate-type-meta! [type-map]
  (doseq [[ident {:keys [sections]}] type-spec-justification]
    (let [type (get type-map ident)]
      (when-not type
        (throw (ex-info "Missing required TypeDefinition" {:type ident})))
      (let [meta (:type/meta type)
            _ (when-not (map? meta)
                (throw (ex-info "TypeDefinition missing :type/meta map" {:type ident :meta meta})))
            declared (set (:spec/sections meta))]
        (when-not (and declared (set/subset? (set sections) declared))
          (throw (ex-info "TypeDefinition is missing spec citations" {:type ident :expected sections :actual declared}))))))
  type-map)

(defn- validate-types-and-attrs!
  [entries]
  (ensure-unique! entries :entity/path "Entity paths")
  (let [types (->> entries (filter type-definition?) (map (juxt :type/ident identity)) (into {}))
        attrs (->> entries (filter attribute-definition?) (map (juxt :attribute/ident identity)) (into {}))]
    (when (empty? types)
      (throw (ex-info "No TypeDefinitions were found" {})))
    (when (empty? attrs)
      (throw (ex-info "No AttributeDefinitions were found" {})))
    (ensure-unique! (vals types) :type/ident "Type idents")
    (ensure-unique! (vals attrs) :attribute/ident "Attribute idents")
    (doseq [attr (vals attrs)]
      (when-not (types (:attribute/owner-type attr))
        (throw (ex-info "Attribute refers to missing owner type" {:attribute attr})))
      (when-not (#{:db.cardinality/one :db.cardinality/many} (:attribute/cardinality attr))
        (throw (ex-info "Unsupported cardinality" {:attribute attr})))
      (validate-enums! attr))
    (doseq [[ident type] types
            :let [attributes (set (:type/attributes type))
                  required-ids (->> attrs
                                    vals
                                    (filter #(and (= (:attribute/owner-type %) ident)
                                                  (:attribute/required? %)))
                                    (map :attribute/ident)
                                    set)]]
      (doseq [attr-ident attributes]
        (when-not (attrs attr-ident)
          (throw (ex-info "Type references unknown attribute" {:type ident :attribute attr-ident}))))
      (when-not (set/subset? required-ids attributes)
        (throw (ex-info "Type is missing required attributes" {:type ident :required required-ids :present attributes})))
      (when-let [extends (seq (get-in type [:type/meta :extends]))]
        (doseq [parent extends]
          (when-not (types parent)
            (throw (ex-info "Type extends missing parent" {:type ident :parent parent}))))))
    {:types (validate-type-meta! types)
     :attributes attrs}))

(defn validate-bundle!
  "Validate the EDN bundle and return {:types .. :attributes .. :entries ..}."
  [entries]
  (let [{:keys [types attributes]} (validate-types-and-attrs! entries)]
    {:entries entries :types types :attributes attributes}))

(defn- ensure-storage-dir! [dir]
  (let [f (io/file dir)]
    (when-not (.exists f)
      (.mkdirs f))))

(def default-config
  {:system "dictionary-system"
   :db-name "dictionary-meta"
   :storage-dir (.getAbsolutePath (io/file "data/dictionary-meta"))})

(defn- client [{:keys [system storage-dir]}]
  (ensure-storage-dir! storage-dir)
  (d/client {:server-type :dev-local
             :system system
             :storage-dir storage-dir}))

(defn- ensure-db! [client db-name]
  (d/create-database client {:db-name db-name})
  (d/connect client {:db-name db-name}))

(defn- ensure-schema! [conn]
  (let [db (d/db conn)
        installed? (seq (d/q '[:find ?e :where [?e :db/ident :entity/path]] db))]
    (when-not installed?
      (d/transact conn {:tx-data base-schema}))))

(defn- transact-bundle! [conn entries]
  (let [tx-data (vec (map stringify-entry entries))]
    (d/transact conn {:tx-data tx-data})))

(defn definition-counts
  "Return {:types n :attributes n} for the given DB/conn."
  [db]
  {:types (count (d/q '[:find ?e :where [?e :entity/type :meta/type]] db))
   :attributes (count (d/q '[:find ?e :where [?e :entity/type :meta/attribute]] db))})

(defn seed-core-meta!
  "Seed Datomic with the core TypeDefinition + AttributeDefinition bundle.

  Options:
  - :resource-path – override EDN resource (default dictionary/meta-types.edn)
  - :system – dev-local system name (defaults to dictionary-system)
  - :db-name – database name (defaults to dictionary-meta)
  - :storage-dir – dev-local storage directory (defaults to data/dictionary-meta)

  Returns {:conn .. :counts .. :db-name .. :tx ..}."
  ([ ] (seed-core-meta! {}))
  ([{:keys [resource-path] :as opts}]
   (let [bundle (load-bundle (or resource-path meta-types-resource))
         {:keys [entries types attributes]} (validate-bundle! bundle)
         cfg (merge default-config (dissoc opts :resource-path))
         conn (ensure-db! (client cfg) (:db-name cfg))]
     (ensure-schema! conn)
     (let [tx (transact-bundle! conn entries)
           db (d/db conn)]
       {:conn conn
        :db-name (:db-name cfg)
        :counts {:types (count types) :attributes (count attributes)
                 :db (definition-counts db)}
        :tx tx}))))
