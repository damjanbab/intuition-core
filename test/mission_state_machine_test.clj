(ns mission-state-machine-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [datomic.client.api :as d]
   [intuition.datomic :as datomic]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [intuition.sfs.log.step :as log.step]
   [intuition.sfs.missions.runtime :as missions]
   [intuition.sfs.missions.state-machine :as sm]
   [support.datomic :as support])
  (:import
   (java.time Instant)
   (java.util Date UUID)))

(def test-mission-id "M-RUNTIME-TEST")
(def agent-id "runtime-tester")
(def required-tracks [:work-track/planning
                      :work-track/code
                      :work-track/test-functional
                      :work-track/doc
                      :work-track/system-map])

(def base-record
  {:mission/id test-mission-id
   :mission/title "Runtime test mission"
   :mission/summary "Covers §3.1 lifecycle transitions."
   :mission/category :mission.category/governance
   :mission/priority :mission.priority/p1
   :mission/status :mission.status/ready
   :mission/protocol :protocol/mission-standard
   :mission/protocol-version 1
   :mission/scope "{:paths [\"src\"]}"
   :mission/prerequisites []
   :mission/deliverables ["README.md"]
   :mission/work-tracks required-tracks
   :mission/tests ["test/actions_contract_test.clj"]
   :mission/spec-section :spec/phase-1
   :mission/owner :role/steward})

(defn- delete-tree!
  [^java.io.File f]
  (when (and f (.exists f))
    (doseq [child (.listFiles f)]
      (delete-tree! child))
    (io/delete-file f true)))

(defn- cleanup-mission-files!
  []
  (delete-tree! (io/file "tmp" "missions" (bootstrap/sanitize-fragment test-mission-id)))
  (delete-tree! (io/file "tmp" "mission-tests" (bootstrap/sanitize-fragment test-mission-id)))
  (delete-tree! (io/file "missions" "logs" (bootstrap/sanitize-fragment test-mission-id))))

(defn- insert-test-mission!
  [conn]
  (missions/prepare-conn! conn)
  (d/transact conn {:tx-data [base-record]}))

(defn- with-runtime-db
  [f]
  (support/with-test-conn
   (fn [conn]
     (insert-test-mission! conn)
     (with-redefs [datomic/ensure-db!
                   (fn ensure-db!
                     ([] conn)
                     ([_] conn))]
       (try
         (f conn)
         (finally
           (cleanup-mission-files!)))))))

(deftest transitions-cannot-skip-work
  (testing "draft cannot jump to awaiting-review"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Transition .*draft.*awaiting-review"
         (sm/enforce-transition!
          (assoc base-record :mission/status :mission.status/draft)
          :mission.status/awaiting-review
          {:worklogs {:count 0 :tracks #{}}}))))
  (testing "awaiting-review requires report context"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Mission report missing"
         (sm/enforce-transition!
          (assoc base-record :mission/status :mission.status/awaiting-review)
          :mission.status/done
          {:worklogs {:count 1 :tracks (set required-tracks)}})))))

(defn- prepare-evidence!
  []
  (let [root (doto (io/file "tmp" "mission-tests" (bootstrap/sanitize-fragment test-mission-id))
               .mkdirs)
        before (io/file root "before.txt")
        after (io/file root "after.txt")
        artifact (io/file root "artifact.txt")]
    (spit before "before-state")
    (spit after "after-state")
    (spit artifact "artifact")
    {:before (.getCanonicalPath before)
     :after (.getCanonicalPath after)
     :artifact (.getCanonicalPath artifact)}))

(defn- ensure-worklog-schema!
  [conn]
  (let [db (d/db conn)
        installed? (seq (d/q '[:find ?e :where [?e :db/ident :worklog/id]] db))]
    (when-not installed?
      (d/transact conn {:tx-data (var-get #'log.step/worklog-schema)}))))

(defn- record-required-worklogs!
  [conn]
  (ensure-worklog-schema! conn)
  (let [files (prepare-evidence!)]
    (doseq [[idx track] (map-indexed vector required-tracks)]
      (d/transact conn {:tx-data [{:worklog/id (UUID/randomUUID)
                                   :worklog/mission-id test-mission-id
                                   :worklog/step-id (format "S-%d" idx)
                                   :worklog/agent-id agent-id
                                   :worklog/deliverable-id (format "deliverable-%d" idx)
                                   :worklog/track track
                                   :worklog/summary (str "Logged " (name track))
                                   :worklog/lock-token "test-lock"
                                   :worklog/evidence-before (:before files)
                                   :worklog/evidence-after (:after files)
                                   :worklog/artifacts [(pr-str {:label (str "artifact-" idx)
                                                                :path (:artifact files)})]
                                   :worklog/markdown-ref (str "missions/logs/" test-mission-id "/worklog-" idx ".md")
                                   :worklog/logged-at (Date/from (Instant/now))}]}))))

(defn- standard-transition-opts
  []
  {:lint {:paths ["src" "test"]}
   :tests {:suite :test.suite/contract
           :paths ["test/actions_contract_test.clj"]}
   :docs {:paths ["SYSTEM_SPEC.md"]}
   :system-map {:entities [:action/mission.validate]}
   :codetype {:paths ["src/intuition/sfs/missions/runtime.clj"]}})

(deftest runtime-requires-worklogs-before-review
  (with-runtime-db
    (fn [conn]
      (missions/start! {:mission/id test-mission-id
                        :agent/id agent-id
                        :conn conn})
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Missing worklog coverage"
           (missions/transition! (merge {:mission/id test-mission-id
                                         :agent/id agent-id
                                         :conn conn
                                         :target :mission.status/awaiting-review}
                                        (standard-transition-opts))))))))

(deftest runtime-calls-actions-and-updates-status
  (with-runtime-db
    (fn [conn]
      (missions/start! {:mission/id test-mission-id
                        :agent/id agent-id
                        :conn conn})
      (record-required-worklogs! conn)
      (let [worklog-count (ffirst
                           (d/q '[:find (count ?e)
                                  :in $ ?mission
                                  :where [?e :worklog/mission-id ?mission]]
                                (d/db conn)
                                test-mission-id))
            summary (missions/worklog-summary conn test-mission-id)
            opts (merge {:mission/id test-mission-id
                         :agent/id agent-id
                         :conn conn
                         :worklogs summary
                         :target :mission.status/awaiting-review}
                        (standard-transition-opts))
            result (missions/transition! opts)
            status (-> (d/pull (d/db conn)
                               [:mission/status]
                               [:mission/id test-mission-id])
                       :mission/status)
            executed (set (map first
                                (d/q '[:find ?action
                                       :where [?e :action.execution/action ?action]]
                                     (d/db conn))))
            branch-file (io/file "missions" "logs" (bootstrap/sanitize-fragment test-mission-id) "branch.edn")]
        (is (= (count required-tracks) worklog-count))
        (is (= (set required-tracks) (:tracks summary)))
        (is (= :mission.status/awaiting-review status))
        (is (= :mission.status/awaiting-review (:mission/status result)))
        (is (every? executed [:action/lint.run
                              :action/test.run-suite
                              :action/codetype.validate
                              :action/docs.sync
                              :action/system-map.refresh]))
        (is (executed :action/git.branch.prepare))
        (is (= :status/ok (get-in result [:context :codetype :status])))
        (is (.exists branch-file))
        (is (.exists (io/file (:log/path result))))))))
