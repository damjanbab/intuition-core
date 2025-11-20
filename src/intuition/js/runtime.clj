(ns intuition.js.runtime
  "Validates JS component bundles and external API integrations before missions execute them."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [intuition.sfs.env.bootstrap :as bootstrap])
  (:import
   (java.time Instant)))

(def ^:private repo-root (.getCanonicalPath (io/file ".")))

(def ^:private risk-order
  {:risk.profile/low 0
   :risk.profile/medium 1
   :risk.profile/high 2})

(def ^:private integration-types
  #{:integration.type/js
    :integration.type/api})

(def ^:private approval-spec-sections
  ["2.1" "2.2" "6" "10"])

(defn- now [] (Instant/now))

(defn- canonical-path
  [path]
  (when (str/blank? (str path))
    (throw (ex-info "Bundle path is required." {:path path})))
  (let [file (io/file path)]
    (when-not (.exists file)
      (throw (ex-info "Bundle path does not exist." {:path path})))
    (let [canonical (.getCanonicalPath file)]
      (when-not (.startsWith canonical repo-root)
        (throw (ex-info "Bundle paths must stay inside the repo."
                        {:path canonical
                         :repo repo-root})))
      canonical)))

(defn- ensure-risk-profile
  [profile]
  (if (contains? risk-order profile)
    profile
    (throw (ex-info "Unknown risk profile for integration."
                    {:risk/profile profile
                     :known (vec (keys risk-order))}))))

(defn- sanitize-call-path
  [label value]
  (when (str/blank? (str value))
    (throw (ex-info (str label " call path required.")
                    {:call value})))
  (when (str/includes? value "..")
    (throw (ex-info "Call paths cannot escape the runtime sandbox."
                    {:call value})))
  (when (re-find #"[^\w\./:-]" value)
    (throw (ex-info "Call paths must use alphanumeric/.-:_ characters."
                    {:call value})))
  value)

(defn- normalize-dependencies
  [deps]
  (let [items (vec (or deps []))]
    (when (empty? items)
      (throw (ex-info "Dependencies are required for every JS component." {})))
    (mapv #(if (str/blank? (str %))
             (throw (ex-info "Dependency identifiers must be non-blank." {}))
             (str %))
          items)))

