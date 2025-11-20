(ns mission-report-requires-tests
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [datomic.client.api :as d]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [intuition.sfs.log.step :as log.step]
   [intuition.sfs.missions.runtime :as missions]
   [support.datomic :as support])
  (:import
   (java.time Instant)
   (java.util Date UUID)))

(def test-mission-id "M-08-TEST")
(def agent-id "report-agent")
(def required-tracks [:work-track/planning
                      :work-track/code
                      :work-track/test-functional
                      :work-track/doc
                      :work-track/system-map])

(def base-record
  {:mission/id test-mission-id
   :mission/title "Mission report + approval flow"
   :mission/summary "Covers report/approve/archive runtime wiring."
   :mission/category :mission.category/spec
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

(defn- transition-options
  []
  {:lint {:paths ["src" "test"]}
   :tests {:suite :test.suite/contract
           :paths ["test/actions_contract_test.clj"]}
   :docs {:paths ["SYSTEM_SPEC.md"]}
   :system-map {:entities [:action/mission.validate :action/mission.transition]
                :mission/id test-mission-id}
   :codetype {:paths ["src/intuition/sfs/missions/runtime.clj"
                      "src/intuition/sfs/missions/state_machine.clj"]}})

(defn- base-artifact
  []
  (let [{:keys [artifact]} (prepare-evidence!)]
    {:label "manual-evidence"
     :path artifact}))

(defn- stub-codetype!
  []
  (let [file (io/file "missions" "logs" (bootstrap/sanitize-fragment test-mission-id) "codetype-validation.edn")]
    (.mkdirs (.getParentFile file))
    (spit file (pr-str {:mission/id test-mission-id
                        :codetype/status :status/ok
                        :codetype/definitions [{:code.definition/ident :code/intuition.sfs.missions.runtime}]}))
    file))

(defn- with-test-mission
  [f]
  (support/with-test-conn
   (fn [conn]
     (cleanup-mission-files!)
     (d/transact conn {:tx-data [base-record]})
     (stub-codetype!)
     (try
       (f conn)
       (finally
         (cleanup-mission-files!))))))

(deftest report-requires-docs-and-system-map
  (with-test-mission
    (fn [conn]
      (missions/start! {:mission/id test-mission-id
                        :agent/id agent-id
                        :conn conn})
      (let [opts {:mission/id test-mission-id
                  :agent/id agent-id
                  :conn conn
                  :artifacts [(base-artifact)]
                  :system-map {:entities [:action/mission.validate]}}]
        (testing "doc work-track failure throws"
          (with-redefs-fn {#'missions/run-docgen! (fn [& _] nil)}
            (fn []
              (is (thrown-with-msg?
                   clojure.lang.ExceptionInfo
                   #"Doc work track requires generated docs"
                   (missions/report! opts))))))
        (testing "system-map config required when work track present"
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"system-map/entities required"
               (missions/report! (dissoc opts :system-map)))))))))

(deftest report-approval-archive-flow
  (with-test-mission
    (fn [conn]
      (missions/start! {:mission/id test-mission-id
                        :agent/id agent-id
                        :conn conn})
      (testing "approval blocked until awaiting review"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"awaiting review"
             (missions/approve! {:mission/id test-mission-id
                                 :agent/id agent-id
                                 :conn conn
                                 :approval {:by "Steward Bot"}}))))
      (record-required-worklogs! conn)
      (let [summary (missions/worklog-summary conn test-mission-id)
            await-opts (merge {:mission/id test-mission-id
                               :agent/id agent-id
                               :conn conn
                               :target :mission.status/awaiting-review
                               :worklogs summary
                               :docgen {:missions {:doc/templates [:template.instance/doc.mission.lifecycle]}}
                               :system-map {:entities [:action/mission.validate :action/mission.transition]}}
                              (transition-options))]
        (missions/transition! await-opts))
      (let [report-result (missions/report! {:mission/id test-mission-id
                                             :agent/id agent-id
                                             :conn conn
                                             :summary "M-08 evidence"
                                             :artifacts [(base-artifact)]
                                             :system-map {:entities [:action/mission.validate :action/mission.transition]}
                                             :docgen {:missions {:doc/templates [:template.instance/doc.mission.lifecycle]}
                                                      :types {:doc/templates [:template.instance/doc.type.mission-record]}}})
            report-file (io/file (:report/path report-result))]
        (testing "report includes docgen + system-map data"
          (is (.exists report-file))
          (is (seq (:docgen report-result)))
          (is (seq (:system-map report-result)))
          (let [payload (edn/read-string (slurp report-file))]
            (is (= test-mission-id (:mission/id payload)))
            (is (seq (:docgen payload)))
            (is (seq (:system-map payload))))))
      (testing "archive requires approval artifact"
        (d/transact conn {:tx-data [{:mission/id test-mission-id
                                     :mission/status :mission.status/done}]})
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"approval artifact is missing"
             (missions/archive! {:mission/id test-mission-id
                                 :agent/id agent-id
                                 :conn conn}))))
      (d/transact conn {:tx-data [{:mission/id test-mission-id
                                   :mission/status :mission.status/awaiting-review}]})
      (let [approve-result (missions/approve! {:mission/id test-mission-id
                                               :agent/id agent-id
                                               :conn conn
                                               :approval {:by "Steward Bot"
                                                          :notes "Ready for archive"}})
            approval-file (io/file "missions" "logs" (bootstrap/sanitize-fragment test-mission-id) "approval.edn")]
        (testing "approval writes artifact and transitions mission"
          (is (.exists approval-file))
          (is (= :mission.status/done
                 (-> (d/pull (d/db conn) [:mission/status] [:mission/id test-mission-id])
                     :mission/status)))
          (is (= :status/ok (:action/status approve-result))))
        (let [archive-result (missions/archive! {:mission/id test-mission-id
                                                 :agent/id agent-id
                                                 :conn conn
                                                 :summary "Archived via tests"})
              archive-file (io/file "missions" "logs" (bootstrap/sanitize-fragment test-mission-id) "archive.edn")
              payload (edn/read-string (slurp archive-file))]
          (testing "archive captures report + approval references"
            (is (.exists archive-file))
            (is (= :mission.status/archived
                   (-> (d/pull (d/db conn) [:mission/status] [:mission/id test-mission-id])
                       :mission/status)))
            (is (= :status/ok (:action/status archive-result)))
            (let [labels (set (map :label (:artifacts payload)))]
              (is (contains? labels "Mission report (edn)"))
              (is (contains? labels "Mission approval (edn)")))))))))
