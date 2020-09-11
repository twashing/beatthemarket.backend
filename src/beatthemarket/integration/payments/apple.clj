(ns beatthemarket.integration.payments.apple
  (:require [clojure.java.io :refer [resource]]
            [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.edn :refer [read-string]]
            [datomic.client.api :as d]
            [com.rpl.specter :refer [transform ALL MAP-VALS]]
            [integrant.repl.state :as repl.state]
            [integrant.core :as ig]

            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.integration.payments.apple.persistence :as apple.persistence]
            [beatthemarket.util :as util]))


(defmethod ig/init-key :payments/apple [_ {_verify-receipt-endpoint :verify-receipt-endpoint
                                           _primary-shared-secret :primary-shared-secret}])

;; Validating locally
;;
;; code to read and validate a PKCS #7 signature,
;; code to parse and validate the signed payload
;;
;; a secure connection between your app and your server,
;; code on your server to to validate the receipt with the App Store


(comment


  (do

    (def apple-hash (-> "example-hash-apple.json"
                        resource
                        slurp
                        (json/read-str :key-fn keyword)))

    (def primary-shared-secret "d552367242fb40d9a2aee861031922ee"))


  ;; app-specific shared-secret
  ;; :beatthemarket-shared-secret ""


  ;; Need to contact a server with an untrusted SSL cert?
  (client/get "https://alioth.debian.org" {:insecure? true})
  (client/get "https://api.github.com/gists")


  (let [request-body {:receipt-data (:transactionReceipt apple-hash)
                      :password primary-shared-secret ;; (if the receipt contains an auto-renewable subscription)
                      :exclude-old-transactions false}]


    ;; PRODUCTION

    #_(def response-prod (util/pprint+identity (client/post "https://buy.itunes.apple.com/verifyReceipt" {:body request-body})))


    ;; SANDBOX

    ;; BAD Response - :body "status" 21002
    ;; https://developer.apple.com/documentation/appstorereceipts/status
    #_(def response-sand
      (util/pprint+identity (client/post "https://sandbox.itunes.apple.com/verifyReceipt")))

    ;; GOOD Response - :body "status" 0
    (def response-sand
      (-> (client/post "https://sandbox.itunes.apple.com/verifyReceipt" {:body (json/write-str request-body)})
           util/pprint+identity
           :body
           (json/read-str :key-fn keyword)))

    ))


(defn group-and-sort-verify-response [latest-receipt-info]
  (->> (group-by :product_id latest-receipt-info)
       (transform [MAP-VALS ALL :original_purchase_date_ms] #(Long/parseLong %))
       (transform [MAP-VALS] #(sort-by :original_purchase_date_ms > %))))

(defn verify-response->latest-receipt [verify-response]
  (->> (:latest_receipt_info verify-response)
       group-and-sort-verify-response
       (transform [MAP-VALS] first)))

(defn user-payments [db]
  (->> (d/q '[:find (pull ?e [*
                              {:payment/provider [*]}
                              {:payment/provider-type [*]}])
              :where [?e :payment/id]] db)
       (map first)))

(defn verify-payment [verify-endpoint primary-shared-secret apple-hash]

  (let [request-body {:receipt-data (:transactionReceipt apple-hash)
                      :password primary-shared-secret ;; (if the receipt contains an auto-renewable subscription)
                      :exclude-old-transactions false}

        response (-> (client/post verify-endpoint {:body (json/write-str request-body)})
                     :body
                     (json/read-str :key-fn keyword))]

    (verify-response->latest-receipt response)))

(defn- conditionally-transact-payment-receipt [conn apple-hash latest-receipts]

  ;; TODO conditionally, if receipt has not been stored
  (->> (apple.persistence/latest-receipts->entity apple-hash latest-receipts)
       (persistence.datomic/transact-entities! conn)))

(defn verify-payment-workflow [conn verify-receipt-endpoint primary-shared-secret apple-hash]

  (->> (verify-payment verify-receipt-endpoint primary-shared-secret apple-hash)
       (conditionally-transact-payment-receipt conn apple-hash)

       ;; TODO check for error
       :db-after
       (d/q '[:find (pull ?e [*
                              {:payment/provider [*]}
                              {:payment/provider-type [*]}])
              :where [?e :payment/id]])
       (map first)))

(comment


  ;; A.
  (def A (let [verify-endpoint "https://sandbox.itunes.apple.com/verifyReceipt"

               apple-hash (-> "example-hash-apple.json"
                              resource
                              slurp
                              (json/read-str :key-fn keyword))

               primary-shared-secret "d552367242fb40d9a2aee861031922ee"]

           (verify-payment verify-endpoint primary-shared-secret apple-hash)))


  ;; B. transact
  (def B (apple.persistence/latest-receipts->entity apple-hash A))
  (def conn (-> repl.state/system :persistence/datomic :opts :conn))


  ;; C. GQL response
  (def Z (persistence.datomic/transact-entities! conn B))
  (def C (->> Z #_(persistence.datomic/transact-entities! conn B)
              :db-after
              (d/q '[:find (pull ?e [*
                                     {:payment/provider [*]}
                                     {:payment/provider-type [*]}])
                     :where [?e :payment/id]])
              util/pprint+identity
              (map first)
              (map payment-purchase->graphql)))

  )

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
