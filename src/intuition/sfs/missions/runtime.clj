(ns intuition.sfs.missions.runtime
  "Runtime entrypoints for the mission lifecycle (SYSTEM_SPEC.md §3)."
  (:require
   [clojure.edn :as edn]
  [clojure.java.io :as io]
  [clojure.string :as str]
  [datomic.client.api :as d]
   [intuition.ci.profiles :as ci.profiles]
   [intuition.code.runtime :as code]
   [intuition.datomic :as datomic]
   [intuition.deploy.runtime :as deploy]
   [intuition.js.runtime :as js]
   [intuition.dictionary :as dictionary]
   [intuition.sfs.actions.runtime :as actions]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [intuition.sfs.missions.registry :as registry]
   [intuition.sfs.permissions :as perms]
   [intuition.sfs.protocols.runtime :as protocols])
  (:import
   (java.io File)
   (java.time Instant)
   (java.util Date UUID)))

(def ^:private repo-root (.getCanonicalFile (io/file ".")))
(def ^:private repo-root-path (.getCanonicalPath repo-root))

(def ^:private mission-schema
  [{:db/ident :mission/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :mission/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/summary
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/category
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/priority
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/queue-tags
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :mission/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/protocol
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/protocol-version
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/scope
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/prerequisites
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :mission/deliverables
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :mission/work-tracks
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :mission/tests
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :mission/js-components
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :mission/external-apis
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :mission/spec-section
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/owner
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/locks-held
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :mission/started-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/approved-by
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/approved-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/report-path
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/report-summary
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/report-generated-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission/branch-artifact
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(def ^:private mission-event-schema
  [{:db/ident :mission.event/id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :mission.event/mission-id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission.event/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission.event/details
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :mission.event/occurred-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}])

(def ^:private mission-db-schema
  (concat mission-schema mission-event-schema))

(def ^:private mission-pull-pattern
  [:db/id
   :mission/id
   :mission/title
   :mission/summary
   :mission/category
   :mission/priority
   :mission/status
   :mission/protocol
   :mission/protocol-version
   :mission/scope
   :mission/prerequisites
   :mission/deliverables
   :mission/work-tracks
   :mission/queue-tags
   :mission/tests
   :mission/js-components
   :mission/external-apis
   :mission/spec-section
   :mission/owner
   :mission/locks-held
   :mission/branch-artifact
   :mission/report-path
   :mission/report-summary])

(defn- now [] (Instant/now))

(defn- instant->date [^Instant inst]
  (when inst (Date/from inst)))

(defn- ensure-schema!
  [conn]
  (let [db (d/db conn)
        installed? (seq (d/q '[:find ?e :where [?e :db/ident :mission/id]] db))]
    (when-not installed?
      (d/transact conn {:tx-data mission-db-schema})))
  conn)

(defn- mission-records
  []
  (->> (dictionary/load-missions)
       (filter :mission/id)))

(defn- serialize-sensitive
  [entries]
  (mapv (fn [entry]
          (if (string? entry)
            entry
            (pr-str entry)))
        (vec (or entries []))))

(defn- parse-sensitive
  [entries]
  (mapv (fn [entry]
          (cond
            (string? entry)
            (try
              (edn/read-string entry)
              (catch Exception e
                (throw (ex-info "Unable to parse sensitive mission entry"
                                {:value entry}
                                e))))
            (map? entry) entry
            :else entry))
        (vec (or entries []))))

(defn- prepare-mission-record
  [record]
  (-> record
      (select-keys [:mission/id :mission/title :mission/summary :mission/category
                    :mission/priority :mission/status :mission/protocol
                    :mission/protocol-version :mission/scope :mission/spec-section
                    :mission/owner :mission/js-components :mission/external-apis])
      (merge {:mission/prerequisites (vec (or (:mission/prerequisites record) []))
              :mission/deliverables (vec (or (:mission/deliverables record) []))
              :mission/work-tracks (vec (or (:mission/work-tracks record) []))
              :mission/queue-tags (vec (or (:mission/queue-tags record) []))
              :mission/tests (vec (or (:mission/tests record) []))
              :mission/js-components (serialize-sensitive (:mission/js-components record))
              :mission/external-apis (serialize-sensitive (:mission/external-apis record))})))

(defn- seed-missions!
  [conn]
  (doseq [record (mission-records)]
    (d/transact conn {:tx-data [(prepare-mission-record record)]}))
  conn)

(defn prepare-conn!
  "Installs dictionary + mission schema seed data into the provided connection."
  [conn]
  (dictionary/seed-all! conn)
  (ensure-schema! conn)
  (seed-missions! conn)
  conn)

(defn- ensure-base!
  []
  (prepare-conn! (datomic/ensure-db!)))

(defn- fetch-mission!
  [conn mission-id]
  (let [entity (d/pull (d/db conn) mission-pull-pattern [:mission/id mission-id])]
    (when-not entity
      (throw (ex-info (str "Unknown mission " mission-id)
                      {:type :mission/not-found
                       :mission/id mission-id})))
    (-> entity
        (update :mission/js-components parse-sensitive)
        (update :mission/external-apis parse-sensitive))))

(defn- mission-id-set
  [conn]
  (->> (d/q '[:find ?mission-id
              :where [?e :mission/id ?mission-id]]
            (d/db conn))
       (map first)
       set))

(defn- parse-mission-scope
  [raw mission-id]
  (try
    (edn/read-string raw)
    (catch Exception e
      (throw (ex-info "Mission scope must be EDN"
                      {:mission/id mission-id}
                      e)))))

(defn- mission-scope-map
  [mission]
  (let [raw (:mission/scope mission)]
    (if (str/blank? (str raw))
      {}
      (let [parsed (parse-mission-scope raw (:mission/id mission))]
        (when-not (map? parsed)
          (throw (ex-info "Mission scope must be a map"
                          {:mission/id (:mission/id mission)
                           :mission/scope raw})))
        parsed))))

(defn- mission-scope-paths
  [mission]
  (let [scope (mission-scope-map mission)]
    (vec (or (:paths scope) []))))

(defn- mission-scope-code-types
  [mission]
  (let [scope (mission-scope-map mission)]
    (vec (or (:code-types scope) []))))

(defn- keyword-entry
  [entry]
  (cond
    (keyword? entry) entry
    (string? entry)
    (let [trimmed (str/trim entry)]
      (when-not (str/blank? trimmed)
        (try
          (if (str/starts-with? trimmed ":")
            (edn/read-string trimmed)
            (keyword trimmed))
          (catch Exception _ nil))))
    :else nil))

(defn- mission-ci-profile-ident
  [mission]
  (let [scope (mission-scope-map mission)
        override (keyword-entry (:ci/profile scope))
        category (:mission/category mission)
        profile (ci.profiles/resolve-profile {:ident override
                                              :category category})]
    (:ci.profile/ident profile)))

(defn- mission-code-type-idents
  [mission]
  (let [idents (->> (mission-scope-code-types mission)
                    (map keyword-entry)
                    (remove nil?)
                    (mapcat (fn [entry]
                              (if (= "code.type" (namespace entry))
                                [entry]
                                (when-let [definition (code/by-ident entry)]
                                  (when-let [type-ident (:code.definition/type definition)]
                                    [type-ident])))))
                    distinct
                    vec)
        unknown (seq (remove code/type-ident? idents))]
    (when unknown
      (throw (ex-info "Mission references CodeTypes outside catalog"
                      {:mission/id (:mission/id mission)
                       :codetype/unknown (vec unknown)})))
    idents))

(defn- mission-generator-codetypes
  [mission]
  (->> (mission-code-type-idents mission)
       (filter (fn [ident]
                 (when-let [entry (code/type-by-ident ident)]
                   (and (:code.type/generator entry)
                        (seq (:code.type/generated-artifacts entry))))))
       vec))

(defn- resolve-codetype-paths
  [mission codetype-config]
  (let [scope-paths (mission-scope-paths mission)
        explicit (vec (or (:codetype/paths codetype-config)
                          (:paths codetype-config)
                          []))]
    (->> (concat explicit scope-paths)
         (map #(some-> % str str/trim))
         (remove str/blank?)
         distinct
         vec)))

(defn- mission-deployments
  [mission]
  (vec (or (:deployments (mission-scope-map mission)) [])))

(defn- deployment-protocol
  [cycle]
  (case (:deploy.cycle/strategy cycle)
    :deploy.strategy/blue-green :protocol/deploy-blue-green
    :deploy.strategy/canary :protocol/deploy-canary
    :deploy.strategy/rollback :protocol/deploy-rollback
    (throw (ex-info "Unknown deployment strategy"
                    {:deploy/strategy (:deploy.cycle/strategy cycle)
                     :deploy.cycle/id (:deploy.cycle/id cycle)}))))

(defn- run-deployment-cycle!
  [conn permissions mission cycle]
  (protocols/run! {:conn conn
                   :protocol/ident (deployment-protocol cycle)
                   :permissions permissions
                   :context {:mission/id (:mission/id mission)
                             :deploy/cycle cycle}}))

(defn- run-deployments!
  [conn mission permissions]
  (let [cycles (mission-deployments mission)]
    (if (seq cycles)
      (do
        (doseq [cycle cycles]
          (run-deployment-cycle! conn permissions mission cycle))
        (let [artifacts (deploy/consume-evidence! (:mission/id mission))]
          (when (empty? artifacts)
            (throw (ex-info "Deployment evidence is missing"
                            {:mission/id (:mission/id mission)})))
          artifacts))
      [])))

(defn- mission-js-components
  [mission]
  (vec (or (:mission/js-components mission) [])))

(defn- mission-external-apis
  [mission]
  (vec (or (:mission/external-apis mission) [])))

(defn- mission-has-js?
  [mission]
  (seq (mission-js-components mission)))

(defn- mission-has-apis?
  [mission]
  (seq (mission-external-apis mission)))

(defn- mission-approver
  [mission agent-id]
  (let [owner (:mission/owner mission)]
    (cond
      (string? owner) owner
      (keyword? owner) (name owner)
      owner owner
      :else agent-id)))

(defn- security-approvals-context
  [mission]
  (let [mission-id (:mission/id mission)
        components (mission-js-components mission)
        apis (mission-external-apis mission)]
    {:security/js-approved? (js/approval-present? {:mission-id mission-id
                                                   :integration-type :integration.type/js
                                                   :components components
                                                   :apis []})
     :security/apis-approved? (js/approval-present? {:mission-id mission-id
                                                     :integration-type :integration.type/api
                                                     :components []
                                                     :apis apis})}))

(defn- security-protocol-context
  [mission agent-id integration-type]
  {:mission/id (:mission/id mission)
   :agent/id agent-id
   :js/components (mission-js-components mission)
   :external/apis (mission-external-apis mission)
   :security/approver (mission-approver mission agent-id)
   :security/justification (or (:mission/summary mission) "Security approval recorded via protocol.")
   :security/integration-type integration-type
   :security/revocation-reason (str "Revoked by " agent-id " for " (:mission/id mission))})

(def ^:private security-approval-permission
  :permission/security.approve)

(defn- require-security-approval!
  [permissions mission-id]
  (when-not (contains? (set permissions) security-approval-permission)
    (throw (ex-info "Security approvals require steward/ops authorization."
                    {:mission/id mission-id
                     :required security-approval-permission}))))

(defn- run-js-approval-protocol!
  [conn mission agent-id permissions]
  (when (mission-has-js? mission)
    (protocols/run! {:conn conn
                     :protocol/ident :protocol/js-approve
                     :permissions permissions
                     :context (security-protocol-context mission agent-id :integration.type/js)})))

(defn- run-api-approval-protocol!
  [conn mission agent-id permissions]
  (when (mission-has-apis? mission)
    (protocols/run! {:conn conn
                     :protocol/ident :protocol/external-api-approve
                     :permissions permissions
                     :context (security-protocol-context mission agent-id :integration.type/api)})))

(defn- run-js-revoke-protocol!
  [conn mission agent-id permissions]
  (when (or (mission-has-js? mission)
            (mission-has-apis? mission))
    (protocols/run! {:conn conn
                     :protocol/ident :protocol/js-revoke
                     :permissions permissions
                     :context (security-protocol-context mission agent-id :integration.type/js)})))

(defn- all-missions
  [conn]
  (let [db (d/db conn)]
    (->> (d/q '[:find ?e
                :where [?e :mission/id _]]
              db)
         (map first)
         (map #(d/pull db mission-pull-pattern %))
         (map #(dissoc % :db/id))
         vec)))

(defn- log-dir
  [mission-id]
  (let [dir (io/file repo-root "missions" "logs" (bootstrap/sanitize-fragment mission-id))]
    (.mkdirs dir)
    dir))

(defn- write-log!
  [mission-id filename data]
  (let [dir (log-dir mission-id)
        file (io/file dir filename)]
    (spit file (pr-str data))
    (.getCanonicalPath file)))

(defn- steps-dir
  [mission-id]
  (let [dir (io/file (log-dir mission-id) "steps")]
    (.mkdirs dir)
    dir))

(defn- repo-file
  [path]
  (let [file (io/file path)]
    (if (.isAbsolute file)
      file
      (io/file repo-root path))))

(defn- repo-canonical-path
  [path]
  (let [canonical (.getCanonicalPath (repo-file path))]
    (when-not (.startsWith canonical repo-root-path)
      (throw (ex-info "Path escapes repository root."
                      {:path canonical
                       :repo/root repo-root-path})))
    canonical))


(defn- codetype-artifact-entry
  [mission-id]
  (let [file (io/file (log-dir mission-id) "codetype-validation.edn")]
    (when (.exists file)
      {:label "codetype-validation.edn"
       :path (.getCanonicalPath file)})))


(defn- record-event!
  [conn {:keys [mission-id type details]}]
  (let [entity {:mission.event/id (UUID/randomUUID)
                :mission.event/mission-id mission-id
                :mission.event/type type
                :mission.event/details (pr-str details)
                :mission.event/occurred-at (instant->date (now))}]
    (d/transact conn {:tx-data [entity]})
    entity))

(defn worklog-summary
  [conn mission-id]
  (try
    (let [db (d/db conn)
          tracks (->> (d/q '[:find ?track
                             :in $ ?mission
                             :where [?e :worklog/mission-id ?mission]
                                    [?e :worklog/track ?track]]
                           db mission-id)
                      (map first)
                      set)
          count (ffirst
                 (d/q '[:find (count ?e)
                        :in $ ?mission
                        :where [?e :worklog/mission-id ?mission]]
                      db mission-id))]
      {:mission/id mission-id
       :count count
       :tracks tracks})
    (catch Exception _
      {:mission/id mission-id
       :count 0
       :tracks #{}})))

(defn- canonical-path
  [path]
  (when (str/blank? (str path))
    (throw (ex-info "Artifact path is required." {:path path})))
  (let [file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info "Artifact path does not exist."
                      {:path path})))
    (let [canonical (.getCanonicalPath file)]
      (when-not (.startsWith canonical (.getAbsolutePath repo-root))
        (throw (ex-info "Artifacts must stay inside the repository."
                        {:path canonical})))
      canonical)))

(defn- compact-map
  [m]
  (into {}
        (remove (comp nil? val))
        m))

(defn- artifact-entry
  [{:keys [path media-type recorded-at step-id]}]
  (compact-map {:mission.artifact/path path
                :mission.artifact/media-type media-type
                :mission.artifact/recorded-at recorded-at
                :mission.artifact/source-step step-id}))

(defn- tool-run-entry
  [{:keys [tool-id params exit-status log-path recorded-at]}]
  (compact-map {:mission.tool-run/tool-id tool-id
                :mission.tool-run/params (when params (pr-str params))
                :mission.tool-run/exit-status exit-status
                :mission.tool-run/log-path log-path
                :mission.tool-run/recorded-at recorded-at}))

(defn- record-step!
  [{:keys [mission kind agent-id inputs outputs summary detail tool-run extra-artifacts]
    :or {summary "Mission step"
         detail ""}}]
  (when (str/blank? (str agent-id))
    (throw (ex-info "agent/id required for mission step"
                    {:field :agent/id
                     :mission/id (:mission/id mission)})))
  (let [mission-id (:mission/id mission)
        started (str (now))
        completed (str (now))
        step-id (str (UUID/randomUUID))
        dir (steps-dir mission-id)
        base (format "%d-%s" (System/currentTimeMillis) (name kind))
        edn-file (io/file dir (str base ".edn"))
        md-file (io/file dir (str base ".md"))
        audit {:mission.step/audit {:recorded-at started
                                    :recorded-by agent-id
                                    :spec/sections ["2.1" "2.2" "6" "10"]
                                    :channel :agent-gateway}}
        md-content (str "# " summary "\n\n"
                        (if (str/blank? detail)
                          "Recorded via Agent Gateway with §2/§6 watermark."
                          detail)
                        "\n\nAudit watermark: " (pr-str (:mission.step/audit audit)) "\n")
        edn-payload (compact-map (merge {:mission/id mission-id
                                         :agent/id agent-id
                                         :mission.step/id step-id
                                         :mission.step/kind kind
                                         :mission.step/inputs (when inputs (pr-str inputs))
                                         :mission.step/outputs (when outputs (pr-str outputs))
                                         :mission.step/status :mission.step.status/completed
                                         :mission.step/started-at started
                                         :mission.step/completed-at completed
                                         :mission.step/tool-run tool-run}
                                        audit))
        artifact-ts (str (now))
        base-artifacts [(artifact-entry {:path (.getCanonicalPath md-file)
                                         :media-type :artifact.media/md
                                         :recorded-at artifact-ts
                                         :step-id step-id})
                        (artifact-entry {:path (.getCanonicalPath edn-file)
                                         :media-type :artifact.media/edn
                                         :recorded-at artifact-ts
                                         :step-id step-id})]
        extra (map (fn [{:keys [path media-type recorded-at]}]
                     (artifact-entry {:path (repo-canonical-path path)
                                      :media-type (or media-type :artifact.media/unknown)
                                      :recorded-at (or recorded-at artifact-ts)
                                      :step-id step-id}))
                   (or extra-artifacts []))
        artifact-refs (vec (remove nil? (concat extra base-artifacts)))
        final-payload (assoc edn-payload :mission.step/artifacts artifact-refs)]
    (spit edn-file (pr-str final-payload))
    (spit md-file md-content)
    (merge final-payload
           {:mission.step/edn (.getCanonicalPath edn-file)
            :mission.step/markdown (.getCanonicalPath md-file)})))

(defn- mission-locked-paths
  [mission]
  (->> (mission-scope-paths mission)
       (map #(when-not (str/blank? (str %)) (repo-canonical-path %)))
       (remove nil?)
       vec))

(defn- ensure-edit-path!
  [mission path]
  (let [canonical (repo-canonical-path path)
        locked (mission-locked-paths mission)
        allowed? (some (fn [locked-path]
                         (or (= canonical locked-path)
                             (.startsWith canonical (str locked-path File/separator))))
                       locked)]
    (when-not (seq locked)
      (throw (ex-info "Mission scope paths required before recording edits."
                      {:mission/id (:mission/id mission)})))
    (when-not allowed?
      (throw (ex-info "Edit path outside locked mission scope."
                      {:mission/id (:mission/id mission)
                       :mission/paths locked
                       :edit/path canonical})))
    canonical))

(defn- read-edn-file
  [path]
  (-> path slurp edn/read-string))

(defn- codetype-generation-artifact
  [mission-id]
  (let [file (io/file (log-dir mission-id) "codetype-generation.edn")]
    (when (.exists file)
      {:label "codetype-generation.edn"
       :path (.getCanonicalPath file)})))

(defn- codetype-generation-context
  [mission-id]
  (when-let [artifact (codetype-generation-artifact mission-id)]
    (let [payload (read-edn-file (:path artifact))]
      {:artifact (:path artifact)
       :runs (:codetype/generations payload)})))

(defn- mission-branch-artifact
  [mission]
  (let [mission-id (:mission/id mission)
        configured (:mission/branch-artifact mission)
        candidate-files (->> [(some-> configured io/file)
                              (io/file (log-dir mission-id) "branch.edn")]
                             (remove nil?))]
    (when-let [existing (some #(when (.exists ^java.io.File %) %) candidate-files)]
      (let [path (.getCanonicalPath ^java.io.File existing)
            data (read-edn-file path)
            markdown (or (:branch/markdown-path data)
                         (.getCanonicalPath (io/file (log-dir mission-id) "branch.md")))]
        (-> data
            (assoc :branch/edn-path path
                   :branch/markdown-path markdown))))))

(defn- require-path!
  [path message data]
  (when (str/blank? (str path))
    (throw (ex-info message data)))
  (let [file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info message
                      (merge data {:path path :reason :missing-file})))))
  path)

(defn- mission-report-payload!
  [mission]
  (let [path (require-path! (:mission/report-path mission)
                            "Mission report is missing."
                            {:type :mission/missing-report
                             :mission/id (:mission/id mission)})]
    (read-edn-file path)))

(defn- approval-log-file
  [mission-id]
  (io/file (log-dir mission-id) "approval.edn"))

(defn- mission-approval-payload!
  [mission]
  (let [file (approval-log-file (:mission/id mission))
        path (.getCanonicalPath file)]
    (when-not (.exists file)
      (throw (ex-info "Mission approval artifact is missing."
                      {:type :mission/missing-approval
                       :mission/id (:mission/id mission)
                       :path path})))
    (read-edn-file path)))

(defn- ensure-workspace!
  [mission-id agent-id workspace-root]
  (let [default-path (io/file repo-root "tmp" "missions" (bootstrap/sanitize-fragment mission-id) agent-id)
        target (io/file (or workspace-root default-path))]
    (.mkdirs target)
    (.getCanonicalPath target)))

(defn- mission-sandbox-root
  [mission-id agent-id sandbox-root workspace-root]
  (if (str/blank? (str sandbox-root))
    (ensure-workspace! mission-id agent-id workspace-root)
    (canonical-path sandbox-root)))

(defn- validate-mission!
  [conn mission known permissions]
  (let [{:keys [result]} (actions/execute!
                          {:conn conn
                           :action/ident :action/mission.validate
                           :config {:mission/config mission
                                    :mission/known known}
                           :permissions permissions})]
    result))

(defn- run-transition-action!
  [conn mission target context permissions]
  (let [{:keys [result]} (actions/execute!
                          {:conn conn
                           :action/ident :action/mission.transition
                           :config {:mission/config mission
                                    :mission/target target
                                    :mission/context context}
                           :permissions permissions})]
    result))

(defn- acquire-locks!
  [conn mission-id locks permissions]
  (let [{:keys [result]} (actions/execute!
                          {:conn conn
                           :action/ident :action/lock.acquire
                           :config {:mission/id mission-id
                                    :locks (set locks)}
                           :permissions permissions})]
    result))

(defn- run-tests!
  [conn mission-id {:keys [suite paths error-mode]} permissions]
  (when-not suite
    (throw (ex-info "test suite is required for transition."
                    {:type :mission/missing-test-config})))
  (let [{:keys [result]} (actions/execute!
                          {:conn conn
                           :action/ident :action/test.run-suite
                           :config {:mission/id mission-id
                                    :test/suite suite
                                    :test/paths paths
                                    :test/error-mode (or error-mode :fail-fast)}
                           :permissions permissions})]
    result))

(defn- run-lint!
  [conn mission-id {:keys [paths command]} permissions]
  (let [config (cond-> {:mission/id mission-id}
                 (seq paths) (assoc :lint/paths (vec paths))
                 (seq command) (assoc :lint/command (vec command)))
        {result :result} (actions/execute!
                          {:conn conn
                           :action/ident :action/lint.run
                           :config config
                           :permissions permissions})]
    result))

(defn- run-docs!
  [conn mission-id {:keys [paths]} permissions]
  (when-not (seq paths)
    (throw (ex-info "Docs paths required for mission transition."
                    {:type :mission/missing-docs-config})))
  (let [{:keys [result]} (actions/execute!
                          {:conn conn
                           :action/ident :action/docs.sync
                           :config {:mission/id mission-id
                                    :docs/paths paths}
                           :permissions permissions})]
    result))

(defn- doc-track-required?
  [mission]
  (some #{:work-track/doc} (:mission/work-tracks mission)))

(defn- system-map-track-required?
  [mission]
  (some #{:work-track/system-map} (:mission/work-tracks mission)))

(defn- docgen-config
  [mission-id docgen kind]
  (let [templates (get-in docgen [kind :doc/templates])]
    (cond-> {:mission/id mission-id}
      (seq templates) (assoc :doc/templates (vec templates)))))

(defn- run-docgen!
  [conn mission-id docgen permissions]
  (let [type-config (docgen-config mission-id docgen :types)
        mission-config (docgen-config mission-id docgen :missions)
        {type-result :result} (actions/execute!
                               {:conn conn
                                :action/ident :action/docgen.types
                                :config type-config
                                :permissions permissions})
        {mission-result :result} (actions/execute!
                                  {:conn conn
                                   :action/ident :action/docgen.missions
                                   :config mission-config
                                   :permissions permissions})]
    {:types type-result
     :missions mission-result}))

(defn- run-ci-profile!
  [conn mission sandbox-root permissions {:keys [log-root trigger]}]
  (let [config (cond-> {:mission/id (:mission/id mission)
                        :sandbox/root sandbox-root
                        :ci/profile (mission-ci-profile-ident mission)}
                 log-root (assoc :ci/log-root log-root)
                 trigger (assoc :ci/trigger trigger))
        {result :result} (actions/execute!
                          {:conn conn
                           :action/ident :action/ci.run-profile
                           :config config
                           :permissions permissions})]
    result))

(defn- docgen->artifacts
  [docgen-results]
  (if-not docgen-results
    []
    (let [entries (->> [:types :missions]
                       (map docgen-results)
                       (remove nil?)
                       (mapcat :docs/generated))]
      (->> entries
           (mapcat (fn [doc]
                     (let [title (:doc/title doc)
                           markdown (:markdown/path doc)
                           edn (:edn/path doc)]
                       (cond-> []
                         markdown (conj {:path markdown
                                         :label (str title " (md)")})
                         edn (conj {:path edn
                                    :label (str title " (edn)")})))))
           vec))))

(defn- run-system-map!
  [conn mission-id {:keys [entities skip?]} permissions]
  (if skip?
    {:action/status :status/ok
     :system-map/entities (vec (or entities []))
     :system-map/skipped? true}
    (let [{:keys [result]} (actions/execute!
                            {:conn conn
                             :action/ident :action/system-map.refresh
                             :config {:mission/id mission-id
                                      :system-map/entities (vec (or entities []))
                                      :system-map/skip? (boolean skip?)}
                             :permissions permissions})]
      result)))

(defn- run-codetype-validation!
  [conn mission-id agent-id paths permissions]
  (when-not (seq paths)
    (throw (ex-info "codetype/paths required for validation."
                    {:type :mission/missing-codetype-paths
                     :mission/id mission-id})))
  (let [{:keys [result]} (actions/execute!
                          {:conn conn
                           :action/ident :action/codetype.validate
                           :config {:mission/id mission-id
                                    :agent/id agent-id
                                    :codetype/paths (vec paths)}
                           :permissions permissions})]
    result))

(defn- run-codetype-generations!
  [conn mission agent-id sandbox-root permissions]
  (let [idents (mission-generator-codetypes mission)]
    (if (or (str/blank? (str sandbox-root)) (empty? idents))
      []
      (mapv (fn [ident]
              (let [{:keys [result]} (actions/execute!
                                       {:conn conn
                                        :action/ident :action/codetype.generate
                                        :config {:mission/id (:mission/id mission)
                                                 :agent/id agent-id
                                                 :sandbox/root sandbox-root
                                                 :codetype/ident ident}
                                        :permissions permissions})]
                result))
            idents))))

(defn- codetype-context
  [result]
  (when result
    {:status (:action/status result)
     :artifact (:codetype/artifact result)
     :paths (:codetype/paths result)
     :spec-sections (:codetype/spec-sections result)
     :validated-at (:codetype/validated-at result)}))

(defn request-js-approval!
  [{:mission/keys [id]
    agent-id :agent/id
    :keys [permissions conn]}]
  (let [mission-id id
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id is required for js approval" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id is required for js approval" {:field :agent/id})))
        conn (or conn (ensure-base!))
        mission (fetch-mission! conn mission-id)
        permissions (perms/normalize permissions)
        _ (require-security-approval! permissions mission-id)]
    (run-js-approval-protocol! conn mission agent-id permissions)))

(defn request-external-api-approval!
  [{:mission/keys [id]
    agent-id :agent/id
    :keys [permissions conn]}]
  (let [mission-id id
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id is required for API approval" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id is required for API approval" {:field :agent/id})))
        conn (or conn (ensure-base!))
        mission (fetch-mission! conn mission-id)
        permissions (perms/normalize permissions)
        _ (require-security-approval! permissions mission-id)]
    (run-api-approval-protocol! conn mission agent-id permissions)))

(defn revoke-js-integrations!
  [{:mission/keys [id]
    agent-id :agent/id
    :keys [permissions conn]}]
  (let [mission-id id
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id is required for revocation" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id is required for revocation" {:field :agent/id})))
        conn (or conn (ensure-base!))
        mission (fetch-mission! conn mission-id)
        permissions (perms/normalize permissions)
        _ (require-security-approval! permissions mission-id)]
    (run-js-revoke-protocol! conn mission agent-id permissions)))

(defn start!
  [{:mission/keys [id]
    agent-id :agent/id
    workspace-root :workspace/root
    branch-prefix :branch/prefix
    :keys [permissions locks conn]}]
  (let [mission-id id
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id is required" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id is required" {:field :agent/id})))
        conn (or conn (ensure-base!))
        mission (fetch-mission! conn mission-id)
        known (mission-id-set conn)
        permissions (perms/normalize permissions)
        _ (validate-mission! conn mission known permissions)
        sandbox-root (ensure-workspace! mission-id agent-id workspace-root)
        mission-sync (:result (actions/execute!
                               {:conn conn
                                :action/ident :action/protocol.run
                                :config {:mission/id mission-id
                                         :agent/id agent-id
                                         :protocol/ident :protocol/mission-sync
                                         :protocol/context {:mission/id mission-id
                                                            :agent/id agent-id
                                                            :workspace/root sandbox-root
                                                            :branch/prefix branch-prefix}}
                                :permissions permissions}))
        sync-run (:protocol/run mission-sync)
        env (or (get-in sync-run [:step-results :step/env-bootstrap])
                (throw (ex-info "mission-sync missing env bootstrap result"
                                {:mission/id mission-id})))
        branch-result (or (get-in sync-run [:step-results :step/git-branch])
                          (throw (ex-info "mission-sync missing branch snapshot"
                                          {:mission/id mission-id})))
        generation-results (run-codetype-generations! conn mission agent-id (:sandbox/root env) permissions)
        lock-set (or (seq locks) #{:lock/dictionary})
        locks-output (acquire-locks! conn mission-id lock-set permissions)
        tests-result (run-tests! conn mission-id {:suite :test.suite/mission-start
                                                  :paths (vec (or (:mission/tests mission) []))}
                                 permissions)
        security (security-approvals-context mission)
        context (merge {:locks/held (:locks/acquired locks-output)} security)
        _ (run-transition-action! conn mission :mission.status/in-progress context permissions)
        _ (d/transact conn {:tx-data [{:mission/id mission-id
                                       :mission/status :mission.status/in-progress
                                       :mission/started-at (instant->date (now))
                                       :mission/locks-held (:locks/acquired locks-output)
                                       :mission/branch-artifact (:branch/edn-path branch-result)}]})
        event {:mission-id mission-id
               :type :mission.event/start
               :details {:agent/id agent-id
                         :sandbox env
                         :branch branch-result
                         :locks (:locks/acquired locks-output)
                         :tests tests-result}}
        log-path (write-log! mission-id "start.edn" event)]
    (record-event! conn event)
    {:mission/id mission-id
     :mission/status :mission.status/in-progress
     :sandbox env
     :branch branch-result
     :locks (:locks/acquired locks-output)
     :tests tests-result
     :codetype/generation generation-results
     :log/path log-path}))

(defn- merge-context
  [conn mission-id mission transition-options action-results]
  (let [worklogs (or (:worklogs transition-options)
                     (worklog-summary conn mission-id))
        report? (boolean (:mission/report-path mission))
        security (security-approvals-context mission)
        branch-artifact (mission-branch-artifact mission)
        generation (codetype-generation-context mission-id)]
    (merge {:worklogs worklogs
            :locks/held (set (:mission/locks-held mission))
            :report/submitted? report?
            :artifacts/captured? report?
            :transition/justification (:justification transition-options)
            :approval (:approval transition-options)}
           (when branch-artifact {:branch/artifact branch-artifact})
           (when generation {:codetype/generation generation})
           action-results
           security)))

(defn transition!
  [{:mission/keys [id]
    :keys [target justification approval tests docs system-map permissions worklogs docgen lint codetype]
    workspace-root :workspace/root
    sandbox-root :sandbox/root
    ci-log-root :ci/log-root
    agent-id :agent/id
    conn :conn}]
  (let [mission-id id
        _ (when-not target
            (throw (ex-info "target status required" {:field :target/status})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id is required" {:field :agent/id})))
        conn (or conn (ensure-base!))
        mission (fetch-mission! conn mission-id)
        doc-track? (doc-track-required? mission)
        known (mission-id-set conn)
        permissions (perms/normalize permissions)
        sandbox-path (mission-sandbox-root mission-id agent-id sandbox-root workspace-root)
        _ (validate-mission! conn mission known permissions)
        codetype-config (or codetype {})
        review-data (when (= target :mission.status/awaiting-review)
                      (let [lint-result (run-lint! conn mission-id lint permissions)
                            test-result (run-tests! conn mission-id tests permissions)
                            docs-result (run-docs! conn mission-id docs permissions)
                            docgen-results (when doc-track?
                                             (run-docgen! conn mission-id docgen permissions))
                            system-result (run-system-map! conn mission-id system-map permissions)
                            codetype-paths (resolve-codetype-paths mission codetype-config)
                            _ (when-not (seq codetype-paths)
                                (throw (ex-info "codetype/paths required before awaiting review."
                                                {:type :mission/missing-codetype-paths
                                                 :mission/id mission-id})))
                            codetype-result (run-codetype-validation! conn mission-id agent-id codetype-paths permissions)]
                        (cond-> {:lint {:status (:action/status lint-result)
                                        :report (:lint/report lint-result)
                                        :paths (:lint/paths lint-result)
                                        :command (:lint/command lint-result)}
                                 :tests {:status (:action/status test-result)
                                         :report (:test/report test-result)}
                                 :docs/synced? (= :status/ok (:action/status docs-result))
                                 :system-map/refreshed? (= :status/ok (:action/status system-result))
                                 :codetype (codetype-context codetype-result)}
                          docgen-results (assoc :docgen docgen-results))))
        action-results (cond-> {}
                         review-data (merge review-data)
                         (= target :mission.status/done)
                         (assoc :report/submitted? (boolean (:mission/report-path mission))))
        completion-ci (when (= target :mission.status/done)
                        (run-ci-profile! conn mission sandbox-path permissions
                                         {:log-root ci-log-root
                                          :trigger :ci.trigger/pre-completion}))
        action-results (cond-> action-results
                         completion-ci (assoc :ci completion-ci))
        context (merge-context conn mission-id mission
                               {:justification justification
                                :approval approval
                                :worklogs worklogs}
                               action-results)
        _ (run-transition-action! conn mission target context permissions)
        tx {:mission/id mission-id
            :mission/status target}
        tx (cond-> tx
             (= target :mission.status/done)
             (assoc :mission/approved-by (or (:by approval) "steward")
                    :mission/approved-at (instant->date (now))))]
    (d/transact conn {:tx-data [tx]})
    (let [details {:target target
                   :agent/id agent-id
                   :context context}
          log-path (write-log! mission-id
                               (format "transition-%s.edn" (name target))
                               details)]
      (record-event! conn {:mission-id mission-id
                           :type :mission.event/transition
                           :details details})
      {:mission/id mission-id
       :mission/status target
       :context context
       :log/path log-path})))

(defn list-ready-action
  [{:keys [config conn]}]
  (let [conn (or conn (ensure-base!))
        missions (all-missions conn)
        active-queues (registry/active-queues missions)
        ready (registry/ready-missions {:missions missions
                                        :queue-tags (:queue/tags config)
                                        :active-queues active-queues})]
    {:action/status :status/ok
     :mission/list ready
     :mission/active-queues active-queues}))

(defn start-mission-action
  [{:keys [config conn]}]
  (let [conn (or conn (ensure-base!))
        missions (all-missions conn)
        active-queues (registry/active-queues missions)
        selection (registry/select-startable {:missions missions
                                              :mission-id (:mission/id config)
                                              :queue-tags (:queue/tags config)
                                              :active-queues active-queues})
        start-result (start! {:mission/id (:mission/id selection)
                              :agent/id (:agent/id config)
                              :workspace/root (:workspace/root config)
                              :branch/prefix (:branch/prefix config)
                              :locks (:locks config)
                              :conn conn})]
    {:action/status :status/ok
     :mission/selection selection
     :mission/start start-result
     :mission/active-queues active-queues}))

(defn report-action
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         artifacts :report/artifacts
         summary :report/summary
         worklogs :mission.report/worklogs
         tests :mission.report/tests
         docgen :mission.report/docgen
         system-map :mission.report/system-map} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required for report" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required for report" {:field :agent/id})))
        _ (when-not (seq artifacts)
            (throw (ex-info "artifacts required for report"
                            {:field :report/artifacts})))
        dir (log-dir mission-id)
        normalized (->> artifacts
                        (map (fn [{:keys [path label]}]
                               {:label (or label (-> path io/file .getName))
                                :path (canonical-path path)}))
                        vec)
        payload (cond-> {:mission/id mission-id
                         :agent/id agent-id
                         :summary (or summary "Mission report")
                         :artifacts normalized
                         :worklogs (vec worklogs)
                         :tests (vec tests)
                         :generated-at (str (now))}
                  docgen (assoc :docgen docgen)
                  system-map (assoc :system-map system-map))
        file (io/file dir "report.edn")]
    (spit file (pr-str payload))
    {:action/status :status/ok
     :report/path (.getCanonicalPath file)
     :report/submitted? true
     :artifacts/captured? true}))

