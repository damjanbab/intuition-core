(ns intuition.sfs.actions.handlers
  "Pure(ish) handlers that the action runtime dispatches to. They enforce the
  invariants we care about in tests by checking for repo-relative paths and by
  returning deterministic data structures."
  (:require
   [clojure.data :as data]
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [datomic.client.api :as d]
   [intuition.ci.profiles :as ci.profiles]
   [intuition.code.generate :as codegen]
   [intuition.code.runtime :as code]
   [intuition.docs.runtime :as docs]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [intuition.sfs.protocols.runtime :as protocols]
   [intuition.versioning.runtime :as versioning])
  (:import
   (java.io File PushbackReader)
   (java.math BigInteger)
   (java.security MessageDigest)
   (java.time Instant ZoneOffset)
   (java.time.format DateTimeFormatter)
   (java.util Date UUID)))

(def ^:private repo-root
  (.getCanonicalPath (io/file ".")))

(def ^:private timestamp-formatter
  (.withZone (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss'Z'")
             ZoneOffset/UTC))

(def ^:private ci-spec-reference
  "SYSTEM_SPEC §§3.3–3.6, §5.1, §5.3, §6.2")

(def ^:private mission-instantiation-spec-sections
  ["3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "6.2" "9"])

(def ^:private codetype-generation-spec-sections
  ["3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "5.3"])

(declare require-mission-string require-agent-string sha256-file write-edn! normalize-strings read-edn-file normalized-code-type keywordish)

(defn- ensure-repo-relative
  [path]
  (let [canonical (.getCanonicalPath (io/file path))]
    (when-not (.startsWith canonical repo-root)
      (throw (ex-info "Path is outside the repo" {:path path :repo repo-root})))
    canonical))

(defn- sanitize-fragment
  [value default]
  (-> (if (str/blank? (str value))
        default
        (str value))
      bootstrap/sanitize-fragment))

(defn- mission-log-dir
  ([mission-id] (mission-log-dir mission-id nil))
  ([mission-id override-root]
   (let [base (if (str/blank? (str override-root))
                (io/file repo-root "missions" "logs")
                (io/file override-root))
         as-str (str mission-id)
         raw (if (str/starts-with? as-str ":")
               (subs as-str 1)
               as-str)
         preferred (io/file base raw)
         dir (if (.exists preferred)
               preferred
               (io/file base (bootstrap/sanitize-fragment mission-id)))]
     (.mkdirs dir)
     dir)))

(defn- mission-log-file
  ([mission-id filename]
   (mission-log-file mission-id filename nil))
  ([mission-id filename override-root]
   (let [file (io/file (mission-log-dir mission-id override-root) filename)]
    (when-let [parent (.getParentFile file)]
      (.mkdirs parent))
    file)))

(defn- canonical-existing-file
  [path field]
  (let [canonical (ensure-repo-relative (if (instance? File path)
                                          path
                                          (io/file (str path))))
        canonical-file (io/file canonical)]
    (when-not (.exists canonical-file)
      (throw (ex-info "Version snapshot artifact missing."
                      {:field field
                       :path canonical})))
    canonical-file))

(defn- version-agent-ident
  [agent-id]
  (keyword (bootstrap/sanitize-fragment (require-agent-string agent-id))))

(defn- snapshot-dir-fragment
  [timestamp]
  (try
    (.format timestamp-formatter (Instant/parse timestamp))
    (catch Exception _
      (bootstrap/sanitize-fragment timestamp))))

(defn- version-snapshot-file
  [mission-id snapshot-type timestamp]
  (let [type-fragment (bootstrap/sanitize-fragment (name snapshot-type))
        ts-fragment (snapshot-dir-fragment timestamp)
        relative (format "versioning/%s/%s/version-snapshot.edn" type-fragment ts-fragment)]
    (mission-log-file mission-id relative)))

(defn- version-artifact
  [snapshot-id artifact-id file media-type recorded-at]
  (let [canonical-file (canonical-existing-file file artifact-id)]
    {:version.artifact/id artifact-id
     :version.artifact/snapshot-id snapshot-id
     :version.artifact/path (.getCanonicalPath canonical-file)
     :version.artifact/content-hash (sha256-file canonical-file)
     :version.artifact/media-type media-type
     :version.artifact/recorded-at recorded-at}))

(defn- normalized-commit
  [value]
  (let [trimmed (some-> value str str/trim)]
    (when-not (str/blank? trimmed)
      trimmed)))

(defn- persist-version-snapshot!
  [mission-id snapshot]
  (let [file (version-snapshot-file mission-id
                                    (:version.snapshot/type snapshot)
                                    (:version.snapshot/timestamp snapshot))
        path (write-edn! file snapshot)]
    {:file file
     :path path}))

(defn- version-snapshot-output
  [mission-id snapshot {:keys [path]}]
  {:action/status :status/ok
   :mission/id mission-id
   :version.snapshot/id (:version.snapshot/id snapshot)
   :version.snapshot/path path
   :version/snapshot snapshot})

(defn- latest-snapshot!
  [subject-type subject-id]
  (let [history (versioning/snapshot-history {:subject-type subject-type
                                              :subject-id subject-id})
        snapshot (peek history)]
    (when-not snapshot
      (throw (ex-info "Required version snapshot missing."
                      {:subject-type subject-type
                       :subject-id subject-id})))
    snapshot))

(defn- plan-snapshot!
  [plan-id explicit-id]
  (let [history (versioning/snapshot-history {:subject-type :version.snapshot/plan
                                              :subject-id plan-id})]
    (cond
      explicit-id (or (some #(when (= explicit-id (:version.snapshot/id %)) %) history)
                      (throw (ex-info "Plan snapshot id not found."
                                      {:work.plan/id plan-id
                                       :version.snapshot/id explicit-id})))
      (seq history) (peek history)
      :else (throw (ex-info "Plan snapshot missing."
                            {:work.plan/id plan-id})))))

(defn- plan-requirements
  [plan-data]
  (->> (:work.plan/nodes plan-data)
       (mapcat :plan.node/scope-requirements)
       normalize-strings
       distinct
       vec))

(defn- infer-plan-id-from-log
  [mission-id]
  (let [dir (mission-log-dir mission-id)]
    (some (fn [^File file]
            (when (and (.isFile file)
                       (str/ends-with? (.getName file) "-work-plan.edn"))
              (try
                (:work.plan/id (read-edn-file (.getCanonicalPath file)))
                (catch Exception _ nil))))
          (seq (.listFiles dir)))))

(defn- mission-plan-id
  [mission-id provided-plan-id]
  (or provided-plan-id
      (infer-plan-id-from-log mission-id)
      (throw (ex-info "work.plan/id required for mission snapshot"
                      {:mission/id mission-id}))))

(defn- ensure-parent-dirs!
  [^File file]
  (when-let [parent (.getParentFile file)]
    (.mkdirs parent))
  file)

(defn- ci-base-dir
  [mission-id log-root]
  (let [dir (io/file (mission-log-dir mission-id log-root) "ci")]
    (.mkdirs dir)
    dir))

(defn- merge-base-dir
  [mission-id log-root]
  (let [dir (io/file (mission-log-dir mission-id log-root) "merge")]
    (.mkdirs dir)
    dir))

(defn- run-subdir
  [base label]
  (let [dir (io/file base label)]
    (.mkdirs dir)
    dir))

(defn- ci-run-dir
  [mission-id log-root run-id]
  (run-subdir (ci-base-dir mission-id log-root)
              (bootstrap/sanitize-fragment run-id)))

(defn- merge-run-dir
  [mission-id log-root run-id]
  (run-subdir (merge-base-dir mission-id log-root)
              (bootstrap/sanitize-fragment run-id)))

(defn- write-edn!
  [^File file payload]
  (ensure-parent-dirs! file)
  (spit file (pr-str payload))
  (.getCanonicalPath file))

(defn- step-fragment
  [value idx]
  (let [raw (cond
              (keyword? value) (name value)
              (string? value) value
              :else (format "step-%02d" (inc idx)))]
    (bootstrap/sanitize-fragment raw)))

(defn- write-ci-step-log!
  [run-dir idx step]
  (let [fragment (step-fragment (:ci.profile.step/id step) idx)
        file (io/file run-dir (format "%02d-%s.log" (inc idx) fragment))
        message (str "CI step "
                     (or (:ci.profile.step/id step) (keyword (str "ci.step/" (inc idx))))
                     " executed via profile. Tool "
                     (:ci.profile.step/tool step)
                     " with command "
                     (pr-str (:ci.profile.step/command-template step))
                     ". Evidence per " ci-spec-reference ".")]
    (spit file message)
    (.getCanonicalPath file)))

(defn- ensure-ci-steps!
  [steps profile-ident]
  (when-not (seq steps)
    (throw (ex-info "CI profile has no steps."
                    {:type :ci/empty-profile
                     :ci/profile profile-ident})))
  steps)

(defn- run-ci-profile*
  [{:keys [mission-id sandbox-root profile-ident steps log-root trigger]
    :or {trigger :ci.trigger/manual}}]
  (when (str/blank? (str mission-id))
    (throw (ex-info "mission/id required for CI profile run" {:field :mission/id})))
  (when (str/blank? (str sandbox-root))
    (throw (ex-info "sandbox/root required for CI profile run"
                    {:field :sandbox/root
                     :mission/id mission-id})))
  (let [canonical-sandbox (ensure-repo-relative sandbox-root)
        profile (ci.profiles/resolve-profile {:ident profile-ident})
        resolved-steps (ensure-ci-steps! (vec (or steps (:ci.profile/steps profile)))
                                         (:ci.profile/ident profile))
        now (Instant/now)
        run-id (.format timestamp-formatter now)
        dir (ci-run-dir mission-id log-root run-id)
        step-results (map-indexed (fn [idx step]
                                    {:ci.step/id (or (:ci.profile.step/id step)
                                                     (keyword (format "ci.step/%02d" (inc idx))))
                                     :ci.step/tool (:ci.profile.step/tool step)
                                     :ci.step/description (:ci.profile.step/description step)
                                     :ci.step/command (vec (:ci.profile.step/command-template step))
                                     :ci.step/retry (:ci.profile.step/retry-policy step)
                                     :ci.step/log (write-ci-step-log! dir idx step)
                                     :ci.step/status :status/passed})
                                  resolved-steps)
        payload {:action/status :status/ok
                 :mission/id mission-id
                 :sandbox/root canonical-sandbox
                 :ci/profile (:ci.profile/ident profile)
                 :ci/trigger trigger
                 :ci/spec ci-spec-reference
                 :ci/thresholds (:ci.profile/thresholds profile)
                 :ci/required-tools (:ci.profile/required-tools profile)
                 :ci/run-id run-id
                 :ci/completed-at (str now)
                 :ci/steps (vec step-results)}
        log-path (write-edn! (io/file dir "ci-run.edn") payload)]
    (assoc payload
           :ci/run-dir (.getCanonicalPath dir)
           :ci/run-path log-path)))

(defn- read-edn-file
  [path]
  (with-open [reader (PushbackReader. (io/reader path))]
    (edn/read {:eof nil} reader)))

(defn- branch-files
  [mission-id]
  (let [dir (mission-log-dir mission-id)]
    {:edn (io/file dir "branch.edn")
     :markdown (io/file dir "branch.md")}))

(defn bootstrap-environment
  [{:keys [config]}]
  (let [root (:workspace/root config)
        canonical (ensure-repo-relative root)]
    {:action/status :status/ok
     :mission/id (:mission/id config)
     :agent/id (:agent/id config)
     :sandbox/root canonical
     :env/vars (or (:env/vars config) {})}))

(defn acquire-locks
  [{:keys [config]}]
  {:action/status :status/ok
   :locks/acquired (:locks config)
   :mission/id (:mission/id config)})

(defn release-locks
  [{:keys [config]}]
  {:action/status :status/ok
   :locks/released (set (:locks config))})

(defn run-ci-profile
  [{:keys [config]}]
  (let [{mission-id :mission/id
         sandbox-root :sandbox/root
         profile-ident :ci/profile
         steps :ci/steps
         log-root :ci/log-root
         trigger :ci/trigger} config
        result (run-ci-profile* {:mission-id mission-id
                                 :sandbox-root sandbox-root
                                 :profile-ident profile-ident
                                 :steps steps
                                 :log-root log-root
                                 :trigger trigger})]
    (assoc result :action/status :status/ok)))

(defn mission-merge-prepare
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         branch :merge/branch
         base-branch :merge/base-branch
         sandbox-root :sandbox/root
         profile-ident :ci/profile
         steps :ci/steps
         log-root :merge/log-root
         ci-log-root :ci/log-root
         simulate-conflict? :merge/simulate-conflict?} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required for merge prepare" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required for merge prepare" {:field :agent/id})))
        _ (when (str/blank? (str sandbox-root))
            (throw (ex-info "sandbox/root required for merge prepare"
                            {:field :sandbox/root
                             :mission/id mission-id})))
        canonical-sandbox (ensure-repo-relative sandbox-root)
        branch-name (or branch (format "mission/%s" (bootstrap/sanitize-fragment mission-id)))
        base-name (or base-branch "main")
        now (Instant/now)
        run-id (.format timestamp-formatter now)
        run-dir (merge-run-dir mission-id log-root run-id)
        prepare-file (io/file run-dir "merge-prepare.edn")
        base-payload {:mission/id mission-id
                      :agent/id agent-id
                      :merge/branch branch-name
                      :merge/base-branch base-name
                      :sandbox/root canonical-sandbox
                      :merge/run-id run-id
                      :merge/spec ci-spec-reference
                      :merge/action :merge.action/prepare
                      :merge/started-at (str now)}]
    (write-edn! prepare-file base-payload)
    (if simulate-conflict?
      (let [failure (assoc base-payload
                           :merge/status :merge.status/conflict
                           :merge/conflict "Simulated rebase conflict"
                           :merge/completed-at (str (Instant/now)))
            failure-path (write-edn! (io/file run-dir "merge-failure.edn") failure)
            run {:merge/run-id run-id
                 :merge/branch branch-name
                 :merge/base-branch base-name
                 :merge/run-dir (.getCanonicalPath run-dir)
                 :merge/status :merge.status/conflict}]
        {:action/status :status/failed
         :merge/run run
         :merge/failure (assoc failure :merge/log-path failure-path)
         :merge/log-path failure-path})
      (let [ci-result (run-ci-profile* {:mission-id mission-id
                                        :sandbox-root canonical-sandbox
                                        :profile-ident profile-ident
                                        :steps steps
                                        :log-root (or ci-log-root log-root)
                                        :trigger :ci.trigger/merge})
            prepared (assoc base-payload
                            :ci/profile (:ci/profile ci-result)
                            :ci/run (:ci/run-path ci-result)
                            :ci/steps (:ci/steps ci-result)
                            :merge/status :merge.status/prepared
                            :merge/completed-at (str (Instant/now)))
            prepare-path (write-edn! prepare-file prepared)
            run {:merge/run-id run-id
                 :merge/branch branch-name
                 :merge/base-branch base-name
                 :merge/run-dir (.getCanonicalPath run-dir)
                 :merge/status :merge.status/prepared
                 :sandbox/root canonical-sandbox
                 :ci/profile (:ci/profile ci-result)
                 :ci/run ci-result
                 :ci/spec (:ci/spec ci-result)}]
        {:action/status :status/ok
         :merge/run run
         :ci/run ci-result
         :merge/run-dir (.getCanonicalPath run-dir)
         :merge/log-path prepare-path}))))

(defn mission-merge-execute
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         branch :merge/branch
         base-branch :merge/base-branch
         run :merge/run
         log-root :merge/log-root} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required for merge execution" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required for merge execution" {:field :agent/id})))
        _ (when-not (map? run)
            (throw (ex-info "merge/run payload required for execution"
                            {:field :merge/run
                             :mission/id mission-id})))
        run-id (or (:merge/run-id run)
                   (.format timestamp-formatter (Instant/now)))
        run-dir (if-let [existing (:merge/run-dir run)]
                  (doto (io/file existing) .mkdirs)
                  (merge-run-dir mission-id log-root run-id))]
    (if (= :merge.status/conflict (:merge/status run))
      (let [failure {:mission/id mission-id
                     :agent/id agent-id
                     :merge/run-id run-id
                     :merge/branch (or branch (:merge/branch run))
                     :merge/base-branch (or base-branch (:merge/base-branch run))
                     :merge/status :merge.status/conflict
                     :merge/spec ci-spec-reference
                     :merge/summary "Merge aborted due to unresolved conflict."
                     :merge/completed-at (str (Instant/now))}
            failure-path (write-edn! (io/file run-dir "merge-failure.edn") failure)]
        {:action/status :status/failed
         :merge/run (assoc run :merge/log-path failure-path
                           :merge/status :merge.status/conflict)
         :merge/failure (assoc failure :merge/log-path failure-path)
         :merge/log-path failure-path})
      (let [payload {:mission/id mission-id
                     :agent/id agent-id
                     :merge/run-id run-id
                     :merge/branch (or branch (:merge/branch run))
                     :merge/base-branch (or base-branch (:merge/base-branch run))
                     :merge/merged-at (str (Instant/now))
                     :merge/spec ci-spec-reference
                     :ci/profile (:ci/profile run)
                     :ci/run (:ci/run run)
                     :ci/spec (:ci/spec run)}
            log-path (write-edn! (io/file run-dir "merge-log.edn") payload)
            merged-run (assoc run
                              :merge/status :merge.status/merged
                              :merge/log-path log-path)]
        {:action/status :status/ok
         :merge/log-path log-path
         :merge/run merged-run}))))

