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
  (reset)


  (pprint integrant.repl.state/config)
  (pprint integrant.repl.state/system)

  )
