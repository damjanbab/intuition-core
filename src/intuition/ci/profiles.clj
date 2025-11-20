(ns intuition.ci.profiles
  "CI profile loader that exposes helpers for resolving mission-specific CI flows."
  (:require
   [intuition.dictionary :as dictionary]))

(def ^:private default-profile-ident :ci.profile/runtime-default)

(defonce ^:private profiles-cache
  (atom nil))

(defn- normalize-profile
  [profile]
  (-> profile
      (update :ci.profile/mission-types #(vec (or % [])))
      (update :ci.profile/required-tools #(vec (or % [])))
      (update :ci.profile/steps #(vec (or % [])))))

(defn- load-profiles
  []
  (mapv normalize-profile (dictionary/load-ci-profiles)))

(defn profiles
  []
  (or @profiles-cache
      (reset! profiles-cache (load-profiles))))

(defn refresh!
  []
  (reset! profiles-cache nil)
  (profiles))

(defn profile-by-ident
  [ident]
  (some #(when (= ident (:ci.profile/ident %)) %) (profiles)))

(defn profile-for-category
  [category]
  (some #(when (some #{category} (:ci.profile/mission-types %)) %) (profiles)))

(defn resolve-profile
  "Returns the CI profile map by ident or mission category. Falls back to the default profile."
  [{:keys [ident category]}]
  (or (when ident
        (profile-by-ident ident))
      (when category
        (profile-for-category category))
      (profile-by-ident default-profile-ident)))
