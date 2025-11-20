(ns code-types-catalog-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [intuition.code.runtime :as code]
   [intuition.docs.runtime :as docs]))

(defn- read-edn
  [path]
  (-> path slurp edn/read-string))

(deftest code-definitions-declare-required-metadata
  (let [definitions (code/definitions)]
    (is (pos? (count definitions)) "Code catalog should not be empty")
    (doseq [definition definitions]
      (testing (str "definition " (:code.definition/ident definition))
        (is (:code.definition/type definition))
        (is (seq (:code.definition/paths definition)))
        (is (seq (:code.definition/missions definition)))
        (is (not (str/blank? (:entity/description definition))))
        (doseq [path (:code.definition/paths definition)]
          (is (.exists (io/file path)) (str "Missing path " path)))))))

(deftest code-definition-dependencies-resolve
  (doseq [definition (code/definitions)
          dependency (or (:code.definition/dependencies definition) [])]
    (is (code/known-dependency? dependency)
        (str "Unknown dependency " dependency " on " (:code.definition/ident definition)))))

(deftest doc-runtime-exposes-code-catalog
  (let [doc-result (docs/generate-type-docs! {:template-idents [:template.instance/doc.type.mission-record]})
        doc (-> (:edn/path (first doc-result)) read-edn)
        spec-sections (:doc/spec-sections doc)
        expected (set (map :code.definition/ident (code/definitions-by-spec spec-sections)))
        referenced (set (map :code.definition/ident (:doc/code-definitions doc)))]
    (is (seq (:doc/code-definitions doc)) "Doc EDN should include catalog data")
    (is (= expected referenced)
        "Doc metadata should list every catalog entry tied to the same spec sections.")))
