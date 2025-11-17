(ns agent.spec-intake
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [agent.core :as core]))

(defn- empty-value? [v]
  (or (nil? v)
      (and (string? v) (str/blank? v))
      (and (coll? v) (empty? v))))

(defn spec []
  (core/active-spec))

(defn save! [spec-map]
  (core/save-spec (core/current-spec-id) spec-map))

(defn set-field!
  "Set spec value at key-path vector."
  [key-path value]
  (save! (assoc-in (spec) key-path value)))

(defn update-field!
  [key-path f & args]
  (save! (apply update-in (spec) key-path f args)))

(defn summary []
  (let [s (spec)]
    {:title (get-in s [:metadata :title])
     :status (:spec/status s)
     :capabilities (count (:capabilities s))
     :stakeholders (count (:stakeholders s))}))

(defn conversation-phases []
  (:phases (core/load-conversation-protocol)))

(defn- keyword->path [k]
  (if-let [ns (namespace k)]
    [(keyword ns) (keyword (name k))]
    [k]))

(defn- phase-missing? [phase]
  (let [s (spec)]
    (some (fn [field]
            (let [path (if (vector? field) field (keyword->path field))]
              (empty-value? (get-in s path))))
          (:fields phase))))

(defn next-phase []
  (some #(when (phase-missing? %) %) (conversation-phases)))

(defn missing-critical-fields []
  (let [s (spec)
        critical [[:vision :summary]
                  [:vision :problem_statement]
                  [:stakeholders]
                  [:capabilities]
                  [:acceptance_criteria]]]
    (->> critical
         (keep (fn [path]
                 (when (empty-value? (get-in s path))
                   {:path path :label (str/join "/" (map name path))}))))))

(defn orientation
  "Prints orientation for spec-intake role using snapshot map."
  [snapshot]
  (let [{:keys [spec spec-id conversation]} snapshot
        title (get-in spec [:metadata :title])
        status (:spec/status spec)
        phase (next-phase)]
    (println "== Spec Intake Role ==")
    (println "Spec:" spec-id "| Title:" (or title "(untitled)") "| Status:" status)
    (println "Stakeholders:" (count (:stakeholders spec))
             "Capabilities:" (count (:capabilities spec)))
    (when-let [m (missing-critical-fields)]
      (doseq [{:keys [label]} m]
        (println "- Missing critical field:" label)))
    (if phase
      (do (println "Next phase:" (:phase/id phase) "-" (:description phase))
          (println "Fields to fill:" (:fields phase))
          (println "Example prompts:")
          (doseq [p (:example_prompts phase)]
            (println "  " p)))
      (println "All phases filled; proceed to lint/approval."))
    (println)
    (println "Helpers available:")
    (println "  (agent.spec-intake/spec)            ;; view current spec map")
    (println "  (agent.spec-intake/set-field! [:vision :summary] \"text\")")
    (println "  (agent.spec-intake/update-field! [:capabilities] conj new-cap)")
    (println "  (agent.spec-intake/summary)        ;; quick counts")
    (println "  (agent.spec-intake/next-phase)     ;; see next incomplete phase")
    (println "  (agent.core/system-snapshot spec-id) ;; inspect full state (spec/schema/protocol)")
    (println "  (agent.core/schema)                ;; view field instructions")
    (println)
    (println "Remember: gather information from the owner, update the spec via these helpers,")
    (println "rerun (agent.spec-intake/next-phase) until everything is filled, then signal for validation.")))
