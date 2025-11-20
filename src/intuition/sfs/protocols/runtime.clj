(ns intuition.sfs.protocols.runtime
  (:refer-clojure :exclude [run!])
  (:require
   [clojure.edn :as edn]
   [clojure.set :as set]
   [clojure.tools.logging :as log]
   [clojure.walk :as walk]
   [datomic.client.api :as d]
   [intuition.sfs.actions.runtime :as actions])
  (:import
   (java.time Instant)
   (java.util Date UUID)))

(def ^:private protocol-pull-pattern
  [:protocol/ident
   :protocol/name
   :protocol/description
   :protocol/owner
   :protocol/escalation
   :protocol/invariants
   :protocol/locks
   :protocol/required-work-tracks
   :protocol/steps])

(def ^:private placeholder-ops #{:context :protocol :state})

(defn- now [] (Instant/now))

(defn- ensure-protocol
  [db ident]
  (let [entity (d/pull db protocol-pull-pattern [:protocol/ident ident])]
    (when-not entity
      (throw (ex-info (str "Unknown protocol " ident)
                      {:type :protocol/not-found
                       :protocol/ident ident})))
    (-> entity
        (update :protocol/steps #(if (string? %)
                                   (edn/read-string %)
                                   %))
        (update :protocol/escalation #(vec (or % [])))
        (update :protocol/locks #(set (or % #{})))
        (update :protocol/required-work-tracks #(set (or % #{}))))))

(defn- placeholder?
  [value]
  (and (vector? value)
       (contains? placeholder-ops (first value))))

(defn- resolve-placeholder
  [context protocol state [op & path]]
  (case op
    :context (get-in context path)
    :protocol (get-in protocol path)
    :state (get-in state path)
    nil))

(defn- resolve-config
  [config context protocol state]
  (when config
    (walk/postwalk
     (fn [value]
       (if (placeholder? value)
         (resolve-placeholder context protocol state value)
         value))
     config)))

(defn- eval-condition
  [context protocol state expr]
  (cond
    (nil? expr) true
    (true? expr) true
    (false? expr) false
    (placeholder? expr) (boolean (resolve-placeholder context protocol state expr))
    (vector? expr)
    (let [[op & args] expr]
      (case op
        :not (not (boolean (eval-condition context protocol state (first args))))
        :equals (= (eval-condition context protocol state (first args))
                   (second args))
        :contains (let [[collection-expr value] args
                         coll (eval-condition context protocol state collection-expr)
                         coll (cond
                                (set? coll) coll
                                (sequential? coll) (set coll)
                                :else #{})]
                    (contains? coll value))
        (boolean op)))
    :else (boolean expr)))

(defn- should-run?
  [context protocol state condition]
  (boolean (eval-condition context protocol state (or condition true))))

(defn- default-log
  [event payload]
  (log/infof "[protocols] %s %s" event (pr-str payload)))

(defn- instrumentation->log-fn
  [instrumentation]
  (cond
    (fn? instrumentation) instrumentation
    (fn? (:log-fn instrumentation)) (:log-fn instrumentation)
    :else default-log))

(defn- ensure-lock-preconditions!
  [state protocol step]
  (when (and (:step/requires-locks? step)
             (seq (:protocol/locks protocol)))
    (let [required (set (:protocol/locks protocol))
          held (set (:locks-held state))]
      (when-not (set/subset? required held)
        (throw (ex-info "Required locks are not held"
                        {:type :protocol/missing-locks
                         :required required
                         :held held
                         :step (:step/id step)}))))))

(defn- ->lock-set
  [value]
  (cond
    (nil? value) #{}
    (set? value) value
    (sequential? value) (set value)
    :else #{value}))

(defn- apply-step-success
  [state step result config]
  (let [locks-from-output (->lock-set (or (:locks/acquired result)
                                          (:locks result)
                                          (:locks config)))]
    (-> state
        (update :steps conj {:id (:step/id step) :status :succeeded})
        (update :step-results assoc (:step/id step) result)
        (cond-> (:step/work-track step)
          (update :work-completed conj (:step/work-track step)))
        (cond-> (:step/acquires-locks? step)
          (update :locks-held set/union locks-from-output))
        (cond-> (:step/releases-locks? step)
          (update :locks-held #(set/difference (set %) locks-from-output))))))

(defn- mark-step-skipped
  [state step]
  (update state :steps conj {:id (:step/id step) :status :skipped}))

(defn- enforce-work-tracks!
  [state protocol]
  (let [required (set (:protocol/required-work-tracks protocol))]
    (when (and (seq required)
               (not (set/subset? required (:work-completed state))))
      (throw (ex-info "Protocol missing required work tracks"
                      {:type :protocol/missing-work
                       :required required
                       :completed (:work-completed state)})))))

(defn- enforce-locks-released!
  [state]
  (when (seq (:locks-held state))
    (throw (ex-info "Protocol ended with dangling locks"
                    {:type :protocol/dangling-locks
                     :locks (:locks-held state)}))))

(defn- record-run!
  [conn {:keys [ident state protocol status context error started-at]}]
  (let [start (or started-at (now))
        entity (cond-> {:protocol.run/id (UUID/randomUUID)
                        :protocol.run/ident ident
                        :protocol.run/status status
                        :protocol.run/started-at (Date/from start)
                        :protocol.run/completed-at (Date/from (now))
                        :protocol.run/context (pr-str context)
                        :protocol.run/steps (pr-str (:steps state))}
                (:protocol/invariants protocol) (assoc :protocol.run/invariants (:protocol/invariants protocol))
                (seq (:work-completed state)) (assoc :protocol.run/work-tracks (vec (:work-completed state)))
                (seq (:locks-held state)) (assoc :protocol.run/locks (vec (:locks-held state)))
                 error (assoc :protocol.run/error (pr-str {:message (.getMessage error)})))]
    (d/transact conn {:tx-data [entity]})
    entity))

(defn run!
  "Executes a ProtocolDefinition by orchestrating the declared steps."
  [{:keys [conn context permissions instrumentation]
    :as opts}]
  (let [ident (:protocol/ident opts)
        _ (when-not conn
            (throw (ex-info "Missing Datomic connection" {:type :protocol/missing-conn})))
        _ (when-not ident
            (throw (ex-info "Missing protocol ident" {:type :protocol/missing-ident})))
        db (d/db conn)
        protocol (ensure-protocol db ident)
        steps (:protocol/steps protocol)
        log! (instrumentation->log-fn instrumentation)
        started-at (now)
        permissions (set (or permissions #{}))
        context (assoc (or context {}) :protocol/ident ident)
        state* (atom {:locks-held #{}
                      :work-completed #{}
                      :steps []
                      :step-results {}})]
    (log! :protocol/start {:protocol ident :context context})
    (try
      (reduce
       (fn [acc-context step]
         (let [state @state*]
           (if (should-run? acc-context protocol state (:step/when step))
             (do
               (ensure-lock-preconditions! state protocol step)
               (let [resolved-config (resolve-config (:step/config step) acc-context protocol state)
                     step-log (fn [event payload]
                                (log! event (assoc payload :step (:step/id step))))
                     result (actions/execute!
                             {:conn conn
                              :action/ident (:step/action step)
                              :config resolved-config
                              :permissions permissions
                              :context (assoc acc-context :protocol/step (:step/id step))
                              :instrumentation {:log-fn step-log}})]
                 (reset! state* (apply-step-success state step (:result result) resolved-config))
                 acc-context))
             (do
               (reset! state* (mark-step-skipped state step))
               acc-context))))
       context
       steps)
      (let [state @state*]
        (enforce-work-tracks! state protocol)
        (enforce-locks-released! state)
        (record-run! conn {:ident ident
                           :state state
                           :protocol protocol
                           :status :status/succeeded
                           :context context
                           :started-at started-at})
        (log! :protocol/success {:protocol ident :steps (:steps state)})
        {:protocol/ident ident
         :status :status/succeeded
         :steps (:steps state)
         :step-results (:step-results state)
         :work-tracks (:work-completed state)})
      (catch Throwable t
        (let [state @state*]
          (record-run! conn {:ident ident
                             :state state
                             :protocol protocol
                             :status :status/failed
                             :context context
                             :started-at started-at
                             :error t}))
        (log! :protocol/failure {:protocol ident :error (.getMessage t)})
        (throw t)))))
