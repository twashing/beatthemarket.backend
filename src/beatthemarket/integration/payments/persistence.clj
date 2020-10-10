(ns beatthemarket.integration.payments.persistence
  (:require [datomic.client.api :as d]
            [beatthemarket.util :refer [ppi] :as util]))


(defn user-payments [conn email]

  (->> (d/q '[:find (pull ?up [*
                               {:payment/provider [*]}
                               {:payment/provider-type [*]}
                               {:user/_payments [*]}])
              :in $ ?email
              :where
              [?u :user/payments ?up]
              [?u :user/email ?email]
              [?up :payment/id]]
            (d/db conn) email)
       (map first)))

(defn payment-by-id [conn payment-id]

  (d/q '[:find (pull ?p [*])
         :in $ ?payment-id
         :where
         [?p :payment/id ?payment-id]]
       (d/db conn) payment-id))

