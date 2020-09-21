(ns beatthemarket.integration.payments.google
  (:require [clojure.data.json :as json]
            [clj-http.client :as client]
            ;; [http.async.client :as http]
            [integrant.core :as ig]

            [beatthemarket.integration.payments.core :as payments.core]
            [beatthemarket.util :as util])
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


;; Google Play Developer API
;; https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.products/get
;; https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptions/get


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


(defn ->refresh-token [{:keys [refresh-token-url redirect-url oauth-client-id]} requestScope]

  ;; TODO test error w/ bad client-id
  ;; TODO first get from DB
  ;; "4/4AGDk3-wNikuaNZsKKj-SpTcw2KOcuR5Zk9OJ9dBYXCtMeH1n_UFGSmEuVlQiqMplGoaf1oVBE0aL5IyHP_u69U"

  ;; NOTE Try async
  #_(let [opts {;; :redirect-strategy :none
              ;; :redirect-strategy :graceful
              ;; :trace-redirects true
              ;; :redirect-strategy :lax
              ;; :max-redirects 5

              :debug true
              :async? true
              :query-params {"scope" requestScope
                             "response_type" "code"
                             "access_type" "offline"
                             "redirect_uri" redirect-url
                             "client_id" oauth-client-id}}]

      (client/get refresh-token-url
                opts

                (fn [response]
                  (println "response is:" response)
                  (util/ppi response))

                (fn [exception]
                  (println "exception message is: " (.getMessage exception))
                  (util/ppi exception))))

  ;; NOTE Try debug
  (let [opts {;; :redirect-strategy :none
              ;; :redirect-strategy :graceful
              :trace-redirects true
              ;; :redirect-strategy :lax
              ;; :max-redirects 5

              :debug true
              :query-params {"scope" requestScope
                             "response_type" "code"
                             "access_type" "offline"
                             "redirect_uri" redirect-url
                             "client_id" oauth-client-id}}]

    #_(util/ppi [refresh-token-url opts])
    (-> (client/get refresh-token-url opts)
        util/ppi
        (get "Location")))

  #_"4/4AF0Ihuc8cwmtQ7sdRRlw__qqZkRfAnKEwcpPyDPSAZs8ri8hPtRtetsRvRFjLmkdQbMtZbHxeEpxWWoBgTd5_o"

  ;; NOTE Try [http.async.client "1.3.1"]
  #_(let [client (http/create-client)
        opts {"scope" requestScope
              "response_type" "code"
              "access_type" "offline"
              "redirect_uri" redirect-url
              "client_id" oauth-client-id}]

    (util/ppi
      (http/GET client refresh-token-url
                :query opts)))

  )

(defn ->access-token [{:keys [access-token-url redirect-url oauth-client-id oauth-client-secret]} refreshToken]

  ;; TODO regenerate only if expired
  ;; "ya29.a0AfH6SMByPGLgn-PL549C6rybLcjAwA1EGdNgJz0_3wrOFyanGNP4FypLcYelm44JW0ycb4OpzFQ0UMMovg94Dat9fJmoADumKf_r5_lNSFFe-3DM3JCBpaeZrLC6SU6XxZFMHs3AwdPCf-nIG2jiSZ_EG2x4uG_eZz59"
  (let [;; refreshToken "4/4AFwYWTl1S92UF520Gmu9oJOe8NNFVopNQ8FyYd4aPD98sEzHN8LxL8wb0BGL9QOeGaBfEJN84kQvuoT2olxqyQ"
        request-body {:grant_type "authorization_code"
                      :code refreshToken
                      :client_id oauth-client-id
                      :client_secret oauth-client-secret
                      :redirect_uri redirect-url}]

    (util/ppi ["Sanity 3" access-token-url request-body])
    (-> (client/post access-token-url {:body (json/write-str request-body)})
        util/ppi
        :body
        (json/read-str :key-fn keyword)
        :access_token)))

(defn verify-payment-workflow [conn
                               {:keys [googleClientId googleClientSecret googleProductName googlePackageName
                                       oauth-client-id oauth-client-secret
                                       productRequestScope subscriptionRequestScope] :as payment-config}
                               {:keys [productId token]}]

  []

  #_(let [requestScope (cond
                       (some payments.core/subscriptions #{productId}) subscriptionRequestScope
                       (some payments.core/products #{productId}) productRequestScope
                       :else (throw (Exception. (format "Invalid productId %s" productId))))

        refreshToken (->refresh-token payment-config requestScope)
        accessToken (->access-token payment-config refreshToken)

        httpTransport (GoogleNetHttpTransport/newTrustedTransport)
        jsonFactory (JacksonFactory/getDefaultInstance)
        tokenResponse (doto (TokenResponse.)
                        (.setAccessToken accessToken)
                        (.setRefreshToken refreshToken)
                        (.setExpiresInSeconds 3600)
                        (.setScope requestScope)
                        (.setTokenType "Bearer"))

        credential (doto (.. (GoogleCredential$Builder.)
                             (setTransport httpTransport)
                             (setJsonFactory jsonFactory)
                             (setClientSecrets googleClientId googleClientSecret)
                             (build))
                     (.setFromTokenResponse tokenResponse))

        publisher (.. (AndroidPublisher$Builder. httpTransport jsonFactory credential)
                      (setApplicationName googleProductName)
                      (build))]

    (util/ppi
      (.. publisher
          (purchases)
          (products)
          (get googlePackageName productId token)
          (execute)))

    ;; TODO check for error
    ;; TODO on verification, save
    ;; TODO query + return payment info
    ))
