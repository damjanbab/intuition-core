(ns run-mission-pipeline-test
  (:require
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [intuition.gateway.orchestrator :as orchestrator]
   [support.datomic :as support]))

(defn- delete-tree!
  [^java.io.File file]
  (when (and file (.exists file))
    (doseq [child (.listFiles file)]
      (delete-tree! child))
    (io/delete-file file true)))

(def required-permissions
  #{:permission/env.bootstrap
    :permission/locks.manage
    :permission/tests.run
    :permission/missions.manage})

(defn- canonical
  [path]
  (.getCanonicalPath (io/file path)))

(defn- bundle-map
  [mission-id root-dir token-path manifest-path run-log-path spec-path plan-path bundle-path]
  {:bundle/id "run-mission-bundle/v2"
   :bundle/version 2
   :mission/id mission-id
   :mission/record {:mission/id mission-id
                    :mission/title "Gateway pipeline integration test"
                    :mission/summary "Exercises SYSTEM_SPEC §§2.1–2.2, §§3.3–3.6, §5, §6, §9, §11."
                    :mission/status :mission.status/ready
                    :mission/category :mission.category/platform
                    :mission/priority :mission.priority/p1
                    :mission/queue-tags [:mission.queue/test]
                    :mission/protocol :protocol/mission-standard
                    :mission/protocol-version 1
                    :mission/scope (pr-str {:paths ["src" "dev" "test"]})
                    :mission/work-tracks [:work-track/plan :work-track/code :work-track/test-functional]
                   :mission/tests ["test/run_mission_pipeline_test.clj"]
                   :mission/spec-section :spec.section/orchestrator
                   :mission/owner :role/dictionary-engineer}
   :spec/source {:spec/id (keyword (str "spec/" mission-id))
                 :spec/title "Gateway pipeline DR1"
                 :spec/summary "Sample spec powering the orchestrator pipeline test"
                 :spec/requirements ["REQ-CTX" "REQ-RUN"]
                 :spec/acceptance-criteria ["AC-BUNDLE" "AC-RUN"]
                 :spec/test-contracts [:code/sample.validator]
                 :spec/spec-sections ["3.3" "3.4" "3.5" "3.6" "5" "9" "11"]
                 :spec/input-path (canonical spec-path)}
   :plan/snapshot {:plan/path (canonical plan-path)}
   :planner/heuristics-path "missions/logs/M-20251121-701/planner-heuristics.edn"
   :locks/required #{:lock/test-bundle}
   :permissions/required required-permissions
   :permissions/escalated #{:permission/security.approve}
   :sandbox {:root (canonical (io/file root-dir "sandbox"))
             :cleanup? true}
   :branch {:prefix "mission"
            :name (str "mission/" mission-id)}
   :trace {:run-id (str "run-" mission-id)
           :request-id (str "req-" mission-id)
           :channel :agent-gateway
           :agent-id "codex"
           :auth/role :role/dictionary-engineer}
   :run/stages [:spec/load :plan/validate :plan/snapshot :mission/instantiate :mission/standard :merge/simulate :analytics/emit]
   :artifacts/expected [{:path (canonical (io/file root-dir "expected-artifact.txt"))
                         :label "Expected artifact"
                         :channel :artifact.channel/custom
                         :required? true
                         :generator :stage/complete}]
   :logging/manifest-path (canonical manifest-path)
   :logging/run-log-path (canonical run-log-path)
   :logging/truncate-bytes 4096
   :analytics/targets [:analytics/edn :analytics/markdown]
   :retry {:idempotency-key (str mission-id "::bundle")
           :max-attempts 1}
   :gateway/cli {:command ["clojure" "-M:dev" "-m" "dev.agent-gateway" "run-mission"
                           (format "{:mission/id \"%s\" :context/bundle-path \"%s\" :agent/id \"codex\" :auth/token \"test-token\"}"
                                   mission-id
                                   (canonical bundle-path))]}
   :auth/token-path (canonical token-path)
   :options {:dry-run? false}})

(deftest orchestrator-run-wires-artifacts-and-permissions
  (testing "gateway run-mission executes governed pipeline with capped logs and evidence"
    (support/with-test-conn
      (fn [conn]
        (let [mission-id (str "M-RUN-" (System/currentTimeMillis))
              root (io/file "tmp" "run-mission-pipeline" mission-id)
              bundle-path (io/file root "context-bundle.edn")
              token-path (io/file root "auth.token")
              manifest-path (io/file root "run-manifest.edn")
              run-log-path (io/file root "run.log")
              spec-path (io/file root "specs" "dr1-spec.edn")
              plan-path (io/file root "plans" "generated-plan.edn")]
          (.mkdirs (.getParentFile bundle-path))
          (spit token-path "test-token")
          (let [bundle (bundle-map mission-id root token-path manifest-path run-log-path spec-path plan-path bundle-path)]
            (spit bundle-path (pr-str bundle))
            (try
              (let [result (orchestrator/run-mission! {:mission/id mission-id
                                                       :context/bundle-path (.getCanonicalPath bundle-path)
                                                       :agent/id "codex"
                                                       :auth/token "test-token"
                                                       :conn conn})
                    manifest (edn/read-string (slurp manifest-path))
                    run-log (io/file run-log-path)]
                (is (= :status/ok (:action/status result)))
                (is (= (:stage/results result)
                       [:spec/load :plan/validate :plan/snapshot :mission/instantiate :mission/standard :merge/simulate :analytics/emit]))
                (is (<= (.length run-log) 4096) "run log should be capped at truncation limit")
                (is (every? #(-> % :artifact/path io/file .exists) (:artifacts manifest)) "each artifact path should exist")
                (is (= [:mission.queue/test] (:mission/queue-tags manifest)) "queue metadata should be recorded")
                (is (= :mission.priority/p1 (:mission/priority manifest)))
                (is (= #{:lock/test-bundle} (:locks/requested manifest)))
                (is (= (canonical bundle-path) (:context/bundle-path manifest)))
                (is (set/superset? (set (:permissions/granted manifest)) required-permissions)
                    "granted permissions cover required set")
                (is (= required-permissions (set (:permissions/enforced manifest))))
                (is (= mission-id (get-in manifest [:trace/watermark :mission/id])))
                (is (seq (:system-spec/sections (get manifest :trace/watermark))))) 
              (finally
                (delete-tree! root)))))))
)) 
