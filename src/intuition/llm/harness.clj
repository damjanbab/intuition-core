(ns intuition.llm.harness
  "One-shot LLM request/response harness. Persists governed requests/responses in
   Datomic, validates surface definitions from resources/dictionary/llm_surfaces.edn,
   and emits structured telemetry (including :meta/self-report) per SYSTEM_SPEC
   §§2.1–2.2, §§3.3–3.6, §4.7, §5.1, §5.3, §8.1, §9, §11."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [datomic.client.api :as d]
   [intuition.datomic :as datomic]
   [intuition.sfs.missions.runtime :as missions])
  (:import
   (java.io PushbackReader)
   (java.math BigInteger)
   (java.security MessageDigest)
   (java.time Instant)
   (java.util Date UUID)))

(def ^:private surfaces-resource "dictionary/llm_surfaces.edn")

(def ^:private system-spec-sections
  ["2.1" "2.2" "3.3" "3.4" "3.5" "3.6" "4.7" "5.1" "5.3" "8.1" "9" "11"])

(def ^:private request-schema
  [{:db/ident :llm.request/id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :llm.request/surface
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :llm.request/idempotency-key
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :llm.request/input
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :llm.request/context-hash
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :llm.request/requested-outputs
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :llm.request/spec-sections
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :llm.request/trace
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :llm.request/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}])

(def ^:private response-schema
  [{:db/ident :llm.response/id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :llm.response/request-id
    :db/valueType :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :llm.response/surface
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :llm.response/requested-outputs
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/many}
   {:db/ident :llm.response/status
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident :llm.response/payload
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :llm.response/meta
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :meta/self-report
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :llm.response/spec-sections
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/many}
   {:db/ident :llm.response/received-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident :llm.response/error
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident :llm.response/created-at
    :db/valueType :db.type/instant
    :db/cardinality :db.cardinality/one}])

(def ^:private request-pull
  [:llm.request/id
   :llm.request/surface
   :llm.request/idempotency-key
   :llm.request/input
   :llm.request/context-hash
   :llm.request/requested-outputs
   :llm.request/spec-sections
   :llm.request/trace
   :llm.request/created-at])

(def ^:private response-pull
  [:llm.response/id
   :llm.response/request-id
   :llm.response/surface
   :llm.response/requested-outputs
   :llm.response/status
   :llm.response/payload
   :llm.response/meta
   :meta/self-report
   :llm.response/spec-sections
   :llm.response/received-at
   :llm.response/error
   :llm.response/created-at])

(defn- sha256
  [^String input]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (.update digest (.getBytes (or input "") "UTF-8"))
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn- parse-edn
  [v]
  (when v
    (try
      (edn/read-string v)
      (catch Exception _
        v))))

