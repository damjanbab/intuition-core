(ns js-security-sandbox-test
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
  (let [targets [(io/file "tmp" "missions" (bootstrap/sanitize-fragment mission-id))
                 (io/file "missions" "logs" (bootstrap/sanitize-fragment mission-id))
                 (io/file "tmp" "js-security" (bootstrap/sanitize-fragment mission-id))]]
    (doseq [dir targets]
      (delete-tree! dir))))

(defn- bundle-path!
  [mission-id]
  (let [dir (io/file "tmp" "js-security" (bootstrap/sanitize-fragment mission-id))]
    (.mkdirs dir)
    (let [file (io/file dir "component.js")]
      (spit file "console.log('secured');")
      (.getCanonicalPath file))))

(defn- sensitive-vector
  [entries]
  (mapv pr-str entries))

(defn- mission-record
  [mission-id js-components]
  {:mission/id mission-id
   :mission/title "JS governed mission"
   :mission/summary "Exercises JS approval gating."
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
   :mission/js-components (sensitive-vector js-components)
   :mission/external-apis (sensitive-vector [])})

(deftest js-components-require-approval
  (support/with-test-conn
    (fn [conn]
      (let [mission-id "M-09-JS"
            _ (cleanup! mission-id)
            bundle (bundle-path! mission-id)
            component {:js.component/ident :js.component/security-console
                       :js.component/name "Security console"
                       :js.component/bundle-path bundle
                       :js.component/dependencies ["react" "viz"]
                       :js.component/call-paths ["ui.security/init" "ui.security/render"]
                       :js.component/risk-profile :risk.profile/high}]
       (d/transact conn {:tx-data [(mission-record mission-id [component])]})
       (testing "start! fails until approval recorded"
         (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"JS components require recorded approvals"
              (missions/start! {:mission/id mission-id
                                :agent/id "secure-agent"
                                :conn conn}))))
       (missions/request-js-approval! {:mission/id mission-id
                                       :agent/id "secure-agent"
                                       :conn conn
                                       :permissions #{:permission/security.approve}})
       (let [start-result (missions/start! {:mission/id mission-id
                                            :agent/id "secure-agent"
                                            :conn conn})]
         (is (= :mission.status/in-progress (:mission/status start-result))))
       (let [log-file (io/file "missions" "logs" (bootstrap/sanitize-fragment mission-id) "js-approvals.edn")]
         (is (.exists log-file))
         (let [payload (edn/read-string (slurp log-file))]
           (is (= #{:js.component/security-console}
                  (set (map :js.component/ident (:integration/components payload))))))))
     (cleanup! "M-09-JS"))))
