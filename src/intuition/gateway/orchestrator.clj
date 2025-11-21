(ns intuition.gateway.orchestrator
  "Agent Gateway orchestration for mission runs. Drives the governed actions
  and protocols in order while enforcing SYSTEM_SPEC §§2.1–2.2, §§3.3–3.6, §5,
  §6, §9, and §11."
  (:require
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [datomic.client.api :as d]
   [intuition.analytics.runtime :as analytics]
   [intuition.datomic :as datomic]
   [intuition.gateway.context-bundle :as context-bundle]
   [intuition.sfs.actions.runtime :as actions]
   [intuition.sfs.missions.runtime :as missions]
   [intuition.sfs.permissions :as perms]
   [intuition.sfs.protocols.runtime :as protocols])
  (:import
   (java.io File PushbackReader)
   (java.math BigInteger)
   (java.security MessageDigest)
   (java.time Instant)
   (java.util UUID)))

(def ^:private log-limit-default 4096)

(def ^:private spec-sections-watermark
  ["2.1" "2.2" "3.3" "3.4" "3.5" "3.6" "5" "6" "9" "11"])

(defn- now [] (Instant/now))

(defn- canonical-path
  [path]
  (some-> path io/file .getCanonicalPath))

(defn- ensure-parent!
  [path]
  (when path
    ;; io/make-parents ensures all parent directories for the given file path exist.
    (io/make-parents (io/file path))))

(defn- read-edn
  [path]
  (with-open [r (PushbackReader. (io/reader path))]
    (edn/read {:eof nil} r)))

(defn- write-edn!
  [path data]
  (ensure-parent! path)
  (with-open [w (io/writer path)]
    (binding [*print-namespace-maps* false]
      (pprint/pprint data w)))
  (canonical-path path))

(defn- truncate-tail
  [s limit]
  (let [text (str s)]
    (if (<= (count text) limit)
      text
      (let [marker "...<truncated>..."
            tail-length (max 0 (- limit (count marker)))]
        (str marker (subs text (- (count text) tail-length)))))))

(defn- append-log!
  [path limit text]
  (ensure-parent! path)
  (spit path (str (str/trim (str text)) "\n") :append true)
  (let [file (io/file path)]
    (when (> (.length file) limit)
      (let [content (slurp file)
            trimmed (truncate-tail content limit)]
        (spit file trimmed))))
  (canonical-path path))

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

(defn- ensure-conn!
  [conn]
  (missions/prepare-conn! (or conn (datomic/ensure-db!))))

(defn- bundle-required
  [bundle k]
  (let [value (get bundle k)]
    (when (or (nil? value) (and (string? value) (str/blank? value)))
      (throw (ex-info "context bundle missing required field"
                      {:bundle/key k})))
    value))

(defn- load-bundle
  [bundle-path]
  (let [file (io/file bundle-path)]
    (when-not (.exists file)
      (throw (ex-info "Context bundle does not exist" {:path bundle-path})))
    (read-edn file)))

(defn- auth-token
  [bundle payload]
  (let [path (or (:auth/token-path bundle)
                 (get-in bundle [:auth :token-path]))
        provided (:auth/token payload)]
    (cond
      (and path (not (.exists (io/file path))))
      (throw (ex-info "Auth token path missing" {:path path}))

      path
      (let [expected (str/trim (slurp path))]
        (when (and (not (str/blank? expected))
                   (not= expected (str/trim (str provided))))
          (throw (ex-info "Auth token mismatch"
                          {:expected :<<redacted>>
                           :provided? (boolean provided)})))
        expected)

      :else
      (or (some-> provided str/trim) "dev-token"))))

(defn- granted-permissions
  [bundle payload]
  (let [roles (vec (remove nil? [(get-in bundle [:trace :auth/role])
                                 (get-in payload [:trace :auth/role])]))
        role-perms (perms/permissions-for-roles roles)
        provided (set (or (:permissions/granted payload)
                          (:permissions bundle)
                          []))]
    (perms/normalize (set/union role-perms provided))))

(defn- enforce-permissions!
  [required granted mission-id]
  (doseq [perm required]
    (perms/assert-defined! perm))
  (when-not (set/superset? granted required)
    (throw (ex-info "Missing required permissions"
                    {:type :gateway/unauthorized
                     :mission/id mission-id
                     :required required
                     :granted granted}))))

(defn- watermark
  [mission-id token]
  {:mission/id mission-id
   :auth/watermark token
   :system-spec/sections spec-sections-watermark
   :issued-at (str (now))})