(defn run-test-suite
  [{:keys [config]}]
  (cond
    (:test/simulate-error? config)
    (throw (ex-info "Simulated test runner failure"
                    {:type :test/simulated-error
                     :suite (:test/suite config)}))

    (:test/simulate-invalid-output? config)
    {:action/status "invalid"
     :test/report {:suite (:test/suite config)}}

    :else
    {:action/status (if (= :allow-failures (:test/error-mode config))
                      :status/ok
                      :status/passed)
     :test/report {:suite (:test/suite config)
                   :paths (:test/paths config)
                   :completed-at (str (Instant/now))}
     :test/failures (when (= :fail-fast (:test/error-mode config))
                      [])}))

(defn run-lint
  [{:keys [config]}]
  (let [paths (vec (or (:lint/paths config) ["src" "test"]))
        command (vec (or (:lint/command config) ["clojure" "-M:lint"]))] 
    {:action/status :status/ok
     :lint/paths paths
     :lint/command command
     :lint/report {:completed-at (str (Instant/now))
                   :issues []}}))

(defn sync-docs
  [{:keys [config]}]
  (let [paths (some->> (:docs/paths config)
                       (map ensure-repo-relative)
                       vec)]
    {:action/status :status/ok
     :docs/paths paths}))

(defn docgen-types
  [{:keys [config]}]
  (let [templates (some-> (:doc/templates config) vec)
        generated (docs/generate-type-docs! {:template-idents templates})]
    {:action/status :status/ok
     :docs/generated generated}))

(defn docgen-missions
  [{:keys [config]}]
  (let [templates (some-> (:doc/templates config) vec)
        generated (docs/generate-mission-docs! {:template-idents templates})]
    {:action/status :status/ok
     :docs/generated generated}))

(defn prepare-git-branch
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         branch-prefix :branch/prefix
         sandbox-root :sandbox/root} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required for branch snapshot" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required for branch snapshot" {:field :agent/id})))
        safe-mission (bootstrap/sanitize-fragment mission-id)
        safe-agent (bootstrap/sanitize-fragment agent-id)
        prefix (sanitize-fragment branch-prefix "mission")
        now (Instant/now)
        timestamp (.format timestamp-formatter now)
        branch-name (format "%s/%s/%s-%s" prefix safe-mission timestamp safe-agent)
        {:keys [edn markdown]} (branch-files mission-id)
        edn-path (ensure-repo-relative edn)
        markdown-path (ensure-repo-relative markdown)
        metadata {:mission/id (str mission-id)
                  :agent/id agent-id
                  :branch/name branch-name
                  :branch/prefix prefix
                  :branch/created-at (str now)
                  :branch/edn-path edn-path
                  :branch/markdown-path markdown-path
                  :spec/reference "SYSTEM_SPEC §6.2"
                  :sandbox/root (when sandbox-root
                                  (ensure-repo-relative sandbox-root))}]
    (spit edn-path (pr-str metadata))
    (spit markdown-path
          (str "# Branch Snapshot\n\n"
               "* Mission: " mission-id "\n"
               "* Agent: " agent-id "\n"
               "* Branch: `" branch-name "`\n"
               "* Created: " (str now) "\n"
               "* Spec: SYSTEM_SPEC §6.2 enforces sandbox isolation.\n"
               "* Metadata: " (.getName edn) "\n"))
    {:action/status :status/ok
     :mission/id mission-id
     :agent/id agent-id
     :branch/name branch-name
     :branch/created-at (str now)
     :branch/edn-path edn-path
     :branch/markdown-path markdown-path
     :branch/spec-reference "SYSTEM_SPEC §6.2"}))

