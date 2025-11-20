(ns dev.analytics
  "CLI wrapper for the analytics runtime so the scheduler/dry-run pipeline can
  satisfy SYSTEM_SPEC §§3.3–3.6, §5.1, §5.3, §9 plus the new analytics step."
  (:require
   [clojure.string :as str]
   [intuition.analytics.runtime :as runtime])
  (:gen-class))

(def ^:private usage
  (str/join
   \newline
   ["Analytics usage:"
    "  clojure -M:dev -m dev.analytics [options]"
    ""
    "Options:"
    "  --log-root <dir>          Root directory containing missions/logs (default missions/logs)."
    "  --reports-dir <dir>       Output directory for reports (default reports/analytics)."
    "  --mission-log <id>        Mission log id that should receive analysis copies."
    "  --mission-log-path <dir>  Explicit mission log directory (overrides --mission-log)."
    "  --source <keyword>        Keyword recorded in :analytics.report/source (default :analytics.source/manual)."
    "  --help                    Print this help text." ]))

(defn- parse-kw
  [value]
  (cond
    (keyword? value) value
    (str/starts-with? value ":") (keyword (subs value 1))
    :else (keyword value)))

(defn- parse-args
  [args]
  (loop [opts {}
         remaining args]
    (if-let [arg (first remaining)]
      (case arg
        "--log-root"
        (let [[value & more] (rest remaining)]
          (when-not value
            (throw (ex-info "--log-root requires a directory" {})))
          (recur (assoc opts :log-root value) more))

        "--reports-dir"
        (let [[value & more] (rest remaining)]
          (when-not value
            (throw (ex-info "--reports-dir requires a directory" {})))
          (recur (assoc opts :reports-dir value) more))

        "--mission-log"
        (let [[value & more] (rest remaining)]
          (when-not value
            (throw (ex-info "--mission-log requires an id" {})))
          (recur (assoc opts :mission-log-id value) more))

        "--mission-log-path"
        (let [[value & more] (rest remaining)]
          (when-not value
            (throw (ex-info "--mission-log-path requires a directory" {})))
          (recur (assoc opts :mission-log-path value) more))

        "--source"
        (let [[value & more] (rest remaining)]
          (when-not value
            (throw (ex-info "--source requires a keyword" {})))
          (recur (assoc opts :source (parse-kw value)) more))

        "--help"
        (assoc opts :help? true)

        (throw (ex-info "Unknown analytics argument" {:argument arg})))
      opts)))

(defn -main
  [& args]
  (try
    (let [parsed (parse-args args)]
      (when (:help? parsed)
        (println usage)
        (System/exit 0))
      (let [result (runtime/generate! (merge {:mission-log-id "M-20251121-409"}
                                             parsed))]
        (println "Analytics markdown:" (:report/markdown result))
        (println "Analytics metrics:" (:report/edn result))
        (when-let [copy (:report/markdown-copy result)]
          (println "Mission log markdown copy:" copy))
        (when-let [copy (:report/edn-copy result)]
          (println "Mission log metrics copy:" copy))
        (System/exit 0)))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println "Analytics CLI error:" (.getMessage e))
        (when-let [data (not-empty (ex-data e))]
          (println "Details:" (pr-str data))))
      (System/exit 1))
    (catch Exception e
      (binding [*out* *err*]
        (println "Analytics CLI error:" (.getMessage e)))
      (System/exit 1))))
