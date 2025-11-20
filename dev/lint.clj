(ns dev.lint
  "Helper entry point so mission protocols can run governed linting with one command."
  (:require [clojure.java.shell :as shell])
  (:gen-class))

(def default-command
  ["clojure" "-M:lint"])

(defn- run-command!
  [cmd]
  (let [{:keys [exit out err]} (apply shell/sh cmd)]
    (when (seq out)
      (print out))
    (when (seq err)
      (binding [*out* *err*]
        (print err)))
    exit))

(defn -main
  [& args]
  (let [cmd (if (seq args)
              (into default-command args)
              default-command)
        exit-code (run-command! cmd)]
    (System/exit exit-code)))
