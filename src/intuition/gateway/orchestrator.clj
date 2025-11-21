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
   [intuition.code.runtime :as code]
   [intuition.analytics.runtime :as analytics]
   [intuition.datomic :as datomic]
   [intuition.gateway.context-bundle :as context-bundle]
   [intuition.llm.harness :as llm]
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

(def ^:private llm-input-limit 3800)

(declare canonicalize-llm)
(def ^:private llm-plan-resource "dictionary/llm_integration_plan.edn")
(def ^:private llm-modes #{:off :shadow :apply})
(def ^:private llm-system-spec-sections
  ["3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "5.3" "6.2" "7" "9"])
(def ^:private llm-plan*
  (delay
    (try
      (some-> llm-plan-resource io/resource slurp edn/read-string)
      (catch Exception _ {}))))

(defn- vectorize
  [value]
  (cond
    (nil? value) []
    (vector? value) value
    (sequential? value) (vec value)
    :else [value]))

(defn- sha256-str
  [^String value]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.getBytes (or value "") "UTF-8")]
    (.update digest bytes)
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- summarize-catalog
  [idents]
  (when (seq idents)
    {:count (count idents)
     :sample (vec (take 32 idents))
     :sha256 (sha256-str (pr-str idents))}))

(defn- summarize-graph
  [graph]
  (when graph
    {:keys (->> graph keys sort (take 32) vec)
     :sha256 (sha256-str (pr-str graph))
     :size (count (pr-str (canonicalize-llm graph)))}))

(defn- bound-llm-input
  "Formats the LLM input to stay under dev-local string limits by summarizing
   known large fields. Returns the possibly-truncated context."
  [context]
  (letfn [(size [ctx] (count (pr-str (canonicalize-llm ctx))))
          (truncate [ctx steps]
            (if (or (empty? steps) (<= (size ctx) llm-input-limit))
              ctx
              (recur ((first steps) ctx) (rest steps))))]
    (truncate context
              [(fn [ctx]
                 (if-let [catalog (:code.type/catalog ctx)]
                   (-> ctx
                       (assoc :code.type/catalog-summary (summarize-catalog catalog))
                       (dissoc :code.type/catalog))
                   ctx))
               (fn [ctx]
                 (if-let [graph (:code.definition/graph ctx)]
                   (-> ctx
                       (assoc :code.definition/graph-summary (summarize-graph graph))
                       (dissoc :code.definition/graph))
                   ctx))
               (fn [ctx]
                 (if-let [coverage (:plan/coverage ctx)]
                   (-> ctx
                       (assoc :plan/coverage-count (count coverage))
                       (dissoc :plan/coverage))
                   ctx))
               (fn [ctx]
                 (if-let [nodes (:plan/nodes ctx)]
                   (-> ctx
                       (assoc :plan/nodes-count (count nodes))
                       (dissoc :plan/nodes))
                   ctx))])))

(defn- canonicalize-llm
  [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[k v]] [k (canonicalize-llm v)]))
                       value)
    (set? value) (->> value (map canonicalize-llm) (sort-by pr-str) vec)
    (sequential? value) (vec (map canonicalize-llm value))
    :else value))

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

(defn- maybe-read-edn
  [path]
  (when-let [p path]
    (let [file (io/file p)]
      (when (.exists file)
        (read-edn file)))))

(defn- llm-environment
  [bundle payload]
  (or (:llm/environment payload)
      (:llm/environment bundle)
      (some-> (System/getenv "LLM_ENVIRONMENT") keyword)
      (some-> (System/getenv "INTUITION_ENV") keyword)
      :env/ci))

(defn- llm-plan
  []
  @llm-plan*)

(defn- llm-surface-config
  [surface]
  (some #(when (= surface (:llm.surface/ident %)) %)
        (:llm.integration/surfaces (llm-plan))))

(defn- coerce-llm-mode
  [mode default]
  (if (llm-modes mode) mode default))

(defn- llm-mode
  [{:keys [mode-key bundle payload]}]
  (let [env (llm-environment bundle payload)
        env-default (get-in (llm-plan) [:llm.integration/env-defaults env mode-key])
        feature-default (or env-default :off)]
    (coerce-llm-mode (or (get payload mode-key)
                         (get bundle mode-key)
                         feature-default)
                     :off)))

