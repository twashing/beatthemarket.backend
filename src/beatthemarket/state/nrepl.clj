(ns beatthemarket.state.nrepl
  (:require [integrant.core :as ig]
            [nrepl.server :refer [start-server stop-server]]))


(defmethod ig/init-key :nrepl/nrepl [_ {port :port}]
  (start-server :bind "0.0.0.0" :port port))

(defmethod ig/halt-key! :nrepl/nrepl [_ nrepl]
  (stop-server nrepl))
