(ns deploy-blue-green-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [datomic.client.api :as d]
   [intuition.deploy.runtime :as deploy]
   [intuition.sfs.actions.runtime :as actions]
   [intuition.sfs.missions.runtime :as missions]
   [intuition.sfs.protocols.runtime :as protocols]
   [support.datomic :as support]))

(def ^:private prod-environment
  {:deploy.environment/ident :deploy.env/prod
   :deploy.environment/name "Production"
   :deploy.environment/strategy :deploy.strategy/blue-green
   :deploy.environment/tier :deploy.tier/prod
   :deploy.environment/risk :deploy.risk/high
   :deploy.environment/required-approvals [:role/ops]
   :deploy.environment/slots [:blue :green]})

(def ^:private prod-build
  {:deploy.build/id "build-001"
   :deploy.build/artifact "builds/app-001.tar"
   :deploy.build/checksum "sha256-abc"
   :deploy.build/commit "abc123"})

(def ^:private approvals
  [{:deploy.approval/role :role/ops
    :deploy.approval/by "ops-bot"
    :deploy.approval/ticket "OPS-1"}])

(def ^:private health-metrics
  {:deploy.health/status :status/ok
   :deploy.health/metrics {:latency-ms 45 :error-rate 0.0}})

(defn- blue-green-cycle
  [id]
  {:deploy.cycle/id id
   :deploy.cycle/strategy :deploy.strategy/blue-green
   :deploy.cycle/environment prod-environment
   :deploy.cycle/build prod-build
   :deploy.cycle/approvals approvals
   :deploy.cycle/health health-metrics})

(defn- run-protocol!
  [conn ident cycle]
  (protocols/run! {:conn conn
                   :protocol/ident ident
                   :permissions #{:permission/deploy.manage}
                   :context {:mission/id "M-10"
                             :deploy/cycle cycle}}))

(deftest blue-green-protocol-produces-evidence
  (support/with-test-conn
   (fn [conn]
     (deploy/reset-state!)
     (run-protocol! conn :protocol/deploy-blue-green (blue-green-cycle "cycle-prod"))
     (let [state (deploy/environment-state :deploy.env/prod)
           cycle-state (get-in state [:cycles "cycle-prod"])
           evidence (deploy/consume-evidence! "M-10")]
       (is (= :green (:active-slot state)) "Traffic should land on the green slot")
       (is (= :deploy.cycle.status/active (:deploy.cycle/status cycle-state)))
       (is (= 1 (count evidence)) "Evidence must be captured for blue/green flips")
       (is (.exists (io/file (:path (first evidence)))))))))

(deftest rollback-restores-previous-slot
  (support/with-test-conn
   (fn [conn]
     (deploy/reset-state!)
     (run-protocol! conn :protocol/deploy-blue-green (blue-green-cycle "cycle-prod"))
     (run-protocol! conn :protocol/deploy-rollback
                    {:deploy.cycle/id "cycle-rollback"
                     :deploy.cycle/strategy :deploy.strategy/rollback
                     :deploy.cycle/environment prod-environment
                     :deploy.cycle/approvals approvals})
     (let [state (deploy/environment-state :deploy.env/prod)
           evidence (deploy/consume-evidence! "M-10")]
       (is (= :blue (:active-slot state)) "Rollback should send traffic back to blue")
       (is (some #(str/includes? (:path %) "deploy-rollback") evidence))))))

(deftest missing-approvals-are-rejected
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo
       #"Missing deployment approvals"
       (deploy/stage-build-action
        {:config {:mission/id "M-10"
                  :deploy/cycle {:deploy.cycle/id "cycle-no-approval"
                                 :deploy.cycle/strategy :deploy.strategy/blue-green
                                 :deploy.cycle/environment prod-environment
                                 :deploy.cycle/build prod-build
                                 :deploy.cycle/health health-metrics}}}))))

(defn- insert-mission!
  [conn mission-id scope-edn]
  (d/transact conn {:tx-data [{:mission/id mission-id
                                :mission/title "Deployment mission"
                                :mission/summary "Covers blue/green deploy"
                                :mission/category :mission.category/ops
                                :mission/priority :mission.priority/p1
                                :mission/status :mission.status/ready
                                :mission/protocol :protocol/mission-standard
                                :mission/protocol-version 1
                                :mission/scope scope-edn
                                :mission/prerequisites []
                                :mission/deliverables ["src/intuition/deploy/runtime.clj"]
                                :mission/work-tracks [:work-track/code]
                                :mission/queue-tags [:mission.queue/core]
                                :mission/tests ["test/deploy_blue_green_test"]
                                :mission/spec-section :spec/phase-4
                                :mission/owner :role/steward}]}))

(defn- mission-scope-edn
  []
  (pr-str {:paths ["src"]
           :deployments [{:deploy.cycle/id "cycle-report"
                           :deploy.cycle/strategy :deploy.strategy/blue-green
                           :deploy.cycle/environment prod-environment
                           :deploy.cycle/build prod-build
                           :deploy.cycle/approvals approvals
                           :deploy.cycle/health health-metrics}]}))

(deftest mission-report-captures-deployment-artifacts
     (support/with-test-conn
   (fn [conn]
     (deploy/reset-state!)
     (insert-mission! conn "M-DEPLOY" (mission-scope-edn))
     (missions/start! {:mission/id "M-DEPLOY"
                       :agent/id "agent"
                       :conn conn})
     (actions/execute!
     {:conn conn
       :action/ident :action/codetype.validate
       :config {:mission/id "M-DEPLOY"
                :agent/id "agent"
                :codetype/paths ["src/intuition/deploy/runtime.clj"]}
       :permissions #{:permission/tests.run}})
     (let [result (missions/report! {:mission/id "M-DEPLOY"
                                     :agent/id "agent"
                                     :artifacts []
                                     :conn conn
                                     :permissions #{:permission/deploy.manage}})
           report-path (:report/path result)
           report (-> report-path slurp edn/read-string)
           deploy-artifact (some #(when (str/includes? (:path %) "deploy-blue-green") %) (:artifacts report))]
       (is report-path)
       (is deploy-artifact "Mission report should include deploy evidence")))))