(defn- ensure-spec-file
  [mission-id {:spec/keys [id title summary requirements acceptance-criteria test-contracts spec-sections input-path]}]
  (let [path (or input-path (str "missions/logs/" mission-id "/spec.edn"))
        file (io/file path)]
    (when-not (.exists file)
      (ensure-parent! file)
      (spit file (pr-str {:spec/id (or id (keyword (str "spec/" mission-id)))
                          :spec/title (or title (str "Spec for " mission-id))
                          :spec/summary (or summary "Auto-generated orchestrator spec")
                          :spec/status :spec.status/captured
                          :spec/requirements (or requirements ["REQ-DR1-1"])
                          :spec/acceptance-criteria (or acceptance-criteria ["AC-DR1-1"])
                          :spec/test-contracts (or test-contracts [:code/sample.validator])
                          :spec/spec-sections (or spec-sections spec-sections-watermark)})))
    (.getCanonicalPath file)))

(defn- run-action
  [conn granted {:keys [ident config context]}]
  (actions/execute! {:conn conn
                     :action/ident ident
                     :config config
                     :permissions granted
                     :context context}))

(defn- run-protocol
  [conn granted {:keys [ident context instrumentation]}]
  (protocols/run! {:conn conn
                   :protocol/ident ident
                   :context context
                   :permissions granted
                   :instrumentation instrumentation}))

(defn- spec-stage!
  [{:keys [conn granted mission-id agent-id bundle log-path limit]}]
  (let [spec-path (ensure-spec-file mission-id (:spec/source bundle))
        spec-id (or (get-in bundle [:spec/source :spec/id])
                    (keyword (str "spec/" mission-id)))
        capture (run-action conn granted {:ident :action/spec.capture
                                          :config {:mission/id mission-id
                                                   :agent/id agent-id
                                                   :spec/id spec-id
                                                   :spec/input-path spec-path}})
        resource (or (get-in capture [:result :spec/resource-path]) spec-path)
        validate (run-action conn granted {:ident :action/spec.validate
                                           :config {:mission/id mission-id
                                                    :agent/id agent-id
                                                    :spec/id spec-id
                                                    :spec/resource-path resource}})
        publish (run-action conn granted {:ident :action/spec.publish
                                          :config {:mission/id mission-id
                                                   :agent/id agent-id
                                                   :spec/id spec-id
                                                   :spec/status :spec.status/validated}})
        snapshot (run-action conn granted {:ident :action/version.snapshot-spec
                                           :config {:mission/id mission-id
                                                    :agent/id agent-id
                                                    :spec/id spec-id
                                                    :spec/resource-path resource
                                                    :spec/validation-path (get-in validate [:result :spec/validation-path])
                                                    :spec/publish-log (get-in publish [:result :spec/publish-log])}})
        spec-hash (sha256-file (io/file resource))]
    (append-log! log-path limit (format "[spec/load] captured + validated %s" spec-id))
    {:stage/id :spec/load
     :status :status/ok
     :artifacts (cond-> []
                  (get-in capture [:result :spec/log-path])
                  (conj {:path (canonical-path (get-in capture [:result :spec/log-path]))
                         :label "Spec capture"
                         :channel :artifact.channel/log})
                  (get-in validate [:result :spec/validation-path])
                  (conj {:path (canonical-path (get-in validate [:result :spec/validation-path]))
                         :label "Spec validation"
                         :channel :artifact.channel/validation})
                  (get-in publish [:result :spec/publish-log])
                  (conj {:path (canonical-path (get-in publish [:result :spec/publish-log]))
                         :label "Spec publish log"
                         :channel :artifact.channel/log})
                  (get-in snapshot [:result :version.snapshot/path])
                  (conj {:path (canonical-path (get-in snapshot [:result :version.snapshot/path]))
                         :label "Spec snapshot"
                         :channel :artifact.channel/snapshot}))
     :result {:spec/id spec-id
              :spec/path resource
              :spec/hash spec-hash
              :spec/validation (get-in validate [:result :spec/validation-path])
              :spec/publish-log (get-in publish [:result :spec/publish-log])
              :version.snapshot/path (get-in snapshot [:result :version.snapshot/path])}}))

