(ns dev.list-missions
  "Operator helper that prints queue-grouped ready missions (SYSTEM_SPEC.md §3.6)."
  (:require
   [clojure.string :as str]
   [intuition.sfs.missions.runtime :as missions]))

(defn- parse-queues
  [args]
  (mapv keyword args))

(defn- group-by-queue
  [missions]
  (->> missions
       (mapcat (fn [mission]
                 (let [queues (or (seq (:mission/queue-tags mission))
                                  [:mission.queue/unspecified])]
                   (for [queue queues]
                     [queue mission]))))
       (group-by first)
       (into {} (map (fn [[queue entries]]
                       [queue (map second entries)])))))

(defn- priority-label
  [mission]
  (-> mission :mission/priority name (str/replace "mission.priority/" "P") str/upper-case))

(defn- describe-mission
  [mission]
  (let [blocked? (:mission/blocked? mission)
        conflicts (:mission/conflicts mission)]
    (str (format "[%s] %s – %s"
                 (priority-label mission)
                 (:mission/id mission)
                 (:mission/title mission))
         (when blocked?
           (str " (blocked by "
                (str/join ", " (map name conflicts))
                ")")))))

(defn print-ready!
  [queues]
  (let [opts (cond-> {}
                (seq queues) (assoc :queue/tags queues))
        {:mission/keys [list active-queues]} (missions/list-ready-missions opts)
        grouped (group-by-queue list)]
    (if (empty? list)
      (println "No ready missions match the provided filters.")
      (doseq [queue (sort (keys grouped))]
        (println (format "Queue %s%s"
                         (name queue)
                         (if (and active-queues (active-queues queue))
                           " (active)"
                           "")))
        (doseq [mission (grouped queue)]
          (println "  " (describe-mission mission)))
        (println)))))

(defn -main
  [& queue-idents]
  (print-ready! (parse-queues queue-idents)))
