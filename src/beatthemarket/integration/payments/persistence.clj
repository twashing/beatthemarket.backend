(ns beatthemarket.integration.payments.persistence
  (:require [datomic.client.api :as d]))


(defn user-payments [conn]

  (->> (d/q '[:find (pull ?e [*
                              {:payment/provider [*]}
                              {:payment/provider-type [*]}])
              :where [?e :payment/id]] (d/db conn))
       (map first)))

(defn payment-by-id [conn payment-id]

  (d/q '[:find (pull ?p [*])
         :in $ ?payment-id
         :where
         [?p :payment/id ?payment-id]]
       (d/db conn) payment-id))