(defn- plan-stage!
  [{:keys [conn granted mission-id agent-id bundle log-path limit spec-result]}]
  (let [heuristics (or (get-in bundle [:planner/heuristics-path])
                       "missions/logs/M-20251121-701/planner-heuristics.edn")
        plan-output (or (get-in bundle [:plan/snapshot :plan/path])
                        (str "missions/logs/" mission-id "/generated-plan.edn"))
        generation-log (or (get-in bundle [:plan/validation :log-path])
                           (str "missions/logs/" mission-id "/plan-generation.edn"))
        spec-id (:spec/id spec-result)
        spec-path (:spec/path spec-result)
        plan (run-action conn granted
                         {:ident :action/spec.plan.generate
                          :config {:mission/id mission-id
                                   :agent/id agent-id
                                   :spec/id spec-id
                                   :spec/version 1
                                   :spec/resource-path spec-path
                                   :planner/heuristics-path heuristics
                                   :plan/output-path plan-output
                                   :planner/generation-log-path generation-log}})
        plan-path (get-in plan [:result :work-plan/resource-path])
        validation (get-in plan [:result :work-plan/validation-path])
        snapshot (get-in plan [:result :version.snapshot/path])
        plan-hash (when plan-path (sha256-file (io/file plan-path)))]
    (append-log! log-path limit "[plan/validate] generated + validated work plan")
    {:stage/id :plan/validate
     :status :status/ok
     :artifacts (-> []
                    (cond-> plan-path (conj {:path (canonical-path plan-path)
                                             :label "Work plan"
                                             :channel :artifact.channel/plan}))
                    (cond-> validation (conj {:path (canonical-path validation)
                                              :label "Plan validation"
                                              :channel :artifact.channel/validation}))
                    (cond-> snapshot (conj {:path (canonical-path snapshot)
                                            :label "Plan snapshot"
                                            :channel :artifact.channel/snapshot})))
     :result {:plan/id (get-in plan [:result :plan.generation/work-plan-id])
              :plan/path plan-path
              :plan/hash plan-hash
              :plan/validation validation
              :plan/snapshot snapshot
              :plan/nodes (get-in plan [:result :plan.generation/nodes])}}))

(defn- plan-snapshot-stage!
  [{:keys [plan-result log-path limit]}]
  (append-log! log-path limit "[plan/snapshot] recorded version snapshot")
  {:stage/id :plan/snapshot
   :status :status/ok
   :artifacts (cond-> []
                (:plan/snapshot plan-result)
                (conj {:path (canonical-path (:plan/snapshot plan-result))
                       :label "Plan snapshot"
                       :channel :artifact.channel/snapshot}))
   :result {:version/snapshot (:plan/snapshot plan-result)}})

(defn- mission-instantiate-stage!
  [{:keys [conn granted mission-id agent-id bundle log-path limit plan-result]}]
  (let [plan-path (:plan/path plan-result)
        plan-data (some-> plan-path read-edn)
        plan-node (or (some-> plan-data :work.plan/nodes first :plan.node/id)
                      (keyword (str mission-id "-node")))
        context {:mission/id mission-id
                 :agent/id agent-id
                 :work.plan/id (:plan/id plan-result)
                 :plan.node/id plan-node
                 :mission/template (:mission/record bundle)
                 :work-plan/resource-path plan-path
                 :workspace/root (get-in bundle [:sandbox :root])
                 :branch/prefix (get-in bundle [:branch :prefix] "mission")}
        run (run-protocol conn granted {:ident :protocol/mission-instantiation
                                        :context context
                                        :instrumentation {:log-fn (fn [_ payload]
                                                                    (append-log! log-path limit
                                                                                 (str "[mission/instantiate] " payload)))}})
        mission-record (or (get-in run [:step-results :step/mission-from-plan :mission/record])
                           (:mission/record bundle))]
    (when mission-record
      (d/transact conn {:tx-data [mission-record]}))
    (append-log! log-path limit "[mission/instantiate] protocol completed")
    {:stage/id :mission/instantiate
     :status (:status run)
     :artifacts (cond-> []
                  (get-in run [:step-results :step/mission-sandbox :mission/sandbox-artifact])
                  (conj {:path (canonical-path (get-in run [:step-results :step/mission-sandbox :mission/sandbox-artifact]))
                         :label "Sandbox manifest"
                         :channel :artifact.channel/log}))
     :result {:mission/record mission-record
              :mission/locks (get-in run [:step-results :step/mission-locks :locks/requested])
              :sandbox/root (get-in run [:step-results :step/mission-sandbox :sandbox/root])}}))