(defn- normalize-component
  [component]
  (let [ident (:js.component/ident component)
        description (:js.component/description component)]
    (when-not (keyword? ident)
      (throw (ex-info "Component ident must be a keyword." {:component component})))
    (let [bundle (canonical-path (:js.component/bundle-path component))
          dependencies (normalize-dependencies (:js.component/dependencies component))
          call-paths (mapv #(sanitize-call-path "JS" %) (or (:js.component/call-paths component) []))
          _ (when (empty? call-paths)
              (throw (ex-info "JS components must declare call paths for auditing."
                              {:component ident})))
          risk-profile (ensure-risk-profile (:js.component/risk-profile component))]
      {:js.component/ident ident
       :js.component/name (or (:js.component/name component) (name ident))
       :js.component/description description
       :js.component/bundle-path bundle
       :js.component/dependencies dependencies
       :js.component/call-paths call-paths
       :js.component/risk-profile risk-profile})))

(defn- normalize-endpoint
  [api-ident endpoint]
  (let [method (keyword (str/lower-case (name (:external.api.endpoint/method endpoint))))
        path (:external.api.endpoint/path endpoint)
        scope (:external.api.endpoint/scope endpoint)
        call-paths (mapv #(sanitize-call-path "API" %) (or (:external.api.endpoint/call-paths endpoint) []))]
    (when (str/blank? path)
      (throw (ex-info "API endpoint path required."
                      {:api api-ident
                       :endpoint endpoint})))
    (when-not (str/starts-with? path "/")
      (throw (ex-info "API endpoint paths must start with /"
                      {:api api-ident
                       :path path})))
    (when (str/blank? (str scope))
      (throw (ex-info "API endpoint scope required." {:api api-ident :endpoint endpoint})))
    (when (empty? call-paths)
      (throw (ex-info "Each API endpoint must declare call paths for scanning."
                      {:api api-ident
                       :endpoint endpoint})))
    {:external.api.endpoint/method method
     :external.api.endpoint/path path
     :external.api.endpoint/scope scope
     :external.api.endpoint/call-paths call-paths}))

(defn- normalize-api
  [api]
  (let [ident (:external.api/ident api)]
    (when-not (keyword? ident)
      (throw (ex-info "API ident must be keyword."
                      {:api api})))
    (let [base-url (:external.api/base-url api)]
      (when-not (and (string? base-url)
                     (str/starts-with? base-url "https://"))
        (throw (ex-info "External APIs must use HTTPS base URLs."
                        {:api ident
                         :base-url base-url}))))
    (let [risk-profile (ensure-risk-profile (:external.api/risk-profile api))
          endpoints (mapv #(normalize-endpoint ident %)
                          (or (:external.api/endpoints api) []))]
      (when (empty? endpoints)
        (throw (ex-info "At least one endpoint is required per external API."
                        {:api ident})))
      {:external.api/ident ident
       :external.api/name (or (:external.api/name api) (name ident))
       :external.api/provider (:external.api/provider api)
       :external.api/base-url (:external.api/base-url api)
       :external.api/endpoints endpoints
       :external.api/risk-profile risk-profile})))

(defn- log-dir
  [mission-id]
  (let [dir (io/file "missions" "logs" (bootstrap/sanitize-fragment mission-id))]
    (.mkdirs dir)
    dir))

(defn- approval-log-file
  [mission-id integration-type]
  (io/file (log-dir mission-id)
           (if (= integration-type :integration.type/api)
             "external-api-approvals.edn"
             "js-approvals.edn")))

(defn- revocation-log-file
  [mission-id]
  (io/file (log-dir mission-id) "js-revocations.edn"))

(defn- call-graph
  [components apis]
  (vec
   (concat
    (mapcat (fn [component]
              (map (fn [call]
                     {:integration.call/type :integration.type/js
                      :integration.call/component (:js.component/ident component)
                      :integration.call/call call})
                   (:js.component/call-paths component)))
            components)
    (mapcat (fn [api]
              (mapcat (fn [endpoint]
                        (map (fn [call]
                               {:integration.call/type :integration.type/api
                                :integration.call/api (:external.api/ident api)
                                :integration.call/method (:external.api.endpoint/method endpoint)
                                :integration.call/path (:external.api.endpoint/path endpoint)
                                :integration.call/scope (:external.api.endpoint/scope endpoint)
                                :integration.call/call call})
                             (:external.api.endpoint/call-paths endpoint)))
                      (:external.api/endpoints api)))
            apis))))

(defn- derive-risk-profile
  [components apis provided]
  (or provided
      (let [profiles (concat (map :js.component/risk-profile components)
                             (map :external.api/risk-profile apis))]
        (or (first (sort-by #(get risk-order % 0) > profiles))
            :risk.profile/medium))))

(defn- write-approval!
  [{:keys [mission-id integration-type approver justification components apis call-graph risk-profile agent-id]}]
  (let [file (approval-log-file mission-id integration-type)
        recorded-at (str (now))
        watermark {:mission/id mission-id
                   :agent/id agent-id
                   :spec/sections approval-spec-sections
                   :recorded-at recorded-at}
        payload {:mission/id mission-id
                 :integration/type integration-type
                 :integration/components components
                 :integration/apis apis
                 :integration/call-graph call-graph
                 :integration/risk-profile risk-profile
                 :integration/watermark watermark
                 :approval {:by approver
                            :justification justification
                            :recorded-at recorded-at}}]
    (spit file (pr-str payload))
    (.getCanonicalPath file)))

(defn- write-revocation!
  [{:keys [mission-id integration-type components apis reason]}]
  (let [file (revocation-log-file mission-id)
        payload {:mission/id mission-id
                 :integration/type integration-type
                 :integration/components (mapv :js.component/ident components)
                 :integration/apis (mapv :external.api/ident apis)
                 :integration/reason (or reason "Revoked via js-revoke protocol.")
                 :recorded-at (str (now))}]
    (spit file (pr-str payload))
    (.getCanonicalPath file)))

(defn approval-artifact
  [mission-id integration-type]
  (let [file (approval-log-file mission-id integration-type)]
    (when (.exists file)
      (edn/read-string (slurp file)))))

(defn approval-present?
  [{:keys [mission-id integration-type components apis]}]
  (let [components (vec (or components []))
        apis (vec (or apis []))]
    (if (and (empty? components)
             (empty? apis))
      true
      (let [artifact (approval-artifact mission-id integration-type)]
        (boolean
         (and artifact
              (= integration-type (:integration/type artifact))
              (set/subset? (set (map :js.component/ident components))
                            (set (map :js.component/ident (:integration/components artifact))))
              (set/subset? (set (map :external.api/ident apis))
                            (set (map :external.api/ident (:integration/apis artifact))))))))))

(defn revocation-present?
  [{:keys [mission-id]}]
  (let [file (revocation-log-file mission-id)]
    (.exists file)))

(defn register-components-action
  [{:keys [config]}]
  (let [components (mapv normalize-component (:js/components config))]
    {:action/status :status/ok
     :integration/components components}))

(defn register-apis-action
  [{:keys [config]}]
  (let [apis (mapv normalize-api (:external/apis config))]
    {:action/status :status/ok
     :integration/apis apis}))

(defn scan-usage-action
  [{:keys [config]}]
  (let [components (vec (or (:integration/components config) []))
        apis (vec (or (:integration/apis config) []))
        call-graph (call-graph components apis)]
    {:action/status :status/ok
     :integration/call-graph call-graph}))

(defn request-approval-action
  [{:keys [config]}]
  (let [{mission-id :mission/id
         agent-id :agent/id
         integration-type :integration/type
         components-input :integration/components
         apis-input :integration/apis
         call-graph-input :integration/call-graph
         approver :integration/approver
         justification :integration/justification
         risk-profile :integration/risk-profile} config]
    (when-not (contains? integration-types integration-type)
      (throw (ex-info "Unknown integration type for approval."
                      {:integration/type integration-type})))
    (let [components (vec (or components-input []))
          apis (vec (or apis-input []))
          call-graph (vec (or call-graph-input []))
          risk (derive-risk-profile components apis risk-profile)
          path (write-approval! {:mission-id mission-id
                                 :integration-type integration-type
                                 :approver approver
                                 :justification justification
                                 :agent-id agent-id
                                 :components components
                                 :apis apis
                                 :call-graph call-graph
                                 :risk-profile risk})]
      {:action/status :status/ok
       :integration/approval-path path
       :integration/risk-profile risk})))

(defn revoke-access-action
  [{:keys [config]}]
  (let [{mission-id :mission/id
         integration-type :integration/type
         components-input :integration/components
         apis-input :integration/apis
         reason :integration/reason} config]
    (when-not (contains? integration-types integration-type)
      (throw (ex-info "Unknown integration type for revocation." {:integration/type integration-type})))
    (let [components (vec (or components-input []))
          apis (vec (or apis-input []))
          path (write-revocation! {:mission-id mission-id
                                   :integration-type integration-type
                                   :components components
                                   :apis apis
                                   :reason reason})]
      {:action/status :status/ok
       :integration/revocation-path path})))
