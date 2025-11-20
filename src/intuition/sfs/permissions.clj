(ns intuition.sfs.permissions
  "Loads the permission + role dictionary bundle so runtime checks stay aligned
   with SYSTEM_SPEC.md §§2.1–2.2, §5, and §6."
  (:require
   [clojure.set :as set]
   [intuition.dictionary :as dictionary]))

(def ^:private default-roles [:role/dictionary-engineer])

(defn- classify-bundle
  [entries]
  (reduce (fn [acc entry]
            (case (:entity/type entry)
              :permission/definition (update acc :permissions conj entry)
              :role/definition (update acc :roles conj entry)
              acc))
          {:permissions [] :roles []}
          entries))

(defonce ^:private bundle
  (delay
    (let [{:keys [permissions roles]} (classify-bundle (dictionary/load-permissions))]
      {:permissions (into {}
                          (map (juxt :permission/ident identity))
                          permissions)
       :roles (into {}
                    (map (fn [role]
                           (let [entry (update role :role/permissions #(vec (or % [])))]
                             [(:role/ident entry) entry])))
                    roles)})))

(defn permission-definitions
  "Returns a map of permission ident → definition map."
  []
  (:permissions @bundle))

(defn permission
  [ident]
  (get (permission-definitions) ident))

(defn permission-defined?
  [ident]
  (contains? (permission-definitions) ident))

(defn all-permissions
  []
  (set (keys (permission-definitions))))

(defn role-definitions
  []
  (:roles @bundle))

(defn role
  [ident]
  (get (role-definitions) ident))

(defn role-permissions
  "Returns the permission set for the given role ident."
  [ident]
  (if-let [role (role ident)]
    (set (:role/permissions role))
    #{}))

(defn permissions-for-roles
  "Expands the union of permissions for the supplied roles."
  [roles]
  (apply set/union #{} (map role-permissions roles)))

(def default-permissions
  "Union of the baseline roles that missions require (dictionary work + deploy escorts)."
  (permissions-for-roles default-roles))

(defn assert-defined!
  "Throws if `ident` has no dictionary definition."
  [ident]
  (when-not (permission-defined? ident)
    (throw (ex-info "Unknown permission" {:permission ident})))
  ident)

(defn normalize
  "Adds baseline permissions to the supplied collection, returning a set."
  [permissions]
  (let [provided (set permissions)]
    (doseq [perm provided]
      (assert-defined! perm))
    (set/union default-permissions provided)))
