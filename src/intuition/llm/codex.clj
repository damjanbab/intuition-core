(ns intuition.llm.codex
  "Codex-backed LLM caller helpers used by the orchestrator and scheduler to
   route governed surfaces through a data-only one-shot flow per SYSTEM_SPEC
   §§2.1–2.2, §§3.3–3.6, §4.7, §5, §6, §8.1, §9, §11. The caller shells out to
   `codex exec` with a structured prompt and returns the EDN response map."
  (:require
   [clojure.edn :as edn]
   [clojure.java.shell :as shell]
   [clojure.string :as str]))

(defn build-prompt
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
   (binding [*print-length* nil
             *print-level* nil]
     (pr-str input))
   (binding [*print-length* nil
             *print-level* nil]
     (pr-str requested-outputs))))

(defn oneshot-call
  "Calls `codex exec` with the governed prompt. Accepts optional opts:
   - :codex/bin – override the codex binary name (default \"codex\")
   - :codex/sandbox – sandbox mode flag (default \"workspace-write\")
   - :process/env – map of environment variables to propagate"
  ([request]
   (oneshot-call {} request))
  ([opts
    {:keys [surface input requested-outputs] :as _request}]
   (let [prompt (build-prompt {:surface surface
                               :input input
                               :requested-outputs requested-outputs})
         bin (or (:codex/bin opts) "codex")
         sandbox (or (:codex/sandbox opts) "workspace-write")
         env (:process/env opts)
         {:keys [exit out err]} (shell/sh bin "exec" "--sandbox" sandbox prompt
                                          :env env)
         trimmed (str/trim out)]
     (when-not (zero? exit)
       (throw (ex-info "codex exec failed"
                       {:exit exit
                        :err err
                        :out trimmed
                        :surface surface})))
     (let [body (edn/read-string trimmed)]
       (when-not (map? body)
         (throw (ex-info "Codex response was not an EDN map"
                         {:out trimmed
                          :surface surface})))
       body))))

(defn call-spec-from-env
  "Allows runtime selection of the caller via env vars."
  []
  (some-> (or (System/getenv "LLM_CALL")
              (System/getenv "LLM_CALL_STRATEGY"))
          str/trim
          (str/replace #"^:+", "")
          keyword))

(defn resolve-call-spec
  "Turns a call spec (function, keyword, string, or {:call :kw :opts {}} map)
   into {:call/fn f :call/strategy kw}. When strategy is :codex-oneshot the
   returned fn delegates to `oneshot-call`."
  ([spec]
   (resolve-call-spec {} spec))
  ([opts spec]
   (cond
     (nil? spec) nil
     (fn? spec) {:call/fn spec
                 :call/strategy :provided-fn}
     (string? spec) (resolve-call-spec opts (keyword spec))
     (map? spec) (let [strategy (or (:call spec) (:strategy spec) (:id spec) (:target spec))
                       resolved (resolve-call-spec opts strategy)]
                   (when resolved
                     (update resolved :call/fn
                             (fn [f]
                               (if-let [call-opts (:opts spec)]
                                 (partial f call-opts)
                                 f)))))
     (keyword? spec)
     (case spec
       (:llm.call/codex-oneshot :codex-oneshot :llm.call/dev-codex-oneshot)
       {:call/fn (partial oneshot-call opts)
        :call/strategy :codex-oneshot}
       nil)
     :else nil)))
