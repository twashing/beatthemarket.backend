(ns beatthemarket.integration.payments.stripe.persistence
  (:require [datomic.client.api :as d]
            [beatthemarket.util :refer [ppi] :as util]))


(defn customer-by-id [conn customer-id]
  (d/q '[:find (pull ?e [*])
         :in $ ?customer-id
         :where
         [?e :payment.stripe/customer-id ?customer-id]]
       (d/db conn) customer-id))