;; Agent Gateway helpers -----------------------------------------------------

(defn get-mission
  [{:mission/keys [id]
    :keys [conn]}]
  (when (str/blank? (str id))
    (throw (ex-info "mission/id is required" {:field :mission/id})))
  (let [conn (or conn (ensure-base!))]
    (fetch-mission! conn id)))

(defn list-step-artifacts
  [{:mission/keys [id]}]
  (when (str/blank? (str id))
    (throw (ex-info "mission/id is required" {:field :mission/id})))
  (let [dir (steps-dir id)
        files (->> (or (.listFiles dir) (make-array File 0))
                   (sort-by #(.getName ^File %))
                   (map (fn [^File file]
                          {:artifact/name (.getName file)
                           :artifact/path (.getCanonicalPath file)
                           :artifact/size (.length file)}))
                   vec)]
    {:action/status :status/ok
     :mission/id id
     :steps/files files}))

(defn plan-step!
  [{:mission/keys [id]
    :keys [conn]
    agent-id :agent/id
    requirements :plan/requirements
    references :plan/references
    summary :plan/summary
    notes :plan/notes}]
  (when (str/blank? (str id))
    (throw (ex-info "mission/id is required" {:field :mission/id})))
  (when (str/blank? (str agent-id))
    (throw (ex-info "agent/id is required" {:field :agent/id})))
  (let [conn (or conn (ensure-base!))
        mission (fetch-mission! conn id)
        payload (record-step! {:mission mission
                               :kind :mission.step/plan
                               :agent-id agent-id
                               :inputs {:plan/requirements (vec (or requirements []))
                                        :plan/references (vec (or references []))}
                               :outputs {:plan/notes notes
                                         :plan/summary summary}
                               :summary (or summary "Plan refinement")
                               :detail (or notes "Plan refinement recorded via Agent Gateway.")})]
    (assoc payload :action/status :status/ok)))

(defn edit-step!
  [{:mission/keys [id]
    :keys [conn permissions]
    workspace-root :workspace/root
    sandbox-root :sandbox/root
    ci-log-root :ci/log-root
    agent-id :agent/id
    files :edit/files
    summary :edit/summary
    justification :edit/justification}]
  (when (str/blank? (str id))
    (throw (ex-info "mission/id is required" {:field :mission/id})))
  (when (str/blank? (str agent-id))
    (throw (ex-info "agent/id is required" {:field :agent/id})))
  (when-not (seq files)
    (throw (ex-info "edit/files required"
                    {:field :edit/files
                     :mission/id id})))
  (let [conn (or conn (ensure-base!))
        mission (fetch-mission! conn id)
        permissions (perms/normalize permissions)
        sandbox-path (mission-sandbox-root id agent-id sandbox-root workspace-root)
        canonical-files (map #(ensure-edit-path! mission %) files)
        payload (record-step! {:mission mission
                               :kind :mission.step/edit
                               :agent-id agent-id
                               :inputs {:edit/files (vec canonical-files)}
                               :outputs {:edit/summary summary
                                         :edit/justification justification}
                               :summary (or summary "File edit recorded")
                               :detail (or justification "Edit recorded via Agent Gateway.")})
        ci-result (run-ci-profile! conn mission sandbox-path permissions
                                   {:log-root ci-log-root
                                    :trigger :ci.trigger/edit})]
    (assoc payload
           :action/status :status/ok
           :ci/run ci-result)))

(defn tool-run-step!
  [{:mission/keys [id]
    :keys [conn permissions]
    agent-id :agent/id
    tool-id :tool/id
    params :tool/params}]
  (when (str/blank? (str id))
    (throw (ex-info "mission/id is required" {:field :mission/id})))
  (when (str/blank? (str agent-id))
    (throw (ex-info "agent/id is required" {:field :agent/id})))
  (when (nil? tool-id)
    (throw (ex-info "tool/id is required" {:field :tool/id})))
  (let [conn (or conn (ensure-base!))
        mission (fetch-mission! conn id)
        permissions (perms/normalize permissions)
        recorded-at (str (now))
        result (case tool-id
                 :tool/lint (run-lint! conn id params permissions)
                 :tool/test (run-tests! conn id params permissions)
                 (throw (ex-info "Unsupported tool id"
                                 {:tool/id tool-id
                                  :mission/id id})))
        exit-status (if (= :status/ok (:action/status result)) 0 1)
        log-path (or (:lint/log result)
                     (:test/log result)
                     (:lint/report result)
                     (:test/report result))
        tool-run (tool-run-entry {:tool-id tool-id
                                  :params params
                                  :exit-status exit-status
                                  :log-path (when (string? log-path)
                                              (repo-canonical-path log-path))
                                  :recorded-at recorded-at})
        payload (record-step! {:mission mission
                               :kind :mission.step/tool-run
                               :agent-id agent-id
                               :inputs {:tool/id tool-id
                                        :tool/params params}
                               :outputs {:tool/status (:action/status result)
                                         :tool/result (dissoc result :action/status)}
                               :summary (format "Tool run – %s" (name tool-id))
                               :detail "See tool-run artifacts for details."
                               :tool-run tool-run})]
    (write-log! id "tool-run.edn" (merge {:mission/id id} tool-run))
    (assoc payload
           :action/status (:action/status result)
           :tool/result result)))

(defn decision-step!
  [{:mission/keys [id]
    :keys [conn]
    agent-id :agent/id
    summary :decision/summary
    requirements :decision/requirements
    tests :decision/tests
    rationale :decision/rationale}]
  (when (str/blank? (str id))
    (throw (ex-info "mission/id is required" {:field :mission/id})))
  (when (str/blank? (str agent-id))
    (throw (ex-info "agent/id is required" {:field :agent/id})))
  (let [conn (or conn (ensure-base!))
        mission (fetch-mission! conn id)
        payload (record-step! {:mission mission
                               :kind :mission.step/decision
                               :agent-id agent-id
                               :inputs {:decision/requirements (vec (or requirements []))
                                        :decision/tests (vec (or tests []))}
                               :outputs {:decision/summary summary
                                         :decision/rationale rationale}
                               :summary (or summary "Decision recorded")
                               :detail (or rationale "Decision rationale recorded via Agent Gateway.")})]
    (assoc payload :action/status :status/ok)))

(defn approve-action
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         report :mission/report
         approval :mission/approval} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required for approval" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required for approval" {:field :agent/id})))
        _ (when-not (seq report)
            (throw (ex-info "mission/report payload required for approval"
                            {:field :mission/report
                             :mission/id mission-id})))
        _ (when-not (seq approval)
            (throw (ex-info "mission/approval payload required"
                            {:field :mission/approval
                             :mission/id mission-id})))
        dir (log-dir mission-id)
        file (io/file dir "approval.edn")
        payload {:mission/id mission-id
                 :agent/id agent-id
                 :report report
                 :approval approval
                 :approved-at (str (now))}
        path (.getCanonicalPath file)]
    (spit file (pr-str payload))
    {:action/status :status/ok
     :approval/path path
     :approval/steward? (true? (:mission.approval/steward? approval))}))