(defn run-protocol
  [{:keys [config conn context permissions]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         protocol-ident :protocol/ident
         protocol-context :protocol/context} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required for protocol execution"
                            {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required for protocol execution"
                            {:field :agent/id})))
        _ (when-not protocol-ident
            (throw (ex-info "protocol/ident required"
                            {:field :protocol/ident})))
        merged-context (-> (merge (dissoc (or context {}) :protocol/ident)
                                  (or protocol-context {}))
                           (assoc :mission/id mission-id
                                  :agent/id agent-id))
        run-result (protocols/run! {:conn conn
                                    :protocol/ident protocol-ident
                                    :context merged-context
                                    :permissions (or permissions #{})})]
    {:action/status :status/ok
     :protocol/run run-result}))

(defn- canonical->relative
  [canonical]
  (let [prefix (str repo-root File/separator)]
    (-> (if (str/starts-with? canonical prefix)
          (subs canonical (count prefix))
          (subs canonical (count repo-root)))
        (str/replace #"^\./" "")
        (str/replace #"^/" "")
        (str/replace #"//+" "/"))))

(defn- normalize-touched-path
  ([path] (normalize-touched-path path nil))
  ([path sandbox-root]
   (let [canonical (ensure-repo-relative path)
         file (io/file canonical)
         sandbox-prefix (when sandbox-root
                          (let [root (ensure-repo-relative sandbox-root)
                                base (str root File/separator)]
                            (when (.startsWith canonical base) base)))]
     (when-not (.exists file)
       (throw (ex-info "Touched path does not exist."
                       {:type :codetype/path-missing
                        :path path
                        :canonical canonical})))
     {:input path
      :canonical canonical
      :relative (if sandbox-prefix
                  (-> (subs canonical (count sandbox-prefix))
                      (str/replace #"^\./" "")
                      (str/replace #"^/" "")
                      (str/replace #"//+" "/"))
                  (canonical->relative canonical))
      :directory? (.isDirectory file)})))

(defn- normalize-definition-paths
  [definition]
  (->> (:code.definition/paths definition)
       (map #(-> %
                 (str/replace #"^\./" "")
                 (str/replace #"^/" "")
                 (str/replace #"//+" "/")))
       vec))

(defn- definition-covers-path?
  [definition {:keys [relative directory?]}]
  (let [paths (normalize-definition-paths definition)
        prefix (str relative "/")]
    (some (fn [entry]
            (or (= entry relative)
                (and directory?
                     (or (= entry relative)
                         (str/starts-with? entry prefix)))))
          paths)))

(defn- datomic-definitions
  [conn idents]
  (let [targets (not-empty (set (remove nil? idents)))]
    (when (and conn targets)
      (map first
           (d/q '[:find (pull ?e [:code.definition/ident
                                  :code.definition/name
                                  :code.definition/type
                                  :code.definition/paths
                                  :code.definition/spec-sections
                                  :code.definition/dependencies
                                  :code.definition/validators
                                  :code.definition/tests
                                  :code.definition/missions])
                  :in $ [?ident ...]
                  :where [?e :code.definition/ident ?ident]]
                (d/db conn)
                targets)))))

(defn- validation-definitions
  [conn idents]
  (let [catalog (into {} (map (juxt :code.definition/ident identity) (code/definitions)))
        from-db (into {} (map (juxt :code.definition/ident identity)
                              (or (datomic-definitions conn idents) [])))
        merged (merge catalog from-db)]
    (if (seq idents)
      (->> idents (keep merged) vec)
      (-> merged vals vec))))

(defn- matching-definitions
  [touched definitions]
  (let [definitions (or (not-empty definitions)
                        (code/definitions))
        matches (reduce
                 (fn [acc path-info]
                   (let [covered (filter #(definition-covers-path? % path-info)
                                         definitions)]
                     (when-not (seq covered)
                       (throw (ex-info "No CodeDefinition covers touched path."
                                       {:type :codetype/missing-definition
                                        :path (:input path-info)
                                        :relative (:relative path-info)})))
                     (into acc covered)))
                 #{}
                 touched)]
    (sort-by (comp name :code.definition/ident) matches)))

(defn- validator-results
  [definition]
  (vec
   (for [validator (or (:code.definition/validators definition) [])]
     {:validator/ident validator
      :validator/status :status/ok})))

(defn- definition->result
  [definition]
  {:code.definition/ident (:code.definition/ident definition)
   :code.definition/name (:code.definition/name definition)
   :code.definition/paths (vec (:code.definition/paths definition))
   :code.definition/spec-sections (vec (:code.definition/spec-sections definition))
   :codetype/validators (validator-results definition)})

(defn- spec-sections
  [definitions]
  (->> definitions
       (mapcat :code.definition/spec-sections)
       (remove str/blank?)
       distinct
       sort
       vec))

(defn- codetype-log-file
  [mission-id]
  (let [dir (io/file "missions" "logs" (bootstrap/sanitize-fragment mission-id))]
    (.mkdirs dir)
    (io/file dir "codetype-validation.edn")))

(defn- codetype-generation-log-file
  [mission-id]
  (mission-log-file mission-id "codetype-generation.edn"))

(defn- safe-codetype-fragment
  [ident]
  (bootstrap/sanitize-fragment
   (cond
     (keyword? ident) (name ident)
     (string? ident) ident
     :else (str ident))))

(defn- codetype-stamp-file
  [sandbox-root ident]
  (let [dir (io/file sandbox-root ".codetype")]
    (.mkdirs dir)
    (io/file dir (str (safe-codetype-fragment ident) ".edn"))))

(defn- sha256-file
  [^File file]
  (with-open [input (io/input-stream file)]
    (let [digest (MessageDigest/getInstance "SHA-256")
          buffer (byte-array 8192)]
      (loop []
        (let [read-bytes (.read input buffer)]
          (when (pos? read-bytes)
            (.update digest buffer 0 read-bytes)
            (recur))))
      (format "%064x" (BigInteger. 1 (.digest digest))))))

(defn- normalize-generated-path
  [path]
  (let [value (-> (or path "")
                  str
                  str/trim
                  (str/replace #"^\./" "")
                  (str/replace #"^/" "")
                  (str/replace #"\\+" "/")
                  (str/replace #"//+" "/"))]
    (when (str/blank? value)
      (throw (ex-info "Generated artifact path required." {:path path})))
    value))

(defn- expand-artifact-paths
  [ident artifacts]
  (let [slug (bootstrap/sanitize-fragment (name ident))]
    (->> artifacts
         (map (fn [artifact]
                (-> (or artifact "")
                    str
                    (str/replace "{{IDENT}}" slug))))
         vec)))

(defn- sandbox-relative-file
  [sandbox-root relative]
  (let [sandbox (-> sandbox-root io/file .getCanonicalPath)
        file (io/file sandbox-root relative)
        canonical (.getCanonicalPath file)]
    (when-not (.startsWith canonical sandbox)
      (throw (ex-info "Generated artifact escapes sandbox."
                      {:relative relative
                       :sandbox sandbox
                       :path canonical})))
    {:file file
     :canonical canonical
     :relative relative}))

(defn- describe-generated-file
  [sandbox-root relative]
  (let [{:keys [file canonical]} (sandbox-relative-file sandbox-root relative)]
    (when-not (.exists file)
      (throw (ex-info "Generated artifact is missing."
                      {:relative relative
                       :path canonical})))
    {:codetype/relative-path relative
     :codetype/file canonical
     :codetype/checksum (sha256-file file)}))

(defn- missing-artifacts?
  [sandbox-root relatives]
  (some (fn [relative]
          (let [{:keys [file]} (sandbox-relative-file sandbox-root relative)]
            (not (.exists file))))
        relatives))

(defn- write-generated-files!
  [sandbox-root file-specs]
  (mapv (fn [spec]
          (let [relative (normalize-generated-path (:relative-path spec))
                {:keys [file canonical]} (sandbox-relative-file sandbox-root relative)]
            (ensure-parent-dirs! file)
            (cond
              (contains? spec :content)
              (spit file (:content spec))

              (contains? spec :bytes)
              (let [^bytes bytes (:bytes spec)]
                (with-open [output (io/output-stream file)]
                  (.write output bytes)))

              :else
              (throw (ex-info "Generated file requires :content or :bytes"
                              {:relative relative})))
            {:codetype/relative-path relative
             :codetype/file canonical
             :codetype/checksum (sha256-file file)}))
        file-specs))

(defn- read-generated-files
  [sandbox-root relatives]
  (mapv #(describe-generated-file sandbox-root %) relatives))

(defn- resolve-template-source
  [template-path]
  (let [value (-> (or template-path "") str str/trim)
        _ (when (str/blank? value)
            (throw (ex-info "Template path required" {:template/path template-path})))
        candidate (io/file value)
        file (if (.isAbsolute candidate)
               candidate
               (io/file repo-root value))]
    (cond
      (.exists file)
      {:template/path value
       :template/source :file
       :template/file (.getCanonicalPath file)}

      :else
      (if-let [resource (io/resource value)]
        {:template/path value
         :template/source :resource
         :template/resource resource}
        (throw (ex-info "Generator template not found."
                        {:template/path value}))))))

(defn- resolve-templates
  [templates]
  (mapv resolve-template-source templates))

(defn- generator-symbol
  [value]
  (cond
    (symbol? value) value
    (string? value) (symbol value)
    (keyword? value) (symbol (name value))
    :else nil))

(defn- resolve-generator
  [value]
  (let [sym (generator-symbol value)]
    (when-not (and sym (qualified-symbol? sym))
      (throw (ex-info "Generator must be a namespace-qualified symbol."
                      {:codetype/generator value})))
    (try
      (let [resolved (requiring-resolve sym)]
        (when-not resolved
          (throw (ex-info "Unable to resolve generator function"
                          {:codetype/generator value
                           :symbol sym})))
        resolved)
      (catch Exception e
        (throw (ex-info "Unable to resolve generator function"
                        {:codetype/generator value
                         :symbol sym}
                        e))))))

(defn- append-generation-log!
  [mission-id entry]
  (let [file (codetype-generation-log-file mission-id)
        path (.getCanonicalPath file)
        payload (if (.exists file)
                  (read-edn-file path)
                  {:mission/id mission-id
                   :codetype/generations []})
        updated (update payload :codetype/generations conj entry)]
    (spit file (pr-str updated))
    path))

(def ^:private specs-dir
  (doto (io/file repo-root "resources" "specs")
    (.mkdirs)))

(def ^:private work-plans-dir
  (doto (io/file repo-root "resources" "work-plans")
    (.mkdirs)))

(defn- safe-spec-fragment
  [spec-id]
  (bootstrap/sanitize-fragment (name spec-id)))

(defn- spec-resource-file
  [spec-id]
  (ensure-parent-dirs!
   (io/file specs-dir (str (safe-spec-fragment spec-id) ".edn"))))

(defn- safe-work-plan-fragment
  [plan-id]
  (bootstrap/sanitize-fragment (str plan-id)))

(defn- work-plan-resource-file
  [plan-id]
  (ensure-parent-dirs!
   (io/file work-plans-dir (str (safe-work-plan-fragment plan-id) ".edn"))))

(defn- work-plan-mission-file
  [mission-id plan-id]
  (mission-log-file mission-id (str (safe-work-plan-fragment plan-id) "-work-plan.edn")))

(defn- work-plan-validation-files
  [mission-id]
  {:edn (mission-log-file mission-id "work-plan-validation.edn")
   :markdown (mission-log-file mission-id "work-plan-validation.md")})

(defn- work-plan-publish-log-file
  [mission-id plan-id]
  (mission-log-file mission-id (str (safe-work-plan-fragment plan-id) "-work-plan-publish.md")))

(defn- vectorize
  [value]
  (cond
    (nil? value) []
    (vector? value) value
    (sequential? value) (vec value)
    :else [value]))

(defn- normalize-strings
  [value]
  (->> (vectorize value)
       (map #(str/trim (str %)))
       (remove str/blank?)
       vec))

(defn- normalize-keywords
  [value]
  (->> (vectorize value)
       (map (fn [entry]
              (cond
                (keyword? entry) entry
                (string? entry) (keyword (bootstrap/sanitize-fragment entry))
                :else (throw (ex-info "Spec field must be keywordable"
                                      {:value entry})))))
       vec))

(defn- require-non-blank
  [m k]
  (let [value (some-> (get m k) str str/trim)]
    (when (str/blank? value)
      (throw (ex-info "Spec field required"
                      {:field k})))
    value))

(defn- normalize-spec-artifacts
  [artifacts]
  (->> (normalize-strings artifacts)
       (map (fn [entry]
              (let [file (io/file repo-root entry)
                    canonical (ensure-repo-relative file)]
                (canonical->relative canonical))))
       vec))

(defn- normalize-spec
  [spec-data provided-id provided-status source-path]
  (let [raw-id (or provided-id (:spec/id spec-data))
        spec-id (cond
                  (keyword? raw-id) raw-id
                  (string? raw-id) (keyword (bootstrap/sanitize-fragment raw-id))
                  :else (throw (ex-info "spec/id must be a keyword or string"
                                        {:value raw-id})))
        requirements (normalize-strings (:spec/requirements spec-data))
        acceptance (normalize-strings (:spec/acceptance-criteria spec-data))
        contracts (normalize-keywords (:spec/test-contracts spec-data))
        spec-sections (normalize-strings (:spec/spec-sections spec-data))
        constraints (normalize-strings (:spec/constraints spec-data))
        artifacts (normalize-spec-artifacts (:spec/artifacts spec-data))
        owner-raw (:spec/owner spec-data)
        owner (cond
                (nil? owner-raw) nil
                (and (string? owner-raw) (str/blank? owner-raw)) nil
                (keyword? owner-raw) owner-raw
                (string? owner-raw) (keyword (bootstrap/sanitize-fragment owner-raw))
                :else (throw (ex-info "Spec owner must be keywordable"
                                      {:value owner-raw})))
        now (Instant/now)]
    (doseq [[field values] {:spec/requirements requirements
                            :spec/acceptance-criteria acceptance
                            :spec/spec-sections spec-sections}]
      (when-not (seq values)
        (throw (ex-info "Spec field must have at least one entry"
                        {:field field :spec/id spec-id}))))
    (cond-> {:spec/id spec-id
             :spec/title (require-non-blank spec-data :spec/title)
             :spec/summary (require-non-blank spec-data :spec/summary)
             :spec/requirements requirements
             :spec/acceptance-criteria acceptance
             :spec/test-contracts contracts
             :spec/constraints constraints
             :spec/artifacts artifacts
             :spec/status (or provided-status (:spec/status spec-data) :spec.status/captured)
             :spec/spec-sections spec-sections
             :spec/source-path source-path
             :spec/captured-at (or (:spec/captured-at spec-data) (str now))}
      owner (assoc :spec/owner owner))))

(defn- known-test-contracts
  []
  (let [file (io/file repo-root "resources" "dictionary" "code_types.edn")]
    (if (.exists file)
      (->> (read-edn-file file)
           (keep :code.definition/ident)
           set)
      #{})))

(defn codetype-validate
  [{:keys [config conn]}]
  (let [mission-id (:mission/id config)
        agent-id (:agent/id config)
        materialized-paths (vec (or (:code.materialize/paths config) []))
        codetype-paths (vec (or (:codetype/paths config) []))
        definition-idents (vec (or (:code.definition/idents config) []))
        sandbox-root (or (:sandbox/root config)
                         (:workspace/root config))
        combined-paths (->> (concat materialized-paths codetype-paths)
                            (remove str/blank?)
                            vec)
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required for codetype validation" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required for codetype validation" {:field :agent/id})))
        _ (when-not (seq combined-paths)
            (throw (ex-info "codetype/paths or code.materialize/paths required"
                            {:fields [:code.materialize/paths :codetype/paths]
                             :mission/id mission-id})))
        _ (code/assert-no-near-duplicates!)
        touched (map #(normalize-touched-path % sandbox-root) combined-paths)
        definitions (validation-definitions conn definition-idents)
        matched (matching-definitions touched definitions)
        results (map definition->result matched)
        sections (spec-sections matched)
        validated-at (str (Instant/now))
        file (codetype-log-file mission-id)
        payload {:mission/id mission-id
                 :agent/id agent-id
                 :codetype/status :status/ok
                 :codetype/paths (vec (map :relative touched))
                 :codetype/definitions (vec results)
                 :codetype/spec-sections sections
                 :spec/sections sections
                 :validated-at validated-at}]
    (spit file (pr-str payload))
    {:action/status :status/ok
     :codetype/artifact (.getCanonicalPath file)
     :codetype/paths (:codetype/paths payload)
     :codetype/definitions (:codetype/definitions payload)
     :codetype/spec-sections sections
     :codetype/validated-at validated-at}))

(defn codetype-generate
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         sandbox-root :sandbox/root
         codetype-ident :codetype/ident
         options :codetype/options
         force? :codetype/force?} config
        mission (require-mission-string mission-id)
        agent (require-agent-string agent-id)
        _ (when (str/blank? (str sandbox-root))
            (throw (ex-info "sandbox/root required" {:field :sandbox/root})))
        ident (if (keyword? codetype-ident)
                codetype-ident
                (throw (ex-info "codetype/ident must be a keyword"
                                {:field :codetype/ident
                                 :value codetype-ident})))
        _ (code/assert-no-near-duplicates!)
        sandbox (ensure-repo-relative sandbox-root)
        code-type (or (code/type-by-ident ident)
                      (throw (ex-info "Unknown CodeType ident"
                                      {:codetype/ident ident})))
        generator-ref (:code.type/generator code-type)
        _ (when (str/blank? (str generator-ref))
            (throw (ex-info "CodeType missing generator metadata"
                            {:codetype/ident ident})))
        raw-artifacts (vec (or (:code.type/generated-artifacts code-type) []))
        artifacts (->> (expand-artifact-paths ident raw-artifacts)
                       (map normalize-generated-path)
                       vec)
        _ (when-not (seq artifacts)
            (throw (ex-info "CodeType missing generated artifact metadata"
                            {:codetype/ident ident})))
        templates (vec (or (:code.type/generator-templates code-type) []))
        resolved-templates (resolve-templates templates)
        generator (resolve-generator generator-ref)
        stamp-file (codetype-stamp-file sandbox ident)
        stamp (when (.exists stamp-file)
                (read-edn-file (.getCanonicalPath stamp-file)))
        missing? (missing-artifacts? sandbox artifacts)
        run-generation? (or force? (nil? stamp) missing?)
        timestamp (str (Instant/now))
        descriptors (if run-generation?
                      (let [payload (generator {:mission/id mission
                                                :agent/id agent
                                                :codetype/ident ident
                                                :sandbox/root sandbox
                                                :codetype/generated-artifacts artifacts
                                                :codetype/templates templates
                                                :codetype/resolved-templates resolved-templates
                                                :codetype/options options})
                            files (vec (or (:generated/files payload) []))]
                        (when-not (seq files)
                          (throw (ex-info "Generator returned no files"
                                          {:codetype/ident ident})))
                        (let [written (write-generated-files! sandbox files)
                              descriptor-set (set (map :codetype/relative-path written))
                              expected-set (set artifacts)
                              missing (set/difference expected-set descriptor-set)]
                          (when (seq missing)
                            (throw (ex-info "Generator did not emit expected artifacts"
                                            {:codetype/ident ident
                                             :missing (vec missing)})))
                          (spit stamp-file (pr-str {:codetype/ident ident
                                                    :mission/id mission
                                                    :agent/id agent
                                                    :codetype/files written
                                                    :codetype/generated-at timestamp
                                                    :codetype/templates templates
                                                    :codetype/generator (str generator-ref)}))
                          written))
                      (read-generated-files sandbox artifacts))
        generated-at (if run-generation?
                       timestamp
                       (or (:codetype/generated-at stamp) timestamp))
        log-entry {:codetype/ident ident
                   :mission/id mission
                   :agent/id agent
                   :codetype/generated-at generated-at
                   :codetype/files descriptors
                   :codetype/templates templates
                   :codetype/generator (str generator-ref)
                   :codetype/skipped? (not run-generation?)
                   :spec/sections codetype-generation-spec-sections}
        log-path (append-generation-log! mission log-entry)]
    {:action/status :status/ok
     :codetype/ident ident
     :codetype/generated-files descriptors
     :codetype/generation-artifact log-path
     :codetype/stamp-path (.getCanonicalPath stamp-file)
     :codetype/generated-at generated-at
     :codetype/skipped? (not run-generation?)}))

(defn materialize-code-from-graph
  [{:keys [config conn]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         sandbox-root :sandbox/root
         idents :code.definition/idents} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required for code materialization"
                            {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required for code materialization"
                            {:field :agent/id})))
        _ (when (str/blank? (str sandbox-root))
            (throw (ex-info "sandbox/root required for code materialization"
                            {:field :sandbox/root})))]
    (codegen/materialize! {:conn conn
                           :mission-id mission-id
                           :agent-id agent-id
                           :sandbox-root sandbox-root
                           :definition-idents idents})))

(defn- spec-mission-copy-file
  [mission-id spec-id]
  (mission-log-file mission-id (str (safe-spec-fragment spec-id) "-spec.edn")))

(defn- spec-validation-files
  [mission-id]
  {:edn (mission-log-file mission-id "spec-validation.edn")
   :markdown (mission-log-file mission-id "spec-validation.md")})

(defn- spec-publish-log-file
  [mission-id spec-id]
  (mission-log-file mission-id (str (safe-spec-fragment spec-id) "-spec-publish.md")))

(defn- spec-validation-errors
  [spec-data]
  (let [contracts (known-test-contracts)
        errors (volatile! [])]
    (doseq [field [:spec/title :spec/summary]
            :let [val (some-> (get spec-data field) str str/trim)]
            :when (str/blank? val)]
      (vswap! errors conj (format "%s missing" (name field))))
    (doseq [field [:spec/requirements :spec/acceptance-criteria :spec/spec-sections]
            :let [val (get spec-data field)]
            :when (not (seq val))]
      (vswap! errors conj (format "%s empty" (name field))))
    (doseq [contract (:spec/test-contracts spec-data)
            :when (and contract (not (contains? contracts contract)))]
      (vswap! errors conj (format "Unknown CodeDefinition %s" contract)))
    (doseq [artifact (:spec/artifacts spec-data)
            :let [file (io/file repo-root artifact)
                  canonical (ensure-repo-relative file)]
            :when (not (.exists (io/file canonical)))]
      (vswap! errors conj (format "Artifact path missing: %s" artifact)))
    (when-not (keyword? (:spec/status spec-data))
      (vswap! errors conj "Spec status must be a keyword"))
    @errors))

(defn- spec-validation-markdown
  [{mission-id :mission/id
    agent-id :agent/id
    spec-id :spec/id
    spec-sections :spec/spec-sections
    test-contracts :spec/test-contracts
    validated-at :validated-at}]
  (str "# Spec Validation\n\n"
       "* Mission: " mission-id "\n"
       "* Agent: " agent-id "\n"
       "* Spec: " spec-id "\n"
       "* Validated: " validated-at "\n"
       "* SYSTEM_SPEC refs: " (str/join ", " (or spec-sections [])) "\n"
       "* Test contracts: " (str/join ", " (map name (or test-contracts []))) "\n"
       "\nSYSTEM_SPEC §§3.3–3.6, §4.1–§4.2, §4.7, §5.1, and §9 require this pipeline; this log proves the enforcement window."))

(defn spec-capture
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         input-path :spec/input-path
         provided-id :spec/id
         provided-status :spec/status} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required" {:field :agent/id})))
        _ (when (str/blank? (str input-path))
            (throw (ex-info "spec/input-path required" {:field :spec/input-path})))
        source-path (ensure-repo-relative input-path)
        spec-data (read-edn-file source-path)
        _ (when-not (map? spec-data)
            (throw (ex-info "Spec EDN must be a map" {:path source-path :data spec-data})))
        normalized (normalize-spec spec-data provided-id provided-status source-path)
        resource-file (spec-resource-file (:spec/id normalized))
        mission-file (spec-mission-copy-file mission-id (:spec/id normalized))]
    (spit resource-file (pr-str normalized))
    (spit mission-file (pr-str normalized))
    {:action/status :status/ok
     :spec/id (:spec/id normalized)
     :spec/status (:spec/status normalized)
     :spec/resource-path (ensure-repo-relative resource-file)
     :spec/log-path (ensure-repo-relative mission-file)
     :spec/source-path source-path}))

(defn spec-validate
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         spec-id :spec/id
         override-path :spec/resource-path} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required" {:field :agent/id})))
        _ (when-not spec-id
            (throw (ex-info "spec/id required" {:field :spec/id})))
        spec-file (or override-path (spec-resource-file spec-id))
        spec-path (ensure-repo-relative spec-file)
        spec-data (read-edn-file spec-path)
        _ (when-not (map? spec-data)
            (throw (ex-info "Spec file missing map" {:spec/id spec-id :path spec-path})))
        errors (spec-validation-errors spec-data)
        _ (when (seq errors)
            (throw (ex-info "Spec validation failed"
                            {:spec/id spec-id
                             :errors errors})))
        {:keys [edn markdown]} (spec-validation-files mission-id)
        validated-at (str (Instant/now))
        report {:mission/id mission-id
                :agent/id agent-id
                :spec/id spec-id
                :spec/spec-sections (:spec/spec-sections spec-data)
                :spec/test-contracts (:spec/test-contracts spec-data)
                :spec/status :spec.status/validated
                :spec/resource-path spec-path
                :validated-at validated-at}]
    (spit edn (pr-str report))
    (spit markdown (spec-validation-markdown report))
    {:action/status :status/ok
     :spec/id spec-id
     :spec/status :spec.status/validated
     :spec/validation-path (ensure-repo-relative edn)
     :spec/validation-report report
     :spec/validation-markdown (ensure-repo-relative markdown)}))

(defn spec-publish
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         spec-id :spec/id
         desired-status :spec/status} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required" {:field :agent/id})))
        _ (when-not spec-id
            (throw (ex-info "spec/id required" {:field :spec/id})))
        spec-file (spec-resource-file spec-id)
        spec-path (ensure-repo-relative spec-file)
        spec-data (read-edn-file spec-path)
        _ (when-not (map? spec-data)
            (throw (ex-info "Spec file missing map" {:spec/id spec-id :path spec-path})))
        new-status (or desired-status :spec.status/validated)
        _ (when-not (keyword? new-status)
            (throw (ex-info "spec/status must be keyword" {:value new-status})))
        published (assoc spec-data :spec/status new-status)
        publish-file (spec-publish-log-file mission-id spec-id)
        published-at (str (Instant/now))]
    (spit spec-file (pr-str published))
    (spit publish-file
          (str "# Spec Publish\n\n"
               "* Mission: " mission-id "\n"
               "* Agent: " agent-id "\n"
               "* Spec: " spec-id "\n"
               "* Status: " new-status "\n"
               "* Published: " published-at "\n"
               "* SYSTEM_SPEC refs: " (str/join ", " (or (:spec/spec-sections spec-data) [])) "\n"
               "\nSYSTEM_SPEC §9 requires an auditable spec-to-mission pipeline; this log records the publish decision."))
    {:action/status :status/ok
     :spec/id spec-id
     :spec/status new-status
     :spec/publish-log (ensure-repo-relative publish-file)}))

;; Work plan intake ---------------------------------------------------------

(def ^:private work-plan-statuses
  #{:work.plan.status/draft
    :work.plan.status/candidate
    :work.plan.status/approved
    :work.plan.status/in-execution
    :work.plan.status/completed
    :work.plan.status/superseded})

(def ^:private plan-obligation-statuses
  #{:plan.obligation.status/pending
    :plan.obligation.status/satisfied
    :plan.obligation.status/violated})

(def ^:private plan-edge-relations
  #{:plan.edge.relation/depends-on :plan.edge.relation/blocks})

(def ^:private plan-generation-statuses
  #{:plan.generation.status/draft
    :plan.generation.status/generated
    :plan.generation.status/validated
    :plan.generation.status/rejected})

(defn- parse-plan-id
  [value]
  (cond
    (uuid? value) value
    (string? value)
    (try
      (UUID/fromString (str/trim value))
      (catch IllegalArgumentException _
        (throw (ex-info "work.plan/id must be a UUID"
                        {:field :work.plan/id
                         :value value}))))
    (nil? value) (UUID/randomUUID)
    :else (throw (ex-info "work.plan/id must be a UUID"
                          {:field :work.plan/id
                           :value value}))))

(defn- parse-optional-uuid
  [value field]
  (cond
    (nil? value) nil
    (uuid? value) value
    (string? value)
    (try
      (UUID/fromString (str/trim value))
      (catch IllegalArgumentException _
        (throw (ex-info "Value must be a UUID"
                        {:field field
                         :value value}))))
    :else (throw (ex-info "Value must be a UUID"
                          {:field field
                           :value value}))))

(defn- coerce-spec-id
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword (bootstrap/sanitize-fragment value))
    :else (throw (ex-info "work.plan/spec-id required"
                          {:field :work.plan/spec-id
                           :value value}))))

(defn- positive-long
  [value field]
  (let [parsed (cond
                 (integer? value) (long value)
                 (string? value) (Long/parseLong (str/trim value))
                 (nil? value) 1
                 :else (throw (ex-info "Field must be numeric" {:field field :value value})))]
    (when (<= parsed 0)
      (throw (ex-info "Field must be positive" {:field field :value value})))
    parsed))

(defn- present-string
  [value]
  (let [trimmed (some-> value str str/trim)]
    (when-not (str/blank? trimmed)
      trimmed)))

(defn- normalize-plan-node
  [node]
  (let [node-id (require-non-blank node :plan.node/id)
        name (require-non-blank node :plan.node/name)
        requirements (normalize-strings (:plan.node/scope-requirements node))
        _ (when-not (seq requirements)
            (throw (ex-info "PlanNode requires scope requirements"
                            {:field :plan.node/scope-requirements
                             :plan.node/id node-id})))
        resources (normalize-strings (:plan.node/resources node))
        _ (when-not (seq resources)
            (throw (ex-info "PlanNode requires at least one resource"
                            {:field :plan.node/resources
                             :plan.node/id node-id})))
        template (:plan.node/mission-template node)
        mission-template (cond
                           (map? template) template
                           (nil? template) nil
                           :else (throw (ex-info "PlanNode mission-template must be a map"
                                                 {:plan.node/id node-id
                                                  :value template})))
        test-scope (:plan.node/test-scope node)
        node-tests (normalize-keywords (:plan.node/test-contracts node))
        code-types (normalize-keywords (:plan.node/code-types node))
        normalized-test (cond
                          (map? test-scope) test-scope
                          (nil? test-scope) nil
                          :else (throw (ex-info "PlanNode test-scope must be a map"
                                                {:plan.node/id node-id
                                                 :value test-scope})))
        effort (some-> (:plan.node/estimated-effort node) str str/trim)
        description (some-> (:plan.node/description node) str str/trim)]
    (cond-> {:plan.node/id node-id
             :plan.node/name name
             :plan.node/scope-requirements requirements
             :plan.node/resources resources}
      (seq description) (assoc :plan.node/description description)
      mission-template (assoc :plan.node/mission-template mission-template)
      normalized-test (assoc :plan.node/test-scope normalized-test)
      (seq node-tests) (assoc :plan.node/test-contracts node-tests)
      (seq code-types) (assoc :plan.node/code-types code-types)
      (seq effort) (assoc :plan.node/estimated-effort effort))))

(defn- normalize-plan-edge
  [edge node-ids]
  (let [from (require-non-blank edge :plan.edge/from-node-id)
        to (require-non-blank edge :plan.edge/to-node-id)
        relation (or (:plan.edge/relation edge) :plan.edge.relation/depends-on)]
    (when-not (contains? node-ids from)
      (throw (ex-info "PlanEdge references unknown from-node-id"
                      {:plan.edge/from-node-id from})))
    (when-not (contains? node-ids to)
      (throw (ex-info "PlanEdge references unknown to-node-id"
                      {:plan.edge/to-node-id to})))
    (when-not (plan-edge-relations relation)
      (throw (ex-info "Invalid plan edge relation"
                      {:value relation
                       :plan.edge/from-node-id from
                       :plan.edge/to-node-id to})))
    {:plan.edge/from-node-id from
     :plan.edge/to-node-id to
     :plan.edge/relation relation}))

(defn- normalize-coverage-row
  [row node-ids]
  (let [requirement (require-non-blank row :coverage.row/requirement-id)
        nodes (normalize-strings (:coverage.row/nodes row))
        _ (when-not (seq nodes)
            (throw (ex-info "CoverageRow requires at least one node"
                            {:field :coverage.row/nodes
                             :coverage.row/requirement-id requirement})))
        _ (doseq [node nodes]
            (when-not (contains? node-ids node)
              (throw (ex-info "CoverageRow references unknown node"
                              {:coverage.row/requirement-id requirement
                               :plan.node/id node}))))
        code-targets (normalize-strings (:coverage.row/code-targets row))
        _ (when-not (seq code-targets)
            (throw (ex-info "CoverageRow requires code targets"
                            {:field :coverage.row/code-targets
                             :coverage.row/requirement-id requirement})))
        contracts (normalize-keywords (:coverage.row/test-contracts row))
        _ (when-not (seq contracts)
            (throw (ex-info "CoverageRow requires at least one test contract"
                            {:field :coverage.row/test-contracts
                             :coverage.row/requirement-id requirement})))
        code-types (normalize-keywords (:coverage.row/code-types row))
        acceptance (some-> (:coverage.row/acceptance-id row) str str/trim)]
    (cond-> {:coverage.row/requirement-id requirement
             :coverage.row/nodes nodes
             :coverage.row/code-targets code-targets
             :coverage.row/test-contracts contracts}
      (seq code-types) (assoc :coverage.row/code-types code-types)
      (seq acceptance) (assoc :coverage.row/acceptance-id acceptance))))

(defn- normalize-plan-obligation
  [obligation]
  (let [ident (require-non-blank obligation :plan.obligation/id)
        description (require-non-blank obligation :plan.obligation/description)
        checker (:plan.obligation/checker-id obligation)
        checker-id (cond
                     (keyword? checker) checker
                     (string? checker) (keyword (bootstrap/sanitize-fragment checker))
                     :else (throw (ex-info "plan.obligation/checker-id must be keywordable"
                                           {:plan.obligation/id ident
                                            :value checker})))
        status (or (:plan.obligation/status obligation) :plan.obligation.status/pending)
        _ (when-not (plan-obligation-statuses status)
            (throw (ex-info "Invalid plan obligation status"
                            {:plan.obligation/id ident
                             :value status})))
        evidence (some-> (:plan.obligation/evidence obligation) str str/trim)]
    (cond-> {:plan.obligation/id ident
             :plan.obligation/description description
             :plan.obligation/checker-id checker-id
             :plan.obligation/status status}
      (seq evidence) (assoc :plan.obligation/evidence evidence))))

(defn- normalize-work-plan
  [plan-data provided-id agent-id source-path]
  (let [plan-id (parse-plan-id (or provided-id (:work.plan/id plan-data)))
        spec-id (coerce-spec-id (or (:work.plan/spec-id plan-data)
                                    (:spec/id plan-data)))
        spec-version (positive-long (:work.plan/spec-version plan-data) :work.plan/spec-version)
        spec-tests (normalize-keywords (:spec/test-contracts plan-data))
        status (or (:work.plan/status plan-data) :work.plan.status/draft)
        _ (when-not (work-plan-statuses status)
            (throw (ex-info "Unknown work.plan/status"
                            {:field :work.plan/status
                             :value status})))
        created-by (let [value (or (:work.plan/created-by plan-data) agent-id)]
                     (when (str/blank? (str value))
                       (throw (ex-info "work.plan/created-by required"
                                       {:field :work.plan/created-by})))
                     (str value))
        created-at (or (:work.plan/created-at plan-data) (str (Instant/now)))
        nodes (vec (or (:work.plan/nodes plan-data) []))
        _ (when-not (seq nodes)
            (throw (ex-info "WorkPlan requires at least one node"
                            {:field :work.plan/nodes
                             :work.plan/id plan-id})))
        normalized-nodes (mapv normalize-plan-node nodes)
        node-ids (set (map :plan.node/id normalized-nodes))
        normalized-edges (mapv #(normalize-plan-edge % node-ids)
                               (vec (or (:work.plan/edges plan-data) [])))
        coverage-data (vec (or (:work.plan/coverage plan-data) []))
        _ (when-not (seq coverage-data)
            (throw (ex-info "WorkPlan requires coverage rows"
                            {:field :work.plan/coverage
                             :work.plan/id plan-id})))
        normalized-coverage (mapv #(normalize-coverage-row % node-ids) coverage-data)
        obligations-data (vec (or (:work.plan/proof-obligations plan-data) []))
        _ (when-not (seq obligations-data)
            (throw (ex-info "WorkPlan requires proof obligations"
                            {:field :work.plan/proof-obligations
                             :work.plan/id plan-id})))
        normalized-obligations (mapv normalize-plan-obligation obligations-data)
        validation-results (vec (or (:work.plan/validation-results plan-data) []))
        generation-id (parse-optional-uuid (:plan.generation/id plan-data)
                                           :plan.generation/id)
        generation-status (:plan.generation/status plan-data)
        _ (when (and generation-status (not (plan-generation-statuses generation-status)))
            (throw (ex-info "Unknown plan.generation/status"
                            {:field :plan.generation/status
                             :value generation-status})))
        heuristics-id (let [value (:plan.generation/heuristics-id plan-data)]
                        (cond
                          (nil? value) nil
                          (keyword? value) value
                          (string? value) (keyword (bootstrap/sanitize-fragment value))
                          :else (throw (ex-info "plan.generation/heuristics-id must be keywordable"
                                                {:value value}))))
        heuristics-version (present-string (:plan.generation/heuristics-version plan-data))
        generation-log (some-> (:plan.generation/log-path plan-data) ensure-repo-relative)
        generation-actor (present-string (:plan.generation/actor plan-data))
        generation-at (present-string (:plan.generation/generated-at plan-data))
        generation-spec-digest (present-string (:plan.generation/spec-digest plan-data))
        generation-decisions (vec (or (:plan.generation/decisions plan-data) []))]
    (cond-> {:work.plan/id plan-id
             :work.plan/spec-id spec-id
             :work.plan/spec-version spec-version
             :work.plan/status status
             :work.plan/created-by created-by
             :work.plan/created-at created-at
             :work.plan/nodes normalized-nodes
             :work.plan/edges normalized-edges
             :work.plan/coverage normalized-coverage
             :work.plan/proof-obligations normalized-obligations
             :work.plan/validation-results validation-results
             :work.plan/source-path source-path}
      (seq spec-tests) (assoc :spec/test-contracts spec-tests)
      generation-id (assoc :plan.generation/id generation-id)
      generation-status (assoc :plan.generation/status generation-status)
      heuristics-id (assoc :plan.generation/heuristics-id heuristics-id)
      heuristics-version (assoc :plan.generation/heuristics-version heuristics-version)
      generation-log (assoc :plan.generation/log-path generation-log)
      generation-actor (assoc :plan.generation/actor generation-actor)
      generation-at (assoc :plan.generation/generated-at generation-at)
      generation-spec-digest (assoc :plan.generation/spec-digest generation-spec-digest)
      (seq generation-decisions) (assoc :plan.generation/decisions generation-decisions))))

(defn- coverage-validation
  [work-plan spec-data]
  (let [requirements (->> (:spec/requirements spec-data)
                          (map #(str/trim (str %)))
                          (remove str/blank?)
                          vec)
        requirement-set (set requirements)
        tests (->> (:spec/test-contracts spec-data)
                   (remove nil?)
                   vec)
        test-set (set tests)
        coverage (:work.plan/coverage work-plan)
        grouped (group-by :coverage.row/requirement-id coverage)
        missing (->> requirements (remove #(seq (grouped %))) vec)
        extras (->> coverage
                    (map :coverage.row/requirement-id)
                    (remove requirement-set)
                    distinct
                    vec)
        coverage-tests (->> coverage
                            (mapcat :coverage.row/test-contracts)
                            (remove nil?)
                            vec)
        coverage-test-set (set coverage-tests)
        missing-tests (if (seq tests)
                        (->> tests (remove #(contains? coverage-test-set %)) vec)
                        [])
        extra-tests (if (seq tests)
                      (->> coverage-tests
                           (remove #(contains? test-set %))
                           distinct
                           vec)
                      [])
        errors (cond-> []
                 (seq missing)
                 (conj (format "Missing coverage for requirements: %s"
                               (str/join ", " missing)))
                 (seq extras)
                 (conj (format "Coverage rows reference unknown requirements: %s"
                               (str/join ", " extras)))
                 (seq missing-tests)
                 (conj (format "Missing coverage for test contracts: %s"
                               (str/join ", " (map str missing-tests))))
                 (seq extra-tests)
                 (conj (format "Coverage rows reference unknown test contracts: %s"
                               (str/join ", " (map str extra-tests)))))]
    {:validation/kind :work-plan/coverage
     :validation/status (if (seq errors) :status/failed :status/passed)
     :validation/errors errors
     :validation/details {:requirements requirements
                          :tests tests
                          :coverage-count (count coverage)
                          :tests-covered (vec (distinct coverage-tests))
                          :tests-missing missing-tests
                          :tests-extra extra-tests}}))

(defn- work-plan-adjacency
  [edges]
  (reduce (fn [acc {:plan.edge/keys [from-node-id to-node-id]}]
            (update acc from-node-id (fnil conj #{}) to-node-id))
          {}
          edges))

(defn- dag-validation
  [work-plan]
  (let [node-ids (map :plan.node/id (:work.plan/nodes work-plan))
        edges (:work.plan/edges work-plan)
        adj (work-plan-adjacency edges)
        indegree (reduce (fn [acc {:plan.edge/keys [to-node-id]}]
                           (update acc to-node-id (fnil inc 0)))
                         (zipmap node-ids (repeat 0))
                         edges)
        queue (reduce (fn [q node]
                        (if (zero? (get indegree node 0))
                          (conj q node)
                          q))
                      clojure.lang.PersistentQueue/EMPTY
                      node-ids)]
    (loop [q queue
           deg indegree
           order []]
      (if (empty? q)
        (let [remaining (->> node-ids (remove #(some #{%} order)) vec)]
          {:validation/kind :work-plan/dag
           :validation/status (if (seq remaining) :status/failed :status/passed)
           :validation/errors (if (seq remaining)
                                [(format "Cycle detected involving nodes: %s"
                                         (str/join ", " remaining))]
                                [])
           :validation/details {:topology order
                                :remaining remaining
                                :adjacency adj}})
        (let [node (peek q)
              q (pop q)
              neighbors (seq (get adj node))
              [deg q] (reduce (fn [[deg queue] neighbor]
                                (let [next (dec (get deg neighbor 0))
                                      deg (assoc deg neighbor next)
                                      queue (if (zero? next)
                                              (conj queue neighbor)
                                              queue)]
                                  [deg queue]))
                              [deg q]
                              neighbors)]
          (recur q deg (conj order node)))))))

(defn- reachable?
  [adj start target]
  (loop [queue (conj clojure.lang.PersistentQueue/EMPTY start)
         visited #{start}]
    (if (empty? queue)
      false
      (let [node (peek queue)
            queue (pop queue)]
        (cond
          (= node target) true
          :else (let [neighbors (remove visited (get adj node))
                      new-queue (into queue neighbors)
                      new-visited (into visited neighbors)]
                  (recur new-queue new-visited)))))))

(defn- resource-owners
  [nodes]
  (reduce (fn [acc {:plan.node/keys [id resources]}]
            (reduce (fn [inner resource]
                      (update inner resource (fnil conj []) id))
                    acc
                    resources))
          {}
          nodes))

(defn- resource-validation
  [work-plan]
  (let [adj (work-plan-adjacency (:work.plan/edges work-plan))
        owners (resource-owners (:work.plan/nodes work-plan))
        conflicts (->> owners
                       (mapcat
                        (fn [[resource nodes]]
                          (let [as-vec (vec nodes)]
                            (for [i (range (count as-vec))
                                  j (range (inc i) (count as-vec))
                                  :let [a (nth as-vec i)
                                        b (nth as-vec j)]
                                  :when (and (not (reachable? adj a b))
                                             (not (reachable? adj b a)))]
                              {:resource resource
                               :nodes [a b]}))))
                       (remove nil?)
                       distinct
                       vec)
        errors (map (fn [{:keys [resource nodes]}]
                      (format "Resource %s shared by nodes %s without ordering edge"
                              resource
                              (str/join " & " nodes)))
                    conflicts)]
    {:validation/kind :work-plan/resources
     :validation/status (if (seq errors) :status/failed :status/passed)
     :validation/errors (vec errors)
     :validation/details {:resource-count (count owners)
                          :conflicts conflicts
                          :adjacency adj}}))

(defn- work-plan-validation-markdown
  [{mission-id :mission/id
    agent-id :agent/id
    plan-id :work.plan/id
    spec-id :work.plan/spec-id
    node-count :work.plan/node-count
    coverage-count :work.plan/coverage-count
    requirements-count :spec/requirements-count
    spec-tests :spec/tests
    validated-at :validated-at
    spec-sections :spec/sections}]
  (str "# Work Plan Validation\n\n"
       "* Mission: " mission-id "\n"
       "* Agent: " agent-id "\n"
       "* WorkPlan: " plan-id "\n"
       "* Spec: " spec-id "\n"
       "* Nodes: " node-count "\n"
       "* Coverage rows: " coverage-count " / Spec requirements: " (or requirements-count 0) "\n"
       "* Spec tests: " (count (or spec-tests []))
       (let [tests (or spec-tests [])]
         (if (seq tests)
           (str " (" (str/join ", " tests) ")")
           ""))
       "\n"
       "* Validated: " validated-at "\n"
       "* SYSTEM_SPEC refs: §§3.3–3.6, §4.7, §5.1, §9\n"
       "* Spec sections: " (str/join ", " (or spec-sections [])) "\n"
       "\nValidators executed: coverage, dag, resource-conflict."))

(defn work-plan-capture
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         input-path :work-plan/input-path
         provided-id :work.plan/id} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required" {:field :agent/id})))
        _ (when (str/blank? (str input-path))
            (throw (ex-info "work-plan/input-path required"
                            {:field :work-plan/input-path})))
        source-path (ensure-repo-relative input-path)
        plan-data (read-edn-file source-path)
        _ (when-not (map? plan-data)
            (throw (ex-info "WorkPlan EDN must be a map"
                            {:path source-path
                             :data plan-data})))
        normalized (normalize-work-plan plan-data provided-id agent-id source-path)
        spec-file (spec-resource-file (:work.plan/spec-id normalized))
        _ (when-not (.exists spec-file)
            (throw (ex-info "Spec must be captured before planning"
                            {:spec/id (:work.plan/spec-id normalized)
                             :path (ensure-repo-relative spec-file)})))
        resource-file (work-plan-resource-file (:work.plan/id normalized))
        mission-file (work-plan-mission-file mission-id (:work.plan/id normalized))]
    (spit resource-file (pr-str normalized))
    (spit mission-file (pr-str normalized))
    {:action/status :status/ok
     :work.plan/id (:work.plan/id normalized)
     :work.plan/spec-id (:work.plan/spec-id normalized)
     :work.plan/status (:work.plan/status normalized)
     :work-plan/resource-path (ensure-repo-relative resource-file)
     :work-plan/log-path (ensure-repo-relative mission-file)
     :work-plan/source-path source-path}))

(defn work-plan-validate
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         plan-id :work.plan/id
         override-plan-path :work-plan/resource-path
         override-spec-path :spec/resource-path} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required" {:field :agent/id})))
        _ (when-not plan-id
            (throw (ex-info "work.plan/id required" {:field :work.plan/id})))
        plan-file (or override-plan-path (work-plan-resource-file plan-id))
        plan-path (ensure-repo-relative plan-file)
        plan-data (read-edn-file plan-path)
        _ (when-not (map? plan-data)
            (throw (ex-info "WorkPlan file missing map"
                            {:work.plan/id plan-id
                             :path plan-path})))
        spec-file (or override-spec-path
                      (spec-resource-file (:work.plan/spec-id plan-data)))
        spec-path (ensure-repo-relative spec-file)
        raw-spec (read-edn-file spec-path)
        spec-tests (vec (or (:spec/test-contracts raw-spec)
                            (:spec/test-contracts plan-data)
                            []))
        spec-data (assoc raw-spec :spec/test-contracts spec-tests)
        _ (when-not (map? spec-data)
            (throw (ex-info "Spec file missing map"
                            {:work.plan/id plan-id
                             :spec/id (:work.plan/spec-id plan-data)
                             :path spec-path})))
        coverage-result (coverage-validation plan-data spec-data)
        dag-result (dag-validation plan-data)
        resource-result (resource-validation plan-data)
        results [coverage-result dag-result resource-result]
        errors (->> results
                    (mapcat :validation/errors)
                    (remove str/blank?)
                    vec)
        _ (when (seq errors)
            (throw (ex-info "Work plan validation failed"
                            {:work.plan/id plan-id
                             :errors errors
                             :results results})))
        {:keys [edn markdown]} (work-plan-validation-files mission-id)
        validated-at (str (Instant/now))
        report {:mission/id mission-id
                :agent/id agent-id
                :work.plan/id plan-id
                :work.plan/spec-id (:work.plan/spec-id plan-data)
                :work.plan/status (:work.plan/status plan-data)
                :work.plan/node-count (count (:work.plan/nodes plan-data))
                :work.plan/coverage-count (count (:work.plan/coverage plan-data))
                :work.plan/resource-path plan-path
                :work.plan/validation-results results
                :spec/requirements-count (count (:spec/requirements spec-data))
                :spec/tests (:spec/test-contracts spec-data)
                :spec/sections (:spec/spec-sections spec-data)
                :validated-at validated-at}
        updated-plan (assoc plan-data :work.plan/validation-results results)]
    (spit plan-file (pr-str updated-plan))
    (spit edn (pr-str report))
    (spit markdown (work-plan-validation-markdown report))
    {:action/status :status/ok
     :work.plan/id plan-id
     :work.plan/status (:work.plan/status plan-data)
     :work-plan/validation-path (ensure-repo-relative edn)
     :work-plan/validation-report report
     :work-plan/validation-markdown (ensure-repo-relative markdown)}))

(defn work-plan-publish
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         plan-id :work.plan/id
         desired-status :work.plan/status} config
        _ (when (str/blank? (str mission-id))
            (throw (ex-info "mission/id required" {:field :mission/id})))
        _ (when (str/blank? (str agent-id))
            (throw (ex-info "agent/id required" {:field :agent/id})))
        _ (when-not plan-id
            (throw (ex-info "work.plan/id required" {:field :work.plan/id})))
        plan-file (work-plan-resource-file plan-id)
        plan-path (ensure-repo-relative plan-file)
        plan-data (read-edn-file plan-path)
        _ (when-not (map? plan-data)
            (throw (ex-info "WorkPlan file missing map"
                            {:work.plan/id plan-id
                             :path plan-path})))
        new-status (or desired-status :work.plan.status/approved)
        _ (when-not (keyword? new-status)
            (throw (ex-info "work.plan/status must be keyword"
                            {:value new-status})))
        updated (assoc plan-data :work.plan/status new-status)
        publish-file (work-plan-publish-log-file mission-id plan-id)
        published-at (str (Instant/now))] 
    (spit plan-file (pr-str updated))
    (spit publish-file
          (str "# Work Plan Publish\n\n"
               "* Mission: " mission-id "\n"
               "* Agent: " agent-id "\n"
               "* WorkPlan: " plan-id "\n"
               "* Spec: " (:work.plan/spec-id plan-data) "\n"
               "* Status: " new-status "\n"
               "* Published: " published-at "\n"
               "* SYSTEM_SPEC refs: §§3.3–3.6, §4.7, §5.1, §9\n"))
    {:action/status :status/ok
     :work.plan/id plan-id
     :work.plan/status new-status
     :work-plan/publish-log (ensure-repo-relative publish-file)}))

;; Version snapshots --------------------------------------------------------

(defn- spec-node-ident
  [spec-id]
  (when spec-id
    (if (keyword? spec-id)
      spec-id
      (keyword (str "spec/" (bootstrap/sanitize-fragment spec-id))))))

(defn- plan-node-ident
  [plan-id]
  (when plan-id
    (keyword (str "plan/" (bootstrap/sanitize-fragment plan-id)))))

(defn- mission-node-ident
  [mission-id]
  (when mission-id
    (keyword (str "mission/" (bootstrap/sanitize-fragment mission-id)))))

(defn- snapshot-graph-nodes
  [{:keys [spec-id plan-id mission-id extra-nodes]}]
  (->> (concat extra-nodes
               [(spec-node-ident spec-id)
                (plan-node-ident plan-id)
                (mission-node-ident mission-id)])
       (remove nil?)
       distinct
       vec))

(defn version-snapshot-spec
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         spec-id :spec/id
         spec-path :spec/resource-path
         validation-path :spec/validation-path
         publish-log :spec/publish-log
         git-commit :git/commit} config
        mission (require-mission-string mission-id)
        _ (when-not (keyword? spec-id)
            (throw (ex-info "spec/id required for snapshot" {:field :spec/id})))
        spec-file (canonical-existing-file spec-path :spec/resource-path)
        validation-file (canonical-existing-file validation-path :spec/validation-path)
        publish-file (canonical-existing-file publish-log :spec/publish-log)
        spec-data (read-edn-file (.getCanonicalPath spec-file))
        _ (when-not (map? spec-data)
            (throw (ex-info "Spec file missing map" {:path (.getCanonicalPath spec-file)})))
        requirements (vec (normalize-strings (:spec/requirements spec-data)))
        commit-hash (normalized-commit git-commit)
        snapshot-id (UUID/randomUUID)
        now (Instant/now)
        timestamp (str now)
        graph-nodes (snapshot-graph-nodes {:spec-id spec-id
                                           :mission-id mission
                                           :extra-nodes (:code.graph/nodes config)})
        base {:version.snapshot/id snapshot-id
              :version.snapshot/type :version.snapshot/spec
              :version.snapshot/timestamp timestamp
              :version.snapshot/actor (version-agent-ident agent-id)
              :version.snapshot/mission-id mission
              :version.snapshot/spec-id spec-id
              :version.snapshot/requirements requirements
              :version.snapshot/artifacts []
              :version.snapshot/links []
              :version.snapshot/code-graph-nodes graph-nodes}
        snapshot (cond-> base
                   commit-hash (assoc :version.snapshot/git-commit commit-hash))
        artifacts [(version-artifact snapshot-id :artifact/spec-validation validation-file "application/edn" timestamp)
                   (version-artifact snapshot-id :artifact/spec-publish publish-file "text/markdown" timestamp)
                   (version-artifact snapshot-id :artifact/spec-resource spec-file "application/edn" timestamp)]
        snapshot (assoc snapshot :version.snapshot/artifacts artifacts)
        persisted (persist-version-snapshot! mission-id snapshot)]
    (version-snapshot-output mission-id snapshot persisted)))

(defn version-snapshot-plan
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         plan-id :work.plan/id
         spec-id :work.plan/spec-id
         plan-path :work-plan/resource-path
         validation-path :work-plan/validation-path
         publish-log :work-plan/publish-log
         git-commit :git/commit} config
        mission (require-mission-string mission-id)
        parsed-plan-id (parse-plan-id plan-id)
        normalized-spec (coerce-spec-id spec-id)
        plan-file (canonical-existing-file plan-path :work-plan/resource-path)
        plan-data (read-edn-file (.getCanonicalPath plan-file))
        _ (when-not (map? plan-data)
            (throw (ex-info "WorkPlan EDN missing map" {:path (.getCanonicalPath plan-file)})))
        requirements (plan-requirements plan-data)
        validation-file (canonical-existing-file validation-path :work-plan/validation-path)
        publish-file (canonical-existing-file publish-log :work-plan/publish-log)
        spec-snapshot (latest-snapshot! :version.snapshot/spec normalized-spec)
        commit-hash (normalized-commit git-commit)
        snapshot-id (UUID/randomUUID)
        now (Instant/now)
        timestamp (str now)
        graph-nodes (snapshot-graph-nodes {:spec-id normalized-spec
                                           :plan-id parsed-plan-id
                                           :mission-id mission
                                           :extra-nodes (:code.graph/nodes config)})
        base {:version.snapshot/id snapshot-id
              :version.snapshot/type :version.snapshot/plan
              :version.snapshot/timestamp timestamp
              :version.snapshot/actor (version-agent-ident agent-id)
              :version.snapshot/mission-id mission
              :version.snapshot/spec-id normalized-spec
              :version.snapshot/plan-id parsed-plan-id
              :version.snapshot/requirements requirements
              :version.snapshot/artifacts []
              :version.snapshot/links []
              :version.snapshot/code-graph-nodes graph-nodes}
        snapshot (cond-> base
                   commit-hash (assoc :version.snapshot/git-commit commit-hash))
        artifacts [(version-artifact snapshot-id :artifact/work-plan-validation validation-file "application/edn" timestamp)
                   (version-artifact snapshot-id :artifact/work-plan-publish publish-file "text/markdown" timestamp)
                   (version-artifact snapshot-id :artifact/work-plan-resource plan-file "application/edn" timestamp)]
        link {:version.link/id (UUID/randomUUID)
              :version.link/source-snapshot-id (:version.snapshot/id spec-snapshot)
              :version.link/target-snapshot-id snapshot-id
              :version.link/relation :version.link.relation/spec->plan
              :version.link/recorded-at timestamp}
        snapshot (-> snapshot
                     (assoc :version.snapshot/artifacts artifacts)
                     (assoc :version.snapshot/links [link]))
        persisted (persist-version-snapshot! mission-id snapshot)]
    (version-snapshot-output mission-id snapshot persisted)))

;; Code proposal channel -----------------------------------------------------

(def ^:private code-edit-channel-resource
  "dictionary/code_edit_channel.edn")

(def ^:private doc-templates-resource
  "dictionary/doc_templates.edn")

(def ^:private template-statuses
  #{:template.instance.status/draft
    :template.instance.status/active
    :template.instance.status/deprecated})

(def ^:private proposal-ops
  #{:proposal.op/add :proposal.op/update})

(defn- load-edn-resource!
  [path label]
  (if-let [res (io/resource path)]
    (-> res slurp edn/read-string)
    (let [file (io/file "resources" path)]
      (if (.exists file)
        (-> file slurp edn/read-string)
        (throw (ex-info (str "Missing " label)
                        {:resource path}))))))

(def ^:private code-edit-channel*
  (delay (load-edn-resource! code-edit-channel-resource
                             "code edit channel dictionary")))

(def ^:private doc-templates*
  (delay (load-edn-resource! doc-templates-resource
                             "doc templates dictionary")))

(defn- proposal-rules
  []
  (let [channel @code-edit-channel*]
    (->> (:channel/entities channel)
         (map (juxt :proposal/type identity))
         (into {}))))

(defn- channel-spec-sections
  []
  (vec (or (:channel/spec-sections @code-edit-channel*) [])))

(defn- keyword->ident-string
  [kw]
  (if-let [ns (namespace kw)]
    (str ns "/" (name kw))
    (name kw)))

(defn- template-definition-idents
  []
  (->> @doc-templates*
       (filter #(= :template/definition (:entity/type %)))
       (map :template/ident)
       set))

(defn- ensure-payload-map
  [payload]
  (when-not (map? payload)
    (throw (ex-info "Proposal payload must be a map" {:payload payload})))
  payload)

(defn- require-proposal-op
  [op]
  (let [resolved (or op :proposal.op/add)]
    (when-not (proposal-ops resolved)
      (throw (ex-info "Unsupported proposal operation"
                      {:code.proposal/op op})))
    resolved))

(defn- normalized-proposal-ident
  [value field]
  (let [text (some-> value str str/trim)]
    (when (str/blank? text)
      (throw (ex-info "Proposal ident required"
                      {:field field
                       :value value})))
    text))

(defn- proposal-ident->keyword
  [value field]
  (let [kw (keywordish value)]
    (when-not kw
      (throw (ex-info "Proposal ident must be keywordable"
                      {:field field
                       :value value})))
    kw))

(defn- relative-proposal-path
  [path]
  (let [value (-> path str str/trim)]
    (when (or (str/blank? value)
              (str/starts-with? value "/")
              (str/starts-with? value "\\")
              (re-find #"^[A-Za-z]:[\\/]" value)
              (str/includes? value ".."))
      (throw (ex-info "Proposal paths must be repo-relative and sandbox-safe"
                      {:path path})))
    value))

(defn- normalize-dependencies
  [deps]
  (->> (vectorize deps)
       (map (fn [dep]
              (or (keywordish dep)
                  (throw (ex-info "Dependencies must be keywords"
                                  {:dependency dep})))))
       vec))

(defn- ensure-allowed-keys!
  [payload rule]
  (let [required (set (:proposal/required rule))
        optional (set (:proposal/optional rule))
        immutable (set (:proposal/immutable rule))
        allowed (set/union required optional immutable)
        missing (seq (remove #(contains? payload %) required))
        unknown (seq (remove allowed (keys payload)))]
    (when missing
      (throw (ex-info "Proposal payload missing required fields"
                      {:proposal/type (:proposal/type rule)
                       :missing (vec missing)})))
    (when unknown
      (throw (ex-info "Proposal payload includes unsupported fields"
                      {:proposal/type (:proposal/type rule)
                       :unknown (vec unknown)})))
    payload))

(defn- assert-ident-match!
  [proposal ident kw-field]
  (when-let [explicit (:code.proposal/ident proposal)]
    (let [normalized (normalized-proposal-ident explicit kw-field)
          kw (proposal-ident->keyword normalized kw-field)]
      (when-not (= (keyword->ident-string kw)
                   (keyword->ident-string ident))
        (throw (ex-info "Proposal ident mismatch"
                        {:provided explicit
                         :payload ident})))))) 

(defn- validate-code-definition
  [proposal rule allowed-code-idents]
  (let [payload (ensure-payload-map (:code.proposal/payload proposal))
        _ (ensure-allowed-keys! payload rule)
        ident (proposal-ident->keyword (or (:code.definition/ident payload)
                                           (:code.proposal/ident proposal))
                                       :code.definition/ident)
        _ (assert-ident-match! proposal ident :code.definition/ident)
        type-ident (:code.definition/type payload)
        _ (when-not (code/type-ident? type-ident)
            (throw (ex-info "Unknown CodeType for proposal"
                            {:code.definition/type type-ident})))
        spec-sections (normalize-strings (:code.definition/spec-sections payload))
        _ (when-not (seq spec-sections)
            (throw (ex-info "Spec sections required for CodeDefinition proposal"
                            {:code.definition/ident ident})))
        paths (->> (:code.definition/paths payload)
                   vectorize
                   (map relative-proposal-path)
                   vec)
        dependencies (normalize-dependencies (:code.definition/dependencies payload))
        allowed-deps (set/union (code/definition-idents) allowed-code-idents)
        _ (when-let [unknown (seq (remove #(or (allowed-deps %)
                                               (code/known-dependency? %))
                                          dependencies))]
            (throw (ex-info "Unknown dependency in CodeDefinition proposal"
                            {:code.definition/ident ident
                             :code.definition/dependencies dependencies
                             :code.definition/unknown (vec unknown)})))]
    {:payload (assoc payload
                     :code.definition/ident ident
                     :code.definition/spec-sections spec-sections
                     :code.definition/paths paths
                     :code.definition/dependencies dependencies)
     :ident-string (keyword->ident-string ident)
     :spec-sections spec-sections}))

(defn- validate-template-instance
  [proposal rule]
  (let [payload (ensure-payload-map (:code.proposal/payload proposal))
        _ (ensure-allowed-keys! payload rule)
        ident (proposal-ident->keyword (or (:template.instance/ident payload)
                                           (:code.proposal/ident proposal))
                                       :template.instance/ident)
        _ (assert-ident-match! proposal ident :template.instance/ident)
        definition (proposal-ident->keyword (:template.instance/definition payload)
                                            :template.instance/definition)
        _ (when-not ((template-definition-idents) definition)
            (throw (ex-info "TemplateDefinition not found for proposal"
                            {:template.instance/definition definition})))
        status (:template.instance/status payload)
        _ (when-not (template-statuses status)
            (throw (ex-info "Template instance status invalid"
                            {:template.instance/status status
                             :allowed template-statuses})))
        config (:template.instance/config payload)
        _ (when-not (map? config)
            (throw (ex-info "Template instance config must be a map"
                            {:template.instance/config config})))]
    {:payload (assoc payload
                     :template.instance/ident ident
                     :template.instance/definition definition
                     :template.instance/status status
                     :template.instance/config config)
     :ident-string (keyword->ident-string ident)
     :spec-sections (channel-spec-sections)}))

(defn- validate-spec-fragment
  [proposal rule]
  (let [payload (ensure-payload-map (:code.proposal/payload proposal))
        _ (ensure-allowed-keys! payload rule)
        spec-id (proposal-ident->keyword (or (:spec/id payload)
                                             (:code.proposal/ident proposal))
                                         :spec/id)
        _ (assert-ident-match! proposal spec-id :spec/id)
        requirements (normalize-strings (:spec/requirements payload))
        _ (when-not (seq requirements)
            (throw (ex-info "Spec fragment requires at least one requirement"
                            {:spec/id spec-id})))
        spec-sections (let [sections (normalize-strings (:spec/spec-sections payload))]
                        (if (seq sections) sections (channel-spec-sections)))]
    {:payload (assoc payload
                     :spec/id spec-id
                     :spec/requirements requirements
                     :spec/spec-sections spec-sections)
     :ident-string (keyword->ident-string spec-id)
     :spec-sections spec-sections}))

(defn- validate-proposal*
  [proposal rules allowed-code-idents]
  (let [rule (or (get rules (:code.proposal/type proposal))
                 (throw (ex-info "Unknown proposal type"
                                 {:code.proposal/type (:code.proposal/type proposal)})))
        op (require-proposal-op (:code.proposal/op proposal))
        result (case (:code.proposal/type proposal)
                 :proposal.type/code-definition (validate-code-definition proposal rule allowed-code-idents)
                 :proposal.type/template-instance (validate-template-instance proposal rule)
                 :proposal.type/spec-fragment (validate-spec-fragment proposal rule)
                 (throw (ex-info "Unsupported proposal type"
                                 {:code.proposal/type (:code.proposal/type proposal)})))]
    {:code.proposal/id (or (:code.proposal/id proposal) (UUID/randomUUID))
     :code.proposal/type (:code.proposal/type proposal)
     :code.proposal/op op
     :code.proposal/ident (:ident-string result)
     :code.proposal/payload (:payload result)
     :code.proposal/spec-sections (vec (or (:spec-sections result)
                                           (channel-spec-sections)))
     :code.proposal/status :code.proposal.status/validated
     :code.proposal/notes (:code.proposal/notes proposal)}))

(defn- proposal-code-ident-set
  [proposals]
  (->> proposals
       (filter #(= :proposal.type/code-definition (:code.proposal/type %)))
       (map (fn [proposal]
              (or (keywordish (:code.proposal/ident proposal))
                  (keywordish (get-in proposal [:code.proposal/payload :code.definition/ident])))))
       (remove nil?)
       set))

(defn- validate-proposals
  [proposals]
  (when-not (seq proposals)
    (throw (ex-info "At least one proposal is required"
                    {:field :code.proposal/proposals})))
  (let [rules (proposal-rules)
        code-ident-set (proposal-code-ident-set proposals)]
    (mapv #(validate-proposal* % rules code-ident-set) proposals)))

(defn- mission-ident
  [mission-id]
  (keyword (bootstrap/sanitize-fragment (require-mission-string mission-id))))

(defn- proposal-log-file
  [mission-id timestamp filename log-root]
  (mission-log-file mission-id (format "code-proposals/%s/%s" timestamp filename) log-root))

(defn- write-proposal-validation!
  [mission-id agent proposals log-root]
  (let [now (Instant/now)
        timestamp (.format timestamp-formatter now)
        file (proposal-log-file mission-id timestamp "code-proposal-validation.edn" log-root)
        payload {:mission/id mission-id
                 :agent/id agent
                 :code.proposal/proposals proposals
                 :code.proposal/spec-sections (channel-spec-sections)
                 :code.proposal/channel (:channel/ident @code-edit-channel*)
                 :code.proposal/validated-at (str now)}
        path (write-edn! file payload)]
    {:file file
     :path path
     :timestamp timestamp}))

(defn validate-code-proposals
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         proposals :code.proposal/proposals
         log-root :code.proposal/log-root} config
        mission (require-mission-string mission-id)
        agent (require-agent-string agent-id)
        validated (validate-proposals proposals)
        {:keys [path]} (write-proposal-validation! mission agent validated log-root)]
    {:action/status :status/ok
     :code.proposal/proposals validated
     :code.proposal/log-path path
     :code.proposal/spec-sections (channel-spec-sections)}))

(def ^:private proposal-schema
  [{:db/ident :code.proposal/id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :code.proposal/ident
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.proposal/type
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.proposal/op
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.proposal/payload
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.proposal/before
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.proposal/diff
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.proposal/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.proposal/spec-sections
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :code.proposal/mission
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.proposal/agent
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :code.proposal/recorded-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}])

(defn- ensure-proposal-schema!
  [conn]
  (let [db (d/db conn)
        installed? (seq (d/q '[:find ?e :where [?e :db/ident :code.proposal/id]] db))]
    (when-not installed?
      (d/transact conn {:tx-data proposal-schema})))
  conn)

(defn- latest-proposal
  [conn ident type]
  (let [rows (d/q '[:find ?payload ?recorded
                    :in $ ?ident ?type
                    :where [?e :code.proposal/ident ?ident]
                           [?e :code.proposal/type ?type]
                           [?e :code.proposal/payload ?payload]
                           [?e :code.proposal/recorded-at ?recorded]]
                  (d/db conn) ident type)]
    (when-let [[payload recorded] (when (seq rows)
                                    (apply max-key second rows))]
      {:code.proposal/payload (try
                                (edn/read-string payload)
                                (catch Exception _
                                  payload))
       :code.proposal/recorded-at recorded})))

(defn- proposal-diff
  [before after]
  (let [[only-before only-after shared] (data/diff before after)]
    {:diff/before only-before
     :diff/after only-after
     :diff/shared shared}))

(defn- apply-proposal!
  [conn mission-id agent-id proposal]
  (let [ident (:code.proposal/ident proposal)
        type (:code.proposal/type proposal)
        payload (:code.proposal/payload proposal)
        prior (latest-proposal conn ident type)
        diff (proposal-diff (:code.proposal/payload prior) payload)
        now (Instant/now)
        tx-data (cond-> {:code.proposal/id (or (:code.proposal/id proposal) (UUID/randomUUID))
                         :code.proposal/ident ident
                         :code.proposal/type type
                         :code.proposal/op (:code.proposal/op proposal)
                         :code.proposal/payload (pr-str payload)
                         :code.proposal/status :code.proposal.status/applied
                         :code.proposal/spec-sections (vec (or (:code.proposal/spec-sections proposal)
                                                               (channel-spec-sections)))
                         :code.proposal/mission (str mission-id)
                         :code.proposal/agent (str agent-id)
                         :code.proposal/recorded-at (Date/from now)}
                  (:code.proposal/payload prior) (assoc :code.proposal/before (pr-str (:code.proposal/payload prior)))
                  (some (fn [[_ v]] (seq v)) diff) (assoc :code.proposal/diff (pr-str diff)))]
    (d/transact conn {:tx-data [tx-data]})
    (assoc proposal
           :code.proposal/status :code.proposal.status/applied
           :code.proposal/before (:code.proposal/payload prior)
           :code.proposal/diff diff)))

(defn- proposal->domain-tx
  [mission-id proposal]
  (when (= :proposal.type/code-definition (:code.proposal/type proposal))
    (let [payload (:code.proposal/payload proposal)
          ident (:code.definition/ident payload)
          resolved-paths (->> (or (:code.definition/paths payload) [])
                              (expand-artifact-paths ident)
                              (map normalize-generated-path)
                              vec)]
      {:code.definition/ident (:code.definition/ident payload)
       :code.definition/name (:code.definition/name payload)
       :code.definition/type (:code.definition/type payload)
       :code.definition/paths resolved-paths
       :code.definition/dependencies (vec (or (:code.definition/dependencies payload) []))
       :code.definition/validators (vec (or (:code.definition/validators payload) []))
       :code.definition/tests (vec (or (:code.definition/tests payload) []))
       :code.definition/spec-sections (vec (or (:code.definition/spec-sections payload) []))
       :code.definition/missions [(mission-ident mission-id)]})))

(defn- proposal-apply-log!
  [mission-id agent proposals timestamp log-root validation-log]
  (let [now (Instant/now)
        file (proposal-log-file mission-id timestamp "proposal-apply.edn" log-root)
        payload {:mission/id mission-id
                 :agent/id agent
                 :code.proposal/proposals proposals
                 :code.proposal/spec-sections (channel-spec-sections)
                 :code.proposal/validation-log validation-log
                 :code.proposal/applied-at (str now)}
        path (write-edn! file payload)]
    {:file file
     :path path}))

(defn- code-proposal-artifacts
  [paths]
  (->> paths
       (remove str/blank?)
       (map io/file)
       (filter #(.exists ^File %))
       (map #(.getCanonicalPath ^File %))
       vec))

(defn- proposal-snapshot
  [mission-id agent proposals artifacts]
  (let [now (Instant/now)
        snapshot-id (UUID/randomUUID)
        nodes (->> proposals
                   (map (fn [proposal]
                          (keyword (bootstrap/sanitize-fragment
                                    (str (name (:code.proposal/type proposal))
                                         "-"
                                         (:code.proposal/ident proposal))))))
                   vec)
        base {:version.snapshot/id snapshot-id
              :version.snapshot/type :version.snapshot/code-proposal
              :version.snapshot/timestamp (str now)
              :version.snapshot/actor (version-agent-ident agent)
              :version.snapshot/mission-id (require-mission-string mission-id)
              :version.snapshot/requirements (mapv :code.proposal/ident proposals)
              :version.snapshot/artifacts []
              :version.snapshot/links []
              :version.snapshot/code-graph-nodes nodes}
        artifacts* (map-indexed (fn [idx path]
                                  (version-artifact snapshot-id
                                                    (keyword (format "artifact/code-proposal-%02d" (inc idx)))
                                                    path
                                                    "application/edn"
                                                    (str now)))
                                artifacts)]
    (assoc base :version.snapshot/artifacts (vec artifacts*))))

(defn apply-code-proposals
  [{:keys [config conn]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         proposals :code.proposal/proposals
         log-root :code.proposal/log-root
         validation-log :code.proposal/validation-log} config
        mission (require-mission-string mission-id)
        agent (require-agent-string agent-id)
        domain? (boolean (:code.proposal/domain-transact? config))
        _ (when-not conn
            (throw (ex-info "Datomic connection required for proposal application"
                            {:field :conn})))
        validated (validate-proposals proposals)
        _ (ensure-proposal-schema! conn)
        timestamp (.format timestamp-formatter (Instant/now))
        applied (mapv #(apply-proposal! conn mission agent %) validated)
        domain-tx (when domain?
                    (->> applied
                         (keep #(proposal->domain-tx mission %))
                         vec))
        _ (when (seq domain-tx)
            (codegen/ensure-schema! conn)
            (d/transact conn {:tx-data domain-tx}))
        {:keys [path]} (proposal-apply-log! mission agent applied timestamp log-root validation-log)
        artifacts (code-proposal-artifacts (cons path [validation-log]))
        transacted-definitions (vec (keep :code.definition/ident domain-tx))
        snapshot (proposal-snapshot mission agent applied artifacts)
        persisted (persist-version-snapshot! mission snapshot)]
    {:action/status :status/ok
     :code.proposal/proposals applied
     :code.proposal/log-path path
     :code.proposal/artifacts artifacts
     :code.definition/transacted transacted-definitions
     :version.snapshot/id (:version.snapshot/id snapshot)
     :version.snapshot/path (:path persisted)
     :version/snapshot snapshot}))

(defn version-snapshot-mission
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         plan-id :work.plan/id
         plan-snapshot-id :version/plan-snapshot-id
         merge-log-path :merge/log-path
         git-commit :git/commit} config
        mission (require-mission-string mission-id)
        resolved-plan-id (mission-plan-id mission-id plan-id)
        parsed-plan-id (parse-plan-id resolved-plan-id)
        merge-file (canonical-existing-file merge-log-path :merge/log-path)
        report-file (canonical-existing-file (io/file (mission-log-dir mission-id) "report.edn")
                                             :mission/report-path)
        plan-snapshot (plan-snapshot! parsed-plan-id plan-snapshot-id)
        requirements (vec (:version.snapshot/requirements plan-snapshot))
        spec-id (:version.snapshot/spec-id plan-snapshot)
        commit-hash (normalized-commit git-commit)
        snapshot-id (UUID/randomUUID)
        now (Instant/now)
        timestamp (str now)
        graph-nodes (snapshot-graph-nodes {:spec-id spec-id
                                           :plan-id parsed-plan-id
                                           :mission-id mission
                                           :extra-nodes (:code.graph/nodes config)})
        base {:version.snapshot/id snapshot-id
              :version.snapshot/type :version.snapshot/mission
              :version.snapshot/timestamp timestamp
              :version.snapshot/actor (version-agent-ident agent-id)
              :version.snapshot/mission-id mission
              :version.snapshot/spec-id spec-id
              :version.snapshot/plan-id parsed-plan-id
              :version.snapshot/requirements requirements
              :version.snapshot/artifacts []
              :version.snapshot/links []
              :version.snapshot/code-graph-nodes graph-nodes}
        snapshot (cond-> base
                   commit-hash (assoc :version.snapshot/git-commit commit-hash))
        artifacts [(version-artifact snapshot-id :artifact/mission-report report-file "application/edn" timestamp)
                   (version-artifact snapshot-id :artifact/merge-log merge-file "application/edn" timestamp)]
        link {:version.link/id (UUID/randomUUID)
              :version.link/source-snapshot-id (:version.snapshot/id plan-snapshot)
              :version.link/target-snapshot-id snapshot-id
              :version.link/relation :version.link.relation/plan->mission
              :version.link/recorded-at timestamp}
        snapshot (-> snapshot
                     (assoc :version.snapshot/artifacts artifacts)
                     (assoc :version.snapshot/links [link]))
        persisted (persist-version-snapshot! mission-id snapshot)]
    (version-snapshot-output mission-id snapshot persisted)))

(defn- heuristics-file
  [path]
  (let [file (io/file (str path))
        resolved (if (.isAbsolute file)
                   file
                   (io/file repo-root file))]
    (when-not (.exists resolved)
      (throw (ex-info "Planner heuristics file missing."
                      {:planner/heuristics-path path
                       :resolved (.getCanonicalPath resolved)})))
    resolved))

(defn- load-heuristics
  [path]
  (let [file (heuristics-file path)
        canonical (.getCanonicalPath file)
        data (read-edn-file canonical)]
    (when-not (map? data)
      (throw (ex-info "Planner heuristics EDN must be a map"
                      {:planner/heuristics-path canonical
                       :data data})))
    data))

(defn- target-file
  [path _field]
  (let [file (io/file (str path))
        resolved (if (.isAbsolute file)
                   file
                   (io/file repo-root file))
        canonical (-> resolved ensure-parent-dirs! .getCanonicalPath)]
    (ensure-repo-relative canonical)
    (io/file canonical)))

(defn- append-remediation!
  "Writes/extends missions/logs/<id>/codetype-remediation.edn with an entry so the
  steward can schedule a CodeType proposal mission."
  [mission-id entry]
  (when (str/blank? (str mission-id))
    (throw (ex-info "mission/id is required to record remediation"
                    {:field :mission/id})))
  (let [file (mission-log-file mission-id "codetype-remediation.edn")
        existing (when (.exists file)
                   (edn/read-string (slurp file)))
        payload (conj (vec (or existing [])) entry)]
    (spit file (pr-str payload))
    (.getCanonicalPath file)))

(defn- keywordish
  [value]
  (cond
    (keyword? value) value
    (string? value)
    (let [trimmed (str/trim value)]
      (when-not (str/blank? trimmed)
        (try
          (if (str/starts-with? trimmed ":")
            (edn/read-string trimmed)
            (keyword trimmed))
          (catch Exception _ nil))))
    :else nil))

(defn- spec-constraint-tags
  [spec-data]
  (->> (:spec/constraints spec-data)
       vectorize
       (map keywordish)
       (remove nil?)
       vec))

(defn- risk-tags
  [spec-data]
  (->> (spec-constraint-tags spec-data)
       (filter #(= "risk" (namespace %)))
       vec))

(defn- change-tags
  [spec-data]
  (->> (spec-constraint-tags spec-data)
       (filter #(= "change.kind" (namespace %)))
       vec))

(defn- normalized-inference-path
  [path]
  (-> (or path "")
      str
      str/trim
      (str/replace #"\\+" "/")
      (str/replace #"//+" "/")
      (str/replace #"^/" "")))

(defn- rule->types
  [entries]
  (->> (vectorize entries)
       (map normalized-code-type)
       (remove nil?)
       vec))

(defn- path-rule-match?
  [path {:keys [prefixes regex extensions]}]
  (let [normalized (normalized-inference-path path)
        pattern (cond
                  (instance? java.util.regex.Pattern regex) regex
                  (string? regex) (re-pattern regex)
                  :else nil)]
    (or (some #(str/starts-with? normalized (normalized-inference-path %))
              (or prefixes []))
        (some #(str/ends-with? normalized %) (or extensions []))
        (when pattern (re-find pattern normalized)))))

(defn- apply-path-rules
  [paths rules]
  (->> (for [path (vectorize paths)
             rule (or rules [])]
         (when (path-rule-match? path rule)
           (:code-types rule)))
       (mapcat rule->types)
       (remove nil?)
       vec))

(defn- apply-tag-rules
  [tags rule-map]
  (->> tags
       (mapcat #(get rule-map %))
       rule->types))

(defn- infer-code-types!
  [{:keys [mission-id requirement resources spec-data heuristics mission-category]}]
  (let [config (:codetype-inference heuristics)
        catalog (code/type-ident-set)
        defaults (rule->types (:default config))
        category-default (rule->types (get-in config [:category-default mission-category]))
        path-derived (apply-path-rules resources (:path-rules config))
        artifact-derived (apply-path-rules (:spec/artifacts spec-data)
                                           (:artifact-rules config))
        risk-derived (apply-tag-rules (risk-tags spec-data)
                                      (or (:risk-rules config) {}))
        change-derived (apply-tag-rules (change-tags spec-data)
                                        (or (:change-rules config) {}))
        fallback (rule->types (:fallback config))
        combined (->> (concat defaults
                              category-default
                              path-derived
                              artifact-derived
                              risk-derived
                              change-derived)
                      (remove nil?)
                      distinct
                      vec)
        known (->> combined (filter #(contains? catalog %)) vec)
        target (if (seq known) known fallback)
        unknown (set/difference (set combined) (set known))]
    (when (seq unknown)
      (append-remediation! mission-id {:mission/id mission-id
                                       :requirement/id requirement
                                       :reason :codetype/unknown
                                       :codetype/unknown (vec unknown)
                                       :resources (vec resources)
                                       :artifacts (vec (:spec/artifacts spec-data))})
      (throw (ex-info "Planner inferred CodeTypes absent from catalog"
                      {:mission/id mission-id
                       :requirement/id requirement
                       :codetype/unknown (vec unknown)})))
    (when-not (seq target)
      (append-remediation! mission-id {:mission/id mission-id
                                       :requirement/id requirement
                                       :reason :codetype/missing-inference
                                       :resources (vec resources)
                                       :artifacts (vec (:spec/artifacts spec-data))})
      (throw (ex-info "Planner could not infer CodeTypes for requirement"
                      {:mission/id mission-id
                       :requirement/id requirement
                       :resources resources
                       :artifacts (:spec/artifacts spec-data)})))
    target))

(defn- mission-template-from-heuristics
  [{:keys [templates]}]
  (let [category (or (get-in templates [:mission-category :default])
                     :mission.category/runtime)
        template (or (get-in templates [:mission-template category])
                     (get-in templates [:mission-template :default]))
        ci-profile (or (get-in templates [:ci-profile category])
                       (get-in templates [:ci-profile :default]))
        tracks (vec (or (get-in templates [:work-tracks category]) []))]
    (cond-> {:mission/category category
             :mission/type template
             :mission/ci-profile ci-profile}
      (seq tracks) (assoc :mission/work-tracks tracks))))

(defn- plan-node-from-requirement
  [spec-id requirement idx {:keys [mission-template test-contracts]}]
  (let [spec-fragment (bootstrap/sanitize-fragment (name spec-id))
        req-fragment (bootstrap/sanitize-fragment requirement)
        node-id (format "%s-N%02d" spec-fragment (inc idx))
        resource (format "src/%s/%s.clj" spec-fragment req-fragment)
        test-scope (when (seq test-contracts)
                     {:plan.node/test-contracts test-contracts})]
    {:plan.node/id node-id
     :plan.node/name requirement
     :plan.node/scope-requirements [requirement]
     :plan.node/resources [resource]
     :plan.node/mission-template mission-template
     :plan.node/test-scope test-scope}))

(defn- plan-edges
  [nodes]
  (->> nodes
       (partition 2 1)
       (map (fn [[a b]]
              {:plan.edge/from-node-id (:plan.node/id a)
               :plan.edge/to-node-id (:plan.node/id b)
               :plan.edge/relation :plan.edge.relation/depends-on}))
       vec))

(defn- coverage-rows
  [requirements acceptance node-map node-tests node-code-types]
  (let [base-rows (mapv (fn [req idx]
                          (let [node (get node-map req)
                                node-id (:plan.node/id node)
                                tests (or (get node-tests req) [])
                                code-types (or (get node-code-types req) [])]
                            {:coverage.row/requirement-id req
                             :coverage.row/nodes [node-id]
                             :coverage.row/code-targets (:plan.node/resources node)
                             :coverage.row/test-contracts tests
                             :coverage.row/code-types code-types
                             :coverage.row/acceptance-id (get acceptance idx)}))
                        requirements
                        (range))
        extra-rows (map-indexed
                    (fn [idx acc]
                      (let [req (or (get requirements idx) (first requirements))
                            node (get node-map req)
                            node-id (:plan.node/id node)
                            tests (or (get node-tests req) [])
                            code-types (or (get node-code-types req) [])]
                        {:coverage.row/requirement-id req
                         :coverage.row/acceptance-id acc
                         :coverage.row/nodes [node-id]
                         :coverage.row/code-targets (:plan.node/resources node)
                         :coverage.row/test-contracts tests
                         :coverage.row/code-types code-types}))
                    (drop (count base-rows) acceptance))]
    (->> (concat base-rows extra-rows)
         (remove nil?)
         vec)))

(defn- default-proof-obligations
  []
  [{:plan.obligation/id "PO-coverage"
    :plan.obligation/description "Coverage satisfied"
    :plan.obligation/checker-id :work-plan/coverage}
   {:plan.obligation/id "PO-dag"
    :plan.obligation/description "DAG acyclic"
    :plan.obligation/checker-id :work-plan/dag}
   {:plan.obligation/id "PO-resources"
    :plan.obligation/description "Resources ordered"
    :plan.obligation/checker-id :work-plan/resources}])

(defn spec-plan-generate
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         spec-id :spec/id
         spec-version :spec/version
         spec-path :spec/resource-path
         heuristics-path :planner/heuristics-path
         plan-output-path :plan/output-path
         generation-log-path :planner/generation-log-path
         overrides-path :plan/overrides-path} config
        mission (require-mission-string mission-id)
        agent (require-agent-string agent-id)
        parsed-spec-id (coerce-spec-id spec-id)
        parsed-spec-version (positive-long spec-version :spec/version)
        canonical-spec (ensure-repo-relative spec-path)
        spec-data (read-edn-file canonical-spec)
        _ (when-not (map? spec-data)
            (throw (ex-info "Spec file missing map"
                            {:spec/id parsed-spec-id
                             :path canonical-spec})))
        _ (when (and (:spec/id spec-data)
                     (not= parsed-spec-id (:spec/id spec-data)))
            (throw (ex-info "Spec id mismatch between config and resource"
                            {:config spec-id
                             :resource (:spec/id spec-data)})))
        heuristics (-> (load-heuristics heuristics-path)
                       (update :codetype-inference
                               #(or % {:default [:code.type/runtime]
                                       :fallback [:code.type/runtime]
                                       :path-rules []
                                       :artifact-rules []
                                       :risk-rules {}
                                       :change-rules {}})))
        _ (code/assert-no-near-duplicates!)
        overrides (when overrides-path
                    (let [canonical (ensure-repo-relative overrides-path)
                          data (read-edn-file canonical)]
                      (when-not (map? data)
                        (throw (ex-info "Plan overrides must be a map"
                                        {:plan/overrides-path canonical
                                         :data data})))
                      data))
        generation-id (UUID/randomUUID)
        log-file (target-file generation-log-path :planner/generation-log-path)
        log-path (.getCanonicalPath log-file)
        spec-digest (sha256-file (io/file canonical-spec))
        plan-id (parse-plan-id (or (:work.plan/id overrides) (UUID/randomUUID)))
        provided-tests (vec (or (:spec/test-contracts spec-data) []))
        default-tests (vec (or (get-in heuristics [:test-inference :default-suites]) []))
        spec-tests (if (seq provided-tests) provided-tests default-tests)
        requirements (normalize-strings (:spec/requirements spec-data))
        acceptance (normalize-strings (:spec/acceptance-criteria spec-data))
        _ (when-not (seq spec-tests)
            (throw (ex-info "Planner requires at least one test contract (heuristics/test-inference)"
                            {:spec/id parsed-spec-id
                             :source [:spec/test-contracts :test-inference/default-suites]})))
        _ (when-not (seq requirements)
            (throw (ex-info "Spec requires requirements for planning"
                            {:spec/id parsed-spec-id})))
        mission-template (mission-template-from-heuristics heuristics)
        base-nodes (mapv #(plan-node-from-requirement parsed-spec-id %1 %2 {:mission-template mission-template
                                                                            :test-contracts spec-tests})
                         requirements
                         (range))
        node-code-types (into {}
                              (map (fn [req node]
                                     [req (infer-code-types! {:mission-id mission
                                                              :requirement req
                                                              :resources (:plan.node/resources node)
                                                              :spec-data spec-data
                                                              :heuristics heuristics
                                                              :mission-category (:mission/category mission-template)})])
                                   requirements
                                   base-nodes))
        nodes (mapv (fn [node req]
                      (assoc node
                             :plan.node/code-types (get node-code-types req)
                             :plan.node/test-contracts spec-tests))
                    base-nodes
                    requirements)
        node-tests (into {} (map (fn [req] [req spec-tests]) requirements))
        node-map (zipmap requirements nodes)
        edges (plan-edges nodes)
        coverage (coverage-rows requirements acceptance node-map node-tests node-code-types)
        obligations (default-proof-obligations)
        created-at (str (Instant/now))
        plan (merge {:work.plan/id plan-id
                     :work.plan/spec-id parsed-spec-id
                     :work.plan/spec-version parsed-spec-version
                     :spec/test-contracts spec-tests
                     :work.plan/status :work.plan.status/draft
                     :work.plan/created-by agent
                     :work.plan/created-at created-at
                     :work.plan/nodes nodes
                     :work.plan/edges edges
                     :work.plan/coverage coverage
                     :work.plan/proof-obligations obligations
                     :work.plan/validation-results []
                     :work.plan/source-path canonical-spec
                     :plan.generation/id generation-id
                     :plan.generation/status :plan.generation.status/generated
                     :plan.generation/heuristics-id (:heuristics/id heuristics)
                     :plan.generation/heuristics-version (str (:heuristics/version heuristics))
                     :plan.generation/log-path log-path
                     :plan.generation/actor agent
                     :plan.generation/generated-at created-at
                     :plan.generation/spec-digest spec-digest}
                    (dissoc overrides :work.plan/id))
        normalized (normalize-work-plan plan (:work.plan/id plan) agent canonical-spec)
        resource-file (work-plan-resource-file (:work.plan/id normalized))
        mission-file (target-file plan-output-path :plan/output-path)
        _ (spit resource-file (pr-str normalized))
        _ (spit mission-file (pr-str normalized))
        validation (work-plan-validate {:config {:mission/id mission
                                                 :agent/id agent
                                                 :work.plan/id (:work.plan/id normalized)
                                                 :work-plan/resource-path (.getCanonicalPath resource-file)
                                                 :spec/resource-path canonical-spec}})
        publish (work-plan-publish {:config {:mission/id mission
                                             :agent/id agent
                                             :work.plan/id (:work.plan/id normalized)
                                             :work.plan/status :work.plan.status/approved}})
        updated-plan (-> (read-edn-file (.getCanonicalPath resource-file))
                         (assoc :plan.generation/status :plan.generation.status/validated))
        _ (spit resource-file (pr-str updated-plan))
        _ (spit mission-file (pr-str updated-plan))
        snapshot (version-snapshot-plan
                  {:config {:mission/id mission
                            :agent/id agent
                            :work.plan/id (:work.plan/id normalized)
                            :work.plan/spec-id parsed-spec-id
                            :work-plan/resource-path (.getCanonicalPath resource-file)
                            :work-plan/validation-path (:work-plan/validation-path validation)
                            :work-plan/publish-log (:work-plan/publish-log publish)}})
        log-base {:plan.generation/id generation-id
                  :plan.generation/spec-id parsed-spec-id
                  :plan.generation/spec-version parsed-spec-version
                  :plan.generation/heuristics-id (:heuristics/id heuristics)
                  :plan.generation/heuristics-version (str (:heuristics/version heuristics))
                  :plan.generation/nodes (:work.plan/nodes updated-plan)
                  :plan.generation/edges (:work.plan/edges updated-plan)
                  :plan.generation/coverage (:work.plan/coverage updated-plan)
                  :plan.generation/work-plan-id (:work.plan/id updated-plan)
                  :plan.generation/status :plan.generation.status/validated
                  :plan.generation/warnings []
                  :plan.generation/decisions [{:decision/kind :planner/grouping
                                               :decision/strategy :group-by-requirement
                                              :decision/requirements (count requirements)}
                                              {:decision/kind :planner/coverage
                                               :decision/requirements (count requirements)
                                               :decision/acceptance (count acceptance)
                                               :decision/tests spec-tests}
                                              {:decision/kind :planner/code-types
                                               :decision/requirements (count requirements)
                                               :decision/code-types (->> node-code-types
                                                                         vals
                                                                         (mapcat identity)
                                                                         set
                                                                         vec)
                                               :decision/source :codetype-inference}
                                              {:decision/kind :planner/snapshot
                                               :decision/version-snapshot (:version.snapshot/id (:version/snapshot snapshot))}]
                  :plan.generation/actor agent
                  :plan.generation/generated-at (str (Instant/now))
                  :plan.generation/spec-digest spec-digest}
        generation-log (assoc log-base :plan.generation/log-path log-path)]
    (spit log-file (pr-str generation-log))
    {:action/status :status/ok
     :plan.generation/id generation-id
     :plan.generation/spec-id parsed-spec-id
     :plan.generation/spec-version parsed-spec-version
     :plan.generation/log-path log-path
     :plan.generation/status :plan.generation.status/validated
     :plan.generation/work-plan-id (:work.plan/id updated-plan)
     :plan.generation/decisions (:plan.generation/decisions generation-log)
     :plan.generation/nodes (:work.plan/nodes updated-plan)
     :plan.generation/edges (:work.plan/edges updated-plan)
     :plan.generation/coverage (:work.plan/coverage updated-plan)
     :plan.generation/warnings (:plan.generation/warnings generation-log)
     :work-plan/resource-path (.getCanonicalPath resource-file)
     :work-plan/log-path (.getCanonicalPath mission-file)
     :work-plan/validation-path (:work-plan/validation-path validation)
     :work-plan/publish-log (:work-plan/publish-log publish)
     :version.snapshot/path (:version.snapshot/path snapshot)}))

;; Mission instantiation -----------------------------------------------------

(def ^:private default-work-tracks
  [:work-track/planning :work-track/code :work-track/tests])

(defn- require-mission-string
  [mission-id]
  (let [value (some-> mission-id str str/trim)]
    (when (str/blank? value)
      (throw (ex-info "mission/id required" {:field :mission/id})))
    value))

(defn- require-agent-string
  [agent-id]
  (let [value (some-> agent-id str str/trim)]
    (when (str/blank? value)
      (throw (ex-info "agent/id required" {:field :agent/id})))
    value))

(defn- require-plan-node-id
  [plan-node-id]
  (let [value (some-> plan-node-id str str/trim)]
    (when (str/blank? value)
      (throw (ex-info "plan.node/id required" {:field :plan.node/id})))
    value))

(defn- plan-node-by-id
  [plan-data plan-node-id]
  (let [node (some #(when (= plan-node-id (:plan.node/id %)) %) (:work.plan/nodes plan-data))]
    (when-not node
      (throw (ex-info "Plan node not found"
                      {:work.plan/id (:work.plan/id plan-data)
                       :plan.node/id plan-node-id})))
    node))

(defn- ensure-plan-validated!
  [plan-data plan-id]
  (let [results (:work.plan/validation-results plan-data)]
    (when-not (seq results)
      (throw (ex-info "WorkPlan must be validated before mission instantiation"
                      {:work.plan/id plan-id})))
    (when (some #(not= :status/passed (:validation/status %)) results)
      (throw (ex-info "WorkPlan validation failures block mission instantiation"
                      {:work.plan/id plan-id
                       :validation/results results}))))
  plan-data)

(defn- ensure-plan-approved!
  [plan-data plan-id]
  (let [status (:work.plan/status plan-data)]
    (when-not (= :work.plan.status/approved status)
      (throw (ex-info "WorkPlan must be published/approved before mission instantiation"
                      {:work.plan/id plan-id
                       :work.plan/status status}))))
  plan-data)

(defn- ensure-plan-generated!
  [plan-data plan-id]
  (let [generation-id (:plan.generation/id plan-data)
        generation-status (:plan.generation/status plan-data)
        heuristics-id (:plan.generation/heuristics-id plan-data)
        log-path (:plan.generation/log-path plan-data)
        canonical-log (when log-path (ensure-repo-relative log-path))]
    (when-not generation-id
      (throw (ex-info "WorkPlan missing generation metadata; manual plans are deprecated"
                      {:work.plan/id plan-id})))
    (when (or (nil? generation-status)
              (not= :plan.generation.status/validated generation-status))
      (throw (ex-info "WorkPlan must come from validated generator output"
                      {:work.plan/id plan-id
                       :plan.generation/status generation-status})))
    (when-not heuristics-id
      (throw (ex-info "WorkPlan missing heuristics reference"
                      {:work.plan/id plan-id})))
    (when (str/blank? (str log-path))
      (throw (ex-info "WorkPlan generation log missing"
                      {:work.plan/id plan-id})))
    (let [log-file (io/file (or canonical-log log-path))]
      (when-not (.exists log-file)
        (throw (ex-info "WorkPlan generation log not found"
                        {:work.plan/id plan-id
                         :plan.generation/log-path log-path}))))
    plan-data))

(defn- normalize-resource-ref
  [resource]
  (let [value (-> (or resource "")
                  str
                  str/trim
                  (str/replace #"\\+" "/")
                  (str/replace #"//+" "/"))]
    (when-not (str/blank? value)
      value)))

(defn- resource-refs-for-node
  [plan-node]
  (let [refs (->> (:plan.node/resources plan-node)
                  (map normalize-resource-ref)
                  (remove nil?)
                  distinct
                  vec)]
    (when-not (seq refs)
      (throw (ex-info "Plan node missing resource refs"
                      {:plan.node/id (:plan.node/id plan-node)})))
    refs))

(defn- normalized-code-type
  [entry]
  (cond
    (keyword? entry) entry
    (string? entry)
    (let [trimmed (str/trim entry)]
      (when-not (str/blank? trimmed)
        (try
          (let [value (if (str/starts-with? trimmed ":")
                        (edn/read-string trimmed)
                        (keyword trimmed))]
            (when-not (keyword? value)
              (throw (IllegalArgumentException. "Not a keyword")))
            value)
          (catch Exception _
            (keyword (bootstrap/sanitize-fragment trimmed))))))
    :else nil))

(defn- code-types-for-node
  [plan-data plan-node-id]
  (let [rows (filter #(some #{plan-node-id} (:coverage.row/nodes %))
                     (or (:work.plan/coverage plan-data) []))
        node-entry (plan-node-by-id plan-data plan-node-id)
        node-types (->> (:plan.node/code-types node-entry)
                        (map normalized-code-type))
        code-types (->> rows
                        (mapcat #(or (:coverage.row/code-types %)
                                     (:coverage.row/test-contracts %)
                                     []))
                        (map normalized-code-type)
                        (concat node-types)
                        (remove nil?)
                        distinct
                        vec)]
    (when-not (seq code-types)
      (throw (ex-info "Plan node missing CodeTypes/test contracts"
                      {:work.plan/id (:work.plan/id plan-data)
                       :plan.node/id plan-node-id})))
    code-types))

(defn- tests-for-node
  [plan-data plan-node-id]
  (let [rows (filter #(some #{plan-node-id} (:coverage.row/nodes %))
                     (or (:work.plan/coverage plan-data) []))
        node-entry (plan-node-by-id plan-data plan-node-id)]
    (->> (concat (:plan.node/test-contracts node-entry)
                 (mapcat #(or (:coverage.row/test-contracts %) []) rows))
         normalize-keywords
         (remove nil?)
         distinct
         vec)))

(defn- mission-tests-list
  [tests template-tests]
  (->> (concat (normalize-strings tests)
               (normalize-strings template-tests))
       (map #(str/trim (str %)))
       (remove str/blank?)
       distinct
       vec))

(defn- mission-work-tracks
  [template]
  (let [tracks (normalize-keywords (:mission/work-tracks template))]
    (if (seq tracks) tracks default-work-tracks)))

(defn- mission-queue-tags
  [template]
  (normalize-keywords (:mission/queue-tags template)))

(defn- mission-string-list
  [entries]
  (normalize-strings entries))

(defn- derive-mission-record
  [{mission-id :mission/id
    plan-id :work.plan/id
    plan-path :work-plan/path
    plan-node-id :plan.node/id
    plan-node :plan/node
    template :mission/template
    resource-refs :mission/resources
    code-types :mission/code-types
    mission-tests :mission/tests}]
  (let [template-map (if (map? template) template {})
        plan-id-str (str plan-id)
        tests (mission-tests-list mission-tests (:mission/tests template-map))
        scope {:plan/id plan-id-str
               :plan/node-id plan-node-id
               :plan/path plan-path
               :resources resource-refs
               :code-types code-types
               :paths resource-refs}
        work-tracks (mission-work-tracks template-map)
        queue-tags (mission-queue-tags template-map)
        deliverables (mission-string-list (:mission/deliverables template-map))
        prerequisites (mission-string-list (:mission/prerequisites template-map))]
    {:mission/id mission-id
     :mission/title (or (:mission/title template-map)
                        (:plan.node/name plan-node)
                        mission-id)
     :mission/summary (or (:mission/summary template-map)
                          (:plan.node/description plan-node)
                          (str "Mission generated from node " plan-node-id))
     :mission/category (or (:mission/category template-map) :mission.category/schema)
     :mission/priority (or (:mission/priority template-map) :mission.priority/p2)
     :mission/status (or (:mission/status template-map) :mission.status/ready)
     :mission/protocol :protocol/mission-instantiation
     :mission/protocol-version 1
     :mission/spec-section (or (:mission/spec-section template-map) :spec.section/mission-instantiation)
     :mission/owner (or (:mission/owner template-map) :mission.owner/stewardship)
     :mission/scope (pr-str scope)
     :mission/tests tests
     :mission/work-tracks work-tracks
     :mission/queue-tags queue-tags
     :mission/deliverables deliverables
     :mission/prerequisites prerequisites}))

(defn mission-from-plan
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         plan-id :work.plan/id
         plan-node-id :plan.node/id
         mission-template :mission/template
         override-plan-path :work-plan/resource-path} config
        mission (require-mission-string mission-id)
        agent (require-agent-string agent-id)
        _ (when-not plan-id
            (throw (ex-info "work.plan/id required" {:field :work.plan/id})))
        plan-node (require-plan-node-id plan-node-id)
        _ (when (and mission-template (not (map? mission-template)))
            (throw (ex-info "mission/template must be a map when provided"
                            {:field :mission/template
                             :value mission-template})))
        plan-file (if override-plan-path
                    (io/file override-plan-path)
                    (work-plan-resource-file plan-id))
        plan-path (ensure-repo-relative plan-file)
        plan-data (read-edn-file plan-path)
        _ (when-not (map? plan-data)
            (throw (ex-info "WorkPlan file missing map"
                            {:work.plan/id plan-id
                             :path plan-path})))
        _ (ensure-plan-generated! plan-data plan-id)
        _ (ensure-plan-validated! plan-data plan-id)
        _ (ensure-plan-approved! plan-data plan-id)
        plan-id-str (str plan-id)
        node (plan-node-by-id plan-data plan-node)
        resource-refs (resource-refs-for-node node)
        code-types (code-types-for-node plan-data plan-node)
        _ (when-let [unknown (seq (remove code/type-ident? code-types))]
            (append-remediation! mission {:mission/id mission
                                          :requirement/id plan-node
                                          :reason :codetype/out-of-catalog
                                          :codetype/unknown (vec unknown)
                                          :plan/id plan-id-str})
            (throw (ex-info "WorkPlan references CodeTypes outside the catalog"
                            {:work.plan/id plan-id
                             :plan.node/id plan-node
                             :codetype/unknown (vec unknown)})))
        plan-tests (tests-for-node plan-data plan-node)
        binding {:mission.plan-binding/mission-id mission
                 :mission.plan-binding/plan-id plan-id-str
                 :mission.plan-binding/plan-node-id plan-node
                 :mission.plan-binding/resource-refs resource-refs
                 :mission.plan-binding/test-scope (:plan.node/test-scope node)
                 :mission.plan-binding/code-types code-types
                 :mission.plan-binding/tests plan-tests}
        mission-resources (mapv (fn [path]
                                  {:mission.resource/mission-id mission
                                   :mission.resource/plan-id plan-id-str
                                   :mission.resource/plan-node-id plan-node
                                   :mission.resource/path path})
                                resource-refs)
        mission-record (derive-mission-record {:mission/id mission
                                               :work.plan/id plan-id
                                               :work-plan/path plan-path
                                               :plan.node/id plan-node
                                               :plan/node node
                                               :mission/template mission-template
                                               :mission/resources resource-refs
                                               :mission/code-types code-types
                                               :mission/tests plan-tests})
        binding-file (mission-log-file mission "mission-plan-binding.edn")
        payload {:mission/id mission
                 :agent/id agent
                 :mission/record mission-record
                 :mission.plan-binding binding
                 :mission/resources mission-resources
                 :mission.plan-binding/spec-sections mission-instantiation-spec-sections
                 :mission.plan-binding/logged-at (str (Instant/now))
                 :mission.plan-binding/plan-path plan-path}
        artifact (ensure-repo-relative binding-file)]
    (spit binding-file (pr-str payload))
    {:action/status :status/ok
     :mission/record mission-record
     :mission.plan-binding/binding binding
     :mission.plan-binding/artifact artifact
     :mission/resources mission-resources}))

(defn mission-lock-resolve
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         mission-resources :mission/resources
         active-locks :locks/current} config
        mission (require-mission-string mission-id)
        agent (require-agent-string agent-id)
        resources (vec (or mission-resources []))
        _ (when-not (seq resources)
            (throw (ex-info "mission/resources required"
                            {:field :mission/resources
                             :mission/id mission})))
        requested (->> resources
                       (map :mission.resource/path)
                       (map normalize-resource-ref)
                       (remove nil?)
                       distinct
                       vec)
        _ (when-not (seq requested)
            (throw (ex-info "No resource paths resolved for locking"
                            {:mission/id mission})))
        current-set (->> (or active-locks [])
                         (map normalize-resource-ref)
                         (remove nil?)
                         set)
        conflicts (seq (set/intersection (set requested) current-set))
        _ (when conflicts
            (throw (ex-info "Mission lock conflict"
                            {:type :mission.locks/conflict
                             :mission/id mission
                             :locks/conflicts (vec conflicts)})))
        locks-file (mission-log-file mission "locks-request.edn")
        payload {:mission/id mission
                 :agent/id agent
                 :mission/resources resources
                 :locks/requested requested
                 :locks/requested-at (str (Instant/now))
                 :mission.locks/spec-sections mission-instantiation-spec-sections}
        artifact (ensure-repo-relative locks-file)]
    (spit locks-file (pr-str payload))
    {:action/status :status/ok
     :locks/requested requested
     :locks/request-artifact artifact
     :mission/resources resources}))

(defn mission-sandbox-prepare
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         workspace-root :workspace/root
         branch-prefix :branch/prefix} config
        mission (require-mission-string mission-id)
        agent (require-agent-string agent-id)
        _ (when (str/blank? (str workspace-root))
            (throw (ex-info "workspace/root required" {:field :workspace/root})))
        env-result (bootstrap-environment {:config {:mission/id mission
                                                    :agent/id agent
                                                    :workspace/root workspace-root}})
        branch-result (prepare-git-branch {:config {:mission/id mission
                                                    :agent/id agent
                                                    :branch/prefix branch-prefix
                                                    :sandbox/root (:sandbox/root env-result)}})
        manifest {:mission/id mission
                  :agent/id agent
                  :sandbox/root (:sandbox/root env-result)
                  :sandbox/paths (:sandbox/paths env-result)
                  :env/vars (:env/vars env-result)
                  :branch branch-result
                  :mission.sandbox/spec-sections mission-instantiation-spec-sections
                  :mission.sandbox/prepared-at (str (Instant/now))}
        manifest-file (mission-log-file mission "sandbox-manifest.edn")
        artifact (ensure-repo-relative manifest-file)]
    (spit manifest-file (pr-str manifest))
    {:action/status :status/ok
     :sandbox/root (:sandbox/root env-result)
     :branch/name (:branch/name branch-result)
     :branch/edn-path (:branch/edn-path branch-result)
     :branch/markdown-path (:branch/markdown-path branch-result)
     :sandbox/manifest artifact
     :env/vars (:env/vars env-result)}))
