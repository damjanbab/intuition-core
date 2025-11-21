(ns scheduler-smoke-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
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

(defn- canonical-path
  [path]
  (.getCanonicalPath (io/file path)))

(defn- seed-bundle!
  [log-root mission-id {:keys [locks retry token]}]
  (let [dir (io/file log-root mission-id)
        bundle-path (io/file dir "context-bundle.edn")
        token-path (io/file dir "auth.token")
        token (or token "test-token")]
    (.mkdirs dir)
    (spit token-path token)
    (spit bundle-path (pr-str {:mission/id mission-id
                               :locks/required (or locks #{})
                               :auth/token-path (canonical-path token-path)
                               :retry retry}))
    (canonical-path bundle-path)))

(def ^:private mission
  {:mission/id "M-TEST"
   :mission/title "Scheduler test mission"
   :mission/summary "Ensures artifacts exist"
   :mission/status :mission.status/ready
   :mission/priority :mission.priority/p1
   :mission/queue-tags [:mission.queue/core]})

(def ^:private mission-stuck
  {:mission/id "M-TEST-FAIL"
   :mission/title "Scheduler failure mission"
   :mission/summary "Stays ready to trigger failure"
   :mission/status :mission.status/ready
   :mission/priority :mission.priority/p2
   :mission/queue-tags [:mission.queue/retry]})

(defn- ready-response
  [mission-record]
  {:mission/list [mission-record]
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
  [root mission-id filename]
  (io/file root mission-id "scheduler" filename))

(deftest scheduler-run-artifacts
  (testing "scheduler-run.edn is written for successful launches"
    (let [tmp (temp-dir)
          log-root (.getCanonicalPath tmp)
          bundle-path (seed-bundle! log-root (:mission/id mission) {:locks #{:lock/test}
                                                                    :retry {:max-attempts 2
                                                                            :backoff-ms 1}})
          captured (atom nil)
          result (scheduler/run-once! {:queue/tags []
                                       :scheduler/log-root log-root
                                       :scheduler/list-ready-fn (constantly (ready-response mission))
                                       :scheduler/fetch-mission-fn success-fetch
                                       :context/bundle-path bundle-path
                                       :scheduler/command-template ["echo" "{{mission-id}}" "{{auth-token}}" "{{mission-queue}}"]
                                       :scheduler/command-runner (fn [{:keys [command] :gateway/keys [payload]}]
                                                                   (reset! captured {:command command
                                                                                     :payload payload})
                                                                   {:exit 0
                                                                    :out "ok"
                                                                    :err ""})
                                       :scheduler/now-fn constant-now})
          run-file (log-path log-root (:mission/id mission) "scheduler-run.edn")
          failure-file (log-path log-root (:mission/id mission) "scheduler-failure.edn")
          contents (edn/read-string (slurp run-file))]
      (is (= :scheduler.status/success (:scheduler/final-status result)))
      (is (.exists run-file))
      (is (not (.exists failure-file)))
      (is (= (canonical-path bundle-path) (:context/bundle-path result)))
      (is (= #{:lock/test} (:locks/requested result)))
      (is (= [:mission.queue/core] (:mission/queue-tags result)))
      (is (= :mission.priority/p1 (:mission/priority result)))
      (is (= "test-token" (:auth/token (:payload @captured))))
      (is (not-any? #(str/includes? % "test-token") (:scheduler/command result)))
      (is (= :scheduler.status/success (:scheduler/final-status contents)))
      (is (= [:mission.queue/core] (:mission/queue-tags contents)))
      (is (= #{:lock/test} (set (:locks/requested contents))))
      (is (= [:mission.queue/core] (get-in contents [:scheduler/gateway-payload :mission/queue-tags])))))

  (testing "scheduler-failure.edn is emitted when mission stays ready and retries are exhausted"
    (let [tmp (temp-dir)
          log-root (.getCanonicalPath tmp)
          bundle-path (seed-bundle! log-root (:mission/id mission-stuck) {:locks #{:lock/test}
                                                                          :retry {:max-attempts 2
                                                                                  :backoff-ms 0}})
          result (scheduler/run-once! {:queue/tags []
                                       :scheduler/log-root log-root
                                       :scheduler/list-ready-fn (constantly (ready-response mission-stuck))
                                       :scheduler/fetch-mission-fn failed-fetch
                                       :scheduler/command-runner (fn [_]
                                                                   {:exit 0
                                                                    :out ""
                                                                    :err ""})
                                       :scheduler/now-fn constant-now
                                       :context/bundle-path bundle-path})
          run-file (log-path log-root (:mission/id mission-stuck) "scheduler-run.edn")
          failure-file (log-path log-root (:mission/id mission-stuck) "scheduler-failure.edn")
          contents (edn/read-string (slurp failure-file))]
      (is (.exists run-file))
      (is (.exists failure-file))
      (is (= :scheduler.status/failure (:scheduler/final-status result)))
      (is (= 2 (:scheduler/attempt result)))
      (is (= #{:lock/test} (set (or (:locks/requested contents) [])))
          "Locks propagate into failure artifacts")
      (is (= :scheduler.status/failure (:scheduler/final-status contents)))
      (is (re-find #"remained in" (:scheduler/error contents)))))

  (testing "scheduler retries on command error then succeeds"
    (let [tmp (temp-dir)
          log-root (.getCanonicalPath tmp)
          bundle-path (seed-bundle! log-root (:mission/id mission) {:retry {:max-attempts 2
                                                                            :backoff-ms 0}})
          attempts (atom 0)
          result (scheduler/run-once! {:queue/tags []
                                       :scheduler/log-root log-root
                                       :scheduler/list-ready-fn (constantly (ready-response mission))
                                       :scheduler/fetch-mission-fn success-fetch
                                       :scheduler/command-runner (fn [_]
                                                                   (if (= 1 (swap! attempts inc))
                                                                     {:exit 1
                                                                      :err "fail once"}
                                                                     {:exit 0
                                                                      :out "recovered"}))
                                       :scheduler/now-fn constant-now
                                       :context/bundle-path bundle-path})
          run-file (log-path log-root (:mission/id mission) "scheduler-run.edn")
          contents (edn/read-string (slurp run-file))]
      (is (= 2 @attempts))
      (is (= :scheduler.status/success (:scheduler/final-status result)))
      (is (= 2 (:scheduler/attempt result)))
      (is (= :scheduler.status/success (:scheduler/final-status contents))))))