(defn archive-action
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         report :mission/report
         approval :mission/approval
         summary :archive/summary
         artifacts :archive/artifacts} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required for archive" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required for archive" {:field :agent/id})))
        _ (when-not (seq report)
            (throw (ex-info "mission/report payload required for archive"
                            {:field :mission/report
                             :mission/id mission-id})))
        _ (when-not (seq artifacts)
            (throw (ex-info "archive requires artifacts"
                            {:field :archive/artifacts
                             :mission/id mission-id})))
        dir (log-dir mission-id)
        normalized (map (fn [{:keys [path label]}]
                          {:label (or label (-> path io/file .getName))
                           :path (canonical-path path)})
                        artifacts)
        file (io/file dir "archive.edn")
        payload {:mission/id mission-id
                 :agent/id agent-id
                 :summary (or summary "Mission archive")
                 :report report
                 :approval approval
                 :artifacts (vec normalized)
                 :archived-at (str (now))}
        path (.getCanonicalPath file)]
    (spit file (pr-str payload))
    {:action/status :status/ok
     :archive/path path
     :artifacts/captured? true}))

(defn report!
  [{:mission/keys [id]
    agent-id :agent/id
    :keys [summary artifacts permissions conn docgen system-map]}]
  (let [mission-id id
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id is required" {:field :agent/id})))
        conn (or conn (ensure-base!))
        mission (fetch-mission! conn mission-id)
        branch-artifact (mission-branch-artifact mission)
        _ (when-not branch-artifact
            (throw (ex-info "Branch snapshot artifact required before report (SYSTEM_SPEC.md §6.2)."
                            {:type :mission/missing-branch-artifact
                             :mission/id mission-id})))
        branch-entry {:label (str "Branch snapshot – " (:branch/name branch-artifact))
                      :path (:branch/markdown-path branch-artifact)}
        permissions (perms/normalize permissions)
        worklogs [(worklog-summary conn mission-id)]
        tests [{:declared (:mission/tests mission)}]
        doc-track? (doc-track-required? mission)
        docgen-results (when doc-track?
                         (run-docgen! conn mission-id docgen permissions))
        system-track? (system-map-track-required? mission)
        _ (when (and system-track?
                     (not (seq (get system-map :entities))))
            (throw (ex-info "system-map/entities required for report"
                            {:field :system-map/entities
                             :mission/id mission-id})))
        system-result (when system-track?
                        (run-system-map! conn mission-id system-map permissions))
        deployment-artifacts (run-deployments! conn mission permissions)
        base-artifacts (vec (or artifacts []))
        doc-artifacts (docgen->artifacts docgen-results)
        codetype-artifact (codetype-artifact-entry mission-id)
        _ (when-not codetype-artifact
            (throw (ex-info "codetype validation artifact required for report"
                            {:type :mission/missing-codetype-artifact
                             :mission/id mission-id})))
        report-artifacts (vec (concat [branch-entry]
                                      deployment-artifacts
                                      base-artifacts
                                      doc-artifacts
                                      [codetype-artifact]))
        _ (when (and doc-track? (not (seq doc-artifacts)))
            (throw (ex-info "Doc work track requires generated docs."
                            {:type :mission/missing-docs
                             :mission/id mission-id})))
        _ (when (and system-track?
                     (not (seq (:system-map/entities system-result))))
            (throw (ex-info "System-map work track requires entity coverage."
                            {:type :mission/missing-system-map
                             :mission/id mission-id})))
        _ (when-not (seq report-artifacts)
            (throw (ex-info "artifacts required for report"
                            {:field :report/artifacts})))
        summary-text (or summary (:mission/title mission) "Mission report")
        report-config (cond-> {:mission/id mission-id
                               :agent/id agent-id
                               :report/artifacts report-artifacts
                               :mission.report/worklogs worklogs
                               :mission.report/tests tests}
                        summary-text (assoc :report/summary summary-text)
                        docgen-results (assoc :mission.report/docgen docgen-results)
                        system-result (assoc :mission.report/system-map system-result))
        {:keys [result]} (actions/execute!
                          {:conn conn
                           :action/ident :action/mission.report
                           :config report-config
                           :permissions permissions})
        tx {:mission/id mission-id
            :mission/report-path (:report/path result)
            :mission/report-summary summary-text
            :mission/report-generated-at (instant->date (now))}]
    (d/transact conn {:tx-data [tx]})
    (record-event! conn {:mission-id mission-id
                         :type :mission.event/report
                         :details result})
    (assoc (cond-> result
             docgen-results (assoc :docgen docgen-results)
             system-result (assoc :system-map system-result))
           :mission/id mission-id)))

