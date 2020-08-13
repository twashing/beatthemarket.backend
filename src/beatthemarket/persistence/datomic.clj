(ns beatthemarket.persistence.datomic
  (:require [datomic.client.api :as d]
            [clojure.java.io :refer [resource]]
            [clojure.edn :refer [read-string]]
            [integrant.core :as ig]
            [compute.datomic-client-memdb.core :as memdb]
            [beatthemarket.util :as util]

            ;; TODO Make configurable, loading of :data-processor namespaces
            ;; [beatthemarket.game.persistence]
            ))


;; COMPONENT
(defn config->client [{:keys [config env]}]

  (let [client (d/client config)]
    (hash-map
      :env env
      :client client
      :conn (d/connect client {:db-name "hello"}))))

(defn ->datomic-client-local [{:keys [db-name config env]}]

  (let [url    (format "datomic:mem://%s" db-name)
        client (memdb/client config)]

    (d/create-database client {:db-name url})

    (hash-map
      :env env
      :url url
      :client client
      :conn (d/connect client {:db-name url}))))

(defn close-db-connection-local! [client]
  (memdb/close client))

(defmulti close-db-connection! :env)

(defmethod close-db-connection! :production [_])   ;; a no-op

(defmethod close-db-connection! :development [{client :client}]
  (close-db-connection-local! client))


(defmulti ->datomic-client :env)

(defmethod ->datomic-client :production [opts]
  (config->client opts))

(defmethod ->datomic-client :development [opts]
  (->datomic-client-local opts))


(defmethod ig/init-key :persistence/datomic [_ {datomic-opts :datomic
                                                data-proccesors :data-proccesors :as opts}]

  {:opts (->datomic-client (assoc datomic-opts :env (:env opts)))
   :data-proccesors (->> (map resolve data-proccesors)
                         (apply juxt)) })

(defmethod ig/halt-key! :persistence/datomic [_ {datomic-component-map :opts}]
  (println "Closing database...")
  (close-db-connection! datomic-component-map))


;; DATABASE
(defn transact! [conn data]

  #_(let [data-proccesors (->> integrant.repl.state/system :persistence/datomic :data-proccesors)]
    (data-proccesors data))

  ;; (util/pprint+identity data)
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
(defn conditionially-wrap-in-sequence [entity]
  (if (and (sequential? entity) ((comp not empty?) entity))
    entity
    (list entity)))

(defn transact-entities! [conn entity]
  (->> (conditionially-wrap-in-sequence entity)
       (transact! conn)))
