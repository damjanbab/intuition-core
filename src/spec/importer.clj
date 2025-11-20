(ns spec.importer
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [datomic.client.api :as d])
  (:import
   (java.math BigInteger)
   (java.security MessageDigest)))

(def db-name "spec")
(def system-name "spec-system")
(def storage-dir (.getAbsolutePath (io/file "data/datomic-spec")))

(def spec-file "SYSTEM_SPEC.md")

(def schema
  [{:db/ident :spec/id
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity
    :db/doc "Stable identifier for a spec section."}
   {:db/ident :spec/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :spec/summary
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :spec/content
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :spec/level
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :spec/tags
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :spec/protocols
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :spec/dependencies
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident :spec/parent
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident :spec/meta
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :spec/version
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :spec/content-hash
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :spec/last-updated-by
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
 {:db/ident :spec/last-updated-at
  :db/valueType :db.type/instant
  :db/cardinality :db.cardinality/one}])

(defn sha1 [^String s]
  (let [digest (.digest (MessageDigest/getInstance "SHA-1")
                        (.getBytes s "UTF-8"))]
    (format "%040x" (BigInteger. 1 digest))))

(def heading-pattern #"(?m)^(#{1,6})\s+(.+)$")

(defn extract-headings [content]
  (let [matcher (re-matcher heading-pattern content)]
    (loop [acc []]
      (if (.find matcher)
        (let [level (count (.group matcher 1))
              title (.group matcher 2)
              start (.start matcher)
              body-start (.end matcher)]
          (recur (conj acc {:level level
                            :title (str/trim title)
                            :start start
                            :body-start body-start})))
        acc))))

(defn slugify [s]
  (-> s
      str/lower-case
      (str/replace #"[^a-z0-9\s\-]" "")
      (str/replace #"\s+" "-")
      (str/replace #"-+" "-")
      str/trim
      (str/replace #"^-|-$" "")))

(defn ensure-unique-slugs [sections]
  (second
   (reduce
    (fn [[seen acc] section]
      (let [base (slugify (:title section))
            idx (get seen base 0)
            slug (if (zero? idx) base (str base "-" idx))]
        [(assoc seen base (inc idx))
         (conj acc (assoc section :slug slug))]))
    [{} []]
    sections)))

(defn attach-content [headings content]
  (map (fn [[current next]]
         (let [end (if next (:start next) (count content))
               body (subs content (:body-start current) end)]
           (assoc current :content (str/trim body))))
       (map vector headings (concat (rest headings) [nil]))))

(defn parse-sections [markdown]
  (let [headings (extract-headings markdown)
        sections (attach-content headings markdown)
        sections (ensure-unique-slugs sections)]
    (loop [stack [] result [] remaining sections]
      (if-let [section (first remaining)]
        (let [level (:level section)
              stack' (loop [s stack]
                       (if (and (seq s) (>= (:level (peek s)) level))
                         (recur (pop s))
                         s))
              parent (peek stack')
              section' (assoc section :parent parent)
              stack'' (conj stack' section')]
          (recur stack'' (conj result section') (rest remaining)))
        result))))

(defn extract-protocols [content]
  (->> (re-seq #"protocol/[a-zA-Z0-9\.\-]+" content)
       (map (fn [p]
              (keyword "protocol" (str/replace p #"protocol/" ""))))
       distinct))

(defn section->tx [{:keys [title content slug level parent]}]
  (let [id (keyword "spec" slug)
        summary (->> (str/split-lines content)
                     (map str/trim)
                     (remove str/blank?)
                     first
                     (or title))
        protocols (extract-protocols content)
        tags [(keyword (str "level-" level))]]
    (cond-> {:spec/id id
             :spec/title title
             :spec/summary summary
             :spec/content content
             :spec/level level
             :spec/tags tags
             :spec/protocols protocols
             :spec/version 1
             :spec/content-hash (sha1 content)
             :spec/last-updated-by "spec.importer"
             :spec/last-updated-at (java.util.Date.)}
      parent (assoc :spec/parent [:spec/id (keyword "spec" (:slug parent))]))))

(defn ensure-storage-dir! []
  (let [dir (io/file storage-dir)]
    (when-not (.exists dir)
      (.mkdirs dir))))

(defn client []
  (ensure-storage-dir!)
  (d/client {:server-type :dev-local
             :system system-name
             :storage-dir storage-dir}))

(defn ensure-db! [client]
  (d/create-database client {:db-name db-name}))

(defn ensure-schema! [conn]
  (let [db (d/db conn)
        has? (seq (d/q '[:find ?e
                         :where [?e :db/ident :spec/id]]
                       db))]
    (when-not has?
      (d/transact conn {:tx-data schema}))))

(defn ingest! []
  (let [markdown (slurp (io/file spec-file))
        sections (parse-sections markdown)
        tx-data (map section->tx sections)
        client (client)]
    (ensure-db! client)
    (let [conn (d/connect client {:db-name db-name})]
      (ensure-schema! conn)
      (doseq [tx tx-data]
        (d/transact conn {:tx-data [tx]}))
      (println (format "Ingested %d sections into db '%s' (system %s, storage %s)"
                       (count tx-data) db-name system-name storage-dir)))))

(defn -main [& _]
  (ingest!))
