(ns beatthemarket.persistence.datomic
  (:require [datomic.client.api :as d]
            [beatthemarket.persistence.datomic.environments :as datomic.environments]

            [clojure.java.io :refer [resource]]
            [clojure.edn :refer [read-string]]
            [integrant.core :as ig]
            [integrant.repl.state :as repl.state]
            [beatthemarket.util :refer [ppi] :as util]

            ;; TODO Make configurable, loading of :data-processor namespaces
            [beatthemarket.game.persistence]))


;; COMPONENT

(defn create-database

  ([]
   (create-database
     (-> integrant.repl.state/config :persistence/datomic :datomic :config d/client)
     (-> integrant.repl.state/config :persistence/datomic :datomic :db-name)))

  ([client db-name]
   (d/create-database client {:db-name db-name})))

(defn delete-database

  ([]
   (delete-database
     (-> integrant.repl.state/config :persistence/datomic :datomic :config d/client)
     (-> integrant.repl.state/config :persistence/datomic :datomic :db-name)))

  ([client db-name]
   (d/delete-database client {:db-name db-name})))


(defmethod ig/init-key :persistence/datomic [_ {datomic-opts :datomic :as opts}]
  {:opts (datomic.environments/->datomic-client (assoc datomic-opts :env (:env opts)))})

(defmethod ig/halt-key! :persistence/datomic [_ {datomic-component-map :opts}]
  (println "Closing database...")
  (datomic.environments/close-db-connection! datomic-component-map))



;; DATABASE
(defn transact! [conn data]
  (d/transact conn {:tx-data data}))

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
  (transact-schema! conn)


  ;; B add data

  ;; BAD
  (->> [{:user/email                 "twashing@gmail.com"
         :user/name                  "Timothy Washington"
         :user/identity-provider-uid "adb6d854-4886-46bd-86ba-d5a9a7dc2028"
         :user/identity-provider     "Google"}
        {:user/email                 "twashing@gmail.com"
         :user/name                  "Timothy Washington"
         :user/identity-provider-uid "adb6d854-4886-46bd-86ba-d5a9a7dc2028"
         :user/identity-provider     "Google"}
        {:user/email                 "swashing@gmail.com"
         :user/name                  "Samuel Washington"
         :user/identity-provider-uid "bdb6d854-4886-46bd-86ba-d5a9a7dc2028"
         :user/identity-provider     "Google"}
        {:user/email                 "mwashing@gmail.com"
         :user/name                  "Michelle Washington"
         :user/identity-provider-uid "cdb6d854-4886-46bd-86ba-d5a9a7dc2028"
         :user/identity-provider     "Google"}]
       (transact! conn))

  ;; GOOD
  (->> [{:user/email                 "twashing@gmail.com"
         :user/name                  "Timothy Washington"
         :user/identity-provider-uid "adb6d854-4886-46bd-86ba-d5a9a7dc2028"
         :user/identity-provider     "Google"}
        {:user/email                 "swashing@gmail.com"
         :user/name                  "Samuel Washington"
         :user/identity-provider-uid "bdb6d854-4886-46bd-86ba-d5a9a7dc2028"
         :user/identity-provider     "Google"}
        {:user/email                 "mwashing@gmail.com"
         :user/name                  "Michelle Washington"
         :user/identity-provider-uid "cdb6d854-4886-46bd-86ba-d5a9a7dc2028"
         :user/identity-provider     "Google"}]
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


;; HELPERs
(defn entity-exists? [conn ident]
  (try
    (d/q '[:find ?e
           :in $ ?ident
           :where
           [?e ?ident]]
         (d/db conn) ident)
    (catch Exception e
      false)))

(defn conditionially-wrap-in-sequence [entity]
  (if (and (sequential? entity) ((comp not empty?) entity))
    entity
    (list entity)))

(defn transact-entities! [conn entity]
  (->> (conditionially-wrap-in-sequence entity)
       (transact! conn)))
