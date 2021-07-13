(ns lrsql.ops.command.document
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as cstr]
            [lrsql.functions :as f]
            [lrsql.spec.common :refer [transaction?]]
            [lrsql.spec.document :as ds]
            [lrsql.util :as u]
            [lrsql.ops.util :refer [throw-invalid-table-ex]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Document Insertion
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/fdef insert-document!
  :args (s/cat :tx transaction? :input ds/insert-document-spec)
  :ret ds/document-command-res-spec)

(defn insert-document!
  "Insert a new document into the DB. Returns an empty map. If the document
   already exists, does nothing; to update existing documents, use
   `upsert-document!` instead."
  [tx {:keys [table] :as input}]
  (case table
    :state-document
    (when-not (f/query-state-document-exists tx input)
      (f/insert-state-document! tx input))
    :agent-profile-document
    (when-not (f/query-agent-profile-document-exists tx input)
      (f/insert-agent-profile-document! tx input))
    :activity-profile-document
    (when-not (f/query-activity-profile-document-exists tx input)
      (f/insert-activity-profile-document! tx input))
    ;; Else
    (throw-invalid-table-ex "insert-document!" input))
  {})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Document Upsertion
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mergeable-json
  "Checks that json is returned, and that it is mergeable"
  [{:keys [json]}]
  (when (and json
             (map? json))
    json))

(defn- wrapped-parse-json
  "Wraps `parse-json` in a try-catch block, returning a map with :json
   or :exception which is the parse exception, wrapped in an ex-info"
  [data]
  (try {:json (u/parse-json data)}
       (catch Exception ex
         {:exception ex})))

(defn- doc->json
  "Given a document map with `:contents` property, return the parsed
   contents if valid, or nil otherwise."
  [doc]
  (mergeable-json
   (wrapped-parse-json
    (:contents doc))))

(defn- json-content-type?
  "Returns true iff the content type is \"application/json\"."
  [ctype]
  (cstr/starts-with? ctype "application/json"))

(defn- invalid-merge-error
  [old-doc input]
  {:error
   (ex-info "Invalid Merge"
            {:type :com.yetanalytics.lrs.xapi.document/invalid-merge
             :old-doc old-doc
             :new-doc input})})

(defn- json-read-error
  [input]
  {:error
   (ex-info "Invalid JSON object"
            {:type :com.yetanalytics.lrs.xapi.document/json-read-error
             :new-doc input})})

(defn- upsert-update-document!
  "Update an existing document when upserting `input`."
  [tx input old-doc update-fn!]
  (let [{old-ctype :content_type} old-doc
        {new-ctype :content-type} input
        ?old-json (and (json-content-type? old-ctype)
                       (doc->json old-doc))
        ?new-json (and (json-content-type? new-ctype)
                       (doc->json input))]
    (if (and ?old-json ?new-json)
      (let [new-data  (->> (merge ?old-json ?new-json)
                           u/write-json)
            new-input (-> input
                          (assoc :contents new-data)
                          (assoc :content-length (count new-data)))]
        (update-fn! tx new-input) ; implicit do
        {})
      ;; One or both documents are not JSON
      (invalid-merge-error old-doc input))))

(defn- upsert-insert-document!
  "Insert a new document when upserting `input`."
  [tx {new-ctype :content-type :as input} insert-fn!]
  (if (and new-ctype
           (json-content-type? new-ctype))
    ;; XAPI-00314 - must check if doc contents are JSON if
    ;; content type is application/json
    (if-some [_ (doc->json input)]
      (do (insert-fn! tx input) {})
      (json-read-error input))
    ;; Non-JSON data - directly insert
    (do (insert-fn! tx input) {})))

(defn- upsert-document!*
  "Common functionality for all cases in `upsert-document!`"
  [tx input query-fn insert-fn! update-fn!]
  (let [query-in (dissoc input :last-modified :contents)]
    (if-some [old-doc (query-fn tx query-in)]
      ;; We have a pre-existing document in the store - update
      (upsert-update-document! tx input old-doc update-fn!)
      ;; We don't have a pre-existing document - insert
      (upsert-insert-document! tx input insert-fn!))))

(s/fdef upsert-document!
  :args (s/cat :tx transaction? :input ds/insert-document-spec)
  :ret ds/document-command-res-spec)

(defn upsert-document!
  "Upsert the document given by `input`, i.e. inserts a new document if it
   does not exist in the DB yet, updates the existing document otherwise.
   Performs merging on JSON documents in particular. Returns an empty map."
  [tx {:keys [table] :as input}]
  (case table
    :state-document
    (upsert-document!* tx
                       input
                       f/query-state-document
                       f/insert-state-document!
                       f/update-state-document!)
    :agent-profile-document
    (upsert-document!* tx
                       input
                       f/query-agent-profile-document
                       f/insert-agent-profile-document!
                       f/update-agent-profile-document!)
    :activity-profile-document
    (upsert-document!* tx
                       input
                       f/query-activity-profile-document
                       f/insert-activity-profile-document!
                       f/update-activity-profile-document!)
    ;; Else
    (throw-invalid-table-ex "upsert-document!" input)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Document Deletion
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(s/fdef delete-document!
  :args (s/cat :tx transaction? :input ds/document-input-spec)
  :ret ds/document-command-res-spec)

(defn delete-document!
  "Delete a single document from the DB. Returns an empty map."
  [tx {:keys [table] :as input}]
  (case table
    :state-document
    (f/delete-state-document! tx input)
    :agent-profile-document
    (f/delete-agent-profile-document! tx input)
    :activity-profile-document
    (f/delete-activity-profile-document! tx input)
    ;; Else
    (throw-invalid-table-ex "delete-document!" input))
  {})

(s/fdef delete-documents!
  :args (s/cat :tx transaction? :input ds/state-doc-multi-input-spec)
  :ret ds/document-command-res-spec)

(defn delete-documents!
  "Delete multiple documents from the DB. Returns an empty map."
  [tx {:keys [table] :as input}]
  (case table
    :state-document
    (f/delete-state-documents! tx input)
    ;; Else
    (throw-invalid-table-ex "delete-documents!" input))
  {})