(ns intuition.analytics.runtime
  "Analytics runtime that inspects mission logs plus Datomic snapshots to emit
  the governed metrics required by SYSTEM_SPEC §§3.3–3.6, §5.1, §5.3, §9 and the
  new post-dry-run analytics step."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str])
  (:import
   (java.io File PushbackReader)
   (java.nio.file Files StandardCopyOption)
   (java.time Duration Instant ZoneOffset)
   (java.time.format DateTimeFormatter)))

(def ^:private spec-sections
  ["3.3" "3.4" "3.5" "3.6" "5.1" "5.3" "9"])

(def ^:private analytics-step-ident
  :analytics.step/post-dry-run)

(def ^:private base-context
  {:spec/sections spec-sections
   :analytics/new-step analytics-step-ident})

(def ^:private timestamp-formatter
  (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss") ZoneOffset/UTC))

(defn- format-timestamp
  [^Instant instant]
  (.format timestamp-formatter instant))

(defn- parse-instant-safe
  [value]
  (cond
    (instance? Instant value) value
    (string? value)
    (try
      (Instant/parse value)
      (catch Exception _
        nil))
    :else nil))

(defn- duration-between-ms
  [start end]
  (let [s (parse-instant-safe start)
        e (parse-instant-safe end)]
    (when (and s e)
      (.toMillis (Duration/between s e)))))

(defn- sum
  [coll]
  (reduce + 0 coll))

(defn- median
  [values]
  (let [sorted (sort (keep identity values))
        n (count sorted)]
    (when (pos? n)
      (nth sorted (quot (dec n) 2)))))

(defn- read-edn-file
  [^File file]
  (when (and file (.exists file))
    (try
      (with-open [r (PushbackReader. (io/reader file))]
        (edn/read {:eof nil} r))
      (catch Exception e
        (throw (ex-info "Unable to read EDN" {:file (.getAbsolutePath file)} e))))))

(defn- mission-directories
  [log-root]
  (let [root (io/file log-root)]
    (if-not (.exists root)
      []
      (->> (.listFiles root)
           (filter #(.isDirectory ^File %))
           (map (fn [^File dir]
                  {:mission/id (.getName dir)
                   :dir dir}))
           (sort-by :mission/id)))))

(defn- mission-file
  [mission filename]
  (io/file (:dir mission) filename))

(defn- file-seq-if-exists
  [^File dir]
  (when (.exists dir)
    (file-seq dir)))

(defn- lock-event?
  [^File file]
  (let [name (.getName file)]
    (and (.isFile file)
         (str/ends-with? name ".edn")
         (str/includes? name "lock"))))

(defn- lock-wait-events
  [mission]
  (->> (file-seq-if-exists (:dir mission))
       (keep (fn [^File file]
               (when (lock-event? file)
                 (let [data (read-edn-file file)
                       requests (:locks/requested data)]
                   (when (seq requests)
                     (let [event-count (count requests)
                           wait-ms (or (some-> (:locks/wait-ms data) long)
                                       (duration-between-ms (:locks/requested-at data)
                                                            (:locks/granted-at data))
                                       0)]
                       {:locks event-count
                        :wait-ms (long wait-ms)}))))))))

(defn- summarize-lock-waits
  [missions]
  (let [details (->> missions
                     (map (fn [mission]
                            (let [events (lock-wait-events mission)]
                              (when (seq events)
                                {:mission/id (:mission/id mission)
                                 :event-count (count events)
                                 :lock-count (sum (map :locks events))
                                 :wait-ms (sum (map :wait-ms events))}))))
                     (remove nil?))]
    {:total-events (sum (map :event-count details))
     :total-locks (sum (map :lock-count details))
     :total-wait-ms (sum (map :wait-ms details))
     :details details}))

(defn- ci-run-files
  [mission]
  (let [ci-dir (io/file (:dir mission) "ci")]
    (->> (file-seq-if-exists ci-dir)
         (filter (fn [^File file]
                   (and (.isFile file)
                        (= "ci-run.edn" (.getName file)))))
         (sort-by #(.getAbsolutePath ^File %)))))

(defn- summarize-ci-runs
  [missions]
  (let [details (->> missions
                      (map (fn [mission]
                             (let [runs (ci-run-files mission)
                                   run-count (count runs)
                                   reruns (max 0 (dec run-count))]
                               (when (pos? run-count)
                                 {:mission/id (:mission/id mission)
                                  :run-count run-count
                                  :rerun-count reruns}))))
                      (remove nil?))]
    {:total-runs (sum (map :run-count details))
     :total-reruns (sum (map :rerun-count details))
     :details details}))

(def ^:private failure-statuses
  #{:status/failed :status/error :status/canceled :status/blocked :status/timeout})

(defn- collect-statuses
  [value]
  (cond
    (map? value)
    (let [candidates [(get value :status)
                      (get value :action/status)
                      (get value :validation/status)
                      (get value :result/status)
                      (get value :codetype/status)]]
      (concat (keep identity candidates)
              (mapcat collect-statuses (vals value))))

    (sequential? value)
    (mapcat collect-statuses value)

    :else
    '()))

(defn- summarize-validator-failures
  [missions]
  (let [details (->> missions
                      (map (fn [mission]
                             (let [failure-count (->> (file-seq-if-exists (:dir mission))
                                                      (filter (fn [^File file]
                                                                (let [name (.getName file)]
                                                                  (and (.isFile file)
                                                                       (str/ends-with? name ".edn")
                                                                       (or (str/includes? name "validation")
                                                                           (str/includes? name "validator"))))))
                                                      (map read-edn-file)
                                                      (mapcat collect-statuses)
                                                      (filter failure-statuses)
                                                      count)]
                               (when (pos? failure-count)
                                 {:mission/id (:mission/id mission)
                                  :failure-count failure-count}))))
                      (remove nil?))]
    {:total-failures (sum (map :failure-count details))
     :details details}))

(defn- coverage-validation-file
  [mission]
  (mission-file mission "work-plan-validation.edn"))

(defn- coverage-snapshots
  [missions]
  (->> missions
       (keep (fn [mission]
               (let [file (coverage-validation-file mission)]
                 (when (.exists file)
                   (let [data (read-edn-file file)
                         ts (or (:validated-at data)
                                (:work.plan/validated-at data))
                         statuses (map (fn [row]
                                         (select-keys row [:validation/kind :validation/status]))
                                       (:work.plan/validation-results data))]
                     {:mission/id (:mission/id mission)
                      :validated-at ts
                      :requirements (:spec/requirements-count data)
                      :coverage-count (:work.plan/coverage-count data)
                      :validation-status statuses}))))))
  )

(defn- scheduler-run-files
  [mission]
  (let [dir (io/file (:dir mission) "scheduler")]
    (->> (file-seq-if-exists dir)
         (filter (fn [^File file]
                   (and (.isFile file)
                        (= "scheduler-run.edn" (.getName file))))))))

(defn- summarize-agent-utilization
  [missions]
  (let [runs (->> missions
                  (mapcat (fn [mission]
                            (->> (scheduler-run-files mission)
                                 (map (fn [file]
                                        (assoc (read-edn-file file)
                                               :mission/id (:mission/id mission)))))))
                  (sort-by :scheduler/start-time))
        durations (map :scheduler/duration-ms runs)
        total-duration (sum durations)
        windows (->> runs
                     (map (fn [run]
                            [(parse-instant-safe (:scheduler/start-time run))
                             (parse-instant-safe (:scheduler/end-time run))]))
                     (remove (fn [[start end]] (or (nil? start) (nil? end)))))]
    (if (seq windows)
      (let [starts (map first windows)
            ends (map second windows)
            start (apply min-key #(.toEpochMilli ^Instant %) starts)
            end (apply max-key #(.toEpochMilli ^Instant %) ends)
            window-ms (.toMillis (Duration/between start end))
            utilization (if (pos? window-ms)
                          (* 100.0 (/ total-duration window-ms))
                          0.0)]
        {:total-runtime-ms total-duration
         :window-ms window-ms
         :utilization-percent utilization
         :runs (map (fn [run]
                      {:mission/id (:mission/id run)
                       :duration-ms (:scheduler/duration-ms run)
                       :status (:scheduler/final-status run)})
                    runs)})
      {:total-runtime-ms total-duration
       :window-ms 0
       :utilization-percent 0.0
       :runs []})))

(defn- merge-log-files
  [mission]
  (let [merge-dir (io/file (:dir mission) "merge")]
    (->> (file-seq-if-exists merge-dir)
         (filter (fn [^File file]
                   (and (.isFile file)
                        (str/ends-with? (.getName file) "merge-log.edn")))))))

(defn- timeline-from-files
  [mission]
  (let [plan-binding (read-edn-file (mission-file mission "mission-plan-binding.edn"))
        branch (read-edn-file (mission-file mission "branch.edn"))
        merge-log (->> (merge-log-files mission)
                       (map read-edn-file)
                       (sort-by :merge/merged-at)
                       last)
        coverage (read-edn-file (coverage-validation-file mission))]
    (cond-> {}
      (:mission.plan-binding/logged-at plan-binding)
      (assoc :plan/published-at (:mission.plan-binding/logged-at plan-binding))

      (:branch/created-at branch)
      (assoc :mission/started-at (:branch/created-at branch))

      (:merge/merged-at merge-log)
      (assoc :mission/merged-at (:merge/merged-at merge-log))

      (:validated-at coverage)
      (assoc :plan/validated-at (:validated-at coverage)))))

(defn- compute-timelines
  [missions timeline-provider]
  (->> missions
       (keep (fn [mission]
               (let [file-timeline (timeline-from-files mission)
                     provider (when timeline-provider
                                (timeline-provider (:mission/id mission)))
                     timeline (merge file-timeline provider)]
                 (when (seq timeline)
                   (let [spec-ts (:spec/captured-at timeline)
                         plan-ts (or (:plan/published-at timeline)
                                     (:plan/validated-at timeline))
                         mission-ts (:mission/started-at timeline)
                         merge-ts (:mission/merged-at timeline)
                         spec-plan (duration-between-ms spec-ts plan-ts)
                         plan-mission (duration-between-ms plan-ts mission-ts)
                         mission-merge (duration-between-ms mission-ts merge-ts)]
                     {:mission/id (:mission/id mission)
                      :spec/captured-at spec-ts
                      :plan/published-at plan-ts
                      :mission/started-at mission-ts
                      :mission/merged-at merge-ts
                      :spec->plan-ms spec-plan
                      :plan->mission-ms plan-mission
                      :mission->merge-ms mission-merge
                      :spec->merge-ms (duration-between-ms spec-ts merge-ts)})))))))

(defn- codetype-generation-files
  [mission]
  (->> (file-seq-if-exists (:dir mission))
       (filter (fn [^File file]
                 (let [name (.getName file)]
                   (and (.isFile file)
                        (str/ends-with? name ".edn")
                        (str/includes? name "codetype-generation")))))))

(defn- summarize-codetype-generations
  [missions]
  (let [details (->> missions
                      (map (fn [mission]
                             (let [runs (->> (codetype-generation-files mission)
                                             (map read-edn-file)
                                             (mapcat :codetype/generations))
                                   total (count runs)
                                   executed (count (remove :codetype/skipped? runs))]
                               (when (pos? total)
                                 {:mission/id (:mission/id mission)
                                  :total total
                                  :executed executed}))))
                      (remove nil?))]
    {:total (sum (map :total details))
     :executed (sum (map :executed details))
     :details details}))

(defn- ->metric
  [metric-key unit value context]
  {:analytics.metric/key metric-key
   :analytics.metric/unit unit
   :analytics.metric/value (pr-str value)
   :analytics.metric/context (pr-str (merge base-context context))})

(defn- render-markdown
  [{:keys [timestamp lock-summary ci-summary validator-summary coverage-summary utilization-summary timelines codetype-summary mission-count]}]
  (let [header (format "# Mission Analytics Report – %s\n\n" timestamp)
        context-line "SYSTEM_SPEC §§3.3–3.6, §5.1, §5.3, §9 demand this new analytics step immediately after dry runs so stewards see structured evidence.\n\n"
        lock-section (format "## Lock waits\n- %d missions triggered %d lock waits across %d locks (total %d ms waiting).\n" (count (:details lock-summary)) (:total-events lock-summary) (:total-locks lock-summary) (:total-wait-ms lock-summary))
        ci-section (format "\n## CI reruns\n- %d total CI runs with %d reruns.\n" (:total-runs ci-summary) (:total-reruns ci-summary))
        validator-section (format "\n## Validator failures\n- %d missions reported %d validator failures.\n" (count (:details validator-summary)) (:total-failures validator-summary))
        latest-coverage (some->> coverage-summary
                                 (sort-by :validated-at)
                                 last
                                 :validated-at)
        coverage-section (format "\n## Requirement coverage timelines\n- %d coverage snapshots captured (latest %s).\n" (count coverage-summary) (or latest-coverage "n/a"))
        utilization-section (format "\n## Agent utilization\n- %d scheduler runs across %d missions used %d ms in a %d ms window (%.2f%% utilization).\n" (count (:runs utilization-summary)) mission-count (:total-runtime-ms utilization-summary) (:window-ms utilization-summary) (:utilization-percent utilization-summary))
        median-duration (median (map :spec->merge-ms timelines))
        timeline-section (format "\n## Spec → Plan → Mission → Merge\n- %d missions supplied full timelines. Median spec→merge duration: %s ms.\n" (count timelines) (or median-duration "n/a"))
        codetype-section (format "\n## Codetype regeneration counts\n- %d executed codetype generations (out of %d total records).\n" (:executed codetype-summary) (:total codetype-summary))]
    (str header context-line lock-section ci-section validator-section coverage-section utilization-section timeline-section codetype-section "\n")))

(defn- write-edn!
  [file data]
  (io/make-parents file)
  (with-open [w (io/writer file)]
    (binding [*print-namespace-maps* false]
      (pprint/pprint data w))))

(defn- write-markdown!
  [file text]
  (io/make-parents file)
  (spit file text))

(defn- copy-into!
  [^File source ^File destination]
  (io/make-parents destination)
  (Files/copy (.toPath source)
              (.toPath destination)
              (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))
  (.getAbsolutePath destination))

(defn generate!
  "Builds analytics metrics, writes markdown/edn reports, and optionally copies
  them into the provided mission log directory. Supported options:
  - :log-root – mission log root (default \"missions/logs\").
  - :reports-dir – directory for analytics reports (default \"reports/analytics\").
  - :mission-log-id – mission id whose log should receive copies.
  - :mission-log-path – explicit filesystem path for the mission log directory.
  - :source – keyword recorded in :analytics.report/source.
  - :timeline-provider – fn of mission-id → timeline map.
  - :now – override clock for deterministic tests."
  [{:keys [log-root reports-dir mission-log-id mission-log-path source timeline-provider now]
    :or {log-root "missions/logs"
         reports-dir "reports/analytics"
         source :analytics.source/manual}}]
  (let [missions (mission-directories log-root)
        lock-summary (summarize-lock-waits missions)
        ci-summary (summarize-ci-runs missions)
        validator-summary (summarize-validator-failures missions)
        coverage-summary (coverage-snapshots missions)
        utilization-summary (summarize-agent-utilization missions)
        timelines (compute-timelines missions timeline-provider)
        codetype-summary (summarize-codetype-generations missions)
        recorded-at (or now (Instant/now))
        timestamp (format-timestamp recorded-at)
        report-id (str "analytics-" timestamp)
        markdown-file (io/file reports-dir (str timestamp "-mission-insights.md"))
        edn-file (io/file reports-dir (str timestamp "-metrics.edn"))
        mission-log-dir (cond
                          mission-log-path (io/file mission-log-path)
                          mission-log-id (io/file log-root mission-log-id)
                          :else nil)
        summary {:timestamp timestamp
                 :lock-summary lock-summary
                 :ci-summary ci-summary
                 :validator-summary validator-summary
                 :coverage-summary coverage-summary
                 :utilization-summary utilization-summary
                 :timelines timelines
                 :codetype-summary codetype-summary
                 :mission-count (count missions)}
        markdown (render-markdown summary)
        metrics [(->metric :analytics.metric/lock-waits :unit/summary lock-summary {:metric/name :lock-waits})
                 (->metric :analytics.metric/ci-reruns :unit/summary ci-summary {:metric/name :ci-reruns})
                 (->metric :analytics.metric/validator-failures :unit/summary validator-summary {:metric/name :validator-failures})
                 (->metric :analytics.metric/requirement-coverage :unit/vector coverage-summary {:metric/name :requirement-coverage})
                 (->metric :analytics.metric/agent-utilization :unit/percent utilization-summary {:metric/name :agent-utilization})
                 (->metric :analytics.metric/spec-plan-mission-merge :unit/vector timelines {:metric/name :spec-plan-mission-merge})
                 (->metric :analytics.metric/codetype-generations :unit/count codetype-summary {:metric/name :codetype-generations})]
        report-edn {:analytics/report {:analytics.report/id report-id
                                       :analytics.report/recorded-at (str recorded-at)
                                       :analytics.report/missions (mapv :mission/id missions)
                                       :analytics.report/metrics metrics
                                       :analytics.report/source source}
                    :spec/sections spec-sections
                    :analytics/new-step analytics-step-ident
                    :analytics/summary summary}]
    (write-markdown! markdown-file markdown)
    (write-edn! edn-file report-edn)
    (let [analysis-dir (when mission-log-dir
                         (io/file mission-log-dir "analysis"))
          md-copy (when analysis-dir
                    (copy-into! markdown-file (io/file analysis-dir (.getName markdown-file))))
          edn-copy (when analysis-dir
                     (copy-into! edn-file (io/file analysis-dir (.getName edn-file))))]
      {:report/id report-id
       :report/markdown (.getAbsolutePath markdown-file)
       :report/edn (.getAbsolutePath edn-file)
       :report/data report-edn
       :report/markdown-copy md-copy
       :report/edn-copy edn-copy
       :metrics metrics
       :summary summary})))
