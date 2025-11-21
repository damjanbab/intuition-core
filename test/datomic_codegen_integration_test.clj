(ns datomic-codegen-integration-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [datomic.client.api :as d]
   [intuition.code.generate :as codegen]
   [intuition.code.runtime :as code]
   [intuition.sfs.protocols.runtime :as protocols]
   [support.datomic :as support])
  (:import
   (java.math BigInteger)
   (java.security MessageDigest)))

(def mission-id "M-20251121-813")
(def mission-ident (keyword (str "mission/" mission-id)))

(defn- sha256
  [^java.io.File file]
  (with-open [input (io/input-stream file)]
    (let [digest (MessageDigest/getInstance "SHA-256")
          buffer (byte-array 8192)]
      (loop []
        (let [read-bytes (.read input buffer)]
          (when (pos? read-bytes)
            (.update digest buffer 0 read-bytes)
            (recur))))
      (format "%064x" (BigInteger. 1 (.digest digest))))))

(defn- normalize-path
  [path]
  (-> path
      (str/replace #"^\./" "")
      (str/replace #"^/" "")
      (str/replace #"//+" "/")))

(def target-definitions
  [:code/spec.importer
   :code/intuition.datomic])

(def base-context
  {:mission/id mission-id
   :agent/id "tester"
   :workspace/root "tmp/datomic-codegen-sandbox"
   :tests/enabled? true
   :tests/suite :test.suite/contract
   :tests/paths ["test/actions_contract_test.clj"]
   :tests/error-mode :fail-fast
   :docs/paths ["SYSTEM_SPEC.md"]
   :system-map/entities [:action/mission.validate]
   :codetype/paths []})

(deftest datomic-codegen-integration-test
  (support/with-test-conn
   (fn [conn]
     (codegen/ensure-schema! conn)
     (let [catalog (into {} (map (juxt :code.definition/ident identity) (code/definitions)))
           tx-defs (for [ident target-definitions
                         :let [definition (get catalog ident)]]
                     (-> (select-keys definition [:code.definition/ident
                                                  :code.definition/type
                                                  :code.definition/name
                                                  :code.definition/paths
                                                  :code.definition/spec-sections])
                         (assoc :code.definition/missions [mission-ident])))]
       (d/transact conn {:tx-data tx-defs})
       (let [result (protocols/run!
                    {:conn conn
                     :protocol/ident :protocol/mission-standard
                     :context base-context
                     :permissions #{:permission/env.bootstrap
                                     :permission/locks.manage
                                     :permission/tests.run
                                     :permission/docs.write
                                     :permission/system-map.write}})
             materialize-result (get-in result [:step-results :step/code-materialize])
             files (:code.materialize/files materialize-result)
             paths (:code.materialize/paths materialize-result)
             definitions (:code.materialize/definitions materialize-result)
             checksum-map (into {} (map (juxt :code.materialize/file :code.materialize/checksum)) files)
             expected-paths (->> tx-defs
                                 (mapcat :code.definition/paths)
                                 (map normalize-path)
                                 set)
             codetype-result (get-in result [:step-results :step/codetype])]
         (is (= :status/succeeded (:status result)))
         (is (= (count target-definitions) (count definitions)))
         (is (= expected-paths (set (map :code.materialize/relative-path files))))
         (is (seq paths))
         (is (= (set paths) (set (map :code.materialize/file files))))
         (doseq [file paths]
           (let [f (io/file file)]
             (is (.exists f))
             (is (= (sha256 f)
                    (get checksum-map (.getCanonicalPath f))))))
         (is (= expected-paths
                (set (map normalize-path (:codetype/paths codetype-result)))))
         (is (= (set target-definitions)
                (set (map :code.definition/ident (:codetype/definitions codetype-result)))))
         (is (false? (:code.materialize/skipped? materialize-result))))))))
