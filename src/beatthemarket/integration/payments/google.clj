(ns beatthemarket.integration.payments.google
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clj-http.client :as client]
            [integrant.core :as ig]

            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.integration.payments.core :as payments.core]
            [beatthemarket.integration.payments.persistence :as payments.persistence]
            [beatthemarket.integration.payments.google.persistence :as payments.google.persistence]
            [beatthemarket.util :refer [ppi] :as util])
  (:import [com.google.api.client.json.jackson2 JacksonFactory]
           [com.google.api.client.auth.oauth2 TokenResponse]
           [com.google.api.client.googleapis.javanet GoogleNetHttpTransport]
           [com.google.api.client.googleapis.auth.oauth2 GoogleCredential GoogleCredential$Builder GoogleRefreshTokenRequest]
           [com.google.api.services.androidpublisher AndroidPublisher AndroidPublisher$Builder AndroidPublisherScopes]
           [java.util Collections]
           [java.io File]))


(defmethod ig/init-key :payment.provider/google [_ {_googleClientId :googleClientId
                                                    _googleClientSecret :googleClientSecret
                                                    _googleProductName :googleProductName
                                                    _googlePackageName :googlePackageName}])


(defn ->google-credential-from-service-account []

  (let [httpTransport (GoogleNetHttpTransport/newTrustedTransport)
        jsonFactory (JacksonFactory/getDefaultInstance)
        serviceAccountId "116484587614025882652"

        ;; serviceAccountScopes (Collections/singleton "https://www.googleapis.com/auth/cloud-platform")
        serviceAccountScopes (Collections/singleton AndroidPublisherScopes/ANDROIDPUBLISHER)

        p12ResourceName "beatthemarket-c13f8-447c4cb482d9.p12"
        p12FileName (str "/tmp/" p12ResourceName)]

    ;; TODO
    ;; Fix this kludge - read resource directly as file
    (with-open [in (io/input-stream (io/resource p12ResourceName))]
      (io/copy in (io/file p12FileName)))

    (let [p12File (File. p12FileName)]

      (.. (GoogleCredential$Builder.)
          (setTransport httpTransport)
          (setJsonFactory jsonFactory)

          (setServiceAccountId serviceAccountId)
          (setServiceAccountScopes serviceAccountScopes)
          (setServiceAccountPrivateKeyFromP12File p12File)

          (build)))))


(defn verify-product-payment [{:keys [googleProductName googlePackageName] :as payment-config}
                              {:keys [productId token]}]

  (let [purchase-hash (json/read-str token :key-fn keyword)
        httpTransport (GoogleNetHttpTransport/newTrustedTransport)
        jsonFactory (JacksonFactory/getDefaultInstance)

        credential (doto (->google-credential-from-service-account)
                     (.refreshToken))

        publisher (.. (AndroidPublisher$Builder. httpTransport jsonFactory credential)
                      (setApplicationName googleProductName)
                      (build))]


    ;; NOTE response codes
    ;; https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products

    ;; TODO check for error
    ;; TODO on verification, save
    ;; TODO query + return payment info

    ;; (ppi [:tokenResponse (bean tokenResponse)])
    ;; (ppi ["Sanity Google - String" token])
    ;; (ppi ["Sanity Google - payment-config" payment-config])
    ;; (ppi ["Sanity Google - parsed" purchase-hash])

    "acknowledgementState" ;; 0. Yet to be acknowledged 1. Acknowledged
    "consumptionState" ;; 0. Yet to be consumed 1. Consumed
    "purchaseState" ;; 0. Purchased 1. Canceled 2. Pending

    (def example-result
      {"acknowledgementState" 1
       "consumptionState" 1
       "developerPayload" ""
       "kind" "androidpublisher#productPurchase"
       "orderId" "GPA.3394-2483-0269-56848"
       "purchaseState" 0
       "purchaseTimeMillis" 1600786858399
       "purchaseType" 0
       "regionCode" "UA"})

    (.. publisher
        (purchases)
        (products)
        (get googlePackageName productId (:purchaseToken purchase-hash))
        (execute))))

(defn check-product-acknowledgement-valid [acknowledgement]

  (let [acknowledgement-status (select-keys acknowledgement ["acknowledgementState" "consumptionState" "purchaseState"])]

    (if-not (= {"acknowledgementState" 1
                "consumptionState" 1
                "purchaseState" 0}
               acknowledgement-status)

      (throw (Exception. (format "Bad acknowledgement status: %s" acknowledgement-status)))

      acknowledgement)))

