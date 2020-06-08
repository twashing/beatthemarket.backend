(ns beatthemarket.persistence.datomic
  (:require [datomic.client.api :as d]
            [clojure.java.io :refer [resource]]
            [clojure.edn :refer [read-string]]))

(def cfg {:server-type :peer-server
          :access-key "myaccesskey"
          :secret "mysecret"
          :endpoint "localhost:8998"
          :validate-hostnames false})

(defn load-schema

  ([] (load-schema "schema.datomic.edn"))

  ([schema]
   (-> schema
       resource slurp
       read-string)))


;; TODO Peer component
;;   client connect
;;
;; TODO add unique constraints to schema: email, name, and identity-provider-uid
;; TODO how to drop schema + data (testing)
;;   d/create-database
;;   d/delete-database
;;
;; TODO get full attributes from Firebase token

;; TODO ensure these keys are in the result (schema)
;; {:db-before :db-after :tx-data :tempids}

;; TODO implement user login functionality

;; TODO Dockerize datomic
(defn transact! [conn data]
  (d/transact conn {:tx-data data}))

(comment


  (def client (d/client cfg))
  (def conn (d/connect client {:db-name "hello"}))


  ;; A create schema
  (->> (load-schema)
       (transact! conn))

  ;; B add data
  (def users
    [{:user/email "twashing@gmail.com"
      :user/name "Timothy Washington"
      :user/identity-provider-uid "adb6d854-4886-46bd-86ba-d5a9a7dc2028"
      :user/identity-provider "Google"}
     {:user/email "swashing@gmail.com"
      :user/name "Samuel Washington"
      :user/identity-provider-uid "bdb6d854-4886-46bd-86ba-d5a9a7dc2028"
      :user/identity-provider "Google"}
     {:user/email "mwashing@gmail.com"
      :user/name "Michelle Washington"
      :user/identity-provider-uid "cdb6d854-4886-46bd-86ba-d5a9a7dc2028"
      :user/identity-provider "Google"}])

  (def result-a (transact! conn users))


  ;; C query data

  (def db (d/db conn))

  (def all-users-q '[:find ?e
                     :where [?e :user/email]])

  (d/q all-users-q db)

  (def all-emails-q '[:find ?user-email
                      :where [_ :user/email ?user-email]])

  (d/q all-emails-q db)

  (def name-from-email '[:find ?name ?identity-provider
                         :where
                         [?e :user/name ?name]
                         [?e :user/identity-provider ?identity-provider]
                         [?e :user/email "swashing@gmail.com"]])

  (d/q name-from-email db)

  )
