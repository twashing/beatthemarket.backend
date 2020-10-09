(ns beatthemarket.integration.payments.apple
  (:require [clojure.java.io :refer [resource]]
            [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.edn :refer [read-string]]
            [datomic.client.api :as d]
            [beatthemarket.integration.payments.persistence :as payments.persistence]
            [com.rpl.specter :refer [transform ALL MAP-VALS]]
            [integrant.repl.state :as repl.state]
            [integrant.core :as ig]

            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.integration.payments.core :as integration.payments.core]
            [beatthemarket.integration.payments.apple.persistence :as apple.persistence]
            [beatthemarket.util :refer [ppi] :as util]))


(defmethod ig/init-key :payment.provider/apple [_ {_verify-receipt-endpoint :verify-receipt-endpoint
                                                   _primary-shared-secret :primary-shared-secret}])

;; Validating locally
;;
;; code to read and validate a PKCS #7 signature,
;; code to parse and validate the signed payload
;;
;; a secure connection between your app and your server,
;; code on your server to to validate the receipt with the App Store

(defn group-and-sort-verify-response [latest-receipt-info]
  (->> (group-by :product_id latest-receipt-info)
       (transform [MAP-VALS ALL :original_purchase_date_ms] #(Long/parseLong %))
       (transform [MAP-VALS] #(sort-by :original_purchase_date_ms > %))))

(defn verify-response->latest-receipt [verify-response]
  (->> (:latest_receipt_info verify-response)
       group-and-sort-verify-response
       (transform [MAP-VALS] first)))

(defn verify-payment [verify-endpoint primary-shared-secret apple-hash]

  (let [request-body {:receipt-data (:transactionReceipt apple-hash)
                      :password primary-shared-secret ;; (if the receipt contains an auto-renewable subscription)
                      :exclude-old-transactions false}

        response (-> (client/post verify-endpoint {:body (json/write-str request-body)})
                     :body
                     (json/read-str :key-fn keyword))]

    (verify-response->latest-receipt response)))

(defn verify-payment-workflow [conn client-id email verify-receipt-endpoint primary-shared-secret apple-hash]

  (let [user-db-id (:db/id (ffirst (iam.persistence/user-by-email conn email '[:db/id])))

        existing-transaction-ids
        (->> (integration.payments.core/payments-for-user conn user-db-id
                                                          '[:db/id
                                                            :payment/id
                                                            :payment/product-id
                                                            {:payment/provider
                                                             [{:payment.apple/receipts [*]}]}])
             (map (comp :payment.apple.receipt/transactionId first :payment.apple/receipts :payment/provider))
             (into #{}))

        match-existing-transaction-ids (fn [[k {tid :transaction_id}]]
                                         (some existing-transaction-ids #{tid}))

        game-and-payments
        (->> (verify-payment verify-receipt-endpoint primary-shared-secret apple-hash)
             (remove match-existing-transaction-ids)
             (apple.persistence/latest-receipts->entity apple-hash)
             (map #(integration.payments.core/mark-payment-applied-conditionally-on-running-game conn email client-id %)))

        user-entity (-> (iam.persistence/user-by-email conn email '[:db/id])
                        ffirst
                        (assoc :user/payments (map :payment game-and-payments)))]

    (persistence.datomic/transact-entities! conn user-entity)
    (run! #(integration.payments.core/apply-payment-conditionally-on-running-game conn email (:payment %) (:game %))
          game-and-payments)
    (payments.persistence/user-payments conn email)))

;; Response keys: (:receipt :environment :latest_receipt_info :latest_receipt :status)


;; > :receipt :in_app - purchase information for this receipt
;; Group by :product_id
;; Sort by :original_purchase_date (Get most recent)


;; TODO


;; ! Example Google Play purchase token
;; ! Example verify



;; > :latest_receipt_info - has the latest receipt
;; > :latest_receipt_info - contains all the transactions for the subscription, including the initial purchase and subsequent renewals



;; >> GQL <<
;;
;; > User, subscriptions (incl. payment provider) (GET)
;; check DB
;;
;; > Verify (One time) Purchase (POST)
;; store product, provider, token
;;
;; > Verify Subscription (POST)
;; store product, provider, token
;;
;; Update subscription (Webhook - POST)


;; > Create Customer (Stripe)

;; > Create Subscription (Stripe)
;; this executes payment




;; [APPLE]
;; product-id
;; provider

;; token (Product Receipt)


;; [GOOGLE]
;; product-id
;; provider

;; token (Purchase tokens - generated only when a user completes a purchase flow)
;; order-id (Order ID - created every time a financial transaction occurs)

;; Webhook to detect subscription changes


;; [STRIPE]
;; product-id
;; provider

;; customer-id
;; payment-method-id
;; price-id

;; Webhook to detect i. payment success | failure ii. subscription changes


;; ? Error Handling & Retries
;; ? Tie purchases or subscriptions to App Version
;; ! A cancelled one-time purchase retracts any new monies that were applied to a User's account
