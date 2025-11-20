(ns intuition.sfs.missions.state-machine
  "Pure validation and lifecycle rules for missions (SYSTEM_SPEC.md §3)."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.string :as str]))

(def allowed-statuses
  #{:mission.status/draft
    :mission.status/ready
    :mission.status/in-progress
    :mission.status/revision
    :mission.status/awaiting-review
    :mission.status/abandoned
    :mission.status/done
    :mission.status/archived})

(def work-tracks
  #{:work-track/planning
    :work-track/code
    :work-track/test-functional
    :work-track/doc
    :work-track/system-map})

(defn- mission-ex
  [message mission extra]
  (ex-info message (merge {:type :mission/invalid
                           :mission/id (:mission/id mission)}
                          extra)))

(defn- non-blank-string?
  [v]
  (and (string? v) (not (str/blank? v))))

(defn- parse-scope
  [mission]
  (let [raw (:mission/scope mission)]
    (when-not (non-blank-string? raw)
      (throw (mission-ex "Mission scope is required (SYSTEM_SPEC.md §3.2)."
                         mission
                         {:field :mission/scope})))
    (let [parsed (try
                   (edn/read-string raw)
                   (catch Exception _
                     (throw (mission-ex "Mission scope must be EDN"
                                        mission
                                        {:field :mission/scope
                                         :value raw}))))]
      (when-not (map? parsed)
        (throw (mission-ex "Mission scope must be a map"
                           mission
                           {:field :mission/scope
                            :value parsed})))
      (when-not (some #(seq (get parsed %))
                      [:paths :namespaces :docs :impact])
        (throw (mission-ex "Scope must cite paths/namespaces/docs (§3.2, §3.10)."
                           mission
                           {:field :mission/scope
                            :value parsed})))
      parsed)))

(defn- ensure-work-tracks
  [mission]
  (let [tracks (vec (or (:mission/work-tracks mission) []))
        unknown (remove work-tracks tracks)]
    (when (empty? tracks)
      (throw (mission-ex "Mission requires at least one work track (§3.3)."
                         mission
                         {:field :mission/work-tracks})))
    (when (seq unknown)
      (throw (mission-ex "Mission declared unknown work tracks"
                         mission
                         {:field :mission/work-tracks
                          :unknown (vec unknown)})))
    tracks))

(defn- ensure-tests
  [mission]
  (let [tests (vec (or (:mission/tests mission) []))]
    (when (empty? tests)
      (throw (mission-ex "Mission requires acceptance tests (§3.1, P3.1)."
                         mission
                         {:field :mission/tests})))
    (when (some (complement non-blank-string?) tests)
      (throw (mission-ex "Mission tests must be non-blank strings"
                         mission
                         {:field :mission/tests})))
    (when-let [dupes (->> tests frequencies (filter (fn [[_ n]] (> n 1))) (map first) seq)]
      (throw (mission-ex "Mission tests must be unique"
                         mission
                         {:field :mission/tests
                          :duplicates (vec dupes)})))
    tests))

(defn- ensure-deliverables
  [mission]
  (let [deliverables (vec (or (:mission/deliverables mission) []))]
    (when (empty? deliverables)
      (throw (mission-ex "Mission requires deliverables (§3.1, §3.4)."
                         mission
                         {:field :mission/deliverables})))
    (when (some (complement non-blank-string?) deliverables)
      (throw (mission-ex "Mission deliverables must be strings"
                         mission
                         {:field :mission/deliverables})))
    (when-let [dupes (->> deliverables frequencies (filter (fn [[_ n]] (> n 1))) (map first) seq)]
      (throw (mission-ex "Mission deliverables must be unique"
                         mission
                         {:field :mission/deliverables
                          :duplicates (vec dupes)})))
    deliverables))

