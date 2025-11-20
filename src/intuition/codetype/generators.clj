(ns intuition.codetype.generators
  "Reusable CodeType generators used by the SfS harness and tests."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]))

(defn- template-content
  [template]
  (let [file (:template/file template)
        resource (:template/resource template)
        path (:template/path template)]
    (cond
      file (slurp file)
      resource (with-open [reader (io/reader resource)]
                 (slurp reader))
      :else (throw (ex-info "Resolved template is missing a file or resource reference."
                            {:template template
                             :template/path path})))))

(defn- codetype-namespace
  [ident {:keys [namespace]}]
  (if (some? namespace)
    namespace
    (let [base (-> (or (some-> ident name) "codex.sample")
                   str/lower-case
                   (str/replace #"[^a-z0-9]+" ".")
                   (str/replace #"\\.+" ".")
                   (str/replace #"^\.+" "")
                   (str/replace #"\.+$" ""))]
      (if (str/blank? base)
        "codex.generated.sample"
        (str "codex.generated." base)))))

(defn sample-runtime
  "Simple generator that renders the sample template into the declared artifact."
  [context]
  (let [mission-id (str (:mission/id context))
        codetype-ident (:codetype/ident context)
        artifacts (:codetype/generated-artifacts context)
        templates (:codetype/resolved-templates context)
        template (first templates)
        artifact (first artifacts)]
    (when-not artifact
      (throw (ex-info "No generated artifact declared for CodeType."
                      {:codetype/ident codetype-ident})))
    (when-not template
      (throw (ex-info "No template resolved for CodeType generator."
                      {:codetype/ident codetype-ident})))
    (let [ns-name (codetype-namespace codetype-ident (:codetype/options context))
          rendered (-> (template-content template)
                       (str/replace "{{MISSION_ID}}" mission-id)
                       (str/replace "{{CODETYPE_IDENT}}" (str codetype-ident))
                       (str/replace "{{NAMESPACE}}" ns-name))]
      {:generated/files
       [{:relative-path artifact
         :content rendered}]})))
