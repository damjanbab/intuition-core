(ns dev-agent-gateway-smoke-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [dev.agent-gateway :as agent-gateway]
   [intuition.sfs.missions.runtime :as missions]))

(deftest agent-gateway-cli-smoke
  ;; SYSTEM_SPEC §§3.3–3.6 and §5.1 require deterministic regression checks for mission gateway tooling.
  (testing "gateway fetch/plan/edit commands delegate through the runtime"
    (let [fetch-payload {:mission/id "M-CLI"}
          plan-payload {:mission/id "M-CLI"
                        :agent/id "codex"
                        :plan/requirements [:req/trace]
                        :plan/notes "Stub notes"}
          edit-payload {:mission/id "M-CLI"
                        :agent/id "codex"
                        :edit/message "Stub edit"}]
      (with-redefs [missions/get-mission (fn [payload]
                                           (is (= fetch-payload payload))
                                           {:result :fetch-ok
                                            :payload payload})
                    missions/plan-step! (fn [payload]
                                           (is (= plan-payload payload))
                                           {:result :plan-ok
                                            :payload payload})
                    missions/edit-step! (fn [payload]
                                          (is (= edit-payload payload))
                                          {:result :edit-ok
                                           :payload payload})]
        (let [fetch-output (with-out-str (agent-gateway/-main "fetch" (pr-str fetch-payload)))
              plan-output (with-out-str (agent-gateway/-main "plan" (pr-str plan-payload)))
              edit-output (with-out-str (agent-gateway/-main "edit" (pr-str edit-payload)))]
          (is (re-find #"fetch-ok" fetch-output))
          (is (re-find #"plan-ok" plan-output))
          (is (re-find #"edit-ok" edit-output)))))))