(defn- ensure-prerequisites
  [mission {:keys [known-missions]}]
  (let [prereqs (vec (or (:mission/prerequisites mission) []))
        known (or known-missions #{})
        mission-id (:mission/id mission)]
    (doseq [pr prereqs]
      (when (= pr mission-id)
        (throw (mission-ex "Mission cannot depend on itself"
                           mission
                           {:field :mission/prerequisites})))
      (when (and (seq known)
                 (not (contains? known pr)))
        (throw (mission-ex "Mission prerequisite does not exist"
                           mission
                           {:field :mission/prerequisites
                            :value pr}))))
    (when-let [dupes (->> prereqs frequencies (filter (fn [[_ n]] (> n 1))) (map first) seq)]
      (throw (mission-ex "Mission prerequisites must be unique"
                         mission
                         {:field :mission/prerequisites
                          :duplicates (vec dupes)})))
    prereqs))

(defn- ensure-required-field
  [mission k]
  (when (nil? (get mission k))
    (throw (mission-ex (str "Mission missing " k) mission {:field k}))))

(defn normalize
  "Validates mission metadata and returns normalized data with derived scope." 
  [mission context]
  (doseq [k [:mission/id :mission/title :mission/summary :mission/category
             :mission/priority :mission/status :mission/protocol
             :mission/protocol-version :mission/spec-section :mission/owner]]
    (ensure-required-field mission k))
  (let [status (:mission/status mission)]
    (when-not (contains? allowed-statuses status)
      (throw (mission-ex "Unknown mission status" mission {:field :mission/status}))))
  (let [scope (parse-scope mission)
        tracks (ensure-work-tracks mission)
        tests (ensure-tests mission)
        deliverables (ensure-deliverables mission)
        prereqs (ensure-prerequisites mission context)]
    (-> mission
        (assoc ::scope scope)
        (assoc ::tracks (set tracks))
        (assoc ::tests (set tests))
        (assoc ::deliverables (set deliverables))
        (assoc ::prereqs (set prereqs)))))

(def transition-rules
  {[:mission.status/draft :mission.status/ready] #{:scope :work-tracks :tests :deliverables}
   [:mission.status/draft :mission.status/abandoned] #{:justification}
   [:mission.status/ready :mission.status/in-progress] #{:locks :integration-approvals}
   [:mission.status/ready :mission.status/abandoned] #{:justification}
   [:mission.status/in-progress :mission.status/awaiting-review]
   #{:worklogs :work-track-coverage :tests-run :docs :system-map :codetype :branch}
   [:mission.status/in-progress :mission.status/revision] #{:justification}
   [:mission.status/in-progress :mission.status/abandoned] #{:justification :worklogs}
   [:mission.status/awaiting-review :mission.status/done] #{:report :approval}
   [:mission.status/awaiting-review :mission.status/revision] #{:justification}
   [:mission.status/awaiting-review :mission.status/abandoned] #{:justification}
   [:mission.status/revision :mission.status/ready] #{:scope :work-tracks}
   [:mission.status/revision :mission.status/abandoned] #{:justification}
   [:mission.status/done :mission.status/archived] #{:artifacts}
   [:mission.status/abandoned :mission.status/archived] #{:artifacts}})

(defn allowed-transition?
  [from to]
  (contains? transition-rules [from to]))

(defn- require-context
  [requirement mission context]
  (case requirement
    :scope (parse-scope mission)
    :work-tracks (ensure-work-tracks mission)
    :tests (ensure-tests mission)
    :deliverables (ensure-deliverables mission)
    :locks (when-not (seq (:locks/held context))
             (throw (mission-ex "Transition requires held locks"
                                mission {:requirement requirement})))
    :worklogs (when-not (pos? (or (get-in context [:worklogs :count]) 0))
                (throw (mission-ex "Transition requires recorded worklogs (§3.4)."
                                   mission {:requirement requirement})))
    :work-track-coverage (let [tracks (or (::tracks mission)
                                          (set (ensure-work-tracks mission)))
                               completed (set (or (get-in context [:worklogs :tracks]) #{}))]
                           (when-not (set/subset? tracks completed)
                             (throw (mission-ex "Missing worklog coverage for required tracks"
                                                mission
                                                {:requirement requirement
                                                 :required (vec tracks)
                                                 :completed (vec completed)}))))
    :tests-run (when-not (= :status/passed (get-in context [:tests :status]))
                 (throw (mission-ex "Acceptance tests must pass"
                                    mission {:requirement requirement})))
    :docs (when-not (true? (:docs/synced? context))
            (throw (mission-ex "Documentation sync required"
                               mission {:requirement requirement})))
    :system-map (when-not (true? (:system-map/refreshed? context))
                  (throw (mission-ex "System map refresh required"
                                     mission {:requirement requirement})))
    :branch (let [artifact (:branch/artifact context)]
              (when-not (and artifact (:branch/edn-path artifact))
                (throw (mission-ex "Branch snapshot artifact missing (SYSTEM_SPEC.md §6.2)."
                                   mission {:requirement requirement}))))
    :codetype (let [codetype (:codetype context)
                    artifact (:artifact codetype)
                    status (:status codetype)]
                (when-not (= :status/ok status)
                  (throw (mission-ex "CodeType validation failed"
                                     mission {:requirement requirement
                                              :status status})))
                (when-not (and (seq artifact)
                               (.exists (io/file artifact)))
                  (throw (mission-ex "CodeType validation artifact missing"
                                     mission {:requirement requirement
                                              :artifact artifact}))))
    :integration-approvals
    (let [components (vec (or (:mission/js-components mission) []))
          apis (vec (or (:mission/external-apis mission) []))]
      (when (and (seq components)
                 (not (true? (:security/js-approved? context))))
        (throw (mission-ex "JS components require recorded approvals (SYSTEM_SPEC.md §§4.8, 10.2)."
                           mission
                           {:requirement requirement
                            :missing :js})))
      (when (and (seq apis)
                 (not (true? (:security/apis-approved? context))))
        (throw (mission-ex "External API approvals missing (SYSTEM_SPEC.md §§4.8, 6, 10.2)."
                           mission
                           {:requirement requirement
                            :missing :external-apis}))))
    :report (when-not (true? (:report/submitted? context))
              (throw (mission-ex "Mission report missing"
                                 mission {:requirement requirement})))
    :approval (when-not (true? (get-in context [:approval :steward?]))
                (throw (mission-ex "Steward approval required"
                                   mission {:requirement requirement})))
    :artifacts (when-not (true? (:artifacts/captured? context))
                 (throw (mission-ex "Artifacts must be archived"
                                    mission {:requirement requirement})))
    :justification (when-not (non-blank-string? (:transition/justification context))
                     (throw (mission-ex "Transition justification required"
                                        mission {:requirement requirement})))
    nil))

(defn enforce-transition!
  [mission target context]
  (let [mission (normalize mission context)
        from (:mission/status mission)]
    (when-not (allowed-transition? from target)
      (throw (mission-ex (format "Transition %s -> %s not allowed"
                                 (name from) (name target))
                         mission
                         {:from from :to target})))
    (doseq [requirement (get transition-rules [from target])]
      (require-context requirement mission context))
    (assoc mission :mission/status target)))

(defn validate!
  [mission context]
  (normalize mission context))

(defn validate-action
  [{:keys [config]}]
  (let [mission (:mission/config config)
        context {:known-missions (:mission/known config)}]
    (validate! mission context)
    {:action/status :status/ok
     :mission/id (:mission/id mission)
     :mission/status (:mission/status mission)}))

(defn transition-action
  [{:keys [config]}]
  (let [mission (:mission/config config)
        target (:mission/target config)
        context (:mission/context config)
        updated (enforce-transition! mission target context)]
    {:action/status :status/ok
     :mission/id (:mission/id mission)
     :mission/status (:mission/status updated)}))