(defn approve!
  [{:mission/keys [id]
    agent-id :agent/id
    :keys [approval permissions conn]}]
  (let [mission-id id
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id is required" {:field :agent/id})))
        conn (or conn (ensure-base!))
        mission (fetch-mission! conn mission-id)
        _ (when-not (= :mission.status/awaiting-review (:mission/status mission))
            (throw (ex-info "Mission must be awaiting review before approval."
                            {:type :mission/invalid-status
                             :mission/id mission-id
                             :mission/status (:mission/status mission)})))
        permissions (perms/normalize permissions)
        report (mission-report-payload! mission)
        approval (or approval {})
        approval-details (compact-map {:mission.approval/by (or (:by approval) agent-id)
                                       :mission.approval/role (:role approval)
                                       :mission.approval/notes (:notes approval)
                                       :mission.approval/steward? (if (contains? approval :steward?)
                                                                    (boolean (:steward? approval))
                                                                    true)})
        {:keys [result]} (actions/execute!
                          {:conn conn
                           :action/ident :action/mission.approve
                           :config {:mission/id mission-id
                                    :agent/id agent-id
                                    :mission/report report
                                    :mission/approval approval-details}
                           :permissions permissions})
        transition-result (transition! {:mission/id mission-id
                                        :agent/id agent-id
                                        :conn conn
                                        :target :mission.status/done
                                        :approval {:by (:mission.approval/by approval-details)
                                                   :role (:mission.approval/role approval-details)
                                                   :notes (:mission.approval/notes approval-details)
                                                   :steward? true}
                                        :permissions permissions})]
    (record-event! conn {:mission-id mission-id
                         :type :mission.event/approve
                         :details result})
    (assoc result
           :mission/id mission-id
           :mission/status (:mission/status transition-result))))

