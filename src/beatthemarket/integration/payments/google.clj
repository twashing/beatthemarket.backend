(ns beatthemarket.integration.payments.google
  (:require ;; [clojure.java.io :refer [resource]]
            [clj-http.client :as client]
            ;; [clojure.data.json :as json]
            ;; [clojure.edn :refer [read-string]]
            [beatthemarket.util :as util])
  (:import [com.google.api.client.googleapis.javanet GoogleNetHttpTransport]
           [com.google.api.client.json.jackson2 JacksonFactory]
           ;; [com.google.auth.oauth2 GoogleCredentials]
           [com.google.api.client.googleapis.auth.oauth2 GoogleCredential]
           [com.google.api.services.androidpublisher AndroidPublisher AndroidPublisher$Builder AndroidPublisherScopes]
           [java.util Collections]))



;; Google Play Developer API
;; https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{packageName}/purchases/subscriptions/{subscriptionId}/tokens/{token}


;; Android : inApp purchase receipt validation google play
;; https://stackoverflow.com/questions/35127086/android-inapp-purchase-receipt-validation-google-play/35138885#35138885
;; https://stackoverflow.com/questions/35169960/android-inapp-purchase-receipt-validation-part-2
;; ... howto get the access token


;; https://github.com/googleapis/google-api-java-client-services/
;; https://googleapis.github.io/google-api-java-client/ (Overview)

;; access_token
;; https://play.google.com/console/developers/8038496863446330572/app-list
;; https://github.com/googleapis/google-api-java-client/blob/master/docs/support.md
;; https://stackoverflow.com/search?q=%5Bgoogle-api-java-client%5D+access+token
;; i. https://stackoverflow.com/questions/11115381/unable-to-get-the-subscription-information-from-google-play-android-developer-ap?rq=1
;; ii. https://developers.google.com/android-publisher/authorization



;; => https://stackoverflow.com/questions/63770549/android-inapp-purchase-receipt-validation-google-play-redux



(comment

  (def httpTransport (GoogleNetHttpTransport/newTrustedTransport))
  (def jsonFactory (JacksonFactory/getDefaultInstance))
  (def credential (doto (GoogleCredential/fromStream
                          (-> (Thread/currentThread)
                              (.getContextClassLoader)
                              (.getResourceAsStream "api-8038496863446330572-265555-671e2c17a86e.json")))
                    (.createScoped (Collections/singleton AndroidPublisherScopes/ANDROIDPUBLISHER))))

  (def publisher (doto (AndroidPublisher$Builder. httpTransport jsonFactory credential)
                   (.setApplicationName "Beatthemarket")
                   (.build)))

  )


;; (AndroidPublisher$Builder. httpTransport jsonFactory credential)
;; (new AndroidPublisher$Builder httpTransport jsonFactory credential)


;; publisher = new
;; AndroidPublisher.Builder(httpTransport, jsonFactory, credential)
;; .setApplicationName(APP_NAME)
;; .build()

