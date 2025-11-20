(ns intuition.datomic
  "Helpers for working with the dev-local Datomic system that the SfS runtimes rely on."
  (:require
   [clojure.java.io :as io]
   [datomic.client.api :as d]))

(def ^:private system-name "spec-system")
(def ^:private storage-dir (.getAbsolutePath (io/file "data/datomic-spec")))

(def default-db-name "intuition-core")

(defn- ensure-storage-dir!
  []
  (let [dir (io/file storage-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))
    storage-dir))

(defn client
  ([] (client {}))
  ([opts]
   (ensure-storage-dir!)
    (d/client (merge {:server-type :dev-local
                      :system system-name
                      :storage-dir storage-dir}
                     opts))))

(defn create-db!
  ([db-name]
   (create-db! (client) db-name))
  ([c db-name]
   (d/create-database c {:db-name db-name})
   db-name))

(defn delete-db!
  ([db-name]
   (delete-db! (client) db-name))
  ([c db-name]
   (d/delete-database c {:db-name db-name})
   db-name))

(defn connect
  ([db-name]
   (connect (client) db-name))
  ([c db-name]
   (d/connect c {:db-name db-name})))

(defn ensure-db!
  "Creates the database if it does not already exist and returns a connection."
  ([] (ensure-db! default-db-name))
  ([db-name]
   (let [c (client)]
     (try
       (create-db! c db-name)
       (catch Exception _
         ;; Already exists, which is fine.
         nil))
     (connect c db-name))))
