(ns intuition.versioning.runtime
  "Helpers for navigating graph-native version snapshots stored under missions/logs."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io File PushbackReader)
   (java.time Instant)))

(def ^:private repo-root
  (.getCanonicalPath (io/file ".")))

(def ^:private snapshot-filename
  "version-snapshot.edn")

(defn- canonical-logs-root
  [logs-root]
  (let [root (if (str/blank? (str logs-root))
               (io/file repo-root "missions" "logs")
               (io/file (str logs-root)))]
    (.getCanonicalPath root)))

(defn- version-snapshot-file?
  [^File file]
  (and (.isFile file)
       (= snapshot-filename (.getName file))))

(defn- read-snapshot-file
  [^File file]
  (with-open [reader (PushbackReader. (io/reader file))]
    (when-let [data (edn/read {:eof nil} reader)]
      (assoc data :version.snapshot/file (.getCanonicalPath file)))))

(defn- snapshot-instant
  [snapshot]
  (try
    (Instant/parse (:version.snapshot/timestamp snapshot))
    (catch Exception _
      Instant/EPOCH)))

(defn- normalize-id
  [value]
  (some-> value str str/trim))

(defn- subject-value
  [snapshot]
  (case (:version.snapshot/type snapshot)
    :version.snapshot/spec (:version.snapshot/spec-id snapshot)
    :version.snapshot/plan (:version.snapshot/plan-id snapshot)
    :version.snapshot/mission (:version.snapshot/mission-id snapshot)
    nil))

(defn- subject-match?
  [snapshot subject-type subject-id]
  (let [type (:version.snapshot/type snapshot)]
    (and (or (nil? subject-type) (= subject-type type))
         (or (nil? subject-id)
             (= (normalize-id subject-id)
                (normalize-id (subject-value snapshot)))))))

(defn load-snapshots
  "Reads every version-snapshot.edn under missions/logs (or a supplied root)."
  ([] (load-snapshots {}))
  ([{:keys [logs-root]}]
   (let [root-path (canonical-logs-root logs-root)
         root-file (io/file root-path)]
     (if (.exists root-file)
       (->> (file-seq root-file)
            (filter version-snapshot-file?)
            (keep read-snapshot-file)
            vec)
       []))))

(defn snapshot-history
  "Returns all snapshots for a given subject (spec/plan/mission) ordered by capture time."
  [{:keys [subject-type subject-id logs-root]}]
  (let [snapshots (load-snapshots {:logs-root logs-root})]
    (->> snapshots
         (filter #(subject-match? % subject-type subject-id))
         (sort-by snapshot-instant)
         vec)))

(defn- trace-paths*
  [snapshot-id children allowed-ids]
  (let [targets (filter allowed-ids (get children snapshot-id))]
    (if (seq targets)
      (mapcat (fn [target-id]
                (map #(into [snapshot-id] %)
                     (trace-paths* target-id children allowed-ids)))
              targets)
      [[snapshot-id]])))

(defn trace-requirement->snapshots
  "Navigates spec→plan→mission snapshots that reference the requirement id."
  [{:keys [requirement-id logs-root]}]
  (let [req (str/trim (str requirement-id))
        snapshots (load-snapshots {:logs-root logs-root})
        touched (->> snapshots
                     (filter #(some (fn [entry]
                                      (= req (normalize-id entry)))
                                    (:version.snapshot/requirements %)))
                     (sort-by snapshot-instant)
                     vec)
        links (mapcat :version.snapshot/links snapshots)
        children (reduce (fn [acc {:version.link/keys [source-snapshot-id target-snapshot-id]}]
                           (update acc source-snapshot-id (fnil conj []) target-snapshot-id))
                         {}
                         links)
        allowed (set (map :version.snapshot/id touched))
        starters (or (seq (filter #(= :version.snapshot/spec (:version.snapshot/type %)) touched))
                     touched)
        paths (->> starters
                   (mapcat #(trace-paths* (:version.snapshot/id %) children allowed))
                   (distinct)
                   vec)]
    {:requirement/id req
     :snapshots touched
     :links links
     :paths (if (seq paths)
              paths
              (mapv (fn [snapshot] [(:version.snapshot/id snapshot)]) touched))}))
