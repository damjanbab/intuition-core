(ns external-api-call-paths-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [datomic.client.api :as d]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [intuition.sfs.missions.runtime :as missions]
   [support.datomic :as support]))

(defn- delete-tree!
  [^java.io.File f]
  (when (and f (.exists f))
    (when (.isDirectory f)
      (doseq [child (.listFiles f)]
        (delete-tree! child)))
    (io/delete-file f true)))

(defn- cleanup!
  [mission-id]
  (doseq [dir [(io/file "tmp" "missions" (bootstrap/sanitize-fragment mission-id))
               (io/file "missions" "logs" (bootstrap/sanitize-fragment mission-id))]]
    (delete-tree! dir)))

(defn- sensitive-vector
  [entries]
  (mapv pr-str entries))

(defn- mission-record
  [mission-id apis]
  {:mission/id mission-id
   :mission/title "External API governed mission"
   :mission/summary "Exercises outbound approval flow."
   :mission/category :mission.category/security
   :mission/priority :mission.priority/p1
   :mission/status :mission.status/ready
   :mission/protocol :protocol/mission-standard
   :mission/protocol-version 1
   :mission/scope "{:paths [\"src\"]}"
   :mission/prerequisites []
   :mission/deliverables ["README.md"]
   :mission/work-tracks [:work-track/planning]
   :mission/tests ["test/actions_contract_test.clj"]
   :mission/spec-section :spec/phase-1
   :mission/owner :role/steward
   :mission/js-components (sensitive-vector [])
   :mission/external-apis (sensitive-vector apis)})

(defn- api-endpoint
  [{:keys [method path scope call-paths]}]
  {:external.api.endpoint/method method
   :external.api.endpoint/path path
   :external.api.endpoint/scope scope
   :external.api.endpoint/call-paths call-paths})

(defn- api-entry
  []
  {:external.api/ident :external.api/audit-feed
   :external.api/name "Audit feed"
   :external.api/provider "AuditOps"
   :external.api/base-url "https://audit.ops/api"
   :external.api/endpoints [(api-endpoint {:method :get
                                           :path "/reports"
                                           :scope :scope/report.read
                                           :call-paths ["http/get" "fetch/reports"]})]
   :external.api/risk-profile :risk.profile/medium})

(deftest external-api-approvals-required
  (support/with-test-conn
   (fn [conn]
     (let [mission-id "M-09-API"
           api (api-entry)]
       (cleanup! mission-id)
       (d/transact conn {:tx-data [(mission-record mission-id [api])]})
       (testing "start blocked until API approved"
       (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"External API approvals missing"
            (missions/start! {:mission/id mission-id
                              :agent/id "api-agent"
                              :conn conn}))))
       (missions/request-external-api-approval! {:mission/id mission-id
                                                 :agent/id "api-agent"
                                                 :conn conn
                                                 :permissions #{:permission/security.approve}})
       (let [result (missions/start! {:mission/id mission-id
                                      :agent/id "api-agent"
                                      :conn conn})]
         (is (= :mission.status/in-progress (:mission/status result))))
       (let [log-file (io/file "missions" "logs" (bootstrap/sanitize-fragment mission-id) "external-api-approvals.edn")]
         (is (.exists log-file))
         (let [payload (edn/read-string (slurp log-file))]
           (is (= #{:external.api/audit-feed}
                  (set (map :external.api/ident (:integration/apis payload))))))))
     (cleanup! "M-09-API")
     (testing "invalid call paths are rejected"
       (let [mission-id "M-09-API-BAD"
             bad-api (assoc-in (api-entry)
                               [:external.api/endpoints 0 :external.api.endpoint/call-paths]
                               ["../etc/passwd"])
             _ (cleanup! mission-id)]
         (d/transact conn {:tx-data [(mission-record mission-id [bad-api])]})
         (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Call paths cannot escape the runtime sandbox"
              (missions/request-external-api-approval! {:mission/id mission-id
                                                        :agent/id "api-agent"
                                                        :conn conn
                                                        :permissions #{:permission/security.approve}}))))
       (cleanup! "M-09-API-BAD")))))
