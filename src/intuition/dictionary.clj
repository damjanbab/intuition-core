(ns intuition.dictionary
  "Loads dictionary seed data for ActionDefinitions + ProtocolDefinitions and installs the schema
  required by the SfS runtimes."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [datomic.client.api :as d]))

(def missions-resource "dictionary/missions.edn")
(def system-map-actions-resource "dictionary/actions_system_map.edn")
(def permissions-resource "dictionary/permissions.edn")
(def ci-profiles-resource "dictionary/ci_profiles.edn")

(def action-schema
  [{:db/ident :action/ident
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Stable identifier for an ActionDefinition."}
   {:db/ident :action/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :action/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :action/invariants
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :action/permissions
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :action/config-spec
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :action/output-spec
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :action/handler
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :action/tags
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :action/meta
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(def protocol-schema
  [{:db/ident :protocol/ident
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Stable identifier for a ProtocolDefinition."}
   {:db/ident :protocol/name
    :db/valueType :db.type/string
   :db/cardinality :db.cardinality/one}
  {:db/ident :protocol/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
  {:db/ident :protocol/owner
   :db/valueType :db.type/keyword
   :db/cardinality :db.cardinality/one}
  {:db/ident :protocol/escalation
   :db/valueType :db.type/keyword
   :db/cardinality :db.cardinality/many}
  {:db/ident :protocol/invariants
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :protocol/locks
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :protocol/required-work-tracks
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :protocol/steps
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(def execution-log-schema
  [{:db/ident :action.execution/id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :action.execution/action
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
  {:db/ident :action.execution/protocol
   :db/valueType :db.type/keyword
   :db/cardinality :db.cardinality/one}
  {:db/ident :action.execution/step
   :db/valueType :db.type/keyword
   :db/cardinality :db.cardinality/one}
  {:db/ident :action.execution/mission
   :db/valueType :db.type/keyword
   :db/cardinality :db.cardinality/one}
   {:db/ident :action.execution/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :action.execution/started-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :action.execution/completed-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :action.execution/config
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :action.execution/output
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :action.execution/error
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :action.execution/invariants
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}])

(def protocol-run-log-schema
  [{:db/ident :protocol.run/id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :protocol.run/ident
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :protocol.run/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :protocol.run/started-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :protocol.run/completed-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :protocol.run/context
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :protocol.run/steps
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :protocol.run/error
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :protocol.run/invariants
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :protocol.run/work-tracks
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :protocol.run/locks
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}])

(def schema
  (concat action-schema protocol-schema execution-log-schema protocol-run-log-schema))

(defn install-schema!
  [conn]
  (d/transact conn {:tx-data schema})
  conn)

(defn- read-resource
  [path]
  (when-let [res (io/resource path)]
    (-> res slurp edn/read-string)))

(defn load-actions
  []
  (let [base (or (read-resource "dictionary/actions.edn")
                 (throw (ex-info (str "Missing resource dictionary/actions.edn")
                                 {:resource "dictionary/actions.edn"})))
        extras (or (read-resource system-map-actions-resource) [])]
    (vec (concat base extras))))

(defn load-protocols
  []
  (or (read-resource "dictionary/protocols.edn")
      (throw (ex-info (str "Missing resource dictionary/protocols.edn") {:resource "dictionary/protocols.edn"}))))

(defn load-permissions
  []
  (or (read-resource permissions-resource)
      (throw (ex-info (str "Missing resource " permissions-resource)
                      {:resource permissions-resource}))))
(defn load-missions
  []
  (or (read-resource missions-resource)
      (throw (ex-info (str "Missing resource " missions-resource)
                      {:resource missions-resource}))))

(defn load-ci-profiles
  []
  (or (read-resource ci-profiles-resource) []))

(defn- prepare-action
  [definition]
  (let [prepared (-> definition
                     (update :action/invariants #(vec (or % [])))
                     (update :action/permissions #(vec (or % [])))
                     (update :action/tags #(vec (or % [])))
                     (update :action/handler str))]
    (cond-> prepared
      (:action/meta prepared) (update :action/meta pr-str)
      (nil? (:action/meta prepared)) (dissoc :action/meta))))

(defn- prepare-protocol
  [definition]
  (-> definition
      (update :protocol/invariants #(vec (or % [])))
      (update :protocol/locks #(vec (or % [])))
      (update :protocol/escalation #(vec (or % [])))
      (update :protocol/required-work-tracks #(vec (or % [])))
      (update :protocol/steps #(pr-str (or % [])))))

(defn seed-actions!
  [conn]
  (let [mission-actions (filter :action/ident (load-missions))
        tx-data (map prepare-action (concat (load-actions) mission-actions))]
    (d/transact conn {:tx-data tx-data})))

(defn seed-protocols!
  [conn]
  (let [tx-data (map prepare-protocol (load-protocols))]
    (d/transact conn {:tx-data tx-data})))

(defn seed-all!
  "Installs the schema and seeds both actions + protocols."
  [conn]
  (install-schema! conn)
  (seed-actions! conn)
  (seed-protocols! conn))
