;; Quickstart: `clojure -M:run` ingests the spec; `clojure -M:test` exercises SfS isolation tests.
(ns intuition.sfs.env.bootstrap
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io File RandomAccessFile)
   (java.nio.channels OverlappingFileLockException)
   (java.time Instant)))

(def ^:private repo-root (.getCanonicalFile (io/file ".")))
(def ^:private tmp-root (doto (io/file repo-root "tmp" "missions") .mkdirs))
(def ^:private default-port-range {:min 41000 :max 47000})

(defn sanitize-fragment
  "Normalizes IDs so sandbox directories/locks stay inside ./tmp."
  [value]
  (let [fragment (-> (str value)
                     str/lower-case
                     (str/replace #"[^a-z0-9\-]" "-")
                     (str/replace #"-+" "-")
                     (str/replace #"^-|-$" ""))]
    (if (str/blank? fragment)
      (throw (ex-info "Identifier collapses to blank; cannot allocate sandbox."
                      {:value value}))
      fragment)))

(defn- mission-root ^File [mission-id]
  (doto (io/file tmp-root (sanitize-fragment mission-id)) .mkdirs))

(defn- agent-root ^File [mission-id agent-id]
  (doto (io/file (mission-root mission-id) (sanitize-fragment agent-id)) .mkdirs))

(defn- ensure-dir! [^File f]
  (when-not (.exists f)
    (.mkdirs f))
  f)

(defn- delete-tree!
  "Recursively deletes directory trees; ignores missing paths."
  [^File path]
  (when (and path (.exists path))
    (doseq [child (.listFiles path)]
      (delete-tree! child))
    (io/delete-file path true)))

(defn- lock-files [mission-id]
  {:state (io/file (mission-root mission-id) ".ports.edn")
   :lock (io/file (mission-root mission-id) ".ports.lock")})

(defn- acquire-lock
  [channel]
  (loop []
    (let [result (try
                   (.lock channel)
                   (catch OverlappingFileLockException _
                     ::retry))]
      (if (= ::retry result)
        (do
          (Thread/sleep 5)
          (recur))
        result))))

(defmacro with-file-lock [mission-id & body]
  `(let [mission# ~mission-id
         lock-map# (lock-files mission#)
         lock-file# (:lock lock-map#)]
     (ensure-dir! (.getParentFile lock-file#))
     (with-open [raf# (RandomAccessFile. lock-file# "rw")
                 channel# (.getChannel raf#)
                 file-lock# (acquire-lock channel#)]
       (try
         ~@body
         (finally
           (.release file-lock#))))))

(defn- read-state [mission-id]
  (let [{:keys [state]} (lock-files mission-id)]
    (if (.exists state)
      (try
        (edn/read-string (slurp state))
        (catch Exception _
          {:agents {}}))
      {:agents {}})))

(defn- write-state! [mission-id new-state]
  (let [state-file (:state (lock-files mission-id))]
    (spit state-file (pr-str new-state))))

(defn- base-port [{:keys [mission-id agent-id]}]
  (let [{:keys [min max]} default-port-range
        span (inc (- max min))
        seed (-> (str mission-id "::" agent-id)
                 hash
                 long
                 (Math/abs))]
    (+ min (mod seed span))))

(defn- inc-port [port]
  (let [{:keys [min max]} default-port-range
        next-port (inc port)]
    (if (> next-port max) min next-port)))

(defn- choose-ports! [{:keys [mission-id agent-id ports-count]}]
  (with-file-lock mission-id
    (let [state (read-state mission-id)
          existing (get-in state [:agents agent-id :ports])]
      (if (seq existing)
        {:ports existing :state state}
        (let [needed (max 1 (or ports-count 2))
              used (set (mapcat :ports (vals (:agents state))))
              start (base-port {:mission-id mission-id :agent-id agent-id})
              max-tries (* 4 (inc (- (:max default-port-range)
                                     (:min default-port-range))))
              new-ports (loop [candidate start
                               acc []
                               tries 0
                               seen used]
                          (cond
                            (= (count acc) needed) acc
                            (> tries max-tries)
                            (throw (ex-info "Ran out of available sandbox ports."
                                            {:mission/id mission-id}))
                            (contains? seen candidate)
                            (recur (inc-port candidate) acc (inc tries) seen)
                            :else (recur (inc-port candidate)
                                         (conj acc candidate)
                                         (inc tries)
                                         (conj seen candidate))))
              updated-state (assoc-in state [:agents agent-id]
                                      {:ports new-ports
                                       :updated-at (str (Instant/now))})]
          (write-state! mission-id updated-state)
          {:ports new-ports
           :state updated-state})))))

(defn- release-ports! [{:keys [mission-id agent-id]}]
  (with-file-lock mission-id
    (let [state (read-state mission-id)
          next-state (update state :agents dissoc agent-id)]
      (write-state! mission-id next-state))))

(defn lock-token-file
  "Returns the on-disk file for a lock token."
  [mission-id token]
  (let [lock-dir (doto (io/file (mission-root mission-id) "locks") .mkdirs)
        token-name (str (sanitize-fragment token) ".edn")]
    (io/file lock-dir token-name)))

(defn register-lock!
  "Creates a simple scope lock token on disk.
   `params` expects :mission/id :agent/id and optional :lock/token."
  [{mission-id :mission/id
    agent-id :agent/id
    token :lock/token
    scope :scope}]
  (when (str/blank? mission-id)
    (throw (ex-info "mission/id required for lock registration." {})))
  (when (str/blank? agent-id)
    (throw (ex-info "agent/id required for lock registration." {})))
  (let [token (or token (str (java.util.UUID/randomUUID)))
        payload {:mission/id mission-id
                 :agent/id agent-id
                 :scope scope
                 :token token
                 :created-at (str (Instant/now))}
        file (lock-token-file mission-id token)]
    (ensure-dir! (.getParentFile file))
    (spit file (pr-str payload))
    (assoc payload :lock/file (.getAbsolutePath file))))

(defn- ensure-secret-dir! [^File dir]
  (ensure-dir! dir)
  (.setReadable dir true true)
  (.setWritable dir true true)
  (.setExecutable dir true true)
  dir)

(defn- manifest-file [mission-id agent-id]
  (io/file (agent-root mission-id agent-id) "manifest.edn"))

(defn bootstrap!
  "Allocates deterministic sandbox directories/ports under ./tmp/missions."
  [{mission-id :mission/id
    agent-id :agent/id
    port-count :ports/count}]
  (when (str/blank? mission-id)
    (throw (ex-info "mission/id is required." {})))
  (when (str/blank? agent-id)
    (throw (ex-info "agent/id is required." {})))
  (let [root (agent-root mission-id agent-id)
        paths {:root root
               :work (io/file root "work")
               :artifacts (io/file root "artifacts")
               :logs (io/file root "logs")
               :evidence (io/file root "evidence")
               :tmp (io/file root "tmp")
               :secrets (io/file root "secrets")}
        _ (run! ensure-dir! (vals paths))
        _ (ensure-secret-dir! (:secrets paths))
        manifest-path (manifest-file mission-id agent-id)
        {:keys [ports]} (choose-ports! {:mission-id mission-id
                                        :agent-id agent-id
                                        :ports-count port-count})
        manifest {:mission/id mission-id
                  :agent/id agent-id
                  :paths (into {} (map (fn [[k ^File file]]
                                         [k (.getAbsolutePath file)])
                                       paths))
                  :sandbox/ports ports
                  :created-at (str (Instant/now))}
        cleanup! (fn cleanup! []
                   (release-ports! {:mission-id mission-id
                                    :agent-id agent-id})
                   (delete-tree! root))]
    (spit manifest-path (pr-str manifest))
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable cleanup!))
    {:action/status :status/ok
     :mission/id mission-id
     :agent/id agent-id
     :sandbox/root (.getAbsolutePath root)
     :sandbox/paths (update-vals paths #(.getAbsolutePath ^File %))
     :sandbox/ports ports
     :env {:INTUITION_SANDBOX_ROOT (.getAbsolutePath root)
           :INTUITION_SANDBOX_WORK (.getAbsolutePath (:work paths))
           :INTUITION_MISSION_ID mission-id
           :INTUITION_AGENT_ID agent-id
           :INTUITION_SANDBOX_PORTS (str/join "," ports)}
     :manifest/path (.getAbsolutePath manifest-path)
     :cleanup! cleanup!}))

(defn with-sandbox
  "Convenience helper that runs `f` with a sandbox and ensures cleanup."
  [opts f]
  (let [env (bootstrap! opts)]
    (try
      (f env)
      (finally
        ((:cleanup! env))))))
