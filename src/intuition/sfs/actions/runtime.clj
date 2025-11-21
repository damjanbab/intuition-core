(ns intuition.sfs.actions.runtime
  (:require
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [datomic.client.api :as d]
   [intuition.sfs.permissions :as perms]
   [intuition.sfs.schemas])
  (:import
   (java.time Instant)
   (java.util Date UUID)))

(def ^:private action-pull-pattern
  [:action/ident
   :action/name
   :action/description
   :action/invariants
   :action/permissions
   :action/config-spec
   :action/output-spec
   :action/handler])

(defn- now [] (Instant/now))

(defn- ensure-action
  [db ident]
  (or (d/pull db action-pull-pattern [:action/ident ident])
      (throw (ex-info (str "Unknown action " ident)
                      {:type :action/not-found
                       :action/ident ident}))))

(defn- resolve-handler
  [handler-symbol]
  (try
    (requiring-resolve (symbol handler-symbol))
    (catch Exception e
      (throw (ex-info (str "Unable to resolve handler " handler-symbol)
                      {:type :action/invalid-handler
                       :handler handler-symbol}
                      e)))))

(defn- resolve-spec
  [spec-kw]
  (when spec-kw
    (or (s/get-spec spec-kw)
        (throw (ex-info (str "Missing spec for " spec-kw)
                        {:type :action/missing-spec
                         :spec spec-kw}))))
  spec-kw)

(defn- validate!
  [spec-kw value stage action-ident]
  (when spec-kw
    (let [spec (resolve-spec spec-kw)]
      (when-not (s/valid? spec value)
        (throw (ex-info (str "Invalid " (name stage) " for " action-ident)
                        {:type (if (= stage :config)
                                 :action/invalid-config
                                 :action/invalid-output)
                         :action/ident action-ident
                         :stage stage
                         :spec spec-kw
                         :problems (s/explain-data spec value)}))))))

(defn- granted-permissions
  [opts]
  (or (:permissions opts)
      (:permissions/granted opts)
      #{}))

(defn- ensure-permissions!
  [granted required ident]
  (doseq [perm (remove nil? (concat granted required))]
    (perms/assert-defined! perm))
  (when-not (set/superset? (set granted) (set required))
    (throw (ex-info (str "Missing permissions for " ident)
                    {:type :action/unauthorized
                     :action/ident ident
                     :required (set required)
                     :granted (set granted)}))))

(defn- default-log
  [event payload]
  (log/infof "[actions] %s %s" event (pr-str payload)))

(def ^:private log-max-chars 4000)

(defn- truncate-log
  [text]
  (when text
    (let [s (str text)]
      (if (> (count s) log-max-chars)
        (str (subs s 0 log-max-chars) "...<truncated>")
        s))))

(defn- compact-log-map
  [m]
  (cond-> m
    (:codetype/definitions m)
    (-> (assoc :codetype/definition-count (count (:codetype/definitions m)))
        (update :codetype/definitions
                #(mapv (fn [definition]
                         (select-keys definition [:codetype/ident :codetype/validators :codetype/status]))
                       %)))
    (:mission/report m)
    (update :mission/report #(select-keys % [:mission/id :report/path :mission.report/tests :mission.report/worklogs]))))

(defn- encode-log
  [value]
  (when (some? value)
    (-> (if (map? value)
          (compact-log-map value)
          value)
        pr-str
        truncate-log)))

(defn- instrumentation->log-fn
  [instrumentation]
  (cond
    (fn? instrumentation) instrumentation
    (fn? (:log-fn instrumentation)) (:log-fn instrumentation)
    :else default-log))

(defn- mission-id-from
  [config context]
  (or (:mission/id context)
      (:mission/id config)))

(defn- mission-ident
  [value]
  (cond
    (keyword? value) value
    (string? value) (keyword value)
    :else value))

(defn- record-execution!
  [conn {:keys [action-ident status config output error definition context started-at step]}]
  (let [start (or started-at (now))
        entity (cond-> {:action.execution/id (UUID/randomUUID)
                        :action.execution/action action-ident
                        :action.execution/status status
                        :action.execution/started-at (Date/from start)
                        :action.execution/completed-at (Date/from (now))}
                 config (assoc :action.execution/config (encode-log config))
                 output (assoc :action.execution/output (encode-log output))
                 error (assoc :action.execution/error (truncate-log (pr-str error)))
                 (:action/invariants definition) (assoc :action.execution/invariants (:action/invariants definition))
                 (mission-id-from config context) (assoc :action.execution/mission (mission-ident (mission-id-from config context)))
                 (:protocol/ident context) (assoc :action.execution/protocol (:protocol/ident context))
                 step (assoc :action.execution/step step))
        tx-result (d/transact conn {:tx-data [entity]})]
    {:execution/id (:action.execution/id entity)
     :tx-result tx-result
     :log entity}))

(defn execute!
  "Executes an ActionDefinition by loading it from Datomic, enforcing permission
  checks + config/output specs, and recording the result. Options:

  {:conn DatomicConn
   :action/ident :action/test.run-suite
   :config {...}
   :permissions #{...}
   :context {...}
   :instrumentation {:log-fn (fn [event payload] ...)} }
  "
  [{:keys [conn config context instrumentation]
    :as opts}]
  (let [ident (:action/ident opts)
        _ (when-not conn
            (throw (ex-info "Missing Datomic connection" {:type :action/missing-conn})))
        _ (when-not ident
            (throw (ex-info "Missing action ident" {:type :action/missing-ident})))
        db (d/db conn)
        definition (ensure-action db ident)
        log! (instrumentation->log-fn instrumentation)
        granted (set (granted-permissions opts))
        required (set (:action/permissions definition))
        started-at (now)
        context (or context {})
        config (cond
                 (map? config) (into {} (remove (comp nil? val)) config)
                 :else config)]
    (log! :action/start {:action ident :context context})
    (try
      (ensure-permissions! granted required ident)
      (validate! (:action/config-spec definition) config :config ident)
      (let [handler (resolve-handler (:action/handler definition))
            result (handler {:config config
                             :context context
                             :definition definition
                             :conn conn
                             :log! log!
                             :permissions granted})]
        (validate! (:action/output-spec definition) result :output ident)
        (let [log-result (record-execution! conn {:action-ident ident
                                                  :status :status/succeeded
                                                  :config config
                                                  :output result
                                                  :definition definition
                                                  :context context
                                                  :started-at started-at
                                                  :step (:protocol/step context)})
              execution-id (:action.execution/id (:log log-result))]
          (log! :action/success {:action ident :result result})
          {:action/ident ident
           :execution/id execution-id
           :result result}))
      (catch Throwable e
        (record-execution! conn {:action-ident ident
                                 :status :status/failed
                                 :config config
                                 :output nil
                                 :error (.getMessage e)
                                 :definition definition
                                 :context context
                                 :started-at started-at
                                 :step (:protocol/step context)})
        (log! :action/failure {:action ident :error (.getMessage e)})
        (throw e)))))
