(ns intuition.agent.core
  "Entry namespace for all agents. Provides shared helpers for loading
   system state, protocols, and specs. Role-specific namespaces build on this."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]))

(def spec-path "specs/project_spec.edn")
(def convo-path "specs/conversation_protocol.edn")

(defn read-edn [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (edn/read {:eof nil} r)))

(defn system-snapshot
  "Returns a map describing the current structured state."
  []
  {:spec (when (.exists (io/file spec-path))
           (read-edn spec-path))
   :conversation_protocol (when (.exists (io/file convo-path))
                            (read-edn convo-path))})

(defn show-snapshot []
  (pprint/pprint (system-snapshot)))

(defn load-conversation-protocol []
  (when-not (.exists (io/file convo-path))
    (throw (ex-info "Conversation protocol missing" {:path convo-path})))
  (read-edn convo-path))

(defn load-spec []
  (when-not (.exists (io/file spec-path))
    (throw (ex-info "Spec file missing" {:path spec-path})))
  (read-edn spec-path))

(defn save-spec [spec-map]
  (with-open [w (io/writer spec-path)]
    (binding [*print-namespace-maps* false]
      (pprint/pprint spec-map w))
    spec-map))
