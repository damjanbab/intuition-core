(ns analytics-runtime-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [intuition.analytics.runtime :as analytics])
  (:import
   (java.io File)
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.time Instant)))

(defn- temp-dir
  []
  (let [path (Files/createTempDirectory
              "analytics-runtime-test"
              (make-array FileAttribute 0))]
    (.toFile path)))

(defn- write-edn!
  [file data]
  (io/make-parents file)
  (spit file (pr-str data)))

(defn- mission-dir!
  [root mission-id]
  (doto (io/file root mission-id)
    (.mkdirs)))

(deftest analytics-runtime-produces-artifacts
  "SYSTEM_SPEC §§3.3–3.6/§5.1 coverage for the analytics runtime."
  (testing "reports and metrics capture lock waits, CI reruns, and validator failures"
    (let [root (temp-dir)
          log-root (.getAbsolutePath root)
          mission-a (mission-dir! root "M-ANALYTICS-1")
          mission-b (mission-dir! root "M-ANALYTICS-2")]
      ;; Mission A artifacts
      (write-edn! (io/file mission-a "locks-request.edn")
                  {:locks/requested ["src/foo.clj"]
                   :locks/requested-at "2025-01-01T00:00:00Z"
                   :locks/granted-at "2025-01-01T00:00:05Z"})
      (write-edn! (io/file mission-a "locks-wait.edn")
                  {:locks/requested ["src/bar.clj" "src/baz.clj"]
                   :locks/requested-at "2025-01-01T00:02:00Z"
                   :locks/granted-at "2025-01-01T00:02:10Z"})
      (doseq [[run-id completed-at] [["20250101-000100Z" "2025-01-01T00:01:30Z"]
                                     ["20250101-000200Z" "2025-01-01T00:02:30Z"]]]
        (let [run-dir (io/file mission-a "ci" run-id)]
          (.mkdirs run-dir)
          (write-edn! (io/file run-dir "ci-run.edn")
                      {:mission/id "M-ANALYTICS-1"
                       :ci/run-id run-id
                       :ci/completed-at completed-at})))
      (write-edn! (io/file mission-a "work-plan-validation.edn")
                  {:spec/requirements-count 2
                   :work.plan/coverage-count 2
                   :work.plan/validation-results [{:validation/kind :work-plan/coverage
                                                   :validation/status :status/passed}
                                                  {:validation/kind :work-plan/dag
                                                   :validation/status :status/failed}]
                   :validated-at "2025-01-01T00:15:00Z"})
      (write-edn! (io/file mission-a "validator-results.edn")
                  {:status :status/error
                   :details {:validator/id :validator/sample}})
      (write-edn! (io/file mission-a "codetype-generation.edn")
                  {:codetype/generations [{:codetype/ident :code.type/demo
                                           :codetype/skipped? false
                                           :codetype/generated-at "2025-01-01T00:18:00Z"}
                                          {:codetype/ident :code.type/demo
                                           :codetype/skipped? true
                                           :codetype/generated-at "2025-01-01T00:18:00Z"}]})
      (write-edn! (io/file mission-a "mission-plan-binding.edn")
                  {:mission.plan-binding/logged-at "2025-01-01T00:05:00Z"})
      (write-edn! (io/file mission-a "branch.edn")
                  {:branch/created-at "2025-01-01T00:06:00Z"})
      (let [merge-dir (io/file mission-a "merge" "20250101-merge")]
        (.mkdirs merge-dir)
        (write-edn! (io/file merge-dir "merge-log.edn")
                    {:merge/merged-at "2025-01-01T00:20:00Z"}))
      (write-edn! (io/file mission-a "scheduler" "scheduler-run.edn")
                  {:scheduler/start-time "2025-01-01T00:30:00Z"
                   :scheduler/end-time "2025-01-01T00:30:10Z"
                   :scheduler/duration-ms 10000
                   :scheduler/final-status :scheduler.status/success})

      ;; Mission B artifacts
      (write-edn! (io/file mission-b "locks-request.edn")
                  {:locks/requested ["src/qux.clj"]
                   :locks/wait-ms 2})
      (let [run-dir (io/file mission-b "ci" "20250101-000300Z")]
        (.mkdirs run-dir)
        (write-edn! (io/file run-dir "ci-run.edn")
                    {:mission/id "M-ANALYTICS-2"
                     :ci/run-id "20250101-000300Z"
                     :ci/completed-at "2025-01-01T01:00:30Z"}))
      (write-edn! (io/file mission-b "scheduler" "scheduler-run.edn")
                  {:scheduler/start-time "2025-01-01T01:00:00Z"
                   :scheduler/end-time "2025-01-01T01:00:05Z"
                   :scheduler/duration-ms 5000
                   :scheduler/final-status :scheduler.status/success})

      (let [mission-log (io/file root "M-ANALYTICS-HUB")
            reports-dir (io/file root "reports" "analytics")
            timeline-provider (fn [mission-id]
                                (when (= mission-id "M-ANALYTICS-1")
                                  {:spec/captured-at "2025-01-01T00:00:00Z"}))
            result (analytics/generate! {:log-root log-root
                                         :reports-dir (.getAbsolutePath reports-dir)
                                         :mission-log-path (.getAbsolutePath mission-log)
                                         :source :analytics.source/test
                                         :timeline-provider timeline-provider
                                         :now (Instant/parse "2025-01-05T12:00:00Z")})
            md-file (io/file (:report/markdown result))
            edn-file (io/file (:report/edn result))]
        (is (.exists md-file))
        (is (.exists edn-file))
        (let [markdown (slurp md-file)]
          (is (re-find #"SYSTEM_SPEC §§3\.3–3\.6" markdown))
          (is (re-find #"analytics step" markdown)))
        (let [data (edn/read-string (slurp edn-file))
              metrics (get-in data [:analytics/report :analytics.report/metrics])
              lock-metric (some #(when (= :analytics.metric/lock-waits (:analytics.metric/key %)) %) metrics)
              rerun-metric (some #(when (= :analytics.metric/ci-reruns (:analytics.metric/key %)) %) metrics)
              validator-metric (some #(when (= :analytics.metric/validator-failures (:analytics.metric/key %)) %) metrics)
              timeline-metric (some #(when (= :analytics.metric/spec-plan-mission-merge (:analytics.metric/key %)) %) metrics)]
          (is (= :analytics.step/post-dry-run (:analytics/new-step data)))
          (is (= #{"M-ANALYTICS-1" "M-ANALYTICS-2"}
                 (set (get-in data [:analytics/report :analytics.report/missions]))))
          (let [lock-value (edn/read-string (:analytics.metric/value lock-metric))]
            (is (= 3 (:total-events lock-value)))
            (is (= 4 (:total-locks lock-value)))
            (is (= 15002 (:total-wait-ms lock-value))))
          (let [rerun-value (edn/read-string (:analytics.metric/value rerun-metric))]
            (is (= 3 (:total-runs rerun-value)))
            (is (= 1 (:total-reruns rerun-value))))
          (let [validator-value (edn/read-string (:analytics.metric/value validator-metric))]
            (is (= 2 (:total-failures validator-value))))
          (let [timeline-value (edn/read-string (:analytics.metric/value timeline-metric))
                snapshot (first timeline-value)]
            (is (= "M-ANALYTICS-1" (:mission/id snapshot)))
            (is (= 300000 (:spec->plan-ms snapshot)))
            (is (= 1200000 (:spec->merge-ms snapshot)))))
        (let [analysis-dir (io/file mission-log "analysis")
              copies (.listFiles analysis-dir)]
          (is (seq copies))
          (is (every? #(.exists ^File %) copies)))))))
