(ns user
  (:require [integrant.core :as ig]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]
            [datomic.dev-local :as dl]

            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :refer [resource]]
            [clojure.core.async :as core.async
              :refer [go-loop chan close! timeout alts! >! <! >!!]]
            [clj-time.core :as t]
            [clojure.core.match :refer [match]]
            [beatthemarket.state.core :as state.core]
            [beatthemarket.migration.core :as migration.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.util :refer [ppi] :as util]

            [magnet.payments.core :as core]
            [magnet.payments.stripe :as stripe]
            [beatthemarket.test-util :as test-util]))


(comment ;; Basic

  ;; Start with
  (prep)
  (init)

  ;; Or just
  (go)

  ;; These functions are also available for control
  (reset-all)



  ;; INSPECT Migrations

  (require '[datomic.client.api :as d])

  (def conn (-> repl.state/system :persistence/datomic :opts :conn))
  (persistence.datomic/entity-exists? conn :migration/id)
  (->> (d/q '[:find (pull ?e [*])
              :where
              [?e :migration/id]]
            (d/db conn))
       ppi)





  ;; CREATE Database


  ;; A
  (state.core/set-prep :dev-local)
  (state.core/init-components)


  ;; B
  (persistence.datomic/create-database
    (-> integrant.repl.state/system :persistence/datomic :opts :client)
    (-> integrant.repl.state/system :persistence/datomic :opts :db-name))

  (persistence.datomic/delete-database
    (-> integrant.repl.state/system :persistence/datomic :opts :client)
    (-> integrant.repl.state/system :persistence/datomic :opts :db-name))


  ;; C
  (let [db-name (-> integrant.repl.state/system :persistence/datomic :opts :db-name)
        conn (-> integrant.repl.state/system
                 :persistence/datomic
                 :opts
                 :client
                 (d/connect {:db-name db-name}))]

    (migration.core/run-migrations conn)))

(comment


  ;; A
  (do
    (require '[beatthemarket.game.calculation :as calculation]
             '[beatthemarket.handler.graphql.encoder :as graphql.encoder])
    (def conn (-> integrant.repl.state/system :persistence/datomic :opts :conn))
    (def email "twashing@gmail.com"))

  ;; B
  (->> (d/q '[:find (pull ?g [:game/id
                              {:game/status [:db/ident]}
                              {:game/users
                               [{:game.user/profit-loss [*]}
                                {:game.user/user
                                 [:db/id
                                  :user/email
                                  :user/name
                                  :user/external-uid]}]}])
              :in $ ?email
              :where
              [?g :game/id]
              [?g :game/users ?gu]
              [?gu :game.user/user ?guu]
              [?guu :user/email ?email]]
            (d/db conn)
            email)
       (take 3)
       (map first)
       ((partial calculation/user-games->user-with-games true))
       ppi)

  ;; C
  (-> (calculation/collect-realized-profit-loss-for-user-allgames conn email true)
      graphql.encoder/user->graphql
      ppi))

(comment ;; Convenience fns


  ;; UP Components

  (do
    (state.core/set-prep :development)
    (state.core/init-components)
    (migration.core/run-migrations))

  (do
    (state.core/set-prep :development)
    (state.core/init-components)
    (migration.core/run-migrations
      (-> repl.state/system :persistence/datomic :opts :conn)
      #{:default :development}))

  (halt)

  (do
    (state.core/set-prep :development)
    (state.core/init-components))



  (do
    (state.core/set-prep :test)
    (state.core/init-components)
    (migration.core/run-migrations))

  (do
    (state.core/set-prep :test)
    (state.core/init-components))



  (do
    (state.core/set-prep :production)
    (state.core/init-components))


  (pprint integrant.repl.state/config)
  (pprint integrant.repl.state/system)



  ;; Individual
  (prep)

  ;; (ig/init integrant.repl.state/config [:service/service])
  (ig/init integrant.repl.state/config [:magnet.payments/stripe])
  (def datomic-client (ig/init integrant.repl.state/config [:persistence/datomic]))


  (require '[beatthemarket.dir]
           '[clojure.tools.namespace.dir]
           '[clojure.tools.namespace.track :as track]
           '[clojure.tools.namespace.file :as file])

  (#'clojure.tools.namespace.dir/dirs-on-classpath)

  (-> (#'clojure.tools.namespace.dir/dirs-on-classpath)
      (#'clojure.tools.namespace.dir/find-files))

  (->> (#'clojure.tools.namespace.dir/dirs-on-classpath)
       (#'clojure.tools.namespace.dir/find-files)
       (#'clojure.tools.namespace.dir/deleted-files (track/tracker)))

  (pprint (#'beatthemarket.dir/scan-all (track/tracker))))

(comment ;; Datomic import to dev-local


  (dl/import-cloud
    {:source {:system      "beatthemarket-datomic4"
              :db-name     "beaththemarket-20201004"
              :server-type :cloud
              :region      "us-east-1"
              :endpoint    "http://entry.beatthemarket-datomic4.us-east-1.datomic.net:8182"
              :proxy-port  8182}

     :dest {:system      "production-imports"
            :server-type :dev-local
            :db-name     "beatthemarket-production"}}))
