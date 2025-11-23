(ns dev.run-protocol
  "Deprecated protocol runner. Direct protocol execution bypasses the Agent Gateway and is blocked per SYSTEM_SPEC
  §§2.1–2.2, §§3.3–3.6, §5, §6, and §9. Use dev.agent-gateway run-mission with a context bundle instead."
  (:require
   [dev.agent-gateway :as gateway]
   [intuition.datomic :as db]
   [intuition.dictionary :as dictionary]
   [intuition.sfs.permissions :as perms]
   [intuition.sfs.protocols.runtime :as protocols]))

(defn- sample-context
  []
  {:mission/id :mission/sample
   :agent/id "dev"
   :workspace/root "work/dev-run"
   :tests/enabled? true
   :tests/suite :test.suite/contract
   :tests/paths ["test/actions_contract_test.clj"]
   :tests/error-mode :fail-fast
   :docs/paths ["docs/M-02.md"]
   :system-map/entities [:action/test.run-suite]})

(def permissions perms/default-permissions)

(defn- warn
  []
  (binding [*out* *err*]
    (println "WARNING: dev.run-protocol is deprecated. Use dev.agent-gateway run-mission with a context bundle.")
    (println "Direct protocol execution is frozen (SYSTEM_SPEC §§2.1–2.2, §§3.3–3.6, §5, §6, §9).")))

(defn run-mission-standard!
  []
  (warn)
  (let [conn (db/ensure-db!)]
    (dictionary/seed-all! conn)
    (protocols/run!
     {:conn conn
      :protocol/ident :protocol/mission-standard
      :context (sample-context)
      :permissions permissions
      :instrumentation {:log-fn (fn [event payload]
                                  (println "[dev]" event payload))}})))

(defn -main
  [& args]
  (if (seq args)
    (do
      (warn)
      (apply gateway/-main args))
    (run-mission-standard!)))
