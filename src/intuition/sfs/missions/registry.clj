(ns intuition.sfs.missions.registry
  "Pure helpers for querying the mission catalog (SYSTEM_SPEC.md §3.6–§3.12)."
  (:require
   [clojure.set :as set]))

(def ^:private priority-order
  [:mission.priority/p0
   :mission.priority/p1
   :mission.priority/p2
   :mission.priority/p3])

(def ^:private priority-rank
  (zipmap priority-order (range)))

(def ^:private blocking-statuses
  #{:mission.status/in-progress
    :mission.status/revision
    :mission.status/awaiting-review})

(defn queue-tags
  "Normalizes queue tags from a mission record into a vector."
  [mission]
  (vec (or (:mission/queue-tags mission) [])))

(defn active-queues
  "Returns a set of queues currently occupied by non-ready missions."
  [missions]
  (->> missions
       (filter #(blocking-statuses (:mission/status %)))
       (mapcat queue-tags)
       set))

(defn- priority-weight
  [mission]
  (get priority-rank (:mission/priority mission)
       (count priority-order)))

(defn- queue-filter
  [queue-tags]
  (set (or queue-tags [])))

(defn- queue-match?
  [mission filter-tags]
  (or (empty? filter-tags)
      (seq (set/intersection (set (queue-tags mission)) filter-tags))))

(defn- summarize
  [mission active-queues]
  (let [queues (queue-tags mission)
        conflicts (vec (sort (set/intersection (set queues) active-queues)))]
    (-> (select-keys mission [:mission/id
                              :mission/title
                              :mission/summary
                              :mission/category
                              :mission/priority
                              :mission/status
                              :mission/spec-section
                              :mission/protocol
                              :mission/protocol-version
                              :mission/work-tracks
                              :mission/tests
                              :mission/prerequisites
                              :mission/owner])
        (assoc :mission/queue-tags queues
               :mission/conflicts conflicts
               :mission/blocked? (boolean (seq conflicts))))))

(defn ready-missions
  "Filters mission records down to :mission.status/ready entries and enriches them
  with queue + conflict metadata so operators can group the backlog by §3.6."
  [{:keys [missions queue-tags] :as opts}]
  (let [filters (queue-filter queue-tags)
        occupied (set (or (:active-queues opts) (active-queues missions)))]
    (->> missions
         (filter #(= :mission.status/ready (:mission/status %)))
         (filter #(queue-match? % filters))
         (map #(summarize % occupied))
         (sort-by (juxt priority-weight :mission/id))
         vec)))

(defn- find-ready-mission
  [summaries mission-id]
  (some #(when (= mission-id (:mission/id %)) %) summaries))

(defn- first-unblocked
  [summaries]
  (first (remove :mission/blocked? summaries)))

(defn select-startable
  "Finds the mission to start based on priority + queue filters. Throws when
  nothing matches the queue constraint or when conflicts are detected."
  [{:keys [missions mission-id queue-tags active-queues]}]
  (let [summaries (ready-missions {:missions missions
                                   :queue-tags queue-tags
                                   :active-queues active-queues})
        chosen (if mission-id
                 (find-ready-mission summaries mission-id)
                 (or (first-unblocked summaries)
                     (first summaries)))]
    (cond
      (nil? chosen)
      (throw (ex-info "No ready missions found for the supplied queue filter"
                      {:type :mission.registry/not-found
                       :queue/tags (vec (or queue-tags []))}))

      (:mission/blocked? chosen)
      (throw (ex-info "Mission is blocked by active queues"
                      {:type :mission.registry/conflict
                       :mission/id (:mission/id chosen)
                       :mission/conflicts (:mission/conflicts chosen)}))

      :else
      chosen)))
