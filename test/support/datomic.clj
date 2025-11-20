(ns support.datomic
  (:require
   [datomic.client.api :as d]
   [intuition.datomic :as db]
   [intuition.sfs.missions.runtime :as missions])
  (:import
   (java.util UUID)))

(defn new-test-db!
  []
  (let [client (db/client)
        db-name (str "test-" (UUID/randomUUID))]
    (d/create-database client {:db-name db-name})
    (let [conn (d/connect client {:db-name db-name})]
      (missions/prepare-conn! conn)
      {:client client
       :db-name db-name
       :conn conn})))

(defn with-test-conn
  [f]
  (let [{:keys [client db-name conn]} (new-test-db!)]
    (try
      (f conn)
      (finally
        (d/delete-database client {:db-name db-name})))))
