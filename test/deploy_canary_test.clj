(ns deploy-canary-test
  (:require
   [clojure.test :refer [deftest is]]
   [intuition.deploy.runtime :as deploy]
   [intuition.sfs.protocols.runtime :as protocols]
   [support.datomic :as support]))

(def ^:private canary-env
  {:deploy.environment/ident :deploy.env/canary
   :deploy.environment/name "Canary"
   :deploy.environment/strategy :deploy.strategy/canary
   :deploy.environment/tier :deploy.tier/prod
   :deploy.environment/risk :deploy.risk/high
   :deploy.environment/required-approvals [:role/ops]
   :deploy.environment/slots [:blue :green :canary]})

(def ^:private canary-build
  {:deploy.build/id "build-canary"
   :deploy.build/artifact "builds/app-canary.tar"
   :deploy.build/checksum "sha256-def"
   :deploy.build/commit "def456"})

(def ^:private canary-approvals
  [{:deploy.approval/role :role/ops
    :deploy.approval/by "ops-bot"
    :deploy.approval/ticket "OPS-2"}])

(def ^:private canary-health
  {:deploy.health/status :status/ok
   :deploy.health/metrics {:latency-ms 30 :error-rate 0.01}})

(def ^:private canary-cycle
  {:deploy.cycle/id "cycle-canary"
   :deploy.cycle/strategy :deploy.strategy/canary
   :deploy.cycle/environment canary-env
   :deploy.cycle/build canary-build
   :deploy.cycle/approvals canary-approvals
   :deploy.cycle/traffic {:blue 90 :canary 10}
   :deploy.cycle/health canary-health})

(defn- run-canary!
  [conn]
  (protocols/run! {:conn conn
                   :protocol/ident :protocol/deploy-canary
                   :permissions #{:permission/deploy.manage}
                   :context {:mission/id "M-10"
                             :deploy/cycle canary-cycle}}))

(deftest canary-protocol-promotes-build
  (support/with-test-conn
   (fn [conn]
     (deploy/reset-state!)
     (run-canary! conn)
     (let [state (deploy/environment-state :deploy.env/canary)
           cycle-state (get-in state [:cycles "cycle-canary"])
           evidence (deploy/consume-evidence! "M-10")]
       (is (= :deploy.cycle.status/promoted (:deploy.cycle/status cycle-state)))
       (is (= {:canary 100 :blue 0 :green 0}
              (:traffic state)))
       (is (= 1 (count evidence)))))))

(deftest invalid-canary-traffic-is-rejected
  (deploy/reset-state!)
  (try
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"traffic"
         (do
           (deploy/stage-build-action {:config {:mission/id "M-10"
                                                :deploy/cycle canary-cycle}})
           (deploy/start-canary-action
            {:config {:mission/id "M-10"
                      :deploy/cycle (assoc canary-cycle :deploy.cycle/traffic {:blue 10 :canary 10})}}))))
    (finally
      (deploy/reset-state!))))
