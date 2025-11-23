(ns intuition.sfs.schemas
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as str]))

(defn- non-blank-string?
  [v]
  (and (string? v) (not (str/blank? v))))

(s/def :mission/id (fn mission-id? [v]
                     (or (keyword? v) (non-blank-string? v))))
(s/def :agent/id non-blank-string?)
(s/def :workspace/root non-blank-string?)
(s/def :spec/id keyword?)
(s/def :spec/status keyword?)
(s/def :git/commit non-blank-string?)

(s/def :version.snapshot/id uuid?)
(s/def :version.snapshot/type keyword?)
(s/def :version.snapshot/timestamp non-blank-string?)
(s/def :version.snapshot/actor keyword?)
(s/def :version.snapshot/spec-id (s/nilable keyword?))
(s/def :version.snapshot/plan-id (s/nilable uuid?))
(s/def :version.snapshot/mission-id non-blank-string?)
(s/def :version.snapshot/git-commit (s/nilable non-blank-string?))
(s/def :version.snapshot/requirements (s/coll-of non-blank-string? :kind vector?))
(s/def :version.snapshot/code-graph-nodes (s/coll-of keyword? :kind vector?))
(s/def :version.snapshot/path non-blank-string?)
(s/def :version.artifact/id keyword?)
(s/def :version.artifact/snapshot-id uuid?)
(s/def :version.artifact/path non-blank-string?)
(s/def :version.artifact/content-hash non-blank-string?)
(s/def :version.artifact/media-type (s/nilable non-blank-string?))
(s/def :version.artifact/recorded-at non-blank-string?)
(s/def :version/artifact
  (s/keys :req [:version.artifact/id
                :version.artifact/snapshot-id
                :version.artifact/path
                :version.artifact/content-hash
                :version.artifact/recorded-at]
          :opt [:version.artifact/media-type]))
(s/def :version.snapshot/artifacts (s/coll-of :version/artifact :kind vector?))
(s/def :version.link/id uuid?)
(s/def :version.link/source-snapshot-id uuid?)
(s/def :version.link/target-snapshot-id uuid?)
(s/def :version.link/relation keyword?)
(s/def :version.link/recorded-at (s/nilable non-blank-string?))
(s/def :version/link
  (s/keys :req [:version.link/id
                :version.link/source-snapshot-id
                :version.link/target-snapshot-id
                :version.link/relation]
          :opt [:version.link/recorded-at]))
(s/def :version.snapshot/links (s/coll-of :version/link :kind vector?))
(s/def :version/snapshot
  (s/keys :req [:version.snapshot/id
                :version.snapshot/type
                :version.snapshot/timestamp
                :version.snapshot/actor
                :version.snapshot/mission-id
                :version.snapshot/requirements
                :version.snapshot/artifacts
                :version.snapshot/links]
          :opt [:version.snapshot/spec-id
                :version.snapshot/plan-id
                :version.snapshot/git-commit
                :version.snapshot/code-graph-nodes]))

;; Mission instantiation data -----------------------------------------------

(s/def :mission.plan-binding/mission-id non-blank-string?)
(s/def :mission.plan-binding/plan-id non-blank-string?)
(s/def :mission.plan-binding/plan-node-id non-blank-string?)
(s/def :mission.plan-binding/resource-refs (s/coll-of non-blank-string? :kind vector? :min-count 1))
(s/def :mission.plan-binding/test-scope (s/nilable map?))
(s/def :mission.plan-binding/code-types (s/coll-of keyword? :kind vector? :min-count 1))
(s/def :mission.plan-binding/binding
  (s/keys :req [:mission.plan-binding/mission-id
                :mission.plan-binding/plan-id
                :mission.plan-binding/plan-node-id
                :mission.plan-binding/resource-refs
                :mission.plan-binding/code-types]
          :opt [:mission.plan-binding/test-scope]))

(s/def :mission.resource/mission-id non-blank-string?)
(s/def :mission.resource/plan-id non-blank-string?)
(s/def :mission.resource/plan-node-id non-blank-string?)
(s/def :mission.resource/path non-blank-string?)
(s/def :mission/resource
  (s/keys :req [:mission.resource/mission-id
                :mission.resource/plan-id
                :mission.resource/plan-node-id
                :mission.resource/path]))
(s/def :mission/resources (s/coll-of :mission/resource :kind vector? :min-count 1))

(defn- keyword-set?
  [v]
  (and (set? v) (every? keyword? v)))

(s/def :permission/ident keyword?)
(s/def :permission/name non-blank-string?)
(s/def :permission/description string?)
(s/def :permission/definition
  (s/keys :req [:permission/ident :permission/name]
          :opt [:permission/description]))

(s/def :role/ident keyword?)
(s/def :role/name non-blank-string?)
(s/def :role/permissions (s/coll-of keyword? :kind vector?))
(s/def :role/description string?)
(s/def :role/definition
  (s/keys :req [:role/ident :role/name :role/permissions]
          :opt [:role/description]))

(s/def :protocol/owner keyword?)
(s/def :protocol/escalation (s/coll-of keyword? :kind vector?))

