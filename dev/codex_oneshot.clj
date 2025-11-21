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
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [intuition.llm.harness :as harness]))

(defn- parse-payload
  [s]
  (if (str/blank? s)
    {}
    (edn/read-string s)))

(defn- safe-pr-str
  "Pretty-print EDN in a compact, single-line form for prompts."
  [x]
  (binding [*print-length* nil
            *print-level* nil]
    (pr-str x)))

(defn- build-prompt
  [{:keys [surface input requested-outputs]}]
  (format
   (str "You are an Intuition Core LLM surface.\n"
        "Surface ident: %s\n\n"
        "You will receive the following EDN input map and requested output keys.\n"
        "You MUST respond with exactly one EDN map of the shape:\n"
        "{:status   :response.status/ok-or-error\n"
        " :payload  {:<output-key> ...}\n"
        " :meta     {:any/additional-metadata}\n"
        " :self-report {:confidence :low/:medium/:high\n"
        "               :reason \"short explanation\"\n"
        "               :assumptions [\"...\"]\n"
        "               :uncertainties [\"...\"]}\n"
        " :error    nil-or-string}\n\n"
        "Do not run shell commands. Do not refer to files or paths. Operate only\n"
        "on the EDN input. Here is the EDN you must use:\n\n"
        "INPUT: %s\n\n"
        "REQUESTED-OUTPUTS: %s\n\n"
        "Now output the EDN response map (and nothing else).")
   (or (some-> surface name) "unknown")
   (safe-pr-str input)
   (safe-pr-str requested-outputs)))

(defn- codex-call-fn
  "Real LLM caller for harness/invoke!. Given the standard harness input map,
   shells out to `codex exec` with a single prompt and expects Codex to print
   a single EDN map with :payload/:meta/:status/:self-report/:error."
  [{:keys [surface input requested-outputs]}]
  (let [prompt (build-prompt {:surface surface
                              :input input
                              :requested-outputs requested-outputs})
        ;; Non-interactive, workspace-write, approval never; everything else is
        ;; governed by the pipeline, not by Codex.
        {:keys [exit out err]} (shell/sh "codex" "exec" "--sandbox" "workspace-write" prompt)]
    (when (not (zero? exit))
      (throw (ex-info "codex exec failed"
                      {:exit exit
                       :err err
                       :out out})))
    (let [trimmed (str/trim out)
          body (edn/read-string trimmed)]
      (if (map? body)
        body
        (throw (ex-info "Codex response was not an EDN map"
                        {:out trimmed}))))))

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
          call-fn (when-not fake? codex-call-fn)
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

