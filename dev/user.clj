(ns user
  (:require [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.core :as ig]
            [clojure.java.io :refer [resource]]
            [beatthemarket.handler.http.server :refer [set-prep+load-namespaces]]
            [beatthemarket.persistence.datomic :as persistence.datomic]))


(set-prep+load-namespaces :development)


(defn dev-fixture []
  (halt)
  (go)

  ;; Create schema
  (-> integrant.repl.state/system
      :persistence/datomic :conn
      persistence.datomic/transact-schema!))


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
  (dev-fixture)


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

  #_(binding [clojure.tools.namespace.dir/update-files
            (fn [tracker deleted modified]
              (let [now (System/currentTimeMillis)]
                (-> tracker
                    (update-in [::files] #(if % (apply disj % deleted) #{}))
                    (file/remove-files deleted)
                    (update-in [::files] into modified)
                    (file/add-files modified)
                    (assoc ::time now))))]

    (pprint (#'clojure.tools.namespace.dir/scan-all (track/tracker))))


  (pprint (#'beatthemarket.dir/scan-all (track/tracker))))
