(ns intuition.sfs.log.step
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [datomic.client.api :as d]
   [intuition.datomic :as datomic]
   [intuition.sfs.env.bootstrap :as bootstrap])
  (:import
   (java.time Instant ZoneOffset)
   (java.time.format DateTimeFormatter)
   (java.util Date UUID)))

(def ^:private repo-root (.getCanonicalPath (io/file ".")))
(def ^:private iso-formatter (-> DateTimeFormatter/ISO_INSTANT
                                 (.withZone ZoneOffset/UTC)))

(def ^:private worklog-schema
  [{:db/ident :worklog/id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :worklog/mission-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :worklog/step-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :worklog/agent-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :worklog/deliverable-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :worklog/track
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :worklog/summary
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :worklog/lock-token
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :worklog/evidence-before
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :worklog/evidence-after
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :worklog/artifacts
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :worklog/markdown-ref
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :worklog/logged-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}])

(defn- ensure-schema!
  [conn]
  (let [db (d/db conn)
        installed? (seq (d/q '[:find ?e :where [?e :db/ident :worklog/id]] db))]
    (when-not installed?
      (d/transact conn {:tx-data worklog-schema})))
  conn)

(defn- canonical-path
  [path]
  (when (str/blank? (str path))
    (throw (ex-info "Path is required." {})))
  (let [file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info "Evidence/artifact path does not exist." {:path path})))
    (let [canonical (.getCanonicalPath file)]
      (when-not (.startsWith canonical repo-root)
        (throw (ex-info "Worklog paths must stay inside repo root."
                        {:path canonical :repo repo-root})))
      canonical)))

(defn- ensure-lock!
  [{:keys [mission-id agent-id] lock-token :lock/token}]
  (let [file (bootstrap/lock-token-file mission-id lock-token)]
    (when-not (.exists file)
      (throw (ex-info "Scope lock token missing."
                      {:mission/id mission-id
                       :agent/id agent-id})))
    (let [data (edn/read-string (slurp file))]
      (when-not (= mission-id (:mission/id data))
        (throw (ex-info "Lock token mission mismatch." {:expected mission-id :token lock-token})))
      (when-not (= agent-id (:agent/id data))
        (throw (ex-info "Lock token is held by another agent."
                        {:expected agent-id :token lock-token})))
      data)))

(defn- ensure-artifacts
  [artifacts]
  (when (empty? artifacts)
    (throw (ex-info "Artifacts are required for every worklog entry." {})))
  (mapv (fn [{:keys [path label]}]
          (when (str/blank? (str path))
            (throw (ex-info "Artifact path required." {})))
          (let [canonical (canonical-path path)
                label (if (str/blank? label)
                        (-> (io/file canonical) .getName)
                        label)]
            {:label label :path canonical}))
        artifacts))

(defn- ensure-evidence
  [{:keys [before after]}]
  (when (or (str/blank? before) (str/blank? after))
    (throw (ex-info "Before/after evidence is required." {})))
  {:before (canonical-path before)
   :after (canonical-path after)})

(defn- render-artifacts
  [artifacts]
  (mapv (fn [{:keys [label path]}]
          (pr-str {:label label :path path}))
        artifacts))

(defn- log-dir
  [mission-id]
  (let [sanitized (bootstrap/sanitize-fragment mission-id)
        dir (io/file repo-root "missions" "logs" sanitized)]
    (doto dir .mkdirs)))

(defn- append-markdown!
  [{:keys [mission-id step-id summary deliverable-id artifacts evidence track agent-id lock-token logged-at]}]
  (let [dir (log-dir mission-id)
        file (io/file dir "worklog.md")
        header (when-not (.exists file)
                 (format "# Mission %s Worklog

" mission-id))
        artifacts-lines (->> artifacts
                             (map (fn [{:keys [label path]}]
                                    (str "  - " label ": " path)))
                             (str/join "
"))
        entry (format (str "## Step %s (%s)
"
                           "- Track: %s
"
                           "- Deliverable: %s
"
                           "- Agent: %s
"
                           "- Lock: %s
"
                           "- Summary: %s
"
                           "- Evidence (before): %s
"
                           "- Evidence (after): %s
"
                           "- Artifacts:
%s

---

")
                      step-id
                      (.format iso-formatter logged-at)
                      track
                      deliverable-id
                      agent-id
                      lock-token
                      summary
                      (:before evidence)
                      (:after evidence)
                      artifacts-lines)]
    (spit file (str (or header "") entry) :append true)
    (.getCanonicalPath file)))

(defn- ensure-unique-step!
  [conn mission-id step-id]
  (let [existing (d/q '[:find ?e :in $ ?mission ?step
                         :where [?e :worklog/mission-id ?mission]
                                [?e :worklog/step-id ?step]]
                      (d/db conn) mission-id step-id)]
    (when (seq existing)
      (throw (ex-info "Step already logged for mission." {:mission/id mission-id
                                                           :step/id step-id})))))

(defn log-step!
  [{:mission/keys [id]
    agent-id :agent/id
    step-id :step/id
    deliverable-id :deliverable/id
    track :track/id
    lock-token :lock/token
    :keys [summary artifacts evidence]}]
  (when (str/blank? id) (throw (ex-info "mission/id required" {})))
  (when (str/blank? agent-id) (throw (ex-info "agent/id required" {})))
  (when (str/blank? step-id) (throw (ex-info "step/id required" {})))
  (when (str/blank? deliverable-id) (throw (ex-info "deliverable/id required" {})))
  (when-not (keyword? track) (throw (ex-info "track/id must be keyword" {:track track})))
  (when (str/blank? summary) (throw (ex-info "summary is required" {})))
  (when (str/blank? lock-token) (throw (ex-info "lock-token is required" {})))
  (let [evidence* (ensure-evidence {:before (:before evidence)
                                    :after (:after evidence)})
        artifacts* (ensure-artifacts artifacts)
        _ (ensure-lock! {:mission-id id
                         :agent-id agent-id
                         :lock/token lock-token})
        conn (-> (datomic/ensure-db!) ensure-schema!)
        _ (ensure-unique-step! conn id step-id)
        log-id (UUID/randomUUID)
        logged-at (Instant/now)
        logged-at-date (Date/from logged-at)
        markdown-path (append-markdown! {:mission-id id
                                         :step-id step-id
                                         :summary summary
                                         :deliverable-id deliverable-id
                                         :artifacts artifacts*
                                         :evidence evidence*
                                         :track track
                                         :agent-id agent-id
                                         :lock/token lock-token
                                         :logged-at logged-at})
        tx {:worklog/id log-id
            :worklog/mission-id id
            :worklog/step-id step-id
            :worklog/agent-id agent-id
            :worklog/deliverable-id deliverable-id
            :worklog/track track
            :worklog/summary summary
            :worklog/lock-token lock-token
            :worklog/evidence-before (:before evidence*)
            :worklog/evidence-after (:after evidence*)
            :worklog/artifacts (render-artifacts artifacts*)
            :worklog/markdown-ref markdown-path
            :worklog/logged-at logged-at-date}
        tx-result (d/transact conn {:tx-data [tx]})]
    {:action/status :status/ok
     :log/id log-id
     :worklog/entity [:worklog/id log-id]
     :markdown/path markdown-path
     :datomic/tx (select-keys tx-result [:db-before :db-after :tx-data])}))
