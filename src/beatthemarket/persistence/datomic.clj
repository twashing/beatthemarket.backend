(ns beatthemarket.persistence.datomic
  (:require [datomic.client.api :as d]
            [datomic.api]
            [clojure.java.io :refer [resource]]
            [clojure.edn :refer [read-string]]
            [integrant.core :as ig]))



(defmethod ig/init-key :persistence/datomic [_ {:keys [db-name config]}]

  #_(-> (d/client config)
        (d/connect {:db-name db-name}))
  (d/client config))

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

;; TODO ensure these keys are in the result (schema)
;; {:db-before :db-after :tx-data :tempids}

;; TODO implement user login functionality
;;   get full attributes from Firebase token

;; TODO Dockerize datomic
;;   bin/run -m datomic.peer-server -h localhost -p 8998 -a myaccesskey,mysecret -d beatthemarket,datomic:mem://beatthemarket

(defn transact! [conn data]
  (d/transact conn {:tx-data data}))

(defn transact-mem! [conn data]
  (datomic.api/transact conn data))

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

  ;; D get client
  :ok


  ;; E create database

  ;; A quick way to start experimenting with Datomic (using [com.datomic/datomic-free "0.9.5697"])
  ;; https://clojureverse.org/t/a-quick-way-to-start-experimenting-with-datomic/5004

  ;; Using [datomic-client-memdb "1.1.1"] from here
  ;; https://forum.datomic.com/t/datomic-free-being-out-phased/1211

  (def db-uri-mem "datomic:mem://beatthemarket")
  ;; (def db-uri-mem "datomic:dev://localhost:8998/hello")


  ;; ====
  (require '[compute.datomic-client-memdb.core :as memdb])
  (def c (memdb/client {}))

  (d/create-database c {:db-name db-uri-mem})
  (def conn (d/connect c {:db-name db-uri-mem}))


  (d/delete-database c {:db-name db-uri-mem})


  ;; ====
  (datomic.api/create-database db-uri-mem)
  (def conn (datomic.api/connect db-uri-mem))

  (->> (load-schema)
       (transact-mem! conn))

  (def result-a (transact-mem! conn users))

  ;; ====
  ;; (datomic.api/create-database (:persistence/datomic user/datomic-client) db-uri-mem)
  ;; (d/create-database (:persistence/datomic user/datomic-client) {:db-name "beatthemarket"})


  ;; F get connetion

  ;; (require '[integrant.repl.state])


  )
