(ns dev.llm-harness
  "CLI wrapper for the LLM harness. Accepts an EDN payload and prints request/response
  records. Designed to stay data-only (Datomic + EDN) per SYSTEM_SPEC §§2.1–2.2,
  §§3.3–3.6, §4.7, §5.1, §5.3, §8.1, §9, §11."
  (:require
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [clojure.string :as str]
   [intuition.llm.harness :as harness]))

(defn- parse-payload
  [s]
  (if (str/blank? s)
    {}
    (edn/read-string s)))

(defn- default-fake-response
  [{:keys [surface input requested-outputs]}]
  {:status :response.status/ok
   :payload {:echo/input input
             :echo/surface surface
             :echo/requested-outputs requested-outputs}
   :meta {:simulated? true
          :source :dev.llm-harness}
   :self-report {:confidence :medium
                 :reason "dev.llm-harness fake response"
                 :assumptions []
                 :uncertainties []}})

(defn -main
  [& [payload-str]]
  (try
    (let [payload (parse-payload (or payload-str "{}"))
          surface (or (:surface payload)
                      (:surface/ident payload)
                      (:llm/surface payload))
          input (or (:input payload) (:llm/input payload) {})
          outputs (or (:requested-outputs payload)
                      (:llm/requested-outputs payload))
          call-fn (:llm/call-fn payload)
          fake? (if (contains? payload :fake/response?)
                  (:fake/response? payload)
                  (nil? call-fn))
          result (harness/invoke! {:surface surface
                                   :input input
                                   :requested-outputs outputs
                                   :trace (:trace payload)
                                   :idempotency-key (:idempotency-key payload)
                                   :context-hash (:context-hash payload)
                                   :spec-sections (:spec-sections payload)
                                   :fake-response-fn (when fake? default-fake-response)
                                   :llm/call-fn call-fn})]
      (pprint/pprint result))
    (catch Exception e
      (binding [*out* *err*]
        (println "LLM harness error:" (.getMessage e))
        (when-let [data (ex-data e)]
          (println "Details:" (pr-str data))))
      (System/exit 1))))
