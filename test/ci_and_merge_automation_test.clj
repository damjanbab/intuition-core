(ns ci-and-merge-automation-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [intuition.sfs.actions.handlers :as handlers])
  (:import
   (java.io File)
   (java.util UUID)))

(defn- delete-tree!
  [^File f]
  (when (and f (.exists f))
    (doseq [child (.listFiles f)]
      (delete-tree! child))
    (io/delete-file f true)))

(defn- temp-dir!
  []
  (let [dir (io/file "tmp" "ci-merge-tests" (str (UUID/randomUUID)))]
    (.mkdirs dir)
    dir))

(defmacro with-temp-dir
  [sym & body]
  `(let [~sym (temp-dir!)]
     (try
       ~@body
       (finally
         (delete-tree! ~sym)))))

(defn- read-edn
  [path]
  (-> path slurp edn/read-string))

(deftest ci-profile-writes-artifacts
  (with-temp-dir root
    (let [sandbox (doto (io/file root "sandbox")
                    .mkdirs)
          result (handlers/run-ci-profile
                  {:config {:mission/id "M-CI-001"
                            :sandbox/root (.getCanonicalPath sandbox)
                            :ci/log-root (.getCanonicalPath root)}})
          payload (read-edn (:ci/run-path result))]
      (is (= :ci.profile/runtime-default (:ci/profile result)))
      (is (= 3 (count (:ci/steps result))))
      (is (= "SYSTEM_SPEC §§3.3–3.6, §5.1, §5.3, §6.2" (:ci/spec payload)))
      (is (.exists (io/file (:ci/run-path result)))))))

(deftest merge-automation-captures-logs
  (with-temp-dir root
    (let [sandbox (doto (io/file root "sandbox")
                    .mkdirs)
          conflict (handlers/mission-merge-prepare
                    {:config {:mission/id "M-MERGE-001"
                              :agent/id "merge-agent"
                              :sandbox/root (.getCanonicalPath sandbox)
                              :merge/log-root (.getCanonicalPath root)
                              :ci/log-root (.getCanonicalPath root)
                              :merge/simulate-conflict? true}})]
      (testing "conflict writes failure file"
        (is (= :status/failed (:action/status conflict)))
        (is (= :merge.status/conflict (get-in conflict [:merge/failure :merge/status])))
        (is (.exists (io/file (:merge/log-path conflict))))))
    (with-temp-dir root
      (let [sandbox (doto (io/file root "sandbox")
                      .mkdirs)
            prepare (handlers/mission-merge-prepare
                     {:config {:mission/id "M-MERGE-002"
                               :agent/id "merge-agent"
                               :sandbox/root (.getCanonicalPath sandbox)
                               :merge/log-root (.getCanonicalPath root)
                               :ci/log-root (.getCanonicalPath root)}})
            execute (handlers/mission-merge-execute
                     {:config {:mission/id "M-MERGE-002"
                               :agent/id "merge-agent"
                               :merge/run (:merge/run prepare)
                               :merge/log-root (.getCanonicalPath root)}})
            log-payload (read-edn (:merge/log-path execute))]
        (testing "prepare succeeds"
          (is (= :status/ok (:action/status prepare)))
          (is (= :merge.status/prepared (get-in prepare [:merge/run :merge/status])))
          (is (= :ci.profile/runtime-default (get-in prepare [:ci/run :ci/profile]))))
        (testing "execute writes merge-log"
          (is (= :status/ok (:action/status execute)))
          (is (= "SYSTEM_SPEC §§3.3–3.6, §5.1, §5.3, §6.2" (:merge/spec log-payload)))
          (is (= (get-in prepare [:ci/run :ci/profile])
                 (get-in execute [:merge/run :ci/profile]))))))))
