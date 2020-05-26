(ns user
  (:require [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.core :as ig]
            [clojure.java.io :refer [resource]]))

(integrant.repl/set-prep!
  (constantly (-> "config.edn"
                  resource
                  (aero.core/read-config {:profile :development})
                  :integrant)))
