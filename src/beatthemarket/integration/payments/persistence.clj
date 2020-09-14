(ns beatthemarket.integration.payments.persistence
  (:require [datomic.client.api :as d]))


(defn user-payments [db]
  (->> (d/q '[:find (pull ?e [*
                              {:payment/provider [*]}
                              {:payment/provider-type [*]}])
              :where [?e :payment/id]] db)
       (map first)))
