(ns user
  (:require [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.core :as ig]
            [clojure.java.io :refer [resource]]))

(integrant.repl/set-prep!
  (constantly (-> "config.edn"
                  resource
                  (aero.core/read-config {:profile :development})
                  :integrant)))


(comment ;; Convenience fns

  ;; Start with
  (prep)
  (init)

  ;; Or just
  (go)

  ;; These functions are also available for control
  (halt)
  (reset-all)


  (pprint integrant.repl.state/config)
  (pprint integrant.repl.state/system)

  (prep)
  (ig/init integrant.repl.state/config [:service/service])


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
