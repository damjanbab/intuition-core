(ns protocol-ownership-test
  (:require
   [clojure.set :as cset]
   [clojure.test :refer [deftest is testing]]
   [intuition.dictionary :as dictionary]))

(defn- role-idents
  []
  (->> (dictionary/load-permissions)
       (filter #(= :role/definition (:entity/type %)))
       (map :role/ident)
       set))

(deftest protocol-owners-are-declared
  "SYSTEM_SPEC §§2.1–2.2 and §11 require every protocol to name owners and escalation roles."
  (let [protocols (dictionary/load-protocols)
        roles (role-idents)]
    (testing "every protocol declares an owner present in the role dictionary"
      (doseq [protocol protocols
              :let [ident (:protocol/ident protocol)
                    owner (:protocol/owner protocol)]]
        (is (keyword? owner) (str ident " missing :protocol/owner"))
        (is (contains? roles owner) (str ident " owner not present in permissions.edn"))))
    (testing "escalation vectors reference valid roles (or stay empty for low-risk flows)"
      (doseq [protocol protocols
              :let [ident (:protocol/ident protocol)
                    escalation (:protocol/escalation protocol)
                    escalate-set (set escalation)]]
        (is (vector? escalation) (str ident " must use vector :protocol/escalation"))
        (is (every? keyword? escalation) (str ident " escalation entries must be keywords"))
        (is (cset/subset? escalate-set roles)
            (str ident " references undefined escalation roles: "
                 (pr-str (cset/difference escalate-set roles))))))))
