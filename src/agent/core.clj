(ns agent.core
  "Shared helpers for all agent roles: loading specs, schema, conversation protocol,
   and bootstrapping role-specific orientation."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(def schema-path "docs/project_spec_schema.edn")
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

(defn current-spec-id []
  (cond
    (some? @active-spec-id*) @active-spec-id*
    (.exists (io/file current-config-path))
    (if-let [sid (:current-spec (read-edn current-config-path))]
      sid
      (throw (ex-info "current.edn missing :current-spec" {})))
    :else
    (or (first (list-spec-ids))
        (throw (ex-info "No specs available" {})))))

(defn set-current-spec! [spec-id]
  (write-edn current-config-path {:current-spec spec-id})
  (reset! active-spec-id* spec-id))

(defn load-spec
  ([spec-id]
   (let [path (spec-path spec-id)]
     (if (.exists (io/file path))
       (read-edn path)
       (throw (ex-info "Spec not found" {:spec-id spec-id :path path}))))))

(defn save-spec [spec-id spec-map]
  (write-edn (spec-path spec-id) spec-map))

(defn load-conversation-protocol []
  (when (.exists (io/file convo-path))
    (read-edn convo-path)))

(defn system-snapshot
  "Returns map {:spec-id .. :spec .. :schema .. :conversation .. :available-specs [...]}"
  [spec-id]
  {:spec-id spec-id
   :available-specs (vec (list-spec-ids))
   :spec (load-spec spec-id)
   :schema (schema)
   :conversation (load-conversation-protocol)})

(def role->namespace
  {"spec-intake" 'agent.spec-intake})

(defn boot!
  "Loads role namespace, prints orientation. spec-id optional (falls back to current)."
  [role spec-id]
  (let [spec-id (if (str/blank? spec-id)
                  (current-spec-id)
                  spec-id)
        _ (reset! active-spec-id* spec-id)
        snapshot (system-snapshot spec-id)]
    (println "Booting role" role "with spec" spec-id)
    (println "Available specs:" (str/join ", " (:available-specs snapshot)))
    (if-let [ns-sym (role->namespace role)]
      (do (require ns-sym)
          (if-let [orient (ns-resolve ns-sym 'orientation)]
            (orient snapshot)
            (println "Role namespace missing orientation function:" ns-sym)))
      (println "Unknown role" role))
    (println "Orientation complete. You are now in the REPL.")))

(defn active-spec []
  (load-spec (current-spec-id)))
