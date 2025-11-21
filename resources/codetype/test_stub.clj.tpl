(ns {{NAMESPACE}}
  (:require
   [clojure.test :refer [deftest is]]))

(deftest generated-smoke
  (is (= "{{MISSION_ID}}" "{{MISSION_ID}}")))