(defn- normalize-strings
  [values]
  (->> (or values [])
       (map #(str/trim (str %)))
       (remove str/blank?)
       vec))

(defn- canonicalize
  [value]
  (cond
    (map? value) (into (sorted-map)
                       (map (fn [[k v]] [k (canonicalize v)]))
                       value)
    (set? value) (->> value (map canonicalize) sort vec)
    (sequential? value) (vec (map canonicalize value))
    :else value))

(defn- normalize-keywords
  [values]
  (->> (or values [])
       (map (fn [entry]
              (cond
                (keyword? entry) entry
                (string? entry) (keyword (str/replace (str/trim entry) #"^:" ""))
                :else (throw (ex-info "Output identifiers must be keywords or strings"
                                      {:value entry})))))
       vec))

(defn- load-surfaces
  []
  (let [url (or
             ;; Preferred: classpath resource (e.g. resources/dictionary/llm_surfaces.edn)
             (io/resource surfaces-resource)
             ;; Some environments prefix resources/ on disk; try that as a resource too.
             (io/resource (str "resources/" surfaces-resource))
             ;; Fallbacks: direct files relative to repo root.
             (let [f (io/file surfaces-resource)]
               (when (.exists f) f))
             (let [f (io/file "resources" surfaces-resource)]
               (when (.exists f) f)))]
    (when-not url
      (throw (ex-info "Missing llm surface resource" {:resource surfaces-resource})))
    (with-open [r (PushbackReader. (io/reader url))]
      (let [data (edn/read {:eof nil} r)
            entries (cond
                      (map? data) (:llm.surfaces/entries data)
                      (sequential? data) data
                      :else nil)
            surfaces (vec (or entries []))]
        (when (empty? surfaces)
          (throw (ex-info "No LLM surfaces defined" {:resource surfaces-resource})))
        surfaces))))

(defn surfaces-by-ident
  []
  (into {} (map (juxt :llm.surface/ident identity) (load-surfaces))))

(defn surface-idents
  []
  (keys (surfaces-by-ident)))

(defn- ensure-schema!
  [conn]
  (let [db (d/db conn)
        request-installed? (seq (d/q '[:find ?e :where [?e :db/ident :llm.request/id]] db))]
    (when-not request-installed?
      (d/transact conn {:tx-data (concat request-schema response-schema)})))
  conn)

(defn prepare-conn!
  "Ensures dictionary + mission schema and the LLM harness schema are installed."
  [conn]
  (-> (or conn (datomic/ensure-db!))
      missions/prepare-conn!
      ensure-schema!))

(defn- ensure-surface!
  [surface]
  (let [surfaces (surfaces-by-ident)]
    (when-not (get surfaces surface)
      (throw (ex-info "Unknown LLM surface" {:surface surface
                                             :known (or (keys surfaces) [])})))
    (get surfaces surface)))

(defn- coerce-requested-outputs
  [surface outputs]
  (let [surface-outputs (vec (or (:llm.surface/outputs surface) []))
        requested (vec (normalize-keywords (or outputs surface-outputs)))]
    (when (empty? requested)
      (throw (ex-info "Requested outputs required" {:surface (:llm.surface/ident surface)})))
    (when (seq surface-outputs)
      (let [declared (set surface-outputs)
            provided (set requested)]
        (when-not (set/subset? provided declared)
          (throw (ex-info "Requested outputs must be subset of surface outputs"
                          {:surface (:llm.surface/ident surface)
                           :declared surface-outputs
                           :requested requested})))))
    requested))

(def ^:private confidence-levels #{:low :medium :high})

(defn- normalize-self-report
  [self-report]
  (when-not (map? self-report)
    (throw (ex-info "meta/self-report must be a map"
                    {:self-report self-report})))
  (let [{:keys [confidence reason assumptions uncertainties]} self-report
        trimmed-reason (str/trim (str (or reason "")))
        clean-assumptions (normalize-strings (or assumptions []))
        clean-uncertainties (normalize-strings (or uncertainties []))]
    (when-not (confidence-levels confidence)
      (throw (ex-info "meta/self-report confidence must be :low/:medium/:high"
                      {:confidence confidence})))
    (when (str/blank? trimmed-reason)
      (throw (ex-info "meta/self-report must include a short reason" {})))
    {:confidence confidence
     :reason trimmed-reason
     :assumptions clean-assumptions
     :uncertainties clean-uncertainties}))

(defn- now []
  (Instant/now))

(defn- persist-request!
  [conn {:keys [surface input outputs trace idempotency-key context-hash spec-sections now*]}]
  (let [canonical-input (canonicalize (or input {}))
        canonical-outputs (vec (sort outputs))
        idempotency (or idempotency-key (sha256 (pr-str {:surface surface
                                                         :input canonical-input
                                                         :outputs canonical-outputs})))
        db (d/db conn)
        existing-eid (ffirst
                      (d/q '[:find ?e
                             :in $ ?key
                             :where [?e :llm.request/idempotency-key ?key]]
                           db
                           idempotency))
        existing (when existing-eid (d/pull db request-pull existing-eid))]
    (if existing
      (-> existing
          (update :llm.request/input parse-edn)
          (update :llm.request/trace parse-edn)
          (update :llm.request/requested-outputs vec)
          (update :llm.request/spec-sections vec))
      (let [timestamp (Date/from (or now* (now)))
            spec-sections (if (seq spec-sections)
                            (normalize-strings spec-sections)
                            system-spec-sections)
            raw-record {:llm.request/id (UUID/randomUUID)
                        :llm.request/surface surface
                        :llm.request/idempotency-key idempotency
                        :llm.request/input (pr-str canonical-input)
                        :llm.request/context-hash (or context-hash (sha256 (pr-str canonical-input)))
                        :llm.request/requested-outputs canonical-outputs
                        :llm.request/spec-sections spec-sections
                        :llm.request/trace (when trace (pr-str trace))
                        :llm.request/created-at timestamp}
            record (into {} (remove (comp nil? val) raw-record))]
        (d/transact conn {:tx-data [record]})
        (-> record
            (update :llm.request/input parse-edn)
            (update :llm.request/trace parse-edn))))))

(defn- find-response
  [conn request-id]
  (when request-id
    (let [db (d/db conn)
          eid (ffirst
               (d/q '[:find ?e
                      :in $ ?request-id
                      :where [?e :llm.response/request-id ?request-id]]
                    db
                    request-id))]
      (when eid
        (d/pull db response-pull eid)))))

(defn- hydrate-response
  [response]
  (-> response
      (update :llm.response/requested-outputs vec)
      (update :llm.response/payload parse-edn)
      (update :llm.response/meta parse-edn)
      (update :meta/self-report parse-edn)
      (update :llm.response/spec-sections vec)))

(defn- persist-response!
  [conn request {:keys [outputs payload meta status self-report error spec-sections now*]}]
  (let [request-id (:llm.request/id request)
        existing (find-response conn request-id)]
    (if existing
      (hydrate-response existing)
      (let [timestamp (Date/from (or now* (now)))
            spec-sections (if (seq spec-sections)
                            (normalize-strings spec-sections)
                            (or (:llm.request/spec-sections request) system-spec-sections))
            status (or status :response.status/ok)
            sr (normalize-self-report self-report)
            raw-record {:llm.response/id (UUID/randomUUID)
                        :llm.response/request-id request-id
                        :llm.response/surface (:llm.request/surface request)
                        :llm.response/requested-outputs outputs
                        :llm.response/status status
                        :llm.response/payload (pr-str (or payload {}))
                        :llm.response/meta (when meta (pr-str meta))
                        :meta/self-report (pr-str sr)
                        :llm.response/spec-sections spec-sections
                        :llm.response/received-at timestamp
                        :llm.response/error (when error (str error))
                        :llm.response/created-at timestamp}
            record (into {} (remove (comp nil? val) raw-record))]
        (d/transact conn {:tx-data [record]})
        (-> record
            (update :llm.response/payload parse-edn)
            (update :llm.response/meta parse-edn)
            (assoc :meta/self-report sr)
            hydrate-response)))))

(defn- normalize-response-body
  [body]
  {:payload (or (:payload body) (:llm.response/payload body) {})
   :meta (or (:meta body) (:llm.response/meta body))
   :status (or (:status body) (:llm.response/status body))
   :self-report (or (:self-report body) (:meta/self-report body))
   :error (:error body)})

(defn invoke!
  "Executes an LLM harness call for the given surface.

  Options:
  - :surface (keyword, required) – surface ident from llm_surfaces.edn
  - :input (map, required) – EDN payload (usually a context bundle)
  - :requested-outputs (vector keyword) – defaults to surface outputs
  - :conn – Datomic connection (defaults to dev-local)
  - :trace – optional trace metadata map
  - :idempotency-key – optional deterministic key
  - :context-hash – optional precomputed hash of the input
  - :spec-sections – optional SYSTEM_SPEC citations
  - :fake-response-fn – function that returns {:payload .. :meta .. :status .. :self-report .. :error ..}
  - :llm/call-fn – real caller; if omitted and no fake provided an error is raised
  - :now – override timestamp for tests"
  [{:keys [surface input requested-outputs trace idempotency-key context-hash spec-sections fake-response-fn now conn]
    :llm/keys [call-fn]}]
  (when-not surface
    (throw (ex-info "surface is required" {:field :surface})))
  (when-not (map? (or input {}))
    (throw (ex-info "input must be a map" {:input input})))
  (let [conn (prepare-conn! conn)
        surface-def (ensure-surface! surface)
        requested (coerce-requested-outputs surface-def requested-outputs)
        request (persist-request! conn {:surface surface
                                        :input input
                                        :outputs requested
                                        :trace trace
                                        :idempotency-key idempotency-key
                                        :context-hash context-hash
                                        :spec-sections (or spec-sections (:llm.surface/spec-sections surface-def) system-spec-sections)
                                        :now* now})
        outputs (:llm.request/requested-outputs request)
        responder (or fake-response-fn call-fn)
        existing-response (find-response conn (:llm.request/id request))]
    (if existing-response
      {:llm/request request
       :llm/response (hydrate-response existing-response)
       :llm/surface surface-def}
      (do
        (when-not responder
          (throw (ex-info "LLM caller missing; provide :fake-response-fn or :llm/call-fn"
                          {:surface surface})))
        (let [body (normalize-response-body
                    (responder {:surface surface
                                :surface/definition surface-def
                                :input input
                                :requested-outputs outputs
                                :request request}))
              response (persist-response! conn request {:outputs outputs
                                                        :payload (:payload body)
                                                        :meta (:meta body)
                                                        :status (:status body)
                                                        :self-report (:self-report body)
                                                        :error (:error body)
                                                        :spec-sections (or spec-sections (:llm.surface/spec-sections surface-def))
                                                        :now* now})]
          {:llm/request request
           :llm/response response
           :llm/surface surface-def})))))
