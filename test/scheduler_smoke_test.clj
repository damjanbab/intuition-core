(ns scheduler-smoke-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [intuition.scheduler.core :as scheduler])
  (:import
   (java.nio.file Files)
   (java.time Instant)))

(defn- temp-dir
  []
  (let [path (Files/createTempDirectory
              "scheduler-smoke"
              (make-array java.nio.file.attribute.FileAttribute 0))]
    (doto (.toFile path)
      (.deleteOnExit))))

(def ^:private mission
  {:mission/id "M-TEST"
   :mission/title "Scheduler test mission"
   :mission/summary "Ensures artifacts exist"
   :mission/status :mission.status/ready
   :mission/priority :mission.priority/p1
   :mission/queue-tags [:mission.queue/core]})

(def ^:private ready-response
  {:mission/list [mission]
   :mission/active-queues {}})

(def ^:private success-fetch
  (fn [_]
    {:mission/status :mission.status/in-progress}))

(def ^:private failed-fetch
  (fn [_]
    {:mission/status :mission.status/ready}))

(def ^:private constant-now
  (constantly (Instant/parse "2025-01-01T00:00:00Z")))

(defn- log-path
  [root filename]
  (io/file root "M-TEST" "scheduler" filename))

(deftest scheduler-run-artifacts
  (testing "scheduler-run.edn is written for successful launches"
    (let [tmp (temp-dir)
          log-root (.getCanonicalPath tmp)
          result (scheduler/run-once! {:queue/tags []
                                       :scheduler/log-root log-root
                                       :scheduler/list-ready-fn (constantly ready-response)
                                       :scheduler/fetch-mission-fn success-fetch
                                       :scheduler/command-runner (fn [_]
                                                                   {:exit 0
                                                                    :out "ok"
                                                                    :err ""})
                                       :scheduler/now-fn constant-now
                                       :scheduler/command-template ["echo" "{{mission-id}}"]})
          run-file (log-path log-root "scheduler-run.edn")
          failure-file (log-path log-root "scheduler-failure.edn")]
      (is (= :scheduler.status/success (:scheduler/final-status result)))
      (is (= ["echo" "M-TEST"] (:scheduler/command result)))
      (is (.exists run-file))
      (is (not (.exists failure-file)))
      (let [contents (edn/read-string (slurp run-file))]
        (is (= :scheduler.status/success (:scheduler/final-status contents)))
        (is (= ["echo" "M-TEST"] (:scheduler/command contents))))))

  (testing "scheduler-failure.edn is emitted when mission stays ready"
    (let [tmp (temp-dir)
          log-root (.getCanonicalPath tmp)
          result (scheduler/run-once! {:queue/tags []
                                       :scheduler/log-root log-root
                                       :scheduler/list-ready-fn (constantly ready-response)
                                       :scheduler/fetch-mission-fn failed-fetch
                                       :scheduler/command-runner (fn [_]
                                                                   {:exit 0
                                                                    :out ""
                                                                    :err ""})
                                       :scheduler/now-fn constant-now})
          run-file (log-path log-root "scheduler-run.edn")
          failure-file (log-path log-root "scheduler-failure.edn")]
      (is (= :scheduler.status/failure (:scheduler/final-status result)))
      (is (.exists run-file))
      (is (.exists failure-file))
      (let [contents (edn/read-string (slurp failure-file))]
        (is (= :scheduler.status/failure (:scheduler/final-status contents)))
        (is (re-find #"remained in" (:scheduler/error contents)))))))