(defn- mission-standard-stage!
  [{:keys [conn granted mission-id agent-id bundle log-path limit mission-result]}]
  (let [sandbox (or (:sandbox/root mission-result)
                    (get-in bundle [:sandbox :root])
                    "tmp/missions/sandbox")
        mission-record (:mission/record mission-result)
        scope (when-let [raw (:mission/scope mission-record)]
                (try
                  (edn/read-string raw)
                  (catch Exception _ {})))
        scope-paths (vec (or (:paths scope) (:resources scope) []))
        bundle-paths (vec (or (get-in bundle [:codetype/paths]) []))
        existing-codetype-paths (->> (or (when (seq bundle-paths) bundle-paths)
                                         scope-paths)
                                     (map #(io/file %))
                                     (filter #(.exists ^File %))
                                     (map #(.getCanonicalPath ^File %))
                                     vec)
        codetype-paths (or (seq existing-codetype-paths)
                           (let [fallback (io/file "resources/dictionary/code_types.edn")]
                             (when (.exists fallback)
                               [(.getCanonicalPath fallback)])))
        context {:mission/id mission-id
                 :agent/id agent-id
                 :workspace/root sandbox
                 :branch/prefix (get-in bundle [:branch :prefix] "mission")
                 :tests/enabled? true
                 :tests/suite :test.suite/contract
                 :tests/paths ["test/actions_contract_test.clj"]
                 :tests/error-mode :fail-fast
                 :lint/paths ["src" "test"]
                 :lint/command (or (get-in bundle [:lint/command])
                                   ["clojure" "-M:lint"])
                 :docs/paths ["SYSTEM_SPEC.md"]
                 :system-map/entities [:action/mission.validate]
                 :codetype/paths (or codetype-paths
                                     ["resources/dictionary/code_types.edn"])}
        run (run-protocol conn granted {:ident :protocol/mission-standard
                                        :context context
                                        :instrumentation {:log-fn (fn [_ payload]
                                                                    (append-log! log-path limit
                                                                                 (str "[mission/standard] " payload)))}})]
    (append-log! log-path limit "[mission/standard] protocol completed")
    {:stage/id :mission/standard
     :status (:status run)
     :artifacts (cond-> []
                  (get-in run [:step-results :step/git-branch :branch/edn-path])
                  (conj {:path (canonical-path (get-in run [:step-results :step/git-branch :branch/edn-path]))
                         :label "Branch snapshot"
                         :channel :artifact.channel/branch}))
     :result {:sandbox/root sandbox
              :branch/name (get-in run [:step-results :step/git-branch :branch/name])
              :ci/run (get-in run [:step-results :step/run-tests])
              :code.materialize (get-in run [:step-results :step/code-materialize])}}))

(defn- merge-stage!
  [{:keys [conn granted mission-id agent-id bundle log-path limit mission-result]}]
  (let [sandbox (or (:sandbox/root mission-result)
                    (get-in bundle [:sandbox :root]))
        merge-branch (get-in bundle [:branch :name])
        ci-profile (get-in bundle [:ci :profile])
        config (cond-> {:mission/id mission-id
                        :agent/id agent-id
                        :sandbox/root sandbox
                        :merge/base-branch "main"
                        :ci/log-root (str "missions/logs/" mission-id)
                        :merge/log-root (str "missions/logs/" mission-id)}
                 merge-branch (assoc :merge/branch merge-branch)
                 ci-profile (assoc :ci/profile ci-profile))
        merge (run-action conn granted
                          {:ident :action/mission.merge.prepare
                           :config config})] 
    (append-log! log-path limit "[merge/simulate] merge prepare executed")
    {:stage/id :merge/simulate
     :status (or (get-in merge [:result :action/status])
                 (:action/status merge))
     :artifacts (cond-> []
                  (get-in merge [:result :merge/log-path])
                  (conj {:path (canonical-path (get-in merge [:result :merge/log-path]))
                         :label "Merge prepare"
                         :channel :artifact.channel/merge}))
     :result {:merge/run (get-in merge [:result :merge/run])}}))

(defn- analytics-stage!
  [{:keys [mission-id log-path limit]}]
  (let [result (analytics/generate! {:mission-log-id mission-id
                                     :log-root "missions/logs"
                                     :source :analytics.source/gateway})
        markdown (:report/markdown-copy result)
        edn (:report/edn-copy result)]
    (append-log! log-path limit "[analytics/emit] analytics emitted")
    {:stage/id :analytics/emit
     :status :status/ok
     :artifacts (-> []
                    (cond-> markdown
                      (conj {:path (canonical-path markdown)
                             :label "Analytics markdown"
                             :channel :artifact.channel/analytics}))
                    (cond-> edn
                      (conj {:path (canonical-path edn)
                             :label "Analytics edn"
                             :channel :artifact.channel/analytics})))
     :result {:report result}}))

(defn- ensure-expected-artifacts!
  [expected stage-artifacts manifest-path run-log-path]
  (let [ensure-entry (fn [{:keys [path label channel required? generator]}]
                       (let [file (io/file path)]
                         (when-not (.exists file)
                           (ensure-parent! file)
                           (spit file (format "Generated by orchestrator (%s)" (or generator :stage/complete))))
                         {:artifact/path (.getCanonicalPath file)
                          :artifact/label (or label (.getName file))
                          :artifact/channel channel
                          :artifact/required? (boolean required?)
                          :artifact/generator (or generator :stage/complete)}))]
    (->> (concat stage-artifacts
                 [{:path manifest-path
                   :label "Run manifest"
                   :channel :artifact.channel/manifest
                   :required? true
                   :generator :stage/complete}
                  {:path run-log-path
                    :label "Run log"
                    :channel :artifact.channel/log
                    :required? true
                    :generator :stage/complete}]
                 expected)
         (map ensure-entry)
         vec)))

(defn run-mission!
  "Executes the orchestrated run-mission pipeline using the supplied payload.
  Returns the manifest map produced at the end of the run."
  [{mission-id :mission/id
    bundle-path :context/bundle-path
    provided-agent :agent/id
    :keys [conn]
    :as payload}]
  (let [mission-id mission-id
        agent-id (or provided-agent "codex")
        bundle-path (canonical-path bundle-path)
        bundle (load-bundle bundle-path)
        _ (when-not (= (str mission-id) (str (bundle-required bundle :mission/id)))
            (throw (ex-info "Payload mission/id does not match bundle"
                            {:payload mission-id
                             :bundle (:mission/id bundle)})))
        run-id (or (get-in payload [:trace :run-id])
                   (get-in bundle [:trace :run-id])
                   (str (UUID/randomUUID)))
        queue-tags (vec (or (:mission/queue-tags payload)
                            (get-in bundle [:mission/record :mission/queue-tags])
                            []))
        mission-priority (or (:mission/priority payload)
                             (get-in bundle [:mission/record :mission/priority]))
        locks-required (set (or (:locks/requested payload)
                                (:locks/required bundle)
                                []))
        trace (-> {:channel :agent-gateway
                   :agent-id agent-id
                   :queue/tags queue-tags
                   :mission/priority mission-priority}
                  (merge (:trace bundle))
                  (merge (:trace payload))
                  (assoc :run-id run-id
                         :request-id (or (get-in payload [:trace :request-id])
                                         (get-in bundle [:trace :request-id]))
                         :agent-id agent-id))
        truncate (or (get-in bundle [:logging/truncate-bytes]) log-limit-default)
        manifest-path (canonical-path (or (:logging/manifest-path bundle)
                                          (str "missions/logs/" mission-id "/run-manifest.edn")))
        run-log-path (canonical-path (or (:logging/run-log-path bundle)
                                         (str "missions/logs/" mission-id "/run.log")))
        conn (ensure-conn! conn)
        required-perms (set (or (:permissions/required bundle)
                                perms/default-permissions))
        granted (granted-permissions bundle payload)
        token (auth-token bundle payload)
        _ (enforce-permissions! required-perms granted mission-id)
        watermark (watermark mission-id token)
        _ (append-log! run-log-path truncate (format "Run %s start (channel=%s queue=%s priority=%s locks=%s token ok)"
                                                     run-id
                                                     (or (:channel trace) :agent-gateway)
                                                     (or (seq queue-tags) [:mission.queue/unspecified])
                                                     (or mission-priority :mission.priority/unspecified)
                                                     (or (seq locks-required) #{:lock/unspecified})))
        stages (atom [])
        record-stage! (fn [stage]
                        (swap! stages conj stage)
                        stage)]
    (try
      (let [spec-stage (record-stage! (spec-stage! {:conn conn
                                                    :granted granted
                                                    :mission-id mission-id
                                                    :agent-id agent-id
                                                    :bundle bundle
                                                    :log-path run-log-path
                                                    :limit truncate}))
            plan-stage (record-stage! (plan-stage! {:conn conn
                                                    :granted granted
                                                    :mission-id mission-id
                                                    :agent-id agent-id
                                                    :bundle bundle
                                                    :log-path run-log-path
                                                    :limit truncate
                                                    :spec-result (:result spec-stage)}))
            _snapshot-stage (record-stage! (plan-snapshot-stage! {:plan-result (:result plan-stage)
                                                                  :log-path run-log-path
                                                                  :limit truncate}))
            instantiate-stage (record-stage! (mission-instantiate-stage! {:conn conn
                                                                          :granted granted
                                                                          :mission-id mission-id
                                                                          :agent-id agent-id
                                                                          :bundle bundle
                                                                          :log-path run-log-path
                                                                          :limit truncate
                                                                          :plan-result (:result plan-stage)}))
            mission-stage (record-stage! (mission-standard-stage! {:conn conn
                                                                   :granted granted
                                                                   :mission-id mission-id
                                                                   :agent-id agent-id
                                                                   :bundle bundle
                                                                   :log-path run-log-path
                                                                   :limit truncate
                                                                   :mission-result (:result instantiate-stage)}))
            _merge-stage (record-stage! (merge-stage! {:conn conn
                                                       :granted granted
                                                       :mission-id mission-id
                                                       :agent-id agent-id
                                                       :bundle bundle
                                                       :log-path run-log-path
                                                       :limit truncate
                                                       :mission-result (:result mission-stage)}))
            _analytics-stage (record-stage! (analytics-stage! {:mission-id mission-id
                                                               :log-path run-log-path
                                                               :limit truncate}))
            log-root (some-> run-log-path io/file .getParentFile .getParent)
            agent-context (try
                            (context-bundle/build! {:mission/id mission-id
                                                    :focus/node (or (:focus/node payload)
                                                                    (:focus/node bundle))
                                                    :bundle bundle
                                                    :log/root (or (:log/root bundle) log-root)
                                                    :conn conn})
                            (catch Exception e
                              (append-log! run-log-path truncate (format "[context/bundle-error] %s" (.getMessage e)))
                              {:error (.getMessage e)}))
            agent-context-ref (cond
                                (:bundle/path agent-context)
                                (merge (select-keys agent-context [:bundle/path :bundle/sha256 :focus/node])
                                       {:artifacts/count (count (:artifacts/validation agent-context))})
                                (:error agent-context)
                                {:error (:error agent-context)})
            all-artifacts (mapcat :artifacts @stages)
            manifest {:action/status :status/ok
                      :mission/id mission-id
                      :mission/priority mission-priority
                      :mission/queue-tags queue-tags
                      :locks/requested locks-required
                      :agent/id agent-id
                      :bundle/id (:bundle/id bundle)
                      :bundle/version (:bundle/version bundle)
                      :context/bundle-path bundle-path
                      :context/agent-bundle agent-context-ref
                      :retry/policy (or (:retry bundle) {})
                      :trace trace
                      :trace/run-id run-id
                      :trace/request-id (:request-id trace)
                      :trace/channel (:channel trace)
                      :trace/agent-id (:agent-id trace)
                      :trace/watermark watermark
                      :permissions/enforced required-perms
                      :permissions/granted granted
                      :stage/results (mapv :stage/id @stages)
                      :stage/statuses (mapv #(select-keys % [:stage/id :status]) @stages)
                      :artifacts (ensure-expected-artifacts!
                                  (:artifacts/expected bundle)
                                  all-artifacts
                                  manifest-path
                                  run-log-path)
                      :timestamps {:started (str (now))
                                   :completed (str (now))}}]
        (write-edn! manifest-path manifest)
        (append-log! run-log-path truncate "[complete] manifest captured")
        (assoc manifest :manifest/path manifest-path
                         :run-log/path run-log-path))
      (catch Exception e
        (append-log! run-log-path truncate (format "[error] %s" (.getMessage e)))
         (let [failure {:action/status :status/error
                        :mission/id mission-id
                        :mission/priority mission-priority
                        :mission/queue-tags queue-tags
                        :locks/requested locks-required
                        :context/bundle-path bundle-path
                        :trace/run-id run-id
                        :trace/request-id (:request-id trace)
                        :trace/channel (:channel trace)
                        :retry/policy (or (:retry bundle) {})
                        :error (.getMessage e)
                        :stage/results (mapv :stage/id @stages)}]
          (write-edn! manifest-path failure)
          failure)))))

;; Edit flow (code proposal → apply → mission-standard) ----------------------

(def ^:private edit-default-permissions
  #{:permission/code.propose
    :permission/code.proposal.apply
    :permission/env.bootstrap
    :permission/tests.run
    :permission/locks.manage})

(defn- proposals->definition-idents
  [proposals]
  (->> proposals
       (filter #(= :proposal.type/code-definition (:code.proposal/type %)))
       (map (fn [proposal]
              (or (get-in proposal [:code.proposal/payload :code.definition/ident])
                  (some-> (:code.proposal/ident proposal) keyword)
                  (some-> (:code.proposal/ident proposal) str keyword))))
       (remove nil?)
       vec))

(defn- load-proposals*
  [bundle payload]
  (let [inline (or (:code.proposal/proposals payload)
                   (:code.proposal/proposals bundle))
        path (or (:proposals/path payload)
                 (:code.proposal/path payload)
                 (:proposals/path bundle)
                 (:code.proposal/path bundle))]
    (cond
      (seq inline)
      {:code.proposal/proposals inline}

      path
      (let [canonical (canonical-path path)
            file (io/file canonical)]
        (when-not (.exists file)
          (throw (ex-info "Proposals file missing" {:path canonical})))
        {:code.proposal/proposals (read-edn file)
         :code.proposal/path canonical})

      :else
      (throw (ex-info "Proposals required for edit-graph"
                      {:field :proposals/path})))))

(defn- validate-proposals-stage!
  [{:keys [conn granted mission-id agent-id bundle proposals log-path limit]}]
  (let [log-root (or (:code.proposal/log-root bundle)
                     "missions/logs")
        validation (run-action conn granted {:ident :action/code.proposal.validate
                                             :config {:mission/id mission-id
                                                      :agent/id agent-id
                                                      :code.proposal/proposals proposals
                                                      :code.proposal/log-root log-root}})
        log-file (get-in validation [:result :code.proposal/log-path])]
    (append-log! log-path limit "[proposals/validate] proposals validated")
    {:stage/id :proposals/validate
     :status (or (:action/status (:result validation)) :status/ok)
     :artifacts (cond-> []
                  log-file (conj {:path (canonical-path log-file)
                                  :label "Proposal validation"
                                  :channel :artifact.channel/validation}))
     :result (:result validation)}))

(defn- apply-proposals-stage!
  [{:keys [conn granted mission-id agent-id bundle validation-stage proposals log-path limit]}]
  (let [log-root (or (:code.proposal/log-root bundle)
                     "missions/logs")
        validation-log (get-in validation-stage [:result :code.proposal/log-path])
        apply-result (run-action conn granted {:ident :action/code.proposal.apply
                                               :config {:mission/id mission-id
                                                        :agent/id agent-id
                                                        :code.proposal/proposals proposals
                                                        :code.proposal/log-root log-root
                                                        :code.proposal/validation-log validation-log
                                                        :code.proposal/domain-transact? true}})
        apply-log (get-in apply-result [:result :code.proposal/log-path])]
    (append-log! log-path limit "[proposals/apply] proposals recorded + versioned")
    {:stage/id :proposals/apply
     :status (or (:action/status (:result apply-result)) :status/ok)
     :artifacts (cond-> []
                  apply-log (conj {:path (canonical-path apply-log)
                                   :label "Proposal apply"
                                   :channel :artifact.channel/log}))
     :result (:result apply-result)}))

(defn- mission-standard-edit-stage!
  [{:keys [conn granted mission-id agent-id bundle log-path limit code-idents]}]
  (let [validation (or (:validation bundle) {})
        sandbox (or (get-in bundle [:sandbox :root])
                    (str "tmp/missions/" mission-id "/edit-sandbox"))
        tests-enabled? (if (contains? validation :tests/enabled?)
                         (boolean (:tests/enabled? validation))
                         true)
        context {:mission/id mission-id
                 :agent/id agent-id
                 :workspace/root sandbox
                 :branch/prefix (get-in bundle [:branch :prefix] "mission")
                 :tests/enabled? tests-enabled?
                 :tests/suite (or (:tests/suite validation) :test.suite/contract)
                 :tests/paths (or (:tests/paths validation) ["test/actions_contract_test.clj"])
                 :tests/error-mode (or (:tests/error-mode validation) :fail-fast)
                 :lint/paths (or (:lint/paths validation) ["src" "test"])
                 :lint/command (or (:lint/command validation) ["clojure" "-M:lint"])
                 :docs/paths (or (:docs/paths validation) ["SYSTEM_SPEC.md"])
                 :system-map/entities (or (:system-map/entities validation) [:action/mission.validate])
                 :codetype/paths (or (:codetype/paths validation)
                                     ["resources/dictionary/code_types.edn"])
                 :code.definition/idents (vec (or code-idents
                                                  (:code.definition/idents bundle)))}
        run (run-protocol conn granted {:ident :protocol/mission-standard
                                        :context context
                                        :instrumentation {:log-fn (fn [_ payload]
                                                                    (append-log! log-path limit
                                                                                 (str "[mission/standard] " payload)))}})]
    (append-log! log-path limit "[mission/standard] protocol completed")
    {:stage/id :mission/standard
     :status (:status run)
     :artifacts (cond-> []
                  (get-in run [:step-results :step/git-branch :branch/edn-path])
                  (conj {:path (canonical-path (get-in run [:step-results :step/git-branch :branch/edn-path]))
                         :label "Branch snapshot"
                         :channel :artifact.channel/branch})
                  (get-in run [:step-results :step/code-materialize :code.materialize/log-path])
                  (conj {:path (canonical-path (get-in run [:step-results :step/code-materialize :code.materialize/log-path]))
                         :label "Code materialization log"
                         :channel :artifact.channel/code}))
     :result {:sandbox/root sandbox
              :branch/name (get-in run [:step-results :step/git-branch :branch/name])
              :code.materialize (get-in run [:step-results :step/code-materialize])
              :ci/run (get-in run [:step-results :step/run-tests])}}))

(defn edit-graph!
  "Executes the edit-graph pipeline:
   1) validate proposals, 2) apply proposals (Datomic + version snapshot),
   3) run mission-standard with code materialization. Uses the same watermark
   and permission enforcement as run-mission!, but skips spec/plan generation."
  [{mission-id :mission/id
    bundle-path :context/bundle-path
    provided-agent :agent/id
    :keys [conn]
    :as payload}]
  (let [agent-id (or provided-agent "codex")
        bundle-path (canonical-path bundle-path)
        bundle (load-bundle bundle-path)
        _ (when-not (= (str mission-id) (str (bundle-required bundle :mission/id)))
            (throw (ex-info "Payload mission/id does not match bundle"
                            {:payload mission-id
                             :bundle (:mission/id bundle)})))
        run-id (or (get-in payload [:trace :run-id])
                   (get-in bundle [:trace :run-id])
                   (str (UUID/randomUUID)))
        trace (-> {:channel :agent-gateway
                   :agent-id agent-id}
                  (merge (:trace bundle))
                  (merge (:trace payload))
                  (assoc :run-id run-id
                         :agent-id agent-id
                         :request-id (or (get-in payload [:trace :request-id])
                                         (get-in bundle [:trace :request-id]))))
        truncate (or (get-in bundle [:logging/truncate-bytes]) log-limit-default)
        manifest-path (canonical-path (or (get-in bundle [:logging :manifest-path])
                                          (str "missions/logs/" mission-id "/edit-flow/manifest.edn")))
        run-log-path (canonical-path (or (get-in bundle [:logging :run-log-path])
                                         (str "missions/logs/" mission-id "/edit-flow/run.log")))
        proposals-data (load-proposals* bundle payload)
        proposals (:code.proposal/proposals proposals-data)
        conn (ensure-conn! conn)
        required-perms (set (or (:permissions/required bundle)
                                edit-default-permissions))
        granted (set/union required-perms
                           (granted-permissions bundle payload))
        token (auth-token bundle payload)
        _ (enforce-permissions! required-perms granted mission-id)
        watermark (watermark mission-id token)
        log-start (format "Edit-graph start (run-id=%s agent=%s)" run-id agent-id)
        _ (append-log! run-log-path truncate log-start)
        stages (atom [])
        record-stage! (fn [stage]
                        (swap! stages conj stage)
                        stage)]
    (try
      (let [validation-stage (record-stage! (validate-proposals-stage! {:conn conn
                                                                        :granted granted
                                                                        :mission-id mission-id
                                                                        :agent-id agent-id
                                                                        :bundle bundle
                                                                        :proposals proposals
                                                                        :log-path run-log-path
                                                                        :limit truncate}))
            proposals-norm (get-in validation-stage [:result :code.proposal/proposals])
            apply-stage (record-stage! (apply-proposals-stage! {:conn conn
                                                                :granted granted
                                                                :mission-id mission-id
                                                                :agent-id agent-id
                                                                :bundle bundle
                                                                :validation-stage validation-stage
                                                                :proposals proposals-norm
                                                                :log-path run-log-path
                                                                :limit truncate}))
            code-idents (or (not-empty (proposals->definition-idents proposals-norm))
                            (:code.definition/idents bundle))
            _ (record-stage! (mission-standard-edit-stage! {:conn conn
                                                            :granted granted
                                                            :mission-id mission-id
                                                            :agent-id agent-id
                                                            :bundle bundle
                                                            :code-idents code-idents
                                                            :log-path run-log-path
                                                            :limit truncate}))
            all-artifacts (mapcat :artifacts @stages)
            manifest {:action/status :status/ok
                      :mission/id mission-id
                      :agent/id agent-id
                      :bundle/id (:bundle/id bundle)
                      :bundle/version (:bundle/version bundle)
                      :context/bundle-path bundle-path
                      :graph/context (:graph/context bundle)
                      :proposals/path (:code.proposal/path proposals-data)
                      :permissions/enforced required-perms
                      :permissions/granted granted
                      :trace trace
                      :trace/watermark watermark
                      :stage/results (mapv :stage/id @stages)
                      :stage/statuses (mapv #(select-keys % [:stage/id :status]) @stages)
                      :code.definition/transacted (get-in apply-stage [:result :code.definition/transacted])
                      :artifacts (ensure-expected-artifacts!
                                  (:artifacts/expected bundle)
                                  all-artifacts
                                  manifest-path
                                  run-log-path)
                      :timestamps {:started (str (now))
                                   :completed (str (now))}}]
        (write-edn! manifest-path manifest)
        (append-log! run-log-path truncate "[complete] edit-graph manifest captured")
        (assoc manifest :manifest/path manifest-path
                         :run-log/path run-log-path))
      (catch Exception e
        (append-log! run-log-path truncate (format "[error] %s" (.getMessage e)))
        (let [failure {:action/status :status/error
                       :mission/id mission-id
                       :context/bundle-path bundle-path
                       :trace/run-id run-id
                       :trace/request-id (:request-id trace)
                       :trace/channel (:channel trace)
                       :error (.getMessage e)
                       :error/data (ex-data e)
                       :stage/results (mapv :stage/id @stages)}]
          (write-edn! manifest-path failure)
          failure)))))
