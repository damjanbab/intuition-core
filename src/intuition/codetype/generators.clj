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

(defn- ident-fragment
  [ident]
  (let [base (-> (or (some-> ident name) "codex.sample")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"--+" "-")
                 (str/replace #"^-+" "")
                 (str/replace #"-+$" ""))]
    (if (str/blank? base) "codex-sample" base)))

(defn- render-template
  [template ident mission-id namespace]
  (-> (template-content template)
      (str/replace "{{MISSION_ID}}" mission-id)
      (str/replace "{{CODETYPE_IDENT}}" (str ident))
      (str/replace "{{NAMESPACE}}" namespace)
      (str/replace "{{IDENT}}" (ident-fragment ident))))

(defn templated-scaffold
  "Generic generator that renders each template into the corresponding artifact,
  defaulting to the first template when counts differ."
  [context]
  (let [mission-id (str (:mission/id context))
        codetype-ident (:codetype/ident context)
        artifacts (:codetype/generated-artifacts context)
        templates (:codetype/resolved-templates context)
        namespace (codetype-namespace codetype-ident (:codetype/options context))
        selected-templates (if (= (count templates) (count artifacts))
                             templates
                             (repeat (first templates)))]
    (when (or (empty? artifacts) (empty? templates))
      (throw (ex-info "Generator requires templates + artifacts"
                      {:codetype/ident codetype-ident
                       :codetype/templates templates
                       :codetype/artifacts artifacts})))
    {:generated/files
     (vec
      (map (fn [artifact template]
             {:relative-path artifact
              :codetype/ident codetype-ident
              :content (render-template template codetype-ident mission-id namespace)})
           artifacts
           selected-templates))}))

(defn sample-runtime
  "Simple generator that renders the sample template into the declared artifact."
  [context]
  (templated-scaffold context))
