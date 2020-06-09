(ns beatthemarket.persistence.datomic
  (:require [datomic.client.api :as d]
            [clojure.java.io :refer [resource]]
            [clojure.edn :refer [read-string]]
            [integrant.core :as ig]
            [compute.datomic-client-memdb.core :as memdb]))


(defn config->client [{:keys [db-name config]}]
  (hash-map :client (d/client config)))

(defmulti ->datomic-client :env)

(defmethod ->datomic-client :development [{:keys [db-name config]}]
  (hash-map
    :client (memdb/client config)
    :url    (format "datomic:mem://%s" db-name)))

(defmethod ->datomic-client :production [opts]
  (config->client opts))

(defmethod ig/init-key :persistence/datomic [a datomic-opts]
  (->datomic-client datomic-opts))

(defn load-schema

  ([] (load-schema "schema.datomic.edn"))

  ([schema]
   (-> schema
       resource slurp
       read-string)))


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


  ;; > DB Client
  (def client (-> integrant.repl.state/system
                  :persistence/datomic
                  :client))

  ;; > PROD Connection
  (def conn (d/connect client {:db-name "hello"}))


  ;; > DEV | TEST Connection

  ;; Create database
  ;;
  ;; A quick way to start experimenting with Datomic (using [com.datomic/datomic-free "0.9.5697"])
  ;; https://clojureverse.org/t/a-quick-way-to-start-experimenting-with-datomic/5004

  ;; Using [datomic-client-memdb "1.1.1"] from here
  ;; https://forum.datomic.com/t/datomic-free-being-out-phased/1211

  (def db-uri-mem (-> integrant.repl.state/system
                      :persistence/datomic
                      :url))
  (d/create-database client {:db-name db-uri-mem})
  (d/delete-database client {:db-name db-uri-mem})

  (def conn (d/connect client {:db-name db-uri-mem}))


  ;; A create schema
  (->> (load-schema)
       (transact! conn))


  ;; B add data

  ;; BAD
  (->> [{:user/email "twashing@gmail.com"
         :user/name "Timothy Washington"
         :user/identity-provider-uid "adb6d854-4886-46bd-86ba-d5a9a7dc2028"
         :user/identity-provider "Google"}
        {:user/email "twashing@gmail.com"
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
         :user/identity-provider "Google"}]
       (transact! conn))

  ;; GOOD
  (->> [{:user/email "twashing@gmail.com"
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
         :user/identity-provider "Google"}]
       (transact! conn))

  (def result-users *1)


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




  ;; ====
  (require '[compute.datomic-client-memdb.core :as memdb])
  (def c (memdb/client {}))



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
