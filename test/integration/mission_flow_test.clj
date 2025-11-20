(ns integration.mission-flow-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [datomic.client.api :as d]
   [intuition.sfs.actions.handlers :as action-handlers]
   [intuition.sfs.actions.runtime :as actions-runtime]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [intuition.sfs.missions.runtime :as missions]
   [support.datomic :as support])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)))

(def mission-id "M-INTEGRATION-FLOW")
(def agent-id "mission-flow-agent")

(def required-tracks
  [:work-track/planning
   :work-track/code
   :work-track/test-functional
   :work-track/doc
   :work-track/system-map])

(def required-track-set (set required-tracks))

(def mission-record
  {:mission/id mission-id
   :mission/title "Integration flow mission"
   :mission/summary "Covers SYSTEM_SPEC §§3.3–3.6 happy path."
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

(defn- create-temp-dir
  [prefix]
  (let [base (io/file "tmp" "integration-testing")]
    (.mkdirs base)
    (let [path (Files/createTempDirectory (.toPath base)
                                          (str (or prefix "mission-flow"))
                                          (make-array FileAttribute 0))]
      (.toFile path))))

(defmacro with-temp-dir
  [[sym prefix] & body]
  `(let [dir# (create-temp-dir ~prefix)]
     (try
       (let [~sym dir#]
         ~@body)
       (finally
         (delete-tree! dir#)))))

(deftest mission-happy-path-produces-temp-logs
  "SYSTEM_SPEC §§3.4–3.6 and §6.2 require mission runtimes to emit traceable logs."
  (testing "start + transition writes artifacts under temp log dir"
    (with-temp-dir [temp-root "mission-flow"]
      (support/with-test-conn
        (fn [conn]
          (missions/prepare-conn! conn)
          (d/transact conn {:tx-data [mission-record]})
          (let [workspace (doto (io/file temp-root "workspace") .mkdirs)
                log-base (doto (io/file temp-root "missions" "logs") .mkdirs)
                temp-log-dir (fn [mission-id]
                               (let [dir (io/file log-base (bootstrap/sanitize-fragment mission-id))]
                                 (.mkdirs dir)
                                 dir))
                temp-codetype-file (fn [mission-id]
                                     (io/file (temp-log-dir mission-id) "codetype-validation.edn"))]
            (with-redefs-fn {#'missions/log-dir temp-log-dir
                             #'action-handlers/mission-log-dir temp-log-dir
                             #'action-handlers/codetype-log-file temp-codetype-file
                             #'actions-runtime/record-execution! (fn [& _] {:execution/id :integration-test})}
              (fn []
                (let [start-result (missions/start! {:mission/id mission-id
                                                     :agent/id agent-id
                                                     :conn conn
                                                     :workspace/root (.getCanonicalPath workspace)})
                      transition-result (missions/transition!
                                         {:mission/id mission-id
                                          :agent/id agent-id
                                          :target :mission.status/awaiting-review
                                          :conn conn
                                          :tests {:suite :test.suite/contract
                                                  :paths ["test/mission_validation_test.clj"]}
                                          :docs {:paths ["SYSTEM_SPEC.md"]}
                                          :lint {:paths ["src" "test"]}
                                          :system-map {:entities [:action/mission.validate]}
                                          :codetype {:paths ["dev/list_missions.clj"]}
                                          :worklogs {:count (count required-track-set)
                                                     :tracks required-track-set}})
                      mission-dir (temp-log-dir mission-id)]
                  (is (= :mission.status/in-progress (:mission/status start-result)))
                  (is (= :mission.status/awaiting-review (:mission/status transition-result)))
                  (doseq [filename ["branch.edn"
                                    "branch.md"
                                    "codetype-validation.edn"
                                    "transition-awaiting-review.edn"]]
                    (is (.exists (io/file mission-dir filename))
                        (str filename " should exist under the temp log directory"))))))))))))
