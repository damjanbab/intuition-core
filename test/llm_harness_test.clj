(ns llm-harness-test
  (:require
   [clojure.java.shell :as shell]
   [clojure.test :refer [deftest is]]
   [datomic.client.api :as d]
   [intuition.llm.harness :as harness]
   [support.datomic :as support]))

(defn- sample-self-report
  []
  {:confidence :high
   :reason "test-only"
   :assumptions []
   :uncertainties []})

(deftest surfaces-are-loaded
  (let [surfaces (harness/surfaces-by-ident)]
    (is (< 1 (count surfaces)) "LLM surfaces file should define multiple entries")
    (is (every? keyword? (keys surfaces)))
    (is (every? :llm.surface/outputs (vals surfaces)))))

(deftest invoke-persists-multiple-surfaces
  (support/with-test-conn
    (fn [conn]
      (let [surface-idents (take 2 (harness/surface-idents))
            call-count (atom 0)
            fake (fn [{:keys [surface input requested-outputs]}]
                   (swap! call-count inc)
                   {:status :response.status/ok
                    :payload {:surface surface
                              :ref input
                              :outputs requested-outputs}
                    :meta {:call @call-count}
                    :self-report (sample-self-report)})]
        (with-redefs [shell/sh (fn [& _] (throw (ex-info "shell not allowed" {})))
                      spit (fn [& _] (throw (ex-info "file write not allowed" {})))]
          (doseq [surface surface-idents]
            (let [result (harness/invoke! {:surface surface
                                           :input {:mission/id "M-20251121-817"
                                                   :surface surface}
                                           :conn conn
                                           :fake-response-fn fake})
                  request (:llm/request result)
                  response (:llm/response result)]
              (is (= surface (:llm.request/surface request)))
              (is (= surface (:llm.response/surface response)))
              (is (= (:llm.request/id request) (:llm.response/request-id response)))
              (is (uuid? (:llm.request/id request)))
              (is (uuid? (:llm.response/id response)))
              (is (= :response.status/ok (:llm.response/status response)))
              (is (seq (:llm.request/requested-outputs request)))
              (is (seq (:llm.response/requested-outputs response)))
              (is (map? (:llm.response/payload response)))
              (is (map? (:meta/self-report response)))
              (is (= #{:confidence :reason :assumptions :uncertainties}
                     (set (keys (:meta/self-report response))))))))
        (is (= (count surface-idents) @call-count))
        (let [db (d/db conn)]
          (is (= (count surface-idents)
                 (count (d/q '[:find ?e :where [?e :llm.request/id]] db))))
          (is (= (count surface-idents)
                 (count (d/q '[:find ?e :where [?e :llm.response/id]] db)))))))))

(deftest idempotent-responses-are-reused
  (support/with-test-conn
    (fn [conn]
      (let [calls (atom 0)
            fake (fn [_]
                   (swap! calls inc)
                   {:status :response.status/ok
                    :payload {:value "once"}
                    :self-report (sample-self-report)})]
        (let [first-result (harness/invoke! {:surface :llm.surface/plan-draft
                                             :input {:plan "demo"}
                                             :conn conn
                                             :idempotency-key "idem-demo"
                                             :fake-response-fn fake})
              second-result (harness/invoke! {:surface :llm.surface/plan-draft
                                              :input {:plan "demo"}
                                              :conn conn
                                              :idempotency-key "idem-demo"
                                              :fake-response-fn fake})
              r1 (:llm/request first-result)
              r2 (:llm/request second-result)
              resp1 (:llm/response first-result)
              resp2 (:llm/response second-result)]
          (is (= 1 @calls) "LLM responder should not run when response already exists")
          (is (= (:llm.request/id r1) (:llm.request/id r2)))
          (is (= (:llm.response/id resp1) (:llm.response/id resp2)))
          (is (= (:llm.response/request-id resp1) (:llm.request/id r1)))
          (is (= :response.status/ok (:llm.response/status resp2))))))))
