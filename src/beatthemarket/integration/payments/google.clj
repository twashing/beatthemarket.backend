(ns beatthemarket.integration.payments.google
  (:require [clojure.data.json :as json]
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
        p12File (File. "resources/beatthemarket-c13f8-447c4cb482d9.p12")]

    (.. (GoogleCredential$Builder.)
        (setTransport httpTransport)
        (setJsonFactory jsonFactory)

        (setServiceAccountId serviceAccountId)
        (setServiceAccountScopes serviceAccountScopes)
        (setServiceAccountPrivateKeyFromP12File p12File)

        (build))))

(defn verify-payment [{:keys [googleProductName googlePackageName] :as payment-config}
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

(defn check-acknowledgement-valid [acknowledgement]

  (let [acknowledgement-status (select-keys acknowledgement ["acknowledgementState" "consumptionState" "purchaseState"])]

    (if-not (= {"acknowledgementState" 1
                "consumptionState" 1
                "purchaseState" 0}
               acknowledgement-status)

      (throw (Exception. (format "Bad acknowledgement status: %s" acknowledgement-status)))

      acknowledgement)))

(defn verify-payment-workflow [conn client-id email payment-config args]

  ;; (ppi payment-config)
  ;; (ppi args)
  (let [{payment :payment
         game :game :as game-and-payments}
        (->> (verify-payment payment-config args)
                               check-acknowledgement-valid
                               (payments.google.persistence/acknowledgement->entity args)
                               (payments.core/mark-payment-applied-conditionally-on-running-game conn email client-id))

        user-entity (-> (iam.persistence/user-by-email conn email '[:db/id])
                        ffirst
                        (assoc :user/payments (:payment game-and-payments)))]

    (persistence.datomic/transact-entities! conn user-entity)
    (payments.core/apply-payment-conditionally-on-running-game conn email payment game)
    (payments.persistence/user-payments conn email)))
