(ns intuition.spec-cli
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.cli :refer [parse-opts]]
            [intuition.agent.core :as agent-core
             :refer [system-snapshot load-spec load-conversation-protocol]]))

(def spec-path "specs/project_spec.edn")
(def template-path "specs/project_spec.template.edn")
(def convo-path "specs/conversation_protocol.edn")

(defn read-edn-file [path]
  (with-open [r (java.io.PushbackReader. (io/reader path))]
    (edn/read {:eof nil} r)))

(defn write-edn-file [path data]
  (with-open [w (io/writer path)]
    (binding [*print-namespace-maps* false]
      (pprint/pprint data w))))

(defn ensure-spec! []
  (when-not (.exists (io/file spec-path))
    (println "Spec file missing. Copying template...")
    (write-edn-file spec-path (read-edn-file template-path))))

(defn cmd-show-spec [_]
  (ensure-spec!)
  (pprint/pprint (load-spec)))

(defn cmd-show-state [_]
  (pprint/pprint (system-snapshot)))

(defn cmd-copy-template [{:keys [options]}]
  (let [dest (:dest options)]
    (if dest
      (do (write-edn-file dest (read-edn-file template-path))
          (println "Copied template to" dest))
      (println "--dest path required"))))

(def cli-options
  [["-h" "--help" "Show usage"]
   ["-c" "--command CMD" "Command to run"
    :default "show-spec"]
   ["-d" "--dest PATH" "Destination path (used by copy-template)"]])

(def commands
  {"show-spec" cmd-show-spec
   "show-state" cmd-show-state
  "copy-template" cmd-copy-template})

(defn usage [options-summary]
  (->> ["Spec CLI"
        ""
        "Usage: clj -M:spec [options]"
        ""
        "Options:"
        options-summary
        ""
        "Commands:"
        "  show-spec     Pretty-print current spec"
        "  show-state    Pretty-print spec + conversation protocol"
        "  copy-template --dest <path> Copy blank template"
        ""]
       (str/join \newline)))

(defn -main [& args]
  (let [{:keys [options errors summary]} (parse-opts args cli-options)
        {:keys [command help]} options]
    (cond
      help (println (usage summary))
      (seq errors) (do (doseq [e errors] (println e))
                       (println (usage summary)))
      :else (if-let [cmd (commands command)]
              (cmd {:options options})
              (do (println "Unknown command" command)
                  (println (usage summary)))))))
