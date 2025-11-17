(ns intuition.agent.spec-intake
  "Helpers for the spec-intake role. Meta Agent requires this namespace
   after orientation to manipulate the project spec programmatically."
  (:require [clojure.string :as str]
            [intuition.agent.core :as core]))

(defn spec [] (core/load-spec))
(defn save! [spec-map] (core/save-spec spec-map))
(defn protocol [] (core/load-conversation-protocol))

(defn update-field
  "Updates spec at key-path (vector of keys) by applying f with args.
   Example: (update-field [:vision :summary] (constantly "new"))"
  [key-path f & args]
  (let [cur (spec)
        updated (apply update-in cur key-path f args)]
    (save! (assoc updated :spec/status (:spec/status cur))))

(defn set-field [key-path value]
  (save! (assoc-in (spec) key-path value)))

(defn list-missing-required-fields
  "Very simple checker for empty strings/vectors on critical sections.
   Intended as a quick helper; full validation lives elsewhere."
  []
  (let [s (spec)
        checks {[:vision :summary] "Vision summary"
                [:vision :problem_statement] "Problem statement"
                [:capabilities] "Capabilities"
                [:acceptance_criteria] "Acceptance criteria"}
        empty? (fn [v]
                 (or (nil? v)
                     (and (string? v) (str/blank? v))
                     (and (coll? v) (empty? v))))]
    (->> checks
         (keep (fn [[path label]]
                 (when (empty? (get-in s path))
                   {:path path :label label})))))))

(defn summary []
  (let [s (spec)]
    {:title (get-in s [:metadata :title])
     :status (:spec/status s)
     :vision (select-keys (:vision s) [:summary :problem_statement])
     :capabilities (map (juxt :capability_id :name :priority) (:capabilities s))}))

(defn next-phase
  "Finds the first protocol phase whose required fields are still empty."
  []
  (let [proto (:phases (protocol))
        s (spec)
        empty? (fn [v]
                 (or (nil? v)
                     (and (string? v) (str/blank? v))
                     (and (coll? v) (empty? v))))]
    (some (fn [phase]
            (when (some #(empty? (get-in s %)) (:fields phase))
              phase))
          proto)))
