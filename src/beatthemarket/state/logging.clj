(ns beatthemarket.state.logging
  (:require [integrant.core :as ig]
            [unilog.config  :refer [start-logging!]]))


(defmethod ig/init-key :logging/logging [_ {config :config}]
  (start-logging! config))
