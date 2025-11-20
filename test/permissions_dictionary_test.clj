(ns permissions-dictionary-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [intuition.dictionary :as dictionary]
   [intuition.sfs.permissions :as perms]))

(defn- read-resource
  [path]
  (some-> path io/resource slurp edn/read-string))

(defn- action-permission-set
  []
  (let [standard (dictionary/load-actions)
        mission-actions (filter :action/ident (dictionary/load-missions))
        env-actions (or (read-resource "dictionary/actions_env.edn") [])
        extra (concat standard mission-actions env-actions)]
    (->> extra
         (filter :action/ident)
         (mapcat :action/permissions)
         (remove nil?)
         set)))

(defn- role-permission-set
  []
  (->> (perms/role-definitions)
       vals
       (map :role/permissions)
       (map set)
       (apply set/union #{})))

(deftest every-action-permission-is-defined
  (let [referenced (action-permission-set)
        defined (perms/all-permissions)]
    (is (set/subset? referenced defined)
        (str "Undefined permissions found: "
             (pr-str (set/difference referenced defined))))))

(deftest every-permission-is-assigned-to-a-role
  (let [defined (perms/all-permissions)
        assigned (role-permission-set)]
    (is (set/subset? defined assigned)
        (str "Permissions missing from role assignments: "
             (pr-str (set/difference defined assigned))))))

(deftest default-permissions-cover-deployments
  (let [default perms/default-permissions]
    (testing "deploy manage requires explicit grant"
      (is (not (contains? default :permission/deploy.manage))))
    (testing "security approvals require explicit grant"
      (is (not (contains? default :permission/security.approve))))
    (testing "doc + system map implied"
      (is (contains? default :permission/docs.write))
      (is (contains? default :permission/system-map.write)))))

(deftest normalize-adds-defaults
  (let [custom #{:permission/missions.report}]
    (is (contains? (perms/normalize custom) :permission/missions.report))
    (is (contains? (perms/normalize custom) :permission/env.bootstrap))
    (is (not (contains? (perms/normalize custom) :permission/deploy.manage)))
    (is (not (contains? (perms/normalize custom) :permission/security.approve)))))

(deftest steward-role-superset
  (let [steward (perms/role-permissions :role/steward)
        dictionary (perms/role-permissions :role/dictionary-engineer)
        ops (perms/role-permissions :role/ops)]
    (is (set/superset? steward dictionary))
    (is (set/superset? steward ops))))
