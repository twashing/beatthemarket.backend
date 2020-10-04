(ns user
  (:require [integrant.core :as ig]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]
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


(comment ;; Convenience fns

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

  (persistence.datomic/create-database)
  (persistence.datomic/delete-database)


  ;; UP Components

  (halt)

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
