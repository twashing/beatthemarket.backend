(ns beatthemarket.integration.payments.google.persistence
  (:require [beatthemarket.persistence.core :as persistence.core])
  (:import [java.util UUID]))


(defn acknowledgement->entity [{:keys [productId token]} acknowledgement]

  (let [order-id (get acknowledgement "orderId")]

    (as-> token v
      (hash-map
        :payment.google/id (java.util.UUID/randomUUID)
        :payment.google/token v
        :payment.google/order-id order-id)
      (persistence.core/bind-temporary-id v)
      (hash-map
        :payment/id (java.util.UUID/randomUUID)
        :payment/product-id productId
        :payment/provider-type :payment.provider/google
        :payment/provider v)
      (persistence.core/bind-temporary-id v))))
