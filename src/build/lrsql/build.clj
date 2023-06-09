(ns lrsql.build
  "Build utils for LRSQL artifacts"
  (:require [hf.depstar :as depstar]))

(def uber-params
  {:jar        "target/bundle/lrsql.jar"
   :aot        true
   :aliases    [:db-sqlite :db-postgres]
   :compile-ns :all
   :no-pom     true
   ;; Don't ship crypto - shouldn't be included in the build path anyways,
   ;; but we exclude them here as extra defense.
   ;; On the other hand, we keep the unobfuscated OSS source code so that users
   ;; have easy access to it.
   :exclude    ["^.*jks$"
                "^.*key$"
                "^.*pem$"]})

(defn uber
  "All backends, as an uberjar"
  [params]
  (-> uber-params
      (merge params)
      (depstar/uberjar)))

(defn uber-manual
  "All backends, as an uberjar, manually
  Ensure that target dir is empty prior to use"
  [params]
  (-> (merge uber-params
             {:target-dir "target"})
      (merge params)
      (depstar/aot)
      (assoc :jar-type :uber)
      (depstar/build)))
