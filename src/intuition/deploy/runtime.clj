(ns intuition.deploy.runtime
  "Deployment orchestration per SYSTEM_SPEC §§6.3, 8 – keeps mission-scoped
  environment state in memory and emits replayable evidence files under
  missions/logs/<mission>/deploy-*.edn."
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]
   [intuition.sfs.env.bootstrap :as bootstrap])
  (:import
   (java.time Instant)
   (java.util Date)))

(defonce ^:private env-state
  (atom {:environments {}
         :evidence {}}))

(defn- now [] (Instant/now))

(defn- ensure-present
  [value message data]
  (if (or (nil? value)
          (and (string? value) (str/blank? value)))
    (throw (ex-info message data))
    value))

(defn- mission-key
  [mission-id]
  (-> mission-id
      (ensure-present "mission/id is required for deployment actions"
                      {:field :mission/id})
      str))

(defn reset-state!
  "Clears cached deployment state and evidence queues (used by tests)."
  []
  (reset! env-state {:environments {} :evidence {}}))

(defn environment-state
  "Returns the tracked state for an environment ident (used in tests)."
  [env-ident]
  (get-in @env-state [:environments env-ident]))

(defn consume-evidence!
  "Returns and clears recorded deployment artifacts for the mission."
  [mission-id]
  (let [mission-key (mission-key mission-id)
        artifacts (get-in @env-state [:evidence mission-key])]
    (swap! env-state update :evidence dissoc mission-key)
    (vec (or artifacts []))))

(defn- slot-order
  [environment]
  (let [slots (vec (or (:deploy.environment/slots environment)
                       [:blue :green]))]
    (when-not (seq slots)
      (throw (ex-info "Deployment environment must declare at least one slot."
                      {:deploy.environment/ident (:deploy.environment/ident environment)})))
    (doseq [slot slots]
      (when-not (keyword? slot)
        (throw (ex-info "Slots must be keywords."
                        {:deploy.environment/ident (:deploy.environment/ident environment)
                         :deploy/slot slot}))))
    slots))

(defn- ensure-slot
  [slots slot]
  (when-not (keyword? slot)
    (throw (ex-info "Deployment slot is required."
                    {:deploy/slot slot})))
  (when-not (some #{slot} slots)
    (throw (ex-info "Unknown deployment slot."
                    {:deploy/slot slot
                     :deploy/slots slots})))
  slot)

(defn- slot->traffic
  [slots slot]
  (let [slot (ensure-slot slots slot)]
    (into {}
          (map (fn [candidate]
                 [candidate (if (= candidate slot) 100 0)])
               slots))))

(defn- align-traffic
  [traffic slots]
  (let [traffic (or traffic {})]
    (into {}
          (map (fn [slot]
                 [slot (int (max 0 (long (or (get traffic slot) 0))))])
               slots))))

(defn- base-environment
  [environment slots]
  (let [active (or (:deploy.environment/active-slot environment)
                   (first slots))]
    {:environment environment
     :slots slots
     :active-slot (ensure-slot slots active)
     :previous-slot nil
     :traffic (slot->traffic slots active)
     :cycles {}}))

(defn- ensure-environment
  [candidate]
  (cond
    (map? candidate)
    (do
      (ensure-present (:deploy.environment/ident candidate)
                      "deploy.environment/ident is required"
                      {:deploy/environment candidate})
      candidate)
    (keyword? candidate)
    (or (:environment (environment-state candidate))
        (throw (ex-info "Environment definition missing from runtime state."
                        {:deploy.environment/ident candidate})))
    :else
    (throw (ex-info "deploy/cycle must provide an environment map or ident."
                    {:deploy/environment candidate}))))

(defn- ensure-environment-state!
  [environment]
  (let [ident (:deploy.environment/ident environment)
        _ (when-not (keyword? ident)
            (throw (ex-info "deploy.environment/ident must be a keyword."
                            {:deploy/environment environment})))
        slots (slot-order environment)]
    (get-in
     (swap! env-state
            (fn [state]
              (let [entry (get-in state [:environments ident])]
                (if entry
                  (let [updated (-> entry
                                    (assoc :environment environment)
                                    (assoc :slots slots)
                                    (update :traffic align-traffic slots)
                                    (update :active-slot #(if (some #{%} slots) % (first slots)))
                                    (update :previous-slot #(when (some #{%} slots) %)))]
                    (assoc-in state [:environments ident] updated))
                  (assoc-in state [:environments ident]
                            (base-environment environment slots))))))
     [:environments ident])))

(defn- update-env!
  [env-ident f & args]
  (let [result (swap! env-state
                      (fn [state]
                        (let [entry (get-in state [:environments env-ident])]
                          (when-not entry
                            (throw (ex-info "Deployment environment state is missing."
                                            {:deploy.environment/ident env-ident})))
                          (assoc-in state [:environments env-ident]
                                    (apply f entry args)))))]
    (get-in result [:environments env-ident])))

(def ^:private supported-strategies
  #{:deploy.strategy/blue-green
    :deploy.strategy/canary
    :deploy.strategy/rollback})

(defn- ensure-strategy
  [cycle]
  (let [strategy (:deploy.cycle/strategy cycle)]
    (when-not (supported-strategies strategy)
      (throw (ex-info "Unknown deployment strategy."
                      {:deploy.cycle/id (:deploy.cycle/id cycle)
                       :deploy.cycle/strategy strategy})))
    strategy))

(defn- ensure-cycle
  [cycle]
  (when-not (map? cycle)
    (throw (ex-info "deploy/cycle must be a dictionary map."
                    {:deploy/cycle cycle})))
  (ensure-present (:deploy.cycle/id cycle)
                  "deploy.cycle/id is required"
                  {:deploy/cycle cycle})
  cycle)

(defn- ensure-cycle-entry
  [env-entry cycle-id]
  (or (get-in env-entry [:cycles cycle-id])
      (throw (ex-info "Deployment cycle state is missing."
                      {:deploy.cycle/id cycle-id
                       :deploy.environment/ident (get-in env-entry [:environment :deploy.environment/ident])}))))

(defn- ensure-build
  [build]
  (cond
    (map? build)
    (let [id (-> (:deploy.build/id build)
                 (ensure-present "deploy.build/id is required" {:deploy/build build})
                 str)]
      (-> build
          (assoc :deploy.build/id id)
          (select-keys [:deploy.build/id
                        :deploy.build/artifact
                        :deploy.build/checksum
                        :deploy.build/commit
                        :deploy.build/tests
                        :deploy.build/meta])))
    (string? build)
    {:deploy.build/id (str (ensure-present build "deploy.build/id is required" {}))}
    (keyword? build)
    {:deploy.build/id (name build)}
    :else
    (throw (ex-info "Deployment cycles must reference a build artifact."
                    {:deploy/build build}))))

(defn- build-summary
  [build]
  (when build
    (select-keys build [:deploy.build/id
                        :deploy.build/artifact
                        :deploy.build/checksum
                        :deploy.build/commit
                        :deploy.build/tests
                        :deploy.build/meta])))

(defn- environment-summary
  [entry]
  (-> (:environment entry)
      (assoc :deploy/active-slot (:active-slot entry)
             :deploy/previous-slot (:previous-slot entry)
             :deploy/traffic (:traffic entry))))

(defn- cycle-summary
  [cycle]
  (select-keys cycle [:deploy.cycle/id
                      :deploy.cycle/strategy
                      :deploy.cycle/status
                      :deploy.cycle/slot
                      :deploy.cycle/traffic
                      :deploy.cycle/approvals
                      :deploy.cycle/health
                      :deploy.cycle/evidence
                      :deploy.cycle/environment]))

(defn- idle-slot
  [{:keys [slots active-slot]}]
  (or (first (remove #{active-slot} slots))
      active-slot))

(defn- stage-cycle-entry
  [cycle environment build slot approvals]
  (cond-> {:deploy.cycle/id (:deploy.cycle/id cycle)
           :deploy.cycle/strategy (:deploy.cycle/strategy cycle)
           :deploy.cycle/environment environment
           :deploy.cycle/build build
           :deploy.cycle/status :deploy.cycle.status/staged
           :deploy.cycle/slot slot
           :deploy.cycle/approvals approvals}
    (:deploy.cycle/traffic cycle) (assoc :deploy.cycle/traffic (:deploy.cycle/traffic cycle))
    (:deploy.cycle/health cycle) (assoc :deploy.cycle/health (:deploy.cycle/health cycle))))

(defn- validate-traffic
  [slots traffic]
  (when-not (map? traffic)
    (throw (ex-info "Canary traffic must be a map of slot->percentage."
                    {:deploy/traffic traffic})))
  (when (empty? traffic)
    (throw (ex-info "Canary traffic configuration is required."
                    {:deploy/traffic traffic})))
  (let [slot-set (set slots)
        normalized (reduce (fn [acc [slot pct]]
                             (when-not (slot-set slot)
                               (throw (ex-info "Traffic references unknown slot."
                                               {:deploy/slot slot
                                                :deploy/slots slots})))
                             (when-not (integer? pct)
                               (throw (ex-info "Traffic weights must be integers."
                                               {:deploy/slot slot
                                                :deploy/weight pct})))
                             (when (neg? pct)
                               (throw (ex-info "Traffic weights cannot be negative."
                                               {:deploy/slot slot
                                                :deploy/weight pct})))
                             (assoc acc slot pct))
                           {}
                           traffic)
        total (reduce + (vals normalized))]
    (when-not (= 100 total)
      (throw (ex-info "Canary traffic must sum to 100."
                      {:deploy/traffic traffic
                       :deploy/total total})))
    (reduce (fn [acc slot]
              (assoc acc slot (get normalized slot 0)))
            {}
            slots)))

(defn- ensure-approvals!
  [environment approvals cycle-id]
  (let [required (set (or (:deploy.environment/required-approvals environment) []))
        provided (set (map :deploy.approval/role approvals))
        missing (seq (set/difference required provided))]
    (when missing
      (throw (ex-info "Missing deployment approvals"
                      {:deploy.cycle/id cycle-id
                       :deploy.environment/ident (:deploy.environment/ident environment)
                       :approvals/missing (vec missing)})))))

(defn- mission-log-dir
  [mission-id]
  (doto (io/file "missions" "logs" (bootstrap/sanitize-fragment mission-id))
    .mkdirs))

(defn- strategy-label
  [strategy]
  (case strategy
    :deploy.strategy/blue-green "blue/green"
    :deploy.strategy/canary "canary"
    :deploy.strategy/rollback "rollback"
    (name strategy)))

(defn- strategy-prefix
  [strategy]
  (case strategy
    :deploy.strategy/blue-green "deploy-blue-green"
    :deploy.strategy/canary "deploy-canary"
    :deploy.strategy/rollback "deploy-rollback"
    "deploy"))

(defn- evidence-file
  [mission-id cycle-id strategy]
  (io/file (mission-log-dir mission-id)
           (str (strategy-prefix strategy)
                "-"
                (bootstrap/sanitize-fragment cycle-id)
                ".edn")))

(defn- record-evidence!
  [mission-id env-entry cycle-state]
  (let [mission-id (mission-key mission-id)
        cycle-id (:deploy.cycle/id cycle-state)
        file (evidence-file mission-id cycle-id (:deploy.cycle/strategy cycle-state))
        payload (cond-> {:mission/id mission-id
                         :recorded-at (Date/from (now))
                         :deploy/environment (:environment env-entry)
                         :deploy/state {:active-slot (:active-slot env-entry)
                                        :previous-slot (:previous-slot env-entry)
                                        :traffic (:traffic env-entry)}
                         :deploy/cycle (cycle-summary cycle-state)}
                  (:deploy.cycle/build cycle-state)
                  (assoc :deploy/build (build-summary (:deploy.cycle/build cycle-state))))]
    (spit file (pr-str payload))
    (let [artifact {:path (.getCanonicalPath file)
                    :label (format "Deploy %s %s"
                                   (strategy-label (:deploy.cycle/strategy cycle-state))
                                   cycle-id)
                    :type :artifact.type/deploy
                    :deploy/cycle-id cycle-id
                    :deploy/strategy (:deploy.cycle/strategy cycle-state)}]
      (swap! env-state update-in [:evidence mission-id] (fnil conj []) artifact)
      artifact)))

(defn- action-result
  ([env-entry cycle-state]
   (action-result env-entry cycle-state nil))
  ([env-entry cycle-state evidence-path]
   (cond-> {:action/status :status/ok
            :deploy/environment (environment-summary env-entry)
            :deploy/cycle (cycle-summary cycle-state)}
     (:deploy.cycle/build cycle-state) (assoc :deploy/build (build-summary (:deploy.cycle/build cycle-state)))
     evidence-path (assoc :deploy/evidence evidence-path))))

(defn- apply-active-slot
  [env-entry slot]
  (let [slots (:slots env-entry)
        slot (ensure-slot slots slot)]
    (-> env-entry
        (assoc :previous-slot (:active-slot env-entry))
        (assoc :active-slot slot)
        (assoc :traffic (slot->traffic slots slot)))))

(defn- stage-slot
  [strategy env-entry cycle]
  (case strategy
    :deploy.strategy/blue-green (idle-slot env-entry)
    :deploy.strategy/canary (let [slot (or (:deploy.cycle/slot cycle) :canary)]
                              (ensure-slot (:slots env-entry) slot))
    (throw (ex-info "Staging is not supported for this strategy."
                    {:deploy.cycle/strategy strategy
                     :deploy.cycle/id (:deploy.cycle/id cycle)}))))

(defn stage-build-action
  [{:keys [config]}]
  (let [{:mission/keys [id]
         :deploy/keys [cycle]} config
        _ (mission-key id)
        cycle (ensure-cycle cycle)
        environment (ensure-environment (:deploy.cycle/environment cycle))
        env-entry (ensure-environment-state! environment)
        env-ident (:deploy.environment/ident environment)
        strategy (ensure-strategy cycle)
        build (ensure-build (:deploy.cycle/build cycle))
        approvals (vec (or (:deploy.cycle/approvals cycle) []))
        _ (ensure-approvals! environment approvals (:deploy.cycle/id cycle))
        slot (stage-slot strategy env-entry cycle)
        cycle-state (stage-cycle-entry cycle environment build slot approvals)
        updated-entry (update-env! env-ident
                                   (fn [entry]
                                     (-> entry
                                         (assoc :environment environment)
                                         (assoc-in [:cycles (:deploy.cycle/id cycle)] cycle-state))))]
    (action-result updated-entry (get-in updated-entry [:cycles (:deploy.cycle/id cycle)]))))

(defn flip-blue-green-action
  [{:keys [config]}]
  (let [{:mission/keys [id]
         :deploy/keys [cycle]} config
        mission-id (mission-key id)
        cycle (ensure-cycle cycle)
        environment (ensure-environment (:deploy.cycle/environment cycle))
        env-entry (ensure-environment-state! environment)
        env-ident (:deploy.environment/ident environment)
        cycle-id (:deploy.cycle/id cycle)
        cycle-state (ensure-cycle-entry env-entry cycle-id)]
    (when-not (= :deploy.strategy/blue-green (:deploy.cycle/strategy cycle-state))
      (throw (ex-info "Flip only applies to blue/green cycles."
                      {:deploy.cycle/id cycle-id})))
    (when-not (= :deploy.cycle.status/staged (:deploy.cycle/status cycle-state))
      (throw (ex-info "Cycle must be staged before flipping traffic."
                      {:deploy.cycle/id cycle-id
                       :deploy.cycle/status (:deploy.cycle/status cycle-state)})))
    (let [next-entry (apply-active-slot env-entry (:deploy.cycle/slot cycle-state))
          staged-cycle (-> cycle-state
                           (assoc :deploy.cycle/status :deploy.cycle.status/active)
                           (assoc :deploy.cycle/traffic (:traffic next-entry)))
          entry-with-cycle (assoc-in next-entry [:cycles cycle-id] staged-cycle)
          artifact (record-evidence! mission-id entry-with-cycle staged-cycle)
          final-cycle (assoc staged-cycle :deploy.cycle/evidence (:path artifact))
          saved-entry (update-env! env-ident (fn [_]
                                               (assoc-in entry-with-cycle [:cycles cycle-id] final-cycle)))]
      (action-result saved-entry (get-in saved-entry [:cycles cycle-id]) (:path artifact)))))

(defn start-canary-action
  [{:keys [config]}]
  (let [{:mission/keys [id]
         :deploy/keys [cycle]} config
        _ (mission-key id)
        cycle (ensure-cycle cycle)
        environment (ensure-environment (:deploy.cycle/environment cycle))
        env-entry (ensure-environment-state! environment)
        env-ident (:deploy.environment/ident environment)
        cycle-id (:deploy.cycle/id cycle)
        cycle-state (ensure-cycle-entry env-entry cycle-id)]
    (when-not (= :deploy.strategy/canary (:deploy.cycle/strategy cycle-state))
      (throw (ex-info "Canary start requires a canary strategy."
                      {:deploy.cycle/id cycle-id})))
    (when-not (= :deploy.cycle.status/staged (:deploy.cycle/status cycle-state))
      (throw (ex-info "Canary must be staged before traffic ramps."
                      {:deploy.cycle/id cycle-id
                       :deploy.cycle/status (:deploy.cycle/status cycle-state)})))
    (let [traffic (validate-traffic (:slots env-entry) (:deploy.cycle/traffic cycle))
          next-cycle (-> cycle-state
                         (assoc :deploy.cycle/status :deploy.cycle.status/canary)
                         (assoc :deploy.cycle/traffic traffic))
          updated-entry (update-env! env-ident
                                     (fn [entry]
                                       (-> entry
                                           (assoc :traffic traffic)
                                           (assoc-in [:cycles cycle-id] next-cycle))))]
      (action-result updated-entry (get-in updated-entry [:cycles cycle-id])))))

(defn- ensure-health
  [health cycle-id]
  (if (map? health)
    health
    (throw (ex-info "Canary stop requires health metrics."
                    {:deploy.cycle/id cycle-id
                     :deploy.cycle/health health}))))

(defn stop-canary-action
  [{:keys [config]}]
  (let [{:mission/keys [id]
         :deploy/keys [cycle]} config
        _ (mission-key id)
        cycle (ensure-cycle cycle)
        environment (ensure-environment (:deploy.cycle/environment cycle))
        env-entry (ensure-environment-state! environment)
        env-ident (:deploy.environment/ident environment)
        cycle-id (:deploy.cycle/id cycle)
        cycle-state (ensure-cycle-entry env-entry cycle-id)]
    (when-not (= :deploy.strategy/canary (:deploy.cycle/strategy cycle-state))
      (throw (ex-info "Canary stop requires a canary strategy."
                      {:deploy.cycle/id cycle-id})))
    (when-not (= :deploy.cycle.status/canary (:deploy.cycle/status cycle-state))
      (throw (ex-info "Canary slice must be active before stopping."
                      {:deploy.cycle/id cycle-id
                       :deploy.cycle/status (:deploy.cycle/status cycle-state)})))
    (let [health (ensure-health (:deploy.cycle/health cycle) cycle-id)
          steady-traffic (slot->traffic (:slots env-entry) (:active-slot env-entry))
          next-cycle (-> cycle-state
                         (assoc :deploy.cycle/status :deploy.cycle.status/canary-complete)
                         (assoc :deploy.cycle/health health)
                         (assoc :deploy.cycle/traffic steady-traffic))
          updated-entry (update-env! env-ident
                                     (fn [entry]
                                       (-> entry
                                           (assoc :traffic steady-traffic)
                                           (assoc-in [:cycles cycle-id] next-cycle))))]
      (action-result updated-entry (get-in updated-entry [:cycles cycle-id])))))

(defn promote-action
  [{:keys [config]}]
  (let [{:mission/keys [id]
         :deploy/keys [cycle]} config
        mission-id (mission-key id)
        cycle (ensure-cycle cycle)
        environment (ensure-environment (:deploy.cycle/environment cycle))
        env-entry (ensure-environment-state! environment)
        env-ident (:deploy.environment/ident environment)
        cycle-id (:deploy.cycle/id cycle)
        cycle-state (ensure-cycle-entry env-entry cycle-id)]
    (when-not (= :deploy.strategy/canary (:deploy.cycle/strategy cycle-state))
      (throw (ex-info "Promotion only applies to canary cycles."
                      {:deploy.cycle/id cycle-id})))
    (when-not (= :deploy.cycle.status/canary-complete (:deploy.cycle/status cycle-state))
      (throw (ex-info "Canary must complete before promotion."
                      {:deploy.cycle/id cycle-id
                       :deploy.cycle/status (:deploy.cycle/status cycle-state)})))
    (let [next-entry (apply-active-slot env-entry (:deploy.cycle/slot cycle-state))
          staged-cycle (-> cycle-state
                           (assoc :deploy.cycle/status :deploy.cycle.status/promoted)
                           (assoc :deploy.cycle/traffic (:traffic next-entry)))
          entry-with-cycle (assoc-in next-entry [:cycles cycle-id] staged-cycle)
          artifact (record-evidence! mission-id entry-with-cycle staged-cycle)
          final-cycle (assoc staged-cycle :deploy.cycle/evidence (:path artifact))
          saved-entry (update-env! env-ident (fn [_]
                                               (assoc-in entry-with-cycle [:cycles cycle-id] final-cycle)))]
      (action-result saved-entry (get-in saved-entry [:cycles cycle-id]) (:path artifact)))))

(defn rollback-action
  [{:keys [config]}]
  (let [{:mission/keys [id]
         :deploy/keys [cycle]} config
        mission-id (mission-key id)
        cycle (ensure-cycle cycle)
        environment (ensure-environment (:deploy.cycle/environment cycle))
        env-entry (ensure-environment-state! environment)
        env-ident (:deploy.environment/ident environment)
        cycle-id (:deploy.cycle/id cycle)
        approvals (vec (or (:deploy.cycle/approvals cycle) []))]
    (ensure-approvals! environment approvals cycle-id)
    (let [target (:previous-slot env-entry)]
      (when-not target
        (throw (ex-info "No previous slot recorded for rollback."
                        {:deploy.environment/ident env-ident})))
      (let [next-entry (apply-active-slot env-entry target)
            cycle-state {:deploy.cycle/id cycle-id
                         :deploy.cycle/strategy :deploy.strategy/rollback
                         :deploy.cycle/environment environment
                         :deploy.cycle/status :deploy.cycle.status/rolled-back
                         :deploy.cycle/approvals approvals
                         :deploy.cycle/slot (:active-slot next-entry)
                         :deploy.cycle/traffic (:traffic next-entry)}
            entry-with-cycle (assoc-in next-entry [:cycles cycle-id] cycle-state)
            artifact (record-evidence! mission-id entry-with-cycle cycle-state)
            final-cycle (assoc cycle-state :deploy.cycle/evidence (:path artifact))
            saved-entry (update-env! env-ident (fn [_]
                                                 (assoc-in entry-with-cycle [:cycles cycle-id] final-cycle)))]
        (action-result saved-entry (get-in saved-entry [:cycles cycle-id]) (:path artifact))))))
