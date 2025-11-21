(ns dev.system-map-code
  (:require
   [clojure.java.io :as io]
   [datomic.client.api :as d]
   [intuition.dictionary :as dictionary]
   [intuition.sfs.system-map.runtime :as system-map]
   [support.datomic :as support]))

(def ^:private default-mission "M-20251121-811")
(def ^:private default-code-graph-path "docs/code-types/code-graph.edn")

(def ^:private node-pull
  [:system-map.node/ident
   :system-map.node/entity
   :system-map.node/entity-kind
   :system-map.node/name
   :system-map.node/spec-refs
   :system-map.node/tags])

(def ^:private edge-pull
  [:system-map.edge/ident
   :system-map.edge/from
   :system-map.edge/to
   :system-map.edge/relation
   :system-map.edge/spec-refs
   :system-map.edge/tags])

(defn- pull-nodes
  [conn]
  (let [db (d/db conn)]
    (map #(d/pull db node-pull %)
         (map first (d/q '[:find ?e
                            :where [?e :system-map.node/ident _]]
                          db)))))

(defn- pull-edges
  [conn]
  (let [db (d/db conn)]
    (map #(d/pull db edge-pull %)
         (map first (d/q '[:find ?e
                            :where [?e :system-map.edge/ident _]]
                          db)))))

(defn refresh-and-export!
  [{:keys [mission-id code-graph-path]}]
  (let [mission-id (or mission-id (System/getenv "MISSION_ID") default-mission)
        graph-path (or code-graph-path (System/getenv "CODE_GRAPH_PATH") default-code-graph-path)
        log-dir (str "missions/logs/" mission-id)
        snapshot-path (str log-dir "/system-map-code-snapshot.edn")
        {:keys [client db-name conn]} (support/new-test-db!)]
    (try
      (dictionary/seed-all! conn)
      (let [result (system-map/refresh! {:conn conn
                                         :code-graph/path graph-path})
            snapshot {:nodes (pull-nodes conn)
                      :edges (pull-edges conn)}]
        (io/make-parents snapshot-path)
        (spit snapshot-path
              (with-out-str
                (binding [*print-namespace-maps* false]
                  (prn snapshot))))
        {:mission/id mission-id
         :snapshot/path snapshot-path
         :system-map result})
      (finally
        (d/delete-database client {:db-name db-name})))))

(defn -main
  [& _]
  (prn (refresh-and-export! {})))
