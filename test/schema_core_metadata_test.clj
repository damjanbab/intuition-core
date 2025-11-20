(ns schema-core-metadata-test
  (:require
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [datomic.client.api :as d]
   [intuition.dictionary.meta-types :as meta])
  (:import
   (java.nio.file Files)
   (java.nio.file.attribute FileAttribute)
   (java.util UUID)))

(defn- temp-config []
  (let [uuid (str (UUID/randomUUID))
        dir (Files/createTempDirectory (str "dictionary-meta-test-" uuid)
                                       (make-array FileAttribute 0))]
    {:db-name (str "dictionary-meta-" uuid)
     :system (str "dictionary-system-" uuid)
     :storage-dir (.toString dir)}))

(deftest type-definitions-only-use-declared-attributes
  (let [{:keys [types attributes]} (meta/validate-bundle! (meta/load-bundle))
        attr-idents (set (keys attributes))]
    (doseq [[ident type] types]
      (testing (str "TypeDefinition " ident)
        (is (set/subset? (set (:type/attributes type)) attr-idents))))))

(deftest seeding-is-idempotent
  (let [cfg (temp-config)
        first-run (meta/seed-core-meta! cfg)
        second-run (meta/seed-core-meta! cfg)]
    (is (= (:counts first-run) (:counts second-run)))
    (is (= (:db (:counts first-run)) (:db (:counts second-run))))))

(deftest datomic-counts-match-edn-bundle
  (let [cfg (temp-config)
        {:keys [conn]} (meta/seed-core-meta! cfg)
        {:keys [types attributes]} (meta/validate-bundle! (meta/load-bundle))
        expected {:types (count types) :attributes (count attributes)}
        db-counts (meta/definition-counts (d/db conn))]
    (is (= expected db-counts))))