(s/def :locks/value (s/and keyword-set? #(pos? (count %))))
(s/def :locks/optional-value keyword-set?)
(s/def :action/status #{:status/ok :status/passed :status/failed})

;; Env bootstrap -------------------------------------------------------------

(s/def :ports/count pos-int?)
(s/def :sandbox/root non-blank-string?)
(s/def :sandbox/paths (s/map-of keyword? non-blank-string?))
(s/def :sandbox/ports (s/coll-of pos-int? :kind vector? :min-count 1))
(s/def :branch/name non-blank-string?)
(s/def :branch/prefix (s/nilable non-blank-string?))
(s/def :branch/created-at non-blank-string?)
(s/def :branch/edn-path non-blank-string?)
(s/def :branch/markdown-path non-blank-string?)
(s/def :branch/spec-reference non-blank-string?)

(s/def :action.env/bootstrap-config
  (s/keys :req [:mission/id :agent/id]
          :opt [:ports/count :env/vars]))

(s/def :action.env/bootstrap-output
  (s/keys :req [:action/status :mission/id :agent/id :sandbox/root]
          :opt [:sandbox/paths :sandbox/ports :env/vars]))

(s/def :action.git.branch/prepare-config
  (s/keys :req [:mission/id :agent/id]
          :opt [:branch/prefix :sandbox/root]))

(s/def :action.git.branch/prepare-output
  (s/keys :req [:action/status :mission/id :agent/id :branch/name :branch/created-at
                :branch/edn-path :branch/markdown-path]
          :opt [:branch/spec-reference]))

;; Lock operations -----------------------------------------------------------

(s/def ::locks :locks/optional-value)

(s/def :action.lock/acquire-config
  (s/and
   (s/keys :req [:mission/id]
           :req-un [::locks])
   #(pos? (count (:locks %)))))

(s/def :action.lock/acquire-output
  (s/keys :req [:action/status :locks/acquired]
          :opt [:mission/id]))

(s/def :locks/acquired :locks/value)

(s/def :action.lock/release-config
  (s/keys :req [:mission/id]
          :opt-un [::locks]))

(s/def :action.lock/release-output
  (s/keys :req [:action/status]
          :opt [:locks/released]))

(s/def :locks/released :locks/optional-value)

;; Mission instantiation ----------------------------------------------------

(s/def :mission/template (s/nilable map?))
(s/def :locks/current (s/nilable (s/coll-of non-blank-string? :kind vector?)))
(s/def :mission.plan-binding/artifact non-blank-string?)
(s/def :locks/requested (s/coll-of non-blank-string? :kind vector? :min-count 1))
(s/def :locks/request-artifact non-blank-string?)
(s/def :sandbox/manifest non-blank-string?)
(s/def :mission/record map?)

(s/def :action.mission/from-plan-config
  (s/keys :req [:mission/id :agent/id :work.plan/id :plan.node/id]
          :opt [:mission/template :work-plan/resource-path]))

(s/def :action.mission/from-plan-output
  (s/keys :req [:action/status
                :mission/record
                :mission.plan-binding/binding
                :mission.plan-binding/artifact
                :mission/resources]))

(s/def :action.mission/lock-resolve-config
  (s/keys :req [:mission/id :agent/id :mission/resources]
          :opt [:locks/current]))

(s/def :action.mission/lock-resolve-output
  (s/keys :req [:action/status :locks/requested :locks/request-artifact]
          :opt [:mission/resources]))

(s/def :action.mission/sandbox-prepare-config
  (s/keys :req [:mission/id :agent/id :workspace/root]
          :opt [:branch/prefix]))

(s/def :action.mission/sandbox-prepare-output
  (s/keys :req [:action/status :sandbox/root :branch/name :sandbox/manifest]
          :opt [:branch/edn-path :branch/markdown-path :env/vars]))

;; Log step ------------------------------------------------------------------

(s/def :step/id non-blank-string?)
(s/def :deliverable/id non-blank-string?)
(s/def :track/id keyword?)
(s/def :lock/token non-blank-string?)
(s/def :log.step/summary non-blank-string?)
(s/def :log.step.evidence/before non-blank-string?)
(s/def :log.step.evidence/after non-blank-string?)
(s/def :log.step/evidence (s/keys :req-un [:log.step.evidence/before :log.step.evidence/after]))
(s/def :log.step.artifact/path non-blank-string?)
(s/def :log.step.artifact/label non-blank-string?)
(s/def :log.step/artifact (s/keys :req-un [:log.step.artifact/path]
                                  :opt-un [:log.step.artifact/label]))
(s/def :log.step/artifacts (s/coll-of :log.step/artifact :kind vector? :min-count 1))

(s/def :action.log/step-config
  (s/keys :req [:mission/id :agent/id :step/id :deliverable/id :track/id :lock/token]
          :req-un [:log.step/summary :log.step/evidence :log.step/artifacts]))

(s/def :markdown/path non-blank-string?)
(s/def :log/id uuid?)
(s/def :action.log/step-output
  (s/keys :req [:action/status :log/id :markdown/path]
          :opt [:worklog/entity]))

;; Tests --------------------------------------------------------------------

(s/def :test/suite keyword?)
(s/def :test/paths (s/coll-of string? :kind vector?))
(s/def :test/error-mode #{:fail-fast :allow-failures})
(s/def :test/simulate-invalid-output? boolean?)
(s/def :test/simulate-error? boolean?)

(s/def :lint/paths (s/coll-of string? :kind vector? :min-count 1))
(s/def :lint/command (s/coll-of string? :kind vector? :min-count 1))

(s/def :action.lint/run-config
  (s/keys :req [:mission/id]
          :opt [:lint/paths :lint/command]))

(s/def :lint/report map?)

(s/def :action.lint/run-output
  (s/keys :req [:action/status]
          :opt [:lint/report :lint/paths :lint/command]))

(s/def :action.test/run-suite-config
  (s/keys :req [:mission/id :test/suite]
          :opt [:test/paths :test/error-mode
                :test/simulate-invalid-output?
                :test/simulate-error?]))

(s/def :test/report map?)
(s/def :test/failures (s/coll-of string? :kind vector?))

(s/def :action.test/run-suite-output
  (s/keys :req [:action/status :test/report]
          :opt [:test/failures]))

;; CI + merge automation -----------------------------------------------------

(s/def :ci/profile keyword?)
(s/def :ci/log-root non-blank-string?)
(s/def :ci/trigger keyword?)
(s/def :ci/run-id non-blank-string?)
(s/def :ci/run-path non-blank-string?)
(s/def :ci/run-dir non-blank-string?)
(s/def :ci/thresholds map?)
(s/def :ci/required-tools (s/coll-of keyword? :kind vector?))
(s/def :ci.step/id keyword?)
(s/def :ci.step/tool keyword?)
(s/def :ci.step/description string?)
(s/def :ci.step/command (s/coll-of string? :kind vector? :min-count 1))
(s/def :ci.step/retry map?)
(s/def :ci.step/log non-blank-string?)
(s/def :ci.step/status #{:status/ok :status/passed})
(s/def :ci/step (s/keys :req [:ci.step/id :ci.step/tool]
                         :opt [:ci.step/description :ci.step/command :ci.step/retry]))
(s/def :ci/steps (s/coll-of :ci/step :kind vector? :min-count 1))
(s/def :ci/step-result
  (s/keys :req [:ci.step/id :ci.step/tool :ci.step/command :ci.step/log :ci.step/status]
          :opt [:ci.step/description :ci.step/retry]))
(s/def :ci/steps-result (s/coll-of :ci/step-result :kind vector? :min-count 1))
(s/def :ci/run
  (s/keys :req [:mission/id :ci/profile :ci/run-path :ci/run-dir :ci/steps]
          :opt [:ci/thresholds :ci/required-tools :ci/spec :ci/run-id :ci/trigger]))

(s/def :action.ci/run-profile-config
  (s/keys :req [:mission/id :sandbox/root]
          :opt [:ci/profile :ci/steps :ci/log-root :ci/trigger]))

(s/def :action.ci/run-profile-output
  (s/keys :req [:action/status :mission/id :ci/profile :ci/run-path :ci/run-dir :ci/steps]
          :opt [:ci/thresholds :ci/required-tools :ci/spec :ci/run-id :ci/trigger]))

(s/def :merge/branch non-blank-string?)
(s/def :merge/base-branch non-blank-string?)
(s/def :merge/run-id non-blank-string?)
(s/def :merge/run-dir non-blank-string?)
(s/def :merge/log-root non-blank-string?)
(s/def :merge/log-path non-blank-string?)
(s/def :merge/status keyword?)
(s/def :merge/simulate-conflict? boolean?)
(s/def :merge/run
  (s/keys :req [:merge/run-id :merge/branch :merge/base-branch]
          :opt [:merge/run-dir :merge/status :ci/profile :ci/run :ci/spec :sandbox/root :merge/log-path]))
(s/def :merge/failure
  (s/keys :req [:mission/id :merge/status :merge/summary]
          :opt [:merge/log-path :merge/run-id :merge/branch :merge/base-branch]))

(s/def :action.mission/merge-prepare-config
  (s/keys :req [:mission/id :agent/id :sandbox/root]
          :opt [:merge/branch :merge/base-branch :ci/profile :ci/steps :ci/log-root
                :merge/log-root :merge/simulate-conflict?]))

(s/def :action.mission/merge-prepare-output
  (s/keys :req [:action/status :merge/run :merge/log-path]
          :opt [:ci/run :merge/failure :merge/run-dir]))

(s/def :action.mission/merge-execute-config
  (s/keys :req [:mission/id :agent/id :merge/run]
          :opt [:merge/branch :merge/base-branch :merge/log-root]))

(s/def :action.mission/merge-execute-output
  (s/keys :req [:action/status :merge/run :merge/log-path]
          :opt [:merge/failure]))

(s/def :version/plan-snapshot-id uuid?)

(s/def :action.version/snapshot-mission-config
  (s/keys :req [:mission/id :agent/id :merge/log-path]
          :opt [:work.plan/id :version/plan-snapshot-id :git/commit]))

(s/def :action.version/snapshot-mission-output
  (s/keys :req [:action/status
                :mission/id
                :version.snapshot/id
                :version.snapshot/path
                :version/snapshot]))

;; CodeType validation -------------------------------------------------------

(s/def :codetype/path non-blank-string?)
(s/def :codetype/paths (s/coll-of :codetype/path :kind vector?))
(s/def :codetype/spec-sections (s/coll-of string? :kind vector?))
(s/def :validator/ident keyword?)
(s/def :validator/status #{:status/ok :status/failed})
(s/def :codetype/validator
  (s/keys :req [:validator/ident :validator/status]
          :opt [:validator/message]))
(s/def :codetype/validators (s/coll-of :codetype/validator :kind vector?))
(s/def :code.definition/paths (s/coll-of non-blank-string? :kind vector? :min-count 1))
(s/def :code.definition/spec-sections (s/coll-of string? :kind vector?))
(s/def :codetype/definition
  (s/keys :req [:code.definition/ident
                :code.definition/paths
                :code.definition/spec-sections
                :codetype/validators]
          :opt [:code.definition/name]))
(s/def :codetype/definitions (s/coll-of :codetype/definition :kind vector? :min-count 1))
(s/def :codetype/artifact non-blank-string?)
(s/def :codetype/validated-at non-blank-string?)

(s/def :action.codetype/validate-config
  (s/and
   (s/keys :req [:mission/id :agent/id]
           :opt [:codetype/paths
                 :code.materialize/paths
                 :code.definition/idents
                 :sandbox/root
                 :workspace/root])
   (fn [config]
     (let [codetype-paths (:codetype/paths config)
           materialized-paths (:code.materialize/paths config)]
       (or (seq codetype-paths) (seq materialized-paths))))))

(s/def :action.codetype/validate-output
  (s/keys :req [:action/status
                :codetype/artifact
                :codetype/paths
                :codetype/spec-sections
                :codetype/definitions
                :codetype/validated-at]))

(s/def :codetype/ident keyword?)
(s/def :codetype/relative-path non-blank-string?)
(s/def :codetype/file non-blank-string?)
(s/def :codetype/checksum non-blank-string?)
(s/def :codetype/generated-file
  (s/keys :req [:codetype/relative-path :codetype/file :codetype/checksum]))
(s/def :codetype/generated-files (s/coll-of :codetype/generated-file :kind vector? :min-count 1))
(s/def :codetype/generation-artifact non-blank-string?)
(s/def :codetype/stamp-path non-blank-string?)
(s/def :codetype/skipped? boolean?)

(s/def :code.definition/ident keyword?)
(s/def :code.definition/idents (s/coll-of keyword? :kind vector? :min-count 1))
(s/def :code.materialize/relative-path non-blank-string?)
(s/def :code.materialize/file non-blank-string?)
(s/def :code.materialize/checksum non-blank-string?)
(s/def :code.materialize/file-entry
  (s/keys :req [:code.materialize/relative-path
                :code.materialize/file
                :code.materialize/checksum]))
(s/def :code.materialize/files (s/coll-of :code.materialize/file-entry :kind vector?))
(s/def :code.materialize/paths (s/coll-of non-blank-string? :kind vector?))
(s/def :code.materialize/spec-sections (s/coll-of string? :kind vector?))
(s/def :code.materialize/skipped? boolean?)
(s/def :code.materialize/definition
  (s/keys :req [:code.definition/ident
                :code.definition/type
                :code.materialize/files
                :code.materialize/generated-at]
          :opt [:code.type/generator
                :code.type/templates
                :code.type/generated-artifacts
                :code.definition/spec-sections]))
(s/def :code.materialize/definitions (s/coll-of :code.materialize/definition :kind vector?))

(s/def :code.proposal/id uuid?)
(s/def :code.proposal/type #{:proposal.type/code-definition
                             :proposal.type/template-instance
                             :proposal.type/spec-fragment})
(s/def :code.proposal/op #{:proposal.op/add :proposal.op/update})
(s/def :code.proposal/ident non-blank-string?)
(s/def :code.proposal/payload map?)
(s/def :code.proposal/spec-sections (s/coll-of string? :kind vector?))
(s/def :code.proposal/log-path non-blank-string?)
(s/def :code.proposal/artifacts (s/coll-of non-blank-string? :kind vector?))
(s/def :code/proposal
  (s/keys :req [:code.proposal/type
                :code.proposal/payload]
          :opt [:code.proposal/op
                :code.proposal/ident
                :code.proposal/id
                :code.proposal/spec-sections
                :code.proposal/status
                :code.proposal/notes]))
(s/def :code.proposal/proposals (s/coll-of :code/proposal :kind vector? :min-count 1))

(s/def :action.code.proposal/validate-config
  (s/keys :req [:mission/id :agent/id :code.proposal/proposals]))

(s/def :action.code.proposal/validate-output
  (s/keys :req [:action/status :code.proposal/proposals :code.proposal/log-path]
          :opt [:code.proposal/spec-sections]))

(s/def :code.proposal/log-root non-blank-string?)
(s/def :code.proposal/validation-log non-blank-string?)
(s/def :code.proposal/domain-transact? boolean?)
(s/def :code.definition/transacted (s/coll-of keyword? :kind vector?))

(s/def :action.code.proposal/apply-config
  (s/keys :req [:mission/id :agent/id :code.proposal/proposals]
          :opt [:code.proposal/log-root
                :code.proposal/validation-log
                :code.proposal/domain-transact?]))

(s/def :action.code.proposal/apply-output
  (s/keys :req [:action/status
                :code.proposal/proposals
                :code.proposal/log-path
                :version.snapshot/path
                :version.snapshot/id
                :version/snapshot]
          :opt [:code.proposal/artifacts
                :code.definition/transacted]))

(s/def :action.codetype/generate-config
  (s/keys :req [:mission/id :agent/id :sandbox/root :codetype/ident]
          :opt [:codetype/options :codetype/force?]))

(s/def :action.codetype/generate-output
  (s/keys :req [:action/status
                :codetype/ident
                :codetype/generated-files
                :codetype/generation-artifact
                :codetype/stamp-path
                :codetype/generated-at]
          :opt [:codetype/skipped?]))

(s/def :action.code.materialize/from-graph-config
  (s/keys :req [:mission/id :agent/id :sandbox/root]
          :opt [:code.definition/idents]))

(s/def :action.code.materialize/from-graph-output
  (s/keys :req [:action/status
                :code.materialize/log-path
                :code.materialize/definitions
                :code.materialize/files
                :code.materialize/paths
                :code.materialize/spec-sections]
          :opt [:mission/id :agent/id :sandbox/root :code.materialize/skipped?]))

;; Docs ---------------------------------------------------------------------

(s/def :docs/paths (s/coll-of string? :kind vector? :min-count 1))

(s/def :action.docs/sync-config
  (s/keys :req [:mission/id]
          :opt [:docs/paths]))

(s/def :action.docs/sync-output
  (s/keys :req [:action/status]
          :opt [:docs/paths]))

(s/def :doc/templates (s/coll-of keyword? :kind vector? :min-count 1))
(s/def :doc/slug non-blank-string?)
(s/def :doc/title non-blank-string?)
(s/def :doc/spec-sections (s/coll-of string? :kind vector?))
(s/def :doc/categories (s/coll-of keyword? :kind vector?))
(s/def :doc/template keyword?)
(s/def :doc/template-instance keyword?)
(s/def :markdown/path non-blank-string?)
(s/def :edn/path non-blank-string?)
(s/def :doc/payload map?)

(s/def :doc/generated
  (s/keys :req [:doc/slug :doc/title :doc/spec-sections :doc/categories
                :doc/template :doc/template-instance :markdown/path :edn/path]
          :opt [:doc/payload]))

(s/def :docs/generated (s/coll-of :doc/generated :kind vector? :min-count 1))

(s/def :action.docgen/types-config
  (s/keys :req [:mission/id]
          :opt [:doc/templates]))

(s/def :action.docgen/missions-config
  (s/keys :req [:mission/id]
          :opt [:doc/templates]))

(s/def :action.docgen/output
  (s/keys :req [:action/status :docs/generated]))

;; Spec intake -------------------------------------------------------------

(s/def :spec/input-path non-blank-string?)
(s/def :spec/resource-path non-blank-string?)
(s/def :spec/log-path non-blank-string?)
(s/def :spec/validation-path non-blank-string?)
(s/def :spec/validation-report map?)
(s/def :spec/validation-markdown non-blank-string?)
(s/def :spec/publish-log non-blank-string?)
(s/def :spec/source-path non-blank-string?)
(s/def :spec/version pos-int?)
(s/def :spec/errors (s/coll-of string? :kind vector?))
(s/def :planner/heuristics-path non-blank-string?)
(s/def :planner/generation-log-path non-blank-string?)
(s/def :plan/output-path non-blank-string?)
(s/def :plan/overrides-path non-blank-string?)
(s/def :plan.generation/id uuid?)
(s/def :plan.generation/spec-id keyword?)
(s/def :plan.generation/spec-version pos-int?)
(s/def :plan.generation/log-path non-blank-string?)
(s/def :plan.generation/work-plan-id uuid?)
(s/def :plan.generation/status #{:plan.generation.status/draft
                                 :plan.generation.status/generated
                                 :plan.generation.status/validated
                                 :plan.generation.status/rejected})
(s/def :plan.generation/decisions (s/coll-of map? :kind vector?))
(s/def :plan.generation/nodes (s/coll-of map? :kind vector?))
(s/def :plan.generation/edges (s/coll-of map? :kind vector?))
(s/def :plan.generation/coverage (s/coll-of map? :kind vector?))
(s/def :plan.generation/warnings (s/coll-of string? :kind vector?))

(s/def :action.spec/capture-config
  (s/keys :req [:mission/id :agent/id :spec/input-path]
          :opt [:spec/id :spec/status]))

(s/def :action.spec/capture-output
  (s/keys :req [:action/status :spec/id :spec/status :spec/resource-path :spec/log-path]
          :opt [:spec/source-path]))

(s/def :action.spec/validate-config
  (s/keys :req [:mission/id :agent/id :spec/id]
          :opt [:spec/resource-path]))

(s/def :action.spec/validate-output
  (s/keys :req [:action/status
                :spec/id
                :spec/status
                :spec/validation-path
                :spec/validation-report
                :spec/validation-markdown]
          :opt [:spec/errors]))

(s/def :action.spec/publish-config
  (s/keys :req [:mission/id :agent/id :spec/id]
          :opt [:spec/status]))

(s/def :action.spec/publish-output
  (s/keys :req [:action/status :spec/id :spec/status :spec/publish-log]))

(s/def :action.spec.plan/generate-config
  (s/keys :req [:mission/id
                :agent/id
                :spec/id
                :spec/version
                :spec/resource-path
                :planner/heuristics-path
                :plan/output-path
                :planner/generation-log-path]
          :opt [:plan/overrides-path]))

(s/def :action.spec.plan/generate-output
  (s/keys :req [:action/status
                :plan.generation/id
                :plan.generation/spec-id
                :plan.generation/spec-version
                :plan.generation/log-path
                :plan.generation/status
                :work-plan/resource-path]
          :opt [:plan.generation/work-plan-id
                :plan.generation/decisions
                :plan.generation/nodes
                :plan.generation/edges
                :plan.generation/coverage
                :plan.generation/warnings
                :work-plan/log-path
                :work-plan/validation-path
                :work-plan/publish-log
                :version.snapshot/path]))

(s/def :action.version/snapshot-spec-config
  (s/keys :req [:mission/id
                :agent/id
                :spec/id
                :spec/resource-path
                :spec/validation-path
                :spec/publish-log]
          :opt [:git/commit]))

(s/def :action.version/snapshot-spec-output
  (s/keys :req [:action/status
                :mission/id
                :version.snapshot/id
                :version.snapshot/path
                :version/snapshot]))

;; Work plan intake --------------------------------------------------------

(s/def :work.plan/id uuid?)
(s/def :work.plan/spec-id keyword?)
(s/def :work.plan/spec-version pos-int?)
(s/def :work.plan/status #{:work.plan.status/draft
                           :work.plan.status/candidate
                           :work.plan.status/approved
                           :work.plan.status/in-execution
                           :work.plan.status/completed
                           :work.plan.status/superseded})
(s/def :work.plan/created-by non-blank-string?)
(s/def :work.plan/created-at string?)
(s/def :plan.node/id non-blank-string?)
(s/def :plan.node/name non-blank-string?)
(s/def :plan.node/description string?)
(s/def :plan.node/mission-template map?)
(s/def :plan.node/scope-requirements (s/coll-of non-blank-string? :kind vector? :min-count 1))
(s/def :plan.node/resources (s/coll-of non-blank-string? :kind vector? :min-count 1))
(s/def :plan.node/test-scope (s/nilable map?))
(s/def :plan.node/estimated-effort string?)
(s/def :plan/node (s/keys :req [:plan.node/id :plan.node/name :plan.node/scope-requirements :plan.node/resources]
                           :opt [:plan.node/description :plan.node/mission-template :plan.node/test-scope :plan.node/estimated-effort]))
(s/def :plan.edge/from-node-id non-blank-string?)
(s/def :plan.edge/to-node-id non-blank-string?)
(s/def :plan.edge/relation #{:plan.edge.relation/depends-on :plan.edge.relation/blocks})
(s/def :plan/edge (s/keys :req [:plan.edge/from-node-id :plan.edge/to-node-id :plan.edge/relation]))
(s/def :coverage.row/requirement-id non-blank-string?)
(s/def :coverage.row/acceptance-id string?)
(s/def :coverage.row/nodes (s/coll-of non-blank-string? :kind vector? :min-count 1))
(s/def :coverage.row/code-targets (s/coll-of non-blank-string? :kind vector? :min-count 1))
(s/def :coverage.row/test-contracts (s/coll-of keyword? :kind vector? :min-count 1))
(s/def :plan/coverage-row (s/keys :req [:coverage.row/requirement-id
                                        :coverage.row/nodes
                                        :coverage.row/code-targets
                                        :coverage.row/test-contracts]
                                  :opt [:coverage.row/acceptance-id]))
(s/def :plan.obligation/id non-blank-string?)
(s/def :plan.obligation/description non-blank-string?)
(s/def :plan.obligation/checker-id keyword?)
(s/def :plan.obligation/status #{:plan.obligation.status/pending
                                 :plan.obligation.status/satisfied
                                 :plan.obligation.status/violated})
(s/def :plan.obligation/evidence string?)
(s/def :plan/proof-obligation (s/keys :req [:plan.obligation/id
                                           :plan.obligation/description
                                           :plan.obligation/checker-id
                                           :plan.obligation/status]
                                      :opt [:plan.obligation/evidence]))
(s/def :work.plan/nodes (s/coll-of :plan/node :kind vector? :min-count 1))
(s/def :work.plan/edges (s/coll-of :plan/edge :kind vector?))
(s/def :work.plan/coverage (s/coll-of :plan/coverage-row :kind vector? :min-count 1))
(s/def :work.plan/proof-obligations (s/coll-of :plan/proof-obligation :kind vector? :min-count 1))
(s/def :work.plan/validation-results (s/coll-of map? :kind vector?))
(s/def :work-plan/resource-path non-blank-string?)
(s/def :work-plan/log-path non-blank-string?)
(s/def :work-plan/source-path non-blank-string?)
(s/def :work-plan/validation-path non-blank-string?)
(s/def :work-plan/validation-report map?)
(s/def :work-plan/validation-markdown non-blank-string?)
(s/def :work-plan/errors (s/coll-of string? :kind vector?))
(s/def :work-plan/publish-log non-blank-string?)

(s/def :action.work-plan/validate-config
  (s/keys :req [:mission/id :agent/id :work.plan/id]
          :opt [:work-plan/resource-path :spec/resource-path]))

(s/def :action.work-plan/validate-output
  (s/keys :req [:action/status
                :work.plan/id
                :work-plan/validation-path
                :work-plan/validation-report
                :work-plan/validation-markdown]
          :opt [:work-plan/errors]))

(s/def :action.work-plan/publish-config
  (s/keys :req [:mission/id :agent/id :work.plan/id]
          :opt [:work.plan/status]))

(s/def :action.work-plan/publish-output
  (s/keys :req [:action/status
                :work.plan/id
                :work.plan/status
                :work-plan/publish-log]))

(s/def :action.version/snapshot-plan-config
  (s/keys :req [:mission/id
                :agent/id
                :work.plan/id
                :work.plan/spec-id
                :work-plan/resource-path
                :work-plan/validation-path
                :work-plan/publish-log]
          :opt [:git/commit]))

(s/def :action.version/snapshot-plan-output
  (s/keys :req [:action/status
                :mission/id
                :version.snapshot/id
                :version.snapshot/path
                :version/snapshot]))

;; System map ----------------------------------------------------------------

(s/def :system-map/entities (s/coll-of keyword? :kind vector?))
(s/def :code-graph/path non-blank-string?)
(s/def :code-graph/graph map?)
(s/def :code-graph/enabled? boolean?)

(s/def :action.system-map/refresh-config
  (s/keys :req [:mission/id]
          :opt [:system-map/entities
                :code-graph/enabled?
                :code-graph/path
                :code-graph/graph]))

(s/def :action.system-map/refresh-output
  (s/keys :req [:action/status]
          :opt [:system-map/entities]))

;; JS + external integrations -----------------------------------------------

(s/def :js.component/ident keyword?)
(s/def :js.component/name non-blank-string?)
(s/def :js.component/bundle-path non-blank-string?)
(s/def :js.component/description string?)
(s/def :js.component/dependencies (s/coll-of non-blank-string? :kind vector? :min-count 1))
(s/def :js.component/call-paths (s/coll-of non-blank-string? :kind vector? :min-count 1))
(s/def :js.component/risk-profile keyword?)
(s/def :js/component
  (s/keys :req [:js.component/ident
                :js.component/bundle-path
                :js.component/dependencies
                :js.component/call-paths
                :js.component/risk-profile]
          :opt [:js.component/name
                :js.component/description]))

(s/def :external.api/ident keyword?)
(s/def :external.api/name non-blank-string?)
(s/def :external.api/provider non-blank-string?)
(s/def :external.api/base-url non-blank-string?)
(s/def :external.api.endpoint/method keyword?)
(s/def :external.api.endpoint/path non-blank-string?)
(s/def :external.api.endpoint/scope keyword?)
(s/def :external.api.endpoint/call-paths (s/coll-of non-blank-string? :kind vector? :min-count 1))
(s/def :external.api/endpoint
  (s/keys :req [:external.api.endpoint/method
                :external.api.endpoint/path
                :external.api.endpoint/scope
                :external.api.endpoint/call-paths]))
(s/def :external.api/endpoints (s/coll-of :external.api/endpoint :kind vector? :min-count 1))
(s/def :external.api/risk-profile keyword?)
(s/def :external/api
  (s/keys :req [:external.api/ident
                :external.api/name
                :external.api/provider
                :external.api/base-url
                :external.api/endpoints
                :external.api/risk-profile]))

(s/def :js/components (s/coll-of :js/component :kind vector? :min-count 1))
(s/def :external/apis (s/coll-of :external/api :kind vector? :min-count 1))
(s/def :integration/components (s/coll-of map? :kind vector?))
(s/def :integration/apis (s/coll-of map? :kind vector?))
(s/def :integration/type #{:integration.type/js :integration.type/api})
(s/def :integration/approver non-blank-string?)
(s/def :integration/justification string?)
(s/def :integration/risk-profile keyword?)
(s/def :integration.call/type :integration/type)
(s/def :integration.call/call non-blank-string?)
(s/def :integration.call/component keyword?)
(s/def :integration.call/api keyword?)
(s/def :integration.call/method keyword?)
(s/def :integration.call/path non-blank-string?)
(s/def :integration.call/scope keyword?)
(s/def :integration/call
  (s/keys :req [:integration.call/type :integration.call/call]
          :opt [:integration.call/component
                :integration.call/api
                :integration.call/method
                :integration.call/path
                :integration.call/scope]))
(s/def :integration/call-graph (s/coll-of :integration/call :kind vector?))
(s/def :integration/reason string?)
(s/def :integration/approval-path non-blank-string?)
(s/def :integration/revocation-path non-blank-string?)

(s/def :action.js.component/register-config
  (s/keys :req [:mission/id :agent/id :js/components]))
(s/def :action.js.component/register-output
  (s/keys :req [:action/status :integration/components]))

(s/def :action.external.api/register-config
  (s/keys :req [:mission/id :agent/id :external/apis]))
(s/def :action.external.api/register-output
  (s/keys :req [:action/status :integration/apis]))

(s/def :action.integration/scan-config
  (s/keys :req [:mission/id :integration/components :integration/apis]))
(s/def :action.integration/scan-output
  (s/keys :req [:action/status :integration/call-graph]))

(s/def :action.integration/approval-config
  (s/keys :req [:mission/id :agent/id :integration/type :integration/components
                :integration/apis :integration/call-graph :integration/approver]
          :opt [:integration/justification :integration/risk-profile]))
(s/def :action.integration/approval-output
  (s/keys :req [:action/status :integration/approval-path :integration/risk-profile]))

(s/def :action.integration/revoke-config
  (s/keys :req [:mission/id :agent/id :integration/type]
          :opt [:integration/components :integration/apis :integration/reason]))
(s/def :action.integration/revoke-output
  (s/keys :req [:action/status :integration/revocation-path]))
;; Deployments --------------------------------------------------------------

(s/def :deploy.environment/ident keyword?)
(s/def :deploy.environment/name non-blank-string?)
(s/def :deploy.environment/strategy #{:deploy.strategy/blue-green
                                      :deploy.strategy/canary
                                      :deploy.strategy/rollback})
(s/def :deploy.environment/tier #{:deploy.tier/dev :deploy.tier/staging :deploy.tier/prod})
(s/def :deploy.environment/risk #{:deploy.risk/low :deploy.risk/medium :deploy.risk/high})
(s/def :deploy.environment/required-approvals (s/coll-of keyword? :kind vector?))
(s/def :deploy.environment/paths (s/coll-of string? :kind vector?))
(s/def :deploy.environment/slots (s/coll-of keyword? :kind vector?))
(s/def :deploy.environment/traffic-policy string?)

(s/def :deploy/environment
  (s/keys :req [:deploy.environment/ident
                :deploy.environment/name
                :deploy.environment/strategy]
          :opt [:deploy.environment/tier
                :deploy.environment/risk
                :deploy.environment/required-approvals
                :deploy.environment/paths
                :deploy.environment/slots
                :deploy.environment/traffic-policy]))

(s/def :deploy.approval/role keyword?)
(s/def :deploy.approval/by non-blank-string?)
(s/def :deploy.approval/ticket non-blank-string?)
(s/def :deploy/approvals
  (s/coll-of (s/keys :req [:deploy.approval/role :deploy.approval/by]
                     :opt [:deploy.approval/ticket])
             :kind vector?
             :min-count 1))

(s/def :deploy.build/id non-blank-string?)
(s/def :deploy.build/artifact non-blank-string?)
(s/def :deploy.build/checksum non-blank-string?)
(s/def :deploy.build/commit non-blank-string?)
(s/def :deploy.build/tests string?)
(s/def :deploy.build/meta string?)

(s/def :deploy/build
  (s/keys :req [:deploy.build/id :deploy.build/artifact]
          :opt [:deploy.build/checksum :deploy.build/commit :deploy.build/tests :deploy.build/meta]))

(s/def :deploy/traffic (s/map-of keyword? int?))
(s/def :deploy/health map?)
(s/def :deploy/evidence non-blank-string?)

(s/def :deploy.cycle/id non-blank-string?)
(s/def :deploy.cycle/strategy #{:deploy.strategy/blue-green
                                 :deploy.strategy/canary
                                 :deploy.strategy/rollback})
(s/def :deploy.cycle/status #{:deploy.cycle.status/staged
                               :deploy.cycle.status/canary
                               :deploy.cycle.status/canary-complete
                               :deploy.cycle.status/active
                               :deploy.cycle.status/promoted
                               :deploy.cycle.status/rolled-back})
(s/def :deploy.cycle/environment (s/or :ident keyword?
                                       :definition :deploy/environment))
(s/def :deploy.cycle/build (s/or :id non-blank-string?
                                 :definition :deploy/build))
(s/def :deploy.cycle/approvals (s/coll-of map? :kind vector?))
(s/def :deploy.cycle/traffic :deploy/traffic)
(s/def :deploy.cycle/health :deploy/health)
(s/def :deploy.cycle/evidence non-blank-string?)
(s/def :deploy.cycle/slot keyword?)

(s/def :deploy/cycle
  (s/keys :req [:deploy.cycle/id :deploy.cycle/strategy :deploy.cycle/environment]
          :opt [:deploy.cycle/build
                :deploy.cycle/status
                :deploy.cycle/approvals
                :deploy.cycle/traffic
                :deploy.cycle/health
                :deploy.cycle/evidence
                :deploy.cycle/slot]))

(s/def :deploy/action-config
  (s/keys :req [:mission/id :deploy/cycle]))

(s/def :deploy/action-output
  (s/keys :req [:action/status :deploy/environment :deploy/cycle]
          :opt [:deploy/build :deploy/evidence]))

(s/def :action.deploy/stage-config :deploy/action-config)
(s/def :action.deploy/flip-config :deploy/action-config)
(s/def :action.deploy/start-canary-config :deploy/action-config)
(s/def :action.deploy/stop-canary-config :deploy/action-config)
(s/def :action.deploy/promote-config :deploy/action-config)
(s/def :action.deploy/rollback-config :deploy/action-config)

(s/def :action.deploy/stage-output :deploy/action-output)
(s/def :action.deploy/flip-output :deploy/action-output)
(s/def :action.deploy/start-canary-output :deploy/action-output)
(s/def :action.deploy/stop-canary-output :deploy/action-output)
(s/def :action.deploy/promote-output :deploy/action-output)
(s/def :action.deploy/rollback-output :deploy/action-output)
;; Mission lifecycle ---------------------------------------------------------

(s/def :mission/queue-tags (s/coll-of keyword? :kind vector?))
(s/def :mission/conflicts (s/coll-of keyword? :kind vector?))
(s/def :mission/blocked? boolean?)
(s/def :mission/active-queues keyword-set?)
(s/def :queue/tag keyword?)
(s/def :queue/tags (s/coll-of :queue/tag :kind vector? :min-count 1))

(s/def :mission.ready/summary
  (s/keys :req [:mission/id :mission/title :mission/summary :mission/category
                :mission/priority :mission/status :mission/queue-tags]
          :opt [:mission/spec-section :mission/protocol :mission/protocol-version
                :mission/tests :mission/work-tracks :mission/prerequisites
                :mission/owner :mission/conflicts :mission/blocked?]))

(s/def :mission/list (s/coll-of :mission.ready/summary :kind vector?))

(s/def :mission/config map?)
(s/def :mission/known (s/coll-of string? :kind set?))
(s/def :mission/target keyword?)
(s/def :mission/context map?)

(s/def :action.mission/validate-config
  (s/keys :req [:mission/config]
          :opt [:mission/known]))

(s/def :mission/status keyword?)

(s/def :action.mission/validate-output
  (s/keys :req [:action/status :mission/id :mission/status]))

(s/def :action.mission/transition-config
  (s/keys :req [:mission/config :mission/target :mission/context]))

(s/def :action.mission/transition-output
  (s/keys :req [:action/status :mission/id :mission/status]))

(s/def :action.mission/list-ready-config
  (s/keys :opt [:queue/tags]))

(s/def :action.mission/list-ready-output
  (s/keys :req [:action/status :mission/list]
          :opt [:mission/active-queues]))

(s/def :mission/selection :mission.ready/summary)
(s/def :mission/start map?)
(s/def :protocol/context map?)
(s/def :protocol/run map?)

(s/def :action.mission/start-config
  (s/keys :req [:agent/id]
          :opt [:mission/id :queue/tags :workspace/root :branch/prefix]
          :opt-un [::locks]))

(s/def :action.mission/start-output
  (s/keys :req [:action/status :mission/selection :mission/start]
          :opt [:mission/active-queues]))

(s/def :action.protocol/run-config
  (s/keys :req [:mission/id :agent/id :protocol/ident]
          :opt [:protocol/context]))

(s/def :action.protocol/run-output
  (s/keys :req [:action/status :protocol/run]))

(s/def :report/artifact :log.step/artifact)
(s/def :report/artifacts (s/coll-of :report/artifact :kind vector? :min-count 1))
(s/def :report/summary non-blank-string?)
(s/def :mission.report/path non-blank-string?)

(s/def :mission.report/worklogs (s/coll-of map? :kind vector?))
(s/def :mission.report/tests (s/coll-of map? :kind vector?))
(s/def :mission.report/docgen (s/map-of keyword? :action.docgen/output))
(s/def :mission.report/system-map :action.system-map/refresh-output)
(s/def :mission.report/generated-at non-blank-string?)
(s/def :mission.report/summary :report/summary)
(s/def :mission.report/artifacts :report/artifacts)
(s/def :mission/report
  (s/keys :req [:mission/id :agent/id]
          :req-un [:mission.report/summary
                   :mission.report/artifacts]
          :opt-un [:mission.report/worklogs
                   :mission.report/tests
                   :mission.report/docgen
                   :mission.report/system-map
                   :mission.report/generated-at]))

(s/def :action.mission/report-config
  (s/keys :req [:mission/id :agent/id :report/artifacts]
          :opt [:report/summary
                :mission.report/worklogs
                :mission.report/tests
                :mission.report/docgen
                :mission.report/system-map]))

(s/def :action.mission/report-output
  (s/keys :req [:action/status :report/path]
          :opt [:report/submitted? :artifacts/captured?]))

(s/def :mission.approval/by non-blank-string?)
(s/def :mission.approval/role keyword?)
(s/def :mission.approval/notes string?)
(s/def :mission.approval/steward? boolean?)
(s/def :mission/approval
  (s/keys :req [:mission.approval/by]
          :opt [:mission.approval/role
                :mission.approval/notes
                :mission.approval/steward?]))

(s/def :approval/path non-blank-string?)

(s/def :action.mission/approve-config
  (s/keys :req [:mission/id :agent/id :mission/report :mission/approval]))

(s/def :action.mission/approve-output
  (s/keys :req [:action/status :approval/path]
          :opt [:approval/steward?]))

(s/def :archive/artifacts :report/artifacts)
(s/def :archive/summary non-blank-string?)
(s/def :archive/path non-blank-string?)

(s/def :action.mission/archive-config
  (s/keys :req [:mission/id :agent/id :mission/report :mission/approval :archive/artifacts]
          :opt [:archive/summary]))

(s/def :action.mission/archive-output
  (s/keys :req [:action/status :archive/path]
          :opt [:artifacts/captured?]))

;; Recipe catalog + planner routing -----------------------------------------

(s/def :recipe/catalog-path non-blank-string?)
(s/def :recipe/count pos-int?)
(s/def :recipe/idents (s/coll-of keyword? :kind vector? :min-count 1))

(s/def :action.recipe/validate-config
  (s/keys :req [:mission/id]
          :opt [:recipe/catalog-path]))

(s/def :action.recipe/validate-output
  (s/keys :req [:action/status :recipe/count :recipe/idents]
          :opt [:recipe/catalog-path]))

(s/def :planner/request map?)
(s/def :planner/classifier map?)
(s/def :planner/recipes (s/coll-of map? :kind vector?))
(s/def :planner/catalog (s/or :path non-blank-string?
                              :catalog map?))
(s/def :planner/route map?)
(s/def :planner/decision keyword?)
(s/def :planner/recipe keyword?)

(s/def :action.planner/route-config
  (s/keys :req [:mission/id :planner/request]
          :opt [:planner/classifier :planner/recipes :planner/catalog]))

(s/def :action.planner/route-output
  (s/keys :req [:action/status :planner/decision :planner/recipe]
          :opt [:planner/route]))
