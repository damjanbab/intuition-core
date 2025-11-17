(ns agent.core
  "Shared helpers for all agent roles: loading specs, schema, conversation protocol,
   and bootstrapping role-specific orientation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def schema-path "docs/project_spec_schema.edn")
(def template-path "specs/project_spec.template.edn")
(def instances-dir "specs/instances")
(def current-config-path "specs/current.edn")
(def convo-path "specs/conversation_protocol.edn")

(defonce ^:private active-spec-id* (atom nil))

(defn- read-edn [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (edn/read {:eof nil} r)))

(defn- write-edn [path data]
  (io/make-parents path)
  (with-open [w (io/writer path)]
    (binding [*print-namespace-maps* false]
      (pprint/pprint data w))
    data))

(defn schema []
  (when (.exists (io/file schema-path))
    (read-edn schema-path)))

(defn template []
  (when-not (.exists (io/file template-path))
    (throw (ex-info "Template file missing" {:path template-path})))
  (read-edn template-path))

(defn spec-path [spec-id]
  (str instances-dir "/" spec-id ".edn"))

(defn list-spec-ids []
  (when (.exists (io/file instances-dir))
    (->> (.listFiles (io/file instances-dir))
         (filter #(.isFile %))
         (map #(.getName %))
         (filter #(str/ends-with? % ".edn"))
         (map #(str/replace % #"\.edn$" ""))
         sort)))

(defn spec-exists? [spec-id]
  (.exists (io/file (spec-path spec-id))))

(defn create-spec!
  "Creates a spec instance by copying the template."
  [spec-id]
  (when (spec-exists? spec-id)
    (throw (ex-info "Spec already exists" {:spec-id spec-id})))
  (println "Creating spec instance" spec-id "from template")
  (write-edn (spec-path spec-id) (template)))

(defn ensure-spec!
  "Ensures a spec file exists, creating it from template if necessary."
  [spec-id]
  (when-not (spec-exists? spec-id)
    (create-spec! spec-id))
  spec-id)

(defn current-spec-id []
  (cond
    (some? @active-spec-id*) @active-spec-id*
    (.exists (io/file current-config-path))
    (if-let [sid (:current-spec (read-edn current-config-path))]
      sid
      (throw (ex-info "current.edn missing :current-spec" {})))
    :else
    (if-let [sid (first (list-spec-ids))]
      sid
      (throw (ex-info "No specs available" {})))))

(defn set-current-spec! [spec-id]
  (write-edn current-config-path {:current-spec spec-id})
  (reset! active-spec-id* spec-id))

(defn load-spec
  ([spec-id]
   (let [spec-id (ensure-spec! spec-id)
         path (spec-path spec-id)]
     (read-edn path))))

(defn save-spec [spec-id spec-map]
  (write-edn (spec-path spec-id) spec-map))

(defn load-conversation-protocol []
  (when (.exists (io/file convo-path))
    (read-edn convo-path)))

(defn system-snapshot
  "Returns map {:spec-id .. :spec .. :schema .. :conversation .. :available-specs [...]}"
  [spec-id]
  (let [spec-id (ensure-spec! spec-id)]
    {:spec-id spec-id
     :available-specs (vec (list-spec-ids))
     :spec (load-spec spec-id)
     :schema (schema)
     :conversation (load-conversation-protocol)}))

(defn describe-core
  "Prints orientation about core capabilities."
  [spec-id]
  (println "== Agent Core ==")
  (println "Active spec:" spec-id)
  (println "Available specs:" (if-let [ids (seq (list-spec-ids))] (str/join ", " ids) "(none)"))
  (println "\nCore helpers:")
  (println "  (agent.core/list-spec-ids)            ;; list all spec IDs")
  (println "  (agent.core/create-spec! \"new-id\")   ;; copy template into instances/")
  (println "  (agent.core/set-current-spec! \"id\")  ;; mark spec as current")
  (println "  (agent.core/system-snapshot \"id\")    ;; view spec + schema + protocol")
  (println "  (agent.core/schema)                    ;; view instructions for each field")
  (println "  (agent.core/load-spec \"id\") / (agent.core/save-spec \"id\" map)")
  (println "  (agent.core/boot! role spec-id)        ;; rerun orientation for another role")
  (println "\nSchema instructions live in docs/project_spec_schema.edn; call (agent.core/schema)")
  (println "Conversation protocol lives in specs/conversation_protocol.edn")
  (println "--------------------------------------------"))

(def role-registry* (atom {}))

(defn register-role!
  "Role namespace must define `orientation` fn taking snapshot map."
  [role-id ns-sym]
  (swap! role-registry* assoc role-id ns-sym))

(defn boot!
  "Loads role namespace, prints orientation. spec-id optional (falls back to current)."
  [role spec-id]
  (let [spec-id (if (or (nil? spec-id) (and (string? spec-id) (str/blank? spec-id)))
                  (current-spec-id)
                  spec-id)
        spec-id (ensure-spec! spec-id)
        _ (set-current-spec! spec-id)
        snapshot (system-snapshot spec-id)]
    (describe-core spec-id)
    (if (and role (not (str/blank? role)))
      (if-let [ns-sym (@role-registry* role)]
        (do (require ns-sym)
            (if-let [orient (ns-resolve ns-sym 'orientation)]
              (orient snapshot)
              (println "Role namespace missing orientation function:" ns-sym)))
        (println "Unknown role" role))
      (println "No role specified; load one via (agent.core/boot! \"role\" spec)."))
    (println "Orientation complete. You are now in the REPL.")))

(defn active-spec []
  (load-spec (current-spec-id)))
