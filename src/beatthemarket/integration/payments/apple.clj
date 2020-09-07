(ns beatthemarket.integration.payments.apple
  (:require [clojure.java.io :refer [resource]]
            [clj-http.client :as client]
            [clojure.data.json :as json]
            [clojure.edn :refer [read-string]]
            [beatthemarket.util :as util]))



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
           (json/read-str :key-fn keyword))))


  ;; Response keys: (:receipt :environment :latest_receipt_info :latest_receipt :status)


  ;; > :receipt :in_app
  ;; > :latest_receipt_info


  ;; > :receipt :in_app - purchase information for this receipt
  ;; Group by :product_id
  ;; Sort by :original_purchase_date (Get most recent)


  ;; TODO


  ;; ! Example Google Play purchase token
  ;; ! Example verify


  ;; X. Parse Apple response body
  ;; > :latest_receipt_info - has the latest receipt
  ;; > :latest_receipt_info - contains all the transactions for the subscription, including the initial purchase and subsequent renewals
  ;; Group by :product_id
  ;; Sort by :original_purchase_date (Get most recent)



  ;; X.
  ;; DB Schema
  ;; User => [Active Product Subscriptions]
  ;; User => Payment Provider (Apple, Google, Stripe)


  ;; X.
  ;; Webhook to update User Subscription status



  ;; >> GQL <<
  ;;
  ;; > Verify (One time) Purchase (POST)
  ;; store product, provider, token
  ;;
  ;; > Verify Subscription (POST)
  ;; store product, provider, token
  ;;
  ;; > User, subscriptions (incl. payment provider) (GET)
  ;; check DB
  ;;
  ;; Update subscription (Webhook - POST)


  ;; [APPLE]
  ;; product-id
  ;; provider
  ;; token (Product Receipt)


  ;; [GOOGLE]
  ;; product-id
  ;; provider
  ;; token (Purchase tokens - generated only when a user completes a purchase flow)
  ;; order-id (Order ID - created every time a financial transaction occurs)


  ;; ? Error Handling & Retries
  ;; ? Tie purchases or subscriptions to App Version
  ;; ! A cancelled one-time purchase retracts any new monies that were applied to a User's account


  )
