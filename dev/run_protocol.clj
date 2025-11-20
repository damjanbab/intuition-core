(ns dev.run-protocol
  (:require
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

(defn run-mission-standard!
  []
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
  [& _]
  (prn (run-mission-standard!)))
