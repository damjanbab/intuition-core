(ns env-isolation-test
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.test :refer [deftest is]]
   [intuition.sfs.env.bootstrap :as bootstrap]))

(defn- unique-mission []
  (str "M-ENV-" (System/currentTimeMillis)))

(deftest sandboxes-are-isolated
  (let [mission (unique-mission)
        env-a (bootstrap/bootstrap! {:mission/id mission :agent/id "alpha"})
        env-b (bootstrap/bootstrap! {:mission/id mission :agent/id "bravo"})]
    (try
      (is (not= (:sandbox/root env-a) (:sandbox/root env-b)) "Roots must differ per agent")
      (is (empty? (set/intersection (set (:sandbox/ports env-a)) (set (:sandbox/ports env-b))))
          "Port allocations must not overlap")
      (finally
        (doseq [env [env-a env-b]]
          (when-let [cleanup (:cleanup! env)]
            (cleanup)))))
    (doseq [env [env-a env-b]]
      (is (not (.exists (io/file (:sandbox/root env)))))))

(deftest cleanup-happens-on-failure
  (let [mission (unique-mission)
        sandbox-path (atom nil)]
    (is (thrown-with-msg?
         RuntimeException
         #"boom"
        (bootstrap/with-sandbox {:mission/id mission
                                  :agent/id "failing-agent"}
                                 (fn [env]
                                   (reset! sandbox-path (:sandbox/root env))
                                   (throw (RuntimeException. "boom"))))))
    (is (not (.exists (io/file @sandbox-path)))))))
