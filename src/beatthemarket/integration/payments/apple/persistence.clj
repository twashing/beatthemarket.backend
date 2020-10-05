(ns beatthemarket.integration.payments.apple.persistence
  (:require [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.util :refer [ppi] :as util])
  (:import [java.util UUID]))


(defn latest-receipts->entity [token latest-receipt]

  (let [receipt->entity (fn [[_ {product-id :product_id
                                transaction-id :transaction_id
                                purchase-date-ms :purchase_date_ms}]]

                          (let [apple-receipt (persistence.core/bind-temporary-id
                                                {:payment.apple.receipt/transactionId transaction-id
                                                 :payment.apple.receipt/transactionDate purchase-date-ms})]

                            (as-> token v
                              (hash-map
                                :payment.apple/id (java.util.UUID/randomUUID)
                                ;; :payment.apple/token (.getBytes (str v))
                                :payment.apple/token (str v)
                                :payment.apple/receipts [apple-receipt])
                              (persistence.core/bind-temporary-id v)
                              (hash-map
                                :payment/id (java.util.UUID/randomUUID)
                                :payment/product-id product-id
                                :payment/provider-type :payment.provider/apple
                                :payment/provider v)
                              (persistence.core/bind-temporary-id v))))]

    (map receipt->entity latest-receipt)))
