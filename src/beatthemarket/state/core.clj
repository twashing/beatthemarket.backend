(ns beatthemarket.state.core
  (:require [clojure.java.io :refer [resource]]
            [integrant.repl :refer [go halt]]
            [integrant.core :as ig]
            [aero.core :as aero]
            [com.rpl.specter :refer [transform MAP-VALS]]
            [beatthemarket.util :refer [ppi] :as util]))


;; NOTE taken from a suggestion from an Integrant issue
;; https://github.com/weavejester/integrant/issues/12#issuecomment-283415380
(defmethod aero/reader 'ig/ref [_ _ value]
  (ig/ref value))

(defn read-config [profile resource]
  (aero/read-config resource {:profile  profile
                              :resolver aero/resource-resolver}))

(defn inject-environment [profile config]
  (transform [MAP-VALS] #(assoc % :env profile) config))

(defn set-prep+load-namespaces [profile]

  (integrant.repl/set-prep!
    (constantly (->> "config.edn"
                     resource
                     (read-config profile)
                     :integrant
                     (inject-environment profile))))

  (ig/load-namespaces {:beatthemarket.handler.http/service        :service/service
                       :beatthemarket.handler.http/server         :server/server
                       :beatthemarket.iam/authentication          :firebase/firebase
                       :beatthemarket.persistence/datomic         :persistence/datomic
                       :beatthemarket.state/nrepl                 :nrepl/nrepl
                       :beatthemarket.state/logging               :logging/logging
                       :beatthemarket.integration.payments/apple  :payment.provider/apple
                       :beatthemarket.integration.payments/google :payment.provider/google
                       :beatthemarket.integration.payments/stripe :payment.provider/stripe
                       :beatthemarket.datasource/name-generator   :name-generator/name-generator
                       :beatthemarket.game/core                   :game/games}))

(defn set-prep

  ([] (set-prep :production))

  ([profile]
   (set-prep+load-namespaces profile)))

(defn init-components []

  (halt)
  (go))

(def halt-components halt)
