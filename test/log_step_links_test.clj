(ns log-step-links-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [datomic.client.api :as d]
   [intuition.datomic :as datomic]
   [intuition.sfs.env.bootstrap :as bootstrap]
   [intuition.sfs.log.step :as log.step]))

(defn- unique-mission []
  (str "M-LOG-" (System/currentTimeMillis)))

(defn- write-file! [path content]
  (spit path content)
  (.getCanonicalPath (io/file path)))

(deftest log-steps-link-deliverables
  (let [mission (unique-mission)
        agent "logger"]
    (bootstrap/with-sandbox {:mission/id mission :agent/id agent}
      (fn [sandbox]
        (let [evidence-dir (io/file (get-in sandbox [:sandbox/paths :evidence]))
              before (write-file! (io/file evidence-dir "before.txt") "before state")
              after (write-file! (io/file evidence-dir "after.txt") "after state")
              artifact (write-file! (io/file (get-in sandbox [:sandbox/paths :artifacts]) "diff.txt") "diff data")
              lock (bootstrap/register-lock! {:mission/id mission
                                              :agent/id agent
                                              :scope {:paths ["src"]}})
              result (log.step/log-step! {:mission/id mission
                                          :agent/id agent
                                          :step/id "S-001"
                                          :deliverable/id "src/example.clj"
                                          :track/id :worktrack/code
                                          :summary "Wrote initial env bootstrap"
                                          :lock/token (:token lock)
                                          :evidence {:before before :after after}
                                          :artifacts [{:path artifact :label "diff"}]})]
          (is (= :status/ok (:action/status result)))
          (is (.exists (io/file (:markdown/path result))))
          (let [conn (datomic/ensure-db!)
                pulled (d/pull (d/db conn)
                               '[:worklog/mission-id :worklog/deliverable-id :worklog/lock-token]
                               [:worklog/id (:log/id result)])]
            (is (= mission (:worklog/mission-id pulled)))
            (is (= "src/example.clj" (:worklog/deliverable-id pulled)))
            (is (= (:token lock) (:worklog/lock-token pulled)))))))))

(deftest rejects-missing-locks
  (let [mission (unique-mission)
        agent "no-lock"]
    (bootstrap/with-sandbox {:mission/id mission :agent/id agent}
      (fn [sandbox]
        (let [evidence-dir (io/file (get-in sandbox [:sandbox/paths :evidence]))
              before (write-file! (io/file evidence-dir "before.txt") "before")
              after (write-file! (io/file evidence-dir "after.txt") "after")
              artifact (write-file! (io/file (get-in sandbox [:sandbox/paths :artifacts]) "artifact.txt") "artifact")]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Scope lock token missing"
               (log.step/log-step! {:mission/id mission
                                    :agent/id agent
                                    :step/id "S-ERR"
                                    :deliverable/id "docs/spec.md"
                                    :track/id :worktrack/docs
                                    :summary "Tried to log without lock"
                                    :lock/token "missing"
                                    :evidence {:before before :after after}
                                    :artifacts [{:path artifact}]}))))))))
