(ns dev.codex-oneshot
  "Thin launcher that connects Datomic-governed LLM surfaces to Codex CLI
   in a one-shot, data-only fashion. It reads an EDN payload describing the
   surface + input, delegates persistence and idempotency to
   `intuition.llm.harness/invoke!`, and, when not in test/fake mode, shells
   out to `codex exec` with a single prompt. Codex never sees file paths or
   shell commands from the mission context—only structured data. This is a
   Mission M-20251121-818 artifact (§§2.1–2.2, §§3.3–3.6, §4.7, §5, §6, §9, §11)."
  (:require
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [intuition.llm.codex :as llm-codex]
   [intuition.llm.harness :as harness]))

(defn- parse-payload
  [s]
  (if (str/blank? s)
    {}
    (edn/read-string s)))

(defn -main
  "Usage:
   clojure -M:dev -m dev.codex-oneshot '{:surface :llm.surface/code-proposal
                                         :input {...}
                                         :requested-outputs [:code/proposals]
                                         ;; optional:
                                         :trace {:channel :codex}
                                         :idempotency-key \"...\"\n
                                         :spec-sections [\"3.3\" \"3.4\"]\n
                                         :fake/response? true}'\n
\n
   When :fake/response? is true (default for tests), Codex is NOT called and
   we instead return a simulated response; this keeps CI independent of Codex.
   In real runs, omit :fake/response? to enable the codex_exec call."
  [& [payload-str]]
  (try
    (let [payload (parse-payload (or payload-str "{}"))
          surface (or (:surface payload)
                      (:surface/ident payload)
                      (:llm/surface payload))
          input (or (:input payload) (:llm/input payload) {})
          outputs (or (:requested-outputs payload)
                      (:llm/requested-outputs payload))
          trace (:trace payload)
          fake? (:fake/response? payload)
          call-fn (when-not fake?
                    (partial llm-codex/oneshot-call {}))
          result (harness/invoke! {:surface surface
                                   :input input
                                   :requested-outputs outputs
                                   :trace trace
                                   :idempotency-key (:idempotency-key payload)
                                   :context-hash (:context-hash payload)
                                   :spec-sections (:spec-sections payload)
                                   :fake-response-fn (when fake?
                                                       (fn [ctx]
                                                         ;; Reuse the harness-normalized fake used in dev.llm-harness
                                                         {:status :response.status/ok
                                                          :payload {:echo/input (:input ctx)
                                                                    :echo/surface (:surface ctx)
                                                                    :echo/requested-outputs (:requested-outputs ctx)}
                                                          :meta {:simulated? true
                                                                 :source :dev.codex-oneshot}
                                                          :self-report {:confidence :medium
                                                                        :reason "dev.codex-oneshot fake response"
                                                                        :assumptions []
                                                                        :uncertainties []}}))
                                   :llm/call-fn call-fn})]
      (pprint/pprint result))
    (catch Exception e
      (binding [*out* *err*]
        (println "Codex one-shot error:" (.getMessage e))
        (when-let [data (ex-data e)]
          (println "Details:" (pr-str data))))
      (System/exit 1))))