(defn- llm-enabled?
  [settings]
  (contains? #{:shadow :apply} (:mode settings)))

(defn- llm-settings
  [{:keys [surface mode-key fake-key call-key bundle payload]}]
  (let [surface-config (llm-surface-config surface)
        mode (llm-mode {:mode-key mode-key
                        :bundle bundle
                        :payload payload
                        :surface surface})]
    {:surface surface
     :surface/config surface-config
     :mode mode
     :feature-flag (or mode-key (:llm.integration/feature-flag surface-config))
     :environment (llm-environment bundle payload)
     :fake-response-fn (or (get payload fake-key)
                           (get bundle fake-key))
     :call-fn (or (get payload call-key)
                  (get bundle call-key))}))

(declare llm-test-doc-stage!)

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
        bundle-code-idents (not-empty (vec (or (:code.definition/idents bundle) [])))
        context (cond-> {:mission/id mission-id
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
                  bundle-code-idents (assoc :code.definition/idents bundle-code-idents))
        llm-test-settings (llm-settings {:surface :llm.surface/test-doc-suggestions
                                         :mode-key :llm.test-doc-suggestions/mode
                                         :fake-key :llm.test-doc-suggestions/fake-response-fn
                                         :call-key :llm.test-doc-suggestions/call-fn
                                         :bundle bundle
                                         :payload {}})
        llm-stage (llm-test-doc-stage! {:conn conn
                                        :mission-id mission-id
                                        :agent-id agent-id
                                        :bundle bundle
                                        :code-idents (:code.definition/idents context)
                                        :log-path log-path
                                        :limit limit
                                        :settings llm-test-settings})
        run (run-protocol conn granted {:ident :protocol/mission-standard
                                        :context context
                                        :instrumentation {:log-fn (fn [_ payload]
                                                                    (append-log! log-path limit
                                                                                 (str "[mission/standard] " payload)))}})]
    (append-log! log-path limit "[mission/standard] protocol completed")
    {:stage/id :mission/standard
     :status (:status run)
     :artifacts (cond-> (vec (:artifacts llm-stage))
                  (get-in run [:step-results :step/git-branch :branch/edn-path])
                  (conj {:path (canonical-path (get-in run [:step-results :step/git-branch :branch/edn-path]))
                         :label "Branch snapshot"
                         :channel :artifact.channel/branch}))
     :result {:sandbox/root sandbox
              :branch/name (get-in run [:step-results :step/git-branch :branch/name])
              :ci/run (get-in run [:step-results :step/run-tests])
              :code.materialize (get-in run [:step-results :step/code-materialize])
              :llm.test-doc (:result llm-stage)}}))

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

(defn- load-proposals
  [bundle payload {:keys [required?] :or {required? true}}]
  (try
    (load-proposals* bundle payload)
    (catch Exception e
      (if required?
        (throw e)
        {:code.proposal/proposals []
         :code.proposal/path nil
         :code.proposal/error e}))))

(defn- proposal-key
  [proposal]
  [(:code.proposal/type proposal)
   (or (:code.proposal/ident proposal)
       (get-in proposal [:code.proposal/payload :code.definition/ident])
       (:code.proposal/id proposal))])