(defn archive!
  [{:mission/keys [id]
    agent-id :agent/id
    :keys [summary permissions conn]}]
  (let [mission-id id
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id is required" {:field :agent/id})))
        conn (or conn (ensure-base!))
        mission (fetch-mission! conn mission-id)
        status (:mission/status mission)
        _ (when-not (#{:mission.status/done :mission.status/abandoned} status)
            (throw (ex-info "Mission must be done/abandoned before archive."
                            {:type :mission/invalid-status
                             :mission/id mission-id
                             :mission/status status})))
        permissions (perms/normalize permissions)
        report (mission-report-payload! mission)
        report-path (:mission/report-path mission)
        approval-log (mission-approval-payload! mission)
        approval-details (:approval approval-log)
        approval-path (.getCanonicalPath (approval-log-file mission-id))
        artifacts (:artifacts report)
        _ (when-not (seq artifacts)
            (throw (ex-info "Mission report missing artifacts for archive."
                            {:type :mission/missing-artifacts
                             :mission/id mission-id})))
        _ (when (and (or (mission-has-js? mission)
                         (mission-has-apis? mission))
                     (not (js/revocation-present? {:mission-id mission-id})))
            (run-js-revoke-protocol! conn mission agent-id permissions))
        archive-artifacts (vec (concat artifacts
                                       [{:label "Mission report (edn)"
                                         :path report-path}
                                        {:label "Mission approval (edn)"
                                         :path approval-path}]))
        {:keys [result]} (actions/execute!
                          {:conn conn
                           :action/ident :action/mission.archive
                           :config {:mission/id mission-id
                                    :agent/id agent-id
                                    :mission/report report
                                    :mission/approval approval-details
                                    :archive/summary summary
                                    :archive/artifacts archive-artifacts}
                           :permissions permissions})
        transition-result (transition! {:mission/id mission-id
                                        :agent/id agent-id
                                        :conn conn
                                        :target :mission.status/archived
                                        :permissions permissions})]
    (record-event! conn {:mission-id mission-id
                         :type :mission.event/archive
                         :details result})
    (assoc result
           :mission/id mission-id
           :mission/status (:mission/status transition-result))))

(defn list-ready-missions
  [{:keys [queue-tags permissions conn]}]
  (let [conn (or conn (ensure-base!))
        permissions (perms/normalize permissions)
        config (cond-> {}
                 (seq queue-tags) (assoc :queue/tags (vec queue-tags)))
        {result :result} (actions/execute!
                          {:conn conn
                           :action/ident :action/mission.list-ready
                           :config config
                           :permissions permissions})]
    result))

(defn start-mission!
  [{mission-id :mission/id
    queue-tags :queue/tags
    agent-id :agent/id
    :keys [workspace-root locks permissions conn]}]
  (let [conn (or conn (ensure-base!))
        permissions (perms/normalize permissions)
        config (cond-> {:agent/id agent-id}
                 mission-id (assoc :mission/id mission-id)
                 (seq queue-tags) (assoc :queue/tags (vec queue-tags))
                 workspace-root (assoc :workspace/root workspace-root)
                 (seq locks) (assoc :locks locks))
        {result :result} (actions/execute!
                          {:conn conn
                           :action/ident :action/mission.start
                           :config config
                           :permissions permissions})]
    result))
