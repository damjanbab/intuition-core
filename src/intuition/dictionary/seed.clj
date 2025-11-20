(ns intuition.dictionary.seed
  (:require
   [clojure.java.io :as io]
   [intuition.datomic :as db]
   [intuition.dictionary :as dictionary])
  (:gen-class))

(defn- storage-root
  []
  (.getAbsolutePath (io/file "data/datomic-spec")))

(defn seed!
  ([] (seed! db/default-db-name))
  ([db-name]
   (let [client (db/client)]
     (try
       (db/create-db! client db-name)
       (catch Exception _
         ;; Database already exists – expected for iterative runs.
         nil))
     (let [conn (db/connect client db-name)]
       (dictionary/seed-all! conn)
       {:db-name db-name
        :storage (storage-root)}))))

(defn -main
  [& [db-name]]
  (let [{:keys [db-name storage]} (seed! (or db-name db/default-db-name))]
    (println (format "Seeded dictionary data into db '%s' (storage %s)" db-name storage))))
