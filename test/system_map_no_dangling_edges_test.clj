(ns system-map-no-dangling-edges-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [datomic.client.api :as d]
   [intuition.sfs.system-map.runtime :as system-map]
   [support.datomic :as support]))

(defn- node-idents
  [conn]
  (->> (d/q '[:find ?ident
              :where [?e :system-map.node/ident ?ident]]
            (d/db conn))
       (map first)
       set))

(defn- edge-idents
  [conn]
  (->> (d/q '[:find ?ident
              :where [?e :system-map.edge/ident ?ident]]
            (d/db conn))
       (map first)
       set))

(deftest refreshes-actions-and-keeps-edges-attached
  (support/with-test-conn
   (fn [conn]
     (system-map/refresh! {:conn conn
                           :entities [:action/env.bootstrap
                                      :action/system-map.refresh]})
     (d/transact conn {:tx-data [{:system-map.edge/ident :system-map.edge/env->system
                                  :system-map.edge/from :action/env.bootstrap
                                  :system-map.edge/to :action/system-map.refresh
                                  :system-map.edge/relation :system-map.relation/depends-on
                                  :system-map.edge/status :system-map.edge.status/active}]})
     (let [result (system-map/refresh! {:conn conn
                                        :entities [:action/env.bootstrap
                                                   :action/system-map.refresh]})]
       (is (= :status/ok (:action/status result)))
       (is (= [:action/env.bootstrap :action/system-map.refresh]
              (:system-map/entities result)))
       (is (= #{:action/env.bootstrap :action/system-map.refresh}
              (node-idents conn)))
       (is (= #{:system-map.edge/env->system}
              (edge-idents conn)))))))

(deftest reports-structured-errors
  (testing "missing dictionary entities are rejected"
    (support/with-test-conn
     (fn [conn]
       (try
         (system-map/refresh! {:conn conn
                               :entities [:action/not-real]})
         (is false "refresh! should throw for unknown entities")
         (catch clojure.lang.ExceptionInfo ex
           (is (= :system-map/missing-dictionary-entities
                  (:type (ex-data ex))))
           (is (= [:action/not-real]
                  (:system-map/entities (ex-data ex)))))))))
  (testing "dangling nodes are surfaced"
    (support/with-test-conn
     (fn [conn]
       (system-map/refresh! {:conn conn :entities [:action/env.bootstrap]})
       (d/transact conn {:tx-data [{:system-map.node/ident :action/ghost
                                    :system-map.node/entity :action/ghost
                                    :system-map.node/entity-kind :system-map.entity/action
                                    :system-map.node/name "Ghost action"
                                    :system-map.node/description "Inserted to test invariant"
                                    :system-map.node/status :system-map.node.status/active}]})
       (try
         (system-map/refresh! {:conn conn})
         (is false "refresh! should throw")
         (catch clojure.lang.ExceptionInfo ex
           (is (= :system-map/dangling-nodes (:type (ex-data ex))))
           (is (= :action/ghost
                  (-> ex ex-data :system-map/nodes first :system-map.node/entity))))))))
  (testing "dangling edges are surfaced"
    (support/with-test-conn
     (fn [conn]
       (system-map/refresh! {:conn conn :entities [:action/env.bootstrap]})
       (d/transact conn {:tx-data [{:system-map.edge/ident :system-map.edge/bad
                                    :system-map.edge/from :action/env.bootstrap
                                    :system-map.edge/to :action/missing
                                    :system-map.edge/relation :system-map.relation/depends-on
                                    :system-map.edge/status :system-map.edge.status/active}]})
       (try
         (system-map/refresh! {:conn conn :entities [:action/env.bootstrap]})
         (is false "refresh! should throw")
         (catch clojure.lang.ExceptionInfo ex
           (is (= :system-map/dangling-edges (:type (ex-data ex))))
           (is (= [:action/missing]
                  (-> ex ex-data :system-map/edges first :missing)))))))))
