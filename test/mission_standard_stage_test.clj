(ns mission-standard-stage-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [datomic.client.api :as d]
   [intuition.code.generate :as codegen]
   [intuition.code.runtime :as code]
   [intuition.gateway.orchestrator]
   [support.datomic :as support]))

(def mission-id "M-20251121-813")
(def mission-ident (keyword (str "mission/" mission-id)))

(def permissions
  #{:permission/env.bootstrap
    :permission/locks.manage
    :permission/tests.run
    :permission/docs.write
    :permission/system-map.write})

(def target-definitions
  [:code/spec.importer])

(deftest mission-standard-stage-test
  (support/with-test-conn
   (fn [conn]
     (codegen/ensure-schema! conn)
     (let [catalog (into {} (map (juxt :code.definition/ident identity) (code/definitions)))
           tx-defs (for [ident target-definitions
                         :let [definition (get catalog ident)]]
                     (-> (select-keys definition [:code.definition/ident
                                                  :code.definition/type
                                                  :code.definition/name
                                                  :code.definition/paths
                                                  :code.definition/spec-sections])
                         (assoc :code.definition/missions [mission-ident])))]
       (d/transact conn {:tx-data tx-defs})
       (let [stage (#'intuition.gateway.orchestrator/mission-standard-stage!
                    {:conn conn
                     :granted permissions
                     :mission-id mission-id
                     :agent-id "tester"
                     :bundle {}
                     :limit 4096
                     :log-path "tmp/mission-standard-stage.log"
                     :mission-result {:sandbox/root "tmp/mission-standard-stage-sandbox"}})
             materialize (:code.materialize (:result stage))]
         (is (= :status/succeeded (:status stage)))
         (is (seq (:code.materialize/definitions materialize)))
         (doseq [entry (:code.materialize/files materialize)]
           (is (.exists (io/file (:code.materialize/file entry)))))
         (is (some? (:code.materialize/log-path materialize))))))))
