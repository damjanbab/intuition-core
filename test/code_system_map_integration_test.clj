(ns code-system-map-integration-test
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

(defn- pull-edges
  [conn]
  (let [db (d/db conn)]
    (map #(d/pull db [:system-map.edge/ident
                      :system-map.edge/from
                      :system-map.edge/to
                      :system-map.edge/relation]
                  %)
         (map first (d/q '[:find ?e
                            :where [?e :system-map.edge/ident _]]
                          db)))))

(deftest code-system-map-integration-test
  (support/with-test-conn
   (fn [conn]
     (let [result (system-map/refresh! {:conn conn
                                        :code-graph/path "docs/code-types/code-graph.edn"})
           nodes (node-idents conn)
           edges (pull-edges conn)]
       (is (= :status/ok (:action/status result)))
       (testing "edges reference existing nodes"
         (let [dangling (keep (fn [edge]
                                (let [missing (remove nodes [(:system-map.edge/from edge)
                                                             (:system-map.edge/to edge)])]
                                  (when (seq missing)
                                    {:edge (:system-map.edge/ident edge)
                                     :missing (vec missing)})))
                              edges)]
           (is (empty? dangling))))
       (testing "code/test/doc coverage edges exist"
         (let [by-rel (group-by :system-map.edge/relation edges)]
           (is (seq (by-rel :system-map.relation/implements)) "Mission implements code")
           (is (seq (by-rel :system-map.relation/validated-by)) "Code validated by tests")
           (is (seq (by-rel :system-map.relation/documented-by)) "Code documented by docs")
           (doseq [edge (mapcat by-rel [:system-map.relation/implements
                                        :system-map.relation/validated-by
                                        :system-map.relation/documented-by])]
             (is (nodes (:system-map.edge/from edge)))
             (is (nodes (:system-map.edge/to edge))))))))))