(defn- merge-proposals
  [base additions]
  (let [seen (volatile! #{})]
    (reduce (fn [acc proposal]
              (let [k (proposal-key proposal)]
                (if (and k (contains? @seen k))
                  acc
                  (do
                    (when k (vswap! seen conj k))
                    (conj acc proposal)))))
            []
            (concat (or base []) (or additions [])))))

(defn- existing-path
  [path]
  (when-let [p path]
    (let [file (io/file p)]
      (when (.exists file)
        (.getCanonicalPath file)))))

(defn- plan-info
  [bundle]
  (let [path (existing-path (or (get-in bundle [:plan/snapshot :plan/path])
                                (get-in bundle [:plan/path])
                                (get-in bundle [:graph/context :plan :path])))
        data (maybe-read-edn path)]
    (when (or path data)
      {:path path
       :data data
       :id (or (:work.plan/id data) (:plan/id data) (:plan.generation/id data))
       :nodes (vec (or (:work.plan/nodes data) (:plan/nodes data) []))
       :coverage (vec (or (:work.plan/coverage data) (:plan/coverage data) []))
       :validation (:plan/validation bundle)})))

(defn- spec-info
  [bundle]
  (let [path (existing-path (or (get-in bundle [:spec/source :spec/input-path])
                                (get-in bundle [:graph/context :spec :path])))
        data (maybe-read-edn path)
        source (:spec/source bundle)
        sections (or (:spec/spec-sections data)
                     (:spec/spec-sections source)
                     llm-system-spec-sections)]
    (when (or path data source)
      {:path path
       :data data
       :id (or (:spec/id data)
               (:spec/id source)
               (keyword (str "spec/" (get bundle :mission/id "unknown"))))
       :requirements (vec (or (:spec/requirements data)
                              (:spec/requirements source)
                              []))
       :sections (vec sections)})))

(defn- code-graph-info
  [bundle]
  (let [path (existing-path (or (get-in bundle [:graph/context :code-graph :path])
                                (get-in bundle [:graph/context :code.graph/path])))]
    (when path
      {:path path
       :data (maybe-read-edn path)})))

(defn- llm-log-path
  [{:keys [mission-id bundle filename default-root]}]
  (let [root (or default-root
                 (:llm/log-root bundle)
                 "missions/logs")]
    (canonical-path (str root "/" mission-id "/llm/" filename))))


(defn- llm-proposals-stage!
  [{:keys [conn mission-id agent-id bundle proposals log-path limit settings]}]
  (let [{:keys [mode surface feature-flag fake-response-fn call-fn environment]} settings
        log-root (or (:code.proposal/log-root bundle)
                     "missions/logs")
        llm-log (llm-log-path {:mission-id mission-id
                               :bundle bundle
                               :filename "code-proposals.edn"
                               :default-root log-root})]
    (if-not (llm-enabled? settings)
      (do
        (append-log! log-path limit "[proposals/llm] disabled; using deterministic proposals only")
        {:stage/id :proposals/llm
         :status :status/ok
         :artifacts []
         :result {:code.proposal/proposals proposals
                  :llm.code-proposal/status :llm.status/disabled
                  :llm.code-proposal/mode mode
                  :llm.code-proposal/feature-flag feature-flag
                  :llm.code-proposal/log-path nil}})
      (let [plan (plan-info bundle)
            spec (spec-info bundle)
            code-graph (code-graph-info bundle)
            context {:mission/id mission-id
                     :agent/id agent-id
                     :mission/context-bundle (select-keys bundle [:bundle/id :bundle/version :graph/context :plan :spec/source :sandbox])
                     :plan/id (:id plan)
                     :plan/path (:path plan)
                     :plan/nodes (:nodes plan)
                     :plan/coverage (:coverage plan)
                     :spec/id (:id spec)
                     :spec/requirements (:requirements spec)
                     :spec/spec-sections (:sections spec)
                     :code.definition/graph (:data code-graph)
                     :code.graph/path (:path code-graph)
                     :code.type/catalog (-> (code/type-ident-set) sort vec)
                     :code.proposal/deterministic (vec (or proposals []))
                     :sandbox/root (get-in bundle [:sandbox :root])}
            llm-input (bound-llm-input context)
            context-hash (sha256-str (pr-str (canonicalize-llm llm-input)))
            context-size (count (pr-str (canonicalize-llm llm-input)))
            context-original-size (count (pr-str (canonicalize-llm context)))
            trace (merge {:mission/id mission-id
                          :agent/id agent-id
                          :llm/mode mode
                          :llm/feature-flag feature-flag
                          :llm/environment environment}
                         (:trace bundle))
            request-opts (cond-> {:surface surface
                                  :input llm-input
                                  :requested-outputs [:code/proposals]
                                  :idempotency-key context-hash
                                  :context-hash context-hash
                                  :trace trace
                                  :spec-sections (:sections spec)
                                  :conn conn}
                           fake-response-fn (assoc :fake-response-fn fake-response-fn)
                           call-fn (assoc :llm/call-fn call-fn))]
        (try
          (let [call-result (llm/invoke! request-opts)
                request (:llm/request call-result)
                response (:llm/response call-result)
                response-status (:llm.response/status response)
                payload (:llm.response/payload response)
                llm-proposals (-> (:code/proposals payload) vectorize vec)
                abort? (= :abort (:status payload))
                llm-status (cond
                             (not= :response.status/ok response-status) :llm.status/error
                             abort? :llm.status/abort
                             (= mode :shadow) :llm.status/shadow
                             :else :llm.status/applied)
                applied? (and (= :llm.status/applied llm-status)
                              (= :apply mode))
                final-proposals (if applied?
                                  (merge-proposals proposals llm-proposals)
                                  (vec (or proposals [])))
                summary {:llm.surface surface
                         :llm.mode mode
                         :llm.status llm-status
                         :llm.feature-flag feature-flag
                         :llm.environment environment
                         :llm.context-hash context-hash
                         :llm.status/reason (or (:reason payload)
                                                (when abort? "abort status")
                                                (when (not= :response.status/ok response-status)
                                                  (str response-status)))
                         :llm.status/applied? applied?
                         :llm.request/id (:llm.request/id request)
                         :llm.response/id (:llm.response/id response)
                         :llm.response/status response-status
                         :llm.response/self-report (:meta/self-report response)
                         :llm.response/error (:llm.response/error response)
                         :code.proposal/deterministic-count (count proposals)
                         :code.proposal/llm-count (count llm-proposals)
                         :code.proposal/final-count (count final-proposals)
                         :llm.context/truncated? (not= context llm-input)
                         :llm.context/original-size context-original-size
                         :llm.context/persisted-size context-size
                         :system-spec/sections llm-system-spec-sections
                         :code.proposal/proposals llm-proposals}
                log-path* (write-edn! llm-log summary)]
            (append-log! log-path limit
                         (format "[proposals/llm] mode=%s status=%s ctx=%s deterministic=%d llm=%d final=%d"
                                 (name mode)
                                 (name llm-status)
                                 (subs context-hash 0 12)
                                 (count proposals)
                                 (count llm-proposals)
                                 (count final-proposals)))
            {:stage/id :proposals/llm
             :status :status/ok
             :artifacts (cond-> []
                          log-path* (conj {:path log-path*
                                           :label "LLM code proposals"
                                           :channel :artifact.channel/log}))
             :result {:code.proposal/proposals final-proposals
                      :llm.code-proposal/status llm-status
                      :llm.code-proposal/mode mode
                      :llm.code-proposal/feature-flag feature-flag
                      :llm.code-proposal/context-hash context-hash
                      :llm.code-proposal/request-id (:llm.request/id request)
                      :llm.code-proposal/response-id (:llm.response/id response)
                      :llm.code-proposal/self-report (:meta/self-report response)
                      :llm.code-proposal/log-path log-path*
                      :llm.code-proposal/proposals llm-proposals
                      :llm.code-proposal/applied? applied?}})
          (catch Exception e
            (let [message (or (ex-message e) (.getMessage e) "LLM code-proposal error")
                  summary {:llm.surface surface
                           :llm.mode mode
                           :llm.status :llm.status/error
                           :llm.feature-flag feature-flag
                           :llm.environment environment
                           :llm.context-hash context-hash
                           :llm.status/reason message
                           :code.proposal/deterministic-count (count proposals)
                           :llm.context/truncated? (not= context llm-input)
                           :llm.context/original-size context-original-size
                           :llm.context/persisted-size context-size
                           :system-spec/sections llm-system-spec-sections}
                  log-path* (write-edn! llm-log summary)]
              (append-log! log-path limit
                           (format "[proposals/llm] error – falling back to deterministic proposals: %s"
                                   message))
              {:stage/id :proposals/llm
               :status :status/ok
               :artifacts (cond-> []
                            log-path* (conj {:path log-path*
                                             :label "LLM code proposals"
                                             :channel :artifact.channel/log}))
               :result {:code.proposal/proposals proposals
                        :llm.code-proposal/status :llm.status/error
                        :llm.code-proposal/mode mode
                        :llm.code-proposal/feature-flag feature-flag
                        :llm.code-proposal/context-hash context-hash
                        :llm.code-proposal/log-path log-path*
                        :llm.code-proposal/error message}})))))))

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

(defn- sanitize-suggestions
  [payload]
  {:tests/suggestions (-> (:tests/suggestions payload) vectorize vec)
   :docs/suggestions (-> (:docs/suggestions payload) vectorize vec)})

(defn- llm-test-doc-stage!
  [{:keys [conn mission-id agent-id bundle code-idents log-path limit settings]}]
  (let [{:keys [mode surface feature-flag fake-response-fn call-fn environment]} settings
        log-root (or (:llm/log-root bundle)
                     (:code.proposal/log-root bundle)
                     "missions/logs")
        llm-log (llm-log-path {:mission-id mission-id
                               :bundle bundle
                               :filename "test-doc-suggestions.edn"
                               :default-root log-root})]
    (if-not (llm-enabled? settings)
      (do
        (append-log! log-path limit "[llm/test-doc] disabled; running deterministic mission-standard")
        {:stage/id :llm/test-doc
         :status :status/ok
         :artifacts []
         :result {:llm.test-doc/status :llm.status/disabled
                  :llm.test-doc/mode mode
                  :llm.test-doc/feature-flag feature-flag
                  :llm.test-doc/log-path nil}})
      (let [plan (plan-info bundle)
            spec (spec-info bundle)
            context {:mission/id mission-id
                     :agent/id agent-id
                     :context/bundle (select-keys bundle [:bundle/id :bundle/version :graph/context :plan :spec/source :sandbox])
                     :plan/id (:id plan)
                     :plan/path (:path plan)
                     :plan/nodes (:nodes plan)
                     :plan/coverage (:coverage plan)
                     :spec/requirements (:requirements spec)
                     :spec/spec-sections (:sections spec)
                     :code.definition/idents (vec (or code-idents []))
                     :code.definition/coverage (:coverage plan)
                     :doc/coverage (:doc/coverage bundle)
                     :analytics/failures (:analytics/failures bundle)
                     :sandbox/root (get-in bundle [:sandbox :root])}
            llm-input (bound-llm-input context)
            context-hash (sha256-str (pr-str (canonicalize-llm llm-input)))
            context-size (count (pr-str (canonicalize-llm llm-input)))
            context-original-size (count (pr-str (canonicalize-llm context)))
            trace (merge {:mission/id mission-id
                          :agent/id agent-id
                          :llm/mode mode
                          :llm/feature-flag feature-flag
                          :llm/environment environment}
                         (:trace bundle))
            request-opts (cond-> {:surface surface
                                  :input llm-input
                                  :requested-outputs [:tests/suggestions :docs/suggestions]
                                  :idempotency-key context-hash
                                  :context-hash context-hash
                                  :trace trace
                                  :spec-sections (:sections spec)
                                     :conn conn}
                              fake-response-fn (assoc :fake-response-fn fake-response-fn)
                              call-fn (assoc :llm/call-fn call-fn))]
        (try
          (let [call-result (llm/invoke! request-opts)
                request (:llm/request call-result)
                response (:llm/response call-result)
                response-status (:llm.response/status response)
                payload (:llm.response/payload response)
                suggestions (sanitize-suggestions payload)
                abort? (= :abort (:status payload))
                llm-status (cond
                             (not= :response.status/ok response-status) :llm.status/error
                             abort? :llm.status/abort
                             (= mode :shadow) :llm.status/shadow
                             :else :llm.status/applied)
                applied? (and (= llm-status :llm.status/applied)
                              (= mode :apply))
                summary {:llm.surface surface
                         :llm.mode mode
                         :llm.status llm-status
                         :llm.feature-flag feature-flag
                         :llm.environment environment
                         :llm.context-hash context-hash
                         :llm.status/reason (or (:reason payload)
                                                (when abort? "abort status")
                                                (when (not= :response.status/ok response-status)
                                                  (str response-status)))
                         :llm.status/applied? applied?
                         :llm.request/id (:llm.request/id request)
                         :llm.response/id (:llm.response/id response)
                         :llm.response/status response-status
                         :llm.response/self-report (:meta/self-report response)
                         :llm.response/error (:llm.response/error response)
                         :llm.context/truncated? (not= context llm-input)
                         :llm.context/original-size context-original-size
                         :llm.context/persisted-size context-size
                         :system-spec/sections llm-system-spec-sections
                         :tests/suggestions-count (count (:tests/suggestions suggestions))
                         :docs/suggestions-count (count (:docs/suggestions suggestions))
                         :tests/suggestions (:tests/suggestions suggestions)
                         :docs/suggestions (:docs/suggestions suggestions)}
                log-path* (write-edn! llm-log summary)]
            (append-log! log-path limit
                         (format "[llm/test-doc] mode=%s status=%s ctx=%s tests=%d docs=%d"
                                 (name mode)
                                 (name llm-status)
                                 (subs context-hash 0 12)
                                 (count (:tests/suggestions suggestions))
                                 (count (:docs/suggestions suggestions))))
            {:stage/id :llm/test-doc
             :status :status/ok
             :artifacts (cond-> []
                          log-path* (conj {:path log-path*
                                           :label "LLM test/doc suggestions"
                                           :channel :artifact.channel/log}))
             :result {:llm.test-doc/status llm-status
                      :llm.test-doc/mode mode
                      :llm.test-doc/feature-flag feature-flag
                      :llm.test-doc/context-hash context-hash
                      :llm.test-doc/request-id (:llm.request/id request)
                      :llm.test-doc/response-id (:llm.response/id response)
                      :llm.test-doc/self-report (:meta/self-report response)
                      :llm.test-doc/log-path log-path*
                      :llm.test-doc/applied? applied?
                      :tests/suggestions (:tests/suggestions suggestions)
                      :docs/suggestions (:docs/suggestions suggestions)}})
          (catch Exception e
            (let [message (or (ex-message e) (.getMessage e) "LLM test/doc suggestion error")
                  summary {:llm.surface surface
                           :llm.mode mode
                           :llm.status :llm.status/error
                           :llm.feature-flag feature-flag
                           :llm.environment environment
                           :llm.context-hash context-hash
                           :llm.status/reason message
                           :llm.context/truncated? (not= context llm-input)
                           :llm.context/original-size context-original-size
                           :llm.context/persisted-size context-size
                           :system-spec/sections llm-system-spec-sections}
                  log-path* (write-edn! llm-log summary)]
              (append-log! log-path limit
                           (format "[llm/test-doc] error – continuing without suggestions: %s"
                                   message))
              {:stage/id :llm/test-doc
               :status :status/ok
               :artifacts (cond-> []
                            log-path* (conj {:path log-path*
                                             :label "LLM test/doc suggestions"
                                             :channel :artifact.channel/log}))
               :result {:llm.test-doc/status :llm.status/error
                        :llm.test-doc/mode mode
                        :llm.test-doc/feature-flag feature-flag
                        :llm.test-doc/context-hash context-hash
                        :llm.test-doc/log-path log-path*
                        :llm.test-doc/error message}})))))))

(defn- mission-standard-edit-stage!
  [{:keys [conn granted mission-id agent-id bundle log-path limit code-idents]}]
  (let [validation (or (:validation bundle) {})
        sandbox (or (get-in bundle [:sandbox :root])
                    (str "tmp/missions/" mission-id "/edit-sandbox"))
        tests-enabled? (if (contains? validation :tests/enabled?)
                         (boolean (:tests/enabled? validation))
                         true)
        context (cond-> {:mission/id mission-id
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
                                             ["resources/dictionary/code_types.edn"])}
                  (seq (or code-idents (:code.definition/idents bundle)))
                  (assoc :code.definition/idents (vec (or code-idents
                                                          (:code.definition/idents bundle)))))
        llm-test-settings (llm-settings {:surface :llm.surface/test-doc-suggestions
                                         :mode-key :llm.test-doc-suggestions/mode
                                         :fake-key :llm.test-doc-suggestions/fake-response-fn
                                         :call-key :llm.test-doc-suggestions/call-fn
                                         :bundle bundle
                                         :payload {}})
        llm-stage (llm-test-doc-stage! {:conn conn
                                        :mission-id mission-id
                                        :agent-id agent-id
                                        :bundle bundle
                                        :code-idents (:code.definition/idents context)
                                        :log-path log-path
                                        :limit limit
                                        :settings llm-test-settings})
        run (run-protocol conn granted {:ident :protocol/mission-standard
                                        :context context
                                        :instrumentation {:log-fn (fn [_ payload]
                                                                    (append-log! log-path limit
                                                                                 (str "[mission/standard] " payload)))}})]
    (append-log! log-path limit "[mission/standard] protocol completed")
    {:stage/id :mission/standard
     :status (:status run)
     :artifacts (cond-> (vec (:artifacts llm-stage))
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
              :ci/run (get-in run [:step-results :step/run-tests])
              :llm.test-doc (:result llm-stage)}}))

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
        llm-code-settings (llm-settings {:surface :llm.surface/code-proposal
                                         :mode-key :llm.code-proposal/mode
                                         :fake-key :llm.code-proposal/fake-response-fn
                                         :call-key :llm.code-proposal/call-fn
                                         :bundle bundle
                                         :payload payload})
        proposals-data (load-proposals bundle payload {:required? (not (llm-enabled? llm-code-settings))})
        proposals (vec (:code.proposal/proposals proposals-data))
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
      (let [llm-stage (record-stage! (llm-proposals-stage! {:conn conn
                                                            :mission-id mission-id
                                                            :agent-id agent-id
                                                            :bundle bundle
                                                            :proposals proposals
                                                            :log-path run-log-path
                                                            :limit truncate
                                                            :settings (assoc llm-code-settings :surface :llm.surface/code-proposal)}))
            proposals-merged (get-in llm-stage [:result :code.proposal/proposals])
            _ (when (empty? proposals-merged)
                (throw (ex-info "Proposals required after LLM + deterministic merge"
                                {:mission/id mission-id
                                 :llm/status (get-in llm-stage [:result :llm.code-proposal/status])})))
            validation-stage (record-stage! (validate-proposals-stage! {:conn conn
                                                                        :granted granted
                                                                        :mission-id mission-id
                                                                        :agent-id agent-id
                                                                        :bundle bundle
                                                                        :proposals proposals-merged
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
            mission-stage (record-stage! (mission-standard-edit-stage! {:conn conn
                                                                         :granted granted
                                                                         :mission-id mission-id
                                                                         :agent-id agent-id
                                                                         :bundle bundle
                                                                         :code-idents code-idents
                                                                         :log-path run-log-path
                                                                         :limit truncate}))
            all-artifacts (mapcat :artifacts @stages)
            llm-test-doc (get-in mission-stage [:result :llm.test-doc])
            manifest {:action/status :status/ok
                      :mission/id mission-id
                      :agent/id agent-id
                      :bundle/id (:bundle/id bundle)
                      :bundle/version (:bundle/version bundle)
                      :context/bundle-path bundle-path
                      :graph/context (:graph/context bundle)
                      :proposals/path (:code.proposal/path proposals-data)
                      :llm/code-proposal (select-keys (:result llm-stage)
                                                      [:llm.code-proposal/status
                                                       :llm.code-proposal/mode
                                                       :llm.code-proposal/feature-flag
                                                       :llm.code-proposal/log-path
                                                       :llm.code-proposal/request-id
                                                       :llm.code-proposal/response-id
                                                       :llm.code-proposal/context-hash
                                                       :llm.code-proposal/applied?
                                                       :llm.code-proposal/proposals])
                      :llm/test-doc (select-keys llm-test-doc
                                                [:llm.test-doc/status
                                                 :llm.test-doc/mode
                                                 :llm.test-doc/feature-flag
                                                 :llm.test-doc/log-path
                                                 :llm.test-doc/request-id
                                                 :llm.test-doc/response-id
                                                 :llm.test-doc/context-hash
                                                 :llm.test-doc/applied?
                                                 :tests/suggestions
                                                 :docs/suggestions])
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
