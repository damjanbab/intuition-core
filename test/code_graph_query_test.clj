(ns code-graph-query-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [intuition.code.graph :as graph]
   [support.datomic :as support]))

(def ^:private sample-plan-id "plan-1")

(def ^:private nodes
  [{:code.graph.node/ident :spec/sample
    :code.graph.node/type :code.graph.node.type/spec
    :code.graph.node/name "Spec"
    :code.graph.node/spec-id :spec/sample
    :code.graph.node/requirements ["REQ-1"]}
   {:code.graph.node/ident :plan/sample
    :code.graph.node/type :code.graph.node.type/plan
    :code.graph.node/name "Plan"
    :code.graph.node/spec-id :spec/sample
    :code.graph.node/plan-id sample-plan-id
    :code.graph.node/requirements ["REQ-1"]}
   {:code.graph.node/ident :mission/M-DET-1
    :code.graph.node/type :code.graph.node.type/mission
    :code.graph.node/name "M-DET-1"
    :code.graph.node/mission-id "M-DET-1"}
   {:code.graph.node/ident :code/my.feature
    :code.graph.node/type :code.graph.node.type/code
    :code.graph.node/name "Feature"
    :code.graph.node/missions ["M-DET-1"]}
   {:code.graph.node/ident :test/my.feature
    :code.graph.node/type :code.graph.node.type/test
    :code.graph.node/name "Feature test"}
   {:code.graph.node/ident :doc/my-feature
    :code.graph.node/type :code.graph.node.type/doc
    :code.graph.node/name "Doc"}
   {:code.graph.node/ident :code.type/runtime
    :code.graph.node/type :code.graph.node.type/code-type
    :code.graph.node/name "Runtime"}
   {:code.graph.node/ident :doc/unrelated
    :code.graph.node/type :code.graph.node.type/doc
    :code.graph.node/name "Unrelated doc"}])

(def ^:private edges
  [{:code.graph.edge/ident :edge/spec->plan
    :code.graph.edge/from :spec/sample
    :code.graph.edge/to :plan/sample
    :code.graph.edge/relation :code.graph.relation/spec->plan
    :code.graph.edge/requirements ["REQ-1"]}
   {:code.graph.edge/ident :edge/plan->mission
    :code.graph.edge/from :plan/sample
    :code.graph.edge/to :mission/M-DET-1
    :code.graph.edge/relation :code.graph.relation/plan->mission
    :code.graph.edge/spec-sections ["3.3"]}
   {:code.graph.edge/ident :edge/mission->code
    :code.graph.edge/from :mission/M-DET-1
    :code.graph.edge/to :code/my.feature
    :code.graph.edge/relation :code.graph.relation/mission->code}
   {:code.graph.edge/ident :edge/code->test
    :code.graph.edge/from :code/my.feature
    :code.graph.edge/to :test/my.feature
    :code.graph.edge/relation :code.graph.relation/code->test}
   {:code.graph.edge/ident :edge/code->doc
    :code.graph.edge/from :code/my.feature
    :code.graph.edge/to :doc/my-feature
    :code.graph.edge/relation :code.graph.relation/code->doc}
   {:code.graph.edge/ident :edge/code->type
    :code.graph.edge/from :code/my.feature
    :code.graph.edge/to :code.type/runtime
    :code.graph.edge/relation :code.graph.relation/code->code-type}])

(deftest graph-filters-and-traversal
  (support/with-test-conn
   (fn [conn]
     (graph/install! {:conn conn :nodes nodes :edges edges})
     (let [full (graph/graph {:conn conn})
           filtered (graph/graph {:conn conn :mission-id "M-DET-1"})
           upstream (graph/upstream {:graph filtered :from :code/my.feature})
           downstream (graph/downstream {:graph filtered :from :code/my.feature})]
       (testing "full graph includes all seeded nodes"
         (is (= (set (map :code.graph.node/ident nodes))
                (set (map :code.graph.node/ident (:nodes full))))))
       (testing "mission filter trims unrelated nodes"
         (is (= #{:spec/sample :plan/sample :mission/M-DET-1 :code/my.feature
                  :test/my.feature :doc/my-feature :code.type/runtime}
                (set (map :code.graph.node/ident (:nodes filtered))))))
       (testing "upstream walks to plan/spec"
         (is (= #{:code/my.feature :mission/M-DET-1 :plan/sample :spec/sample :code.type/runtime}
                (set (map :code.graph.node/ident upstream)))))
       (testing "downstream reaches docs/tests"
         (is (= #{:code/my.feature :mission/M-DET-1 :plan/sample :spec/sample
                  :test/my.feature :doc/my-feature :code.type/runtime}
                (set (map :code.graph.node/ident downstream)))))))))