(defn verify-product-payment-workflow [conn client-id email payment-config args]

  ;; (ppi payment-config)
  ;; (ppi args)
  (let [{payment :payment
         game :game :as game-and-payments}
        (->> (verify-product-payment payment-config args)
             check-product-acknowledgement-valid
             (payments.google.persistence/acknowledgement->entity args)
             (payments.core/mark-payment-applied-conditionally-on-running-game conn email client-id))

        user-entity (-> (iam.persistence/user-by-email conn email '[:db/id])
                        ffirst
                        (assoc :user/payments (:payment game-and-payments)))]

    (persistence.datomic/transact-entities! conn user-entity)
    (payments.core/apply-payment-conditionally-on-running-game conn email payment game)
    (payments.persistence/user-payments conn email)))


(defn verify-subscription-payment [{:keys [googleProductName googlePackageName] :as payment-config}
                                   {:keys [productId token]}]

  (let [purchase-hash (json/read-str token :key-fn keyword)
        httpTransport (GoogleNetHttpTransport/newTrustedTransport)
        jsonFactory (JacksonFactory/getDefaultInstance)

        credential (doto (->google-credential-from-service-account)
                     (.refreshToken))

        publisher (.. (AndroidPublisher$Builder. httpTransport jsonFactory credential)
                      (setApplicationName googleProductName)
                      (build))]


    ;; NOTE response codes
    ;; https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptions

    ;; (ppi [:tokenResponse (bean tokenResponse)])
    ;; (ppi ["Sanity Google - String" token])
    ;; (ppi ["Sanity Google - payment-config" payment-config])
    ;; (ppi ["Sanity Google - parsed" purchase-hash])

    "acknowledgementState" ;; 0. Yet to be acknowledged 1. Acknowledged
    "paymentState" ;; 0. Payment pending 1. Payment received 2. Free trial 3. Pending deferred upgrade/downgrade
    "cancelReason"
    ;; The reason why a subscription was canceled or is not auto-renewing. Possible values are:
    ;;   0. User canceled the subscription
    ;;   1. Subscription was canceled by the system, for example because of a billing problem
    ;;   2. Subscription was replaced with a new subscription
    ;;   3. Subscription was canceled by the developer

    "linkedPurchaseToken"
    ;; The purchase token of the originating purchase if this subscription is one of the following:
    ;; 0. Re-signup of a canceled but non-lapsed subscription
    ;; 1. Upgrade/downgrade from a previous subscription
    ;;
    ;; For example, suppose a user originally signs up and you receive purchase token X, then the user cancels and goes through the resignup flow (before their subscription lapses) and you receive purchase token Y, and finally the user upgrades their subscription and you receive purchase token Z. If you call this API with purchase token Z, this field will be set to Y. If you call this API with purchase token Y, this field will be set to X. If you call this API with purchase token X, this field will not be set.

    {"acknowledgementState" 1,  ;; 1. Acknowledged
     "autoRenewing" false,
     "cancelReason" 1,  ;; 1. Subscription was canceled by the system, for example because of a billing problem
     "countryCode" "UA",
     "developerPayload" "",
     "startTimeMillis" 1602662889948,  ;; Wednesday, October 14, 2020 8:08:09.948 AM
     "expiryTimeMillis" 1602665098898,  ;; Wednesday, October 14, 2020 8:44:58.898 AM GMT
     "kind" "androidpublisher#subscriptionPurchase",
     "orderId" "GPA.3358-8655-9676-96352..5",
     "priceAmountMicros" 339990000,
     "priceCurrencyCode" "UAH",
     "purchaseType" 0}


    (def example-result
      {"acknowledgementState" 1
       "consumptionState" 1
       "developerPayload" ""
       "kind" "androidpublisher#productPurchase"
       "orderId" "GPA.3394-2483-0269-56848"
       "purchaseState" 0
       "purchaseTimeMillis" 1600786858399
       "purchaseType" 0
       "regionCode" "UA"})

    (.. publisher
        (purchases)
        (subscriptions)
        (get googlePackageName productId (:purchaseToken purchase-hash))
        (execute))))

(defn check-subscription-acknowledgement-valid [acknowledgement]

  (let [acknowledgement-status (select-keys acknowledgement ["acknowledgementState" "consumptionState" "purchaseState"])]

    (if-not (= {"acknowledgementState" 1
                "consumptionState" 1
                "purchaseState" 0}
               acknowledgement-status)

      (throw (Exception. (format "Bad acknowledgement status: %s" acknowledgement-status)))

      acknowledgement)))

(defn verify-subscription-payment-workflow [conn client-id email payment-config args]

  ;; (ppi payment-config)
  ;; (ppi args)
  (let [{payment :payment
         game :game :as game-and-payments}
        (->> (verify-subscription-payment payment-config args)
             ;; check-subscription-acknowledgement-valid
             (payments.google.persistence/acknowledgement->entity args)
             (payments.core/mark-payment-applied-conditionally-on-running-game conn email client-id))

        user-entity (-> (iam.persistence/user-by-email conn email '[:db/id])
                        ffirst
                        (assoc :user/payments (:payment game-and-payments)))]

    (persistence.datomic/transact-entities! conn user-entity)
    (payments.core/apply-payment-conditionally-on-running-game conn email payment game)
    (payments.persistence/user-payments conn email)))
