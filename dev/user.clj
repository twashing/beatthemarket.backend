(ns user
  (:require [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.core :as ig]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :refer [resource]]
            [clojure.core.async :as core.async
              :refer [go-loop chan close! timeout alts! >! <! >!!]]
            [clj-time.core :as t]
            [clojure.core.match :refer [match]]
            [beatthemarket.state.core :as state.core]
            [beatthemarket.migration.core :as migration.core]
            [beatthemarket.util :as util]))


(comment ;; Convenience fns

  ;; Start with
  (prep)
  (init)

  ;; Or just
  (go)

  ;; These functions are also available for control
  (halt)
  (reset-all)


  ;; Catach all
  (do
    (state.core/set-prep :development)
    (state.core/init-components)
    (migration.core/run-migrations))


  (pprint integrant.repl.state/config)
  (pprint integrant.repl.state/system)


  ;; Individual
  (prep)
  (ig/init integrant.repl.state/config [:service/service])
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
