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


(defmethod ig/init-key :payments/google [_ {_googleClientId :googleClientId
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

#_(defn ->google-credential [googleClientId googleClientSecret]

  #_(let [httpTransport (GoogleNetHttpTransport/newTrustedTransport)
          jsonFactory (JacksonFactory/getDefaultInstance)]

    (.. (GoogleCredential$Builder.)
        (setTransport httpTransport)
        (setJsonFactory jsonFactory)
        (setClientSecrets googleClientId googleClientSecret)
        (build)))

  (doto (GoogleCredential/fromStream
          (-> (Thread/currentThread)
              (.getContextClassLoader)
              (.getResourceAsStream "beatthemarket-c13f8-a87cc30b3d77.json")))
    (.createScoped (Collections/singleton "https://www.googleapis.com/auth/cloud-platform" #_AndroidPublisherScopes/ANDROIDPUBLISHER))))

(defn ->google-credential []

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

        (build))

    ))

#_(defn ->refresh-token [googleClientId googleClientSecret] ;; Using GoogleRefreshTokenRequest.

  (let [httpTransport (GoogleNetHttpTransport/newTrustedTransport)
        jsonFactory (JacksonFactory/getDefaultInstance)
        refreshToken "4/4AE96XXWhqviSmHlPml0HAS0mYtCAbUPyA6_UmMuRee7M-X6NgFvWVmwE9xDODr7pUEXioZMOD1pwP0PuhAHBy0"]

    (.. (GoogleRefreshTokenRequest. httpTransport
                                    jsonFactory
                                    refreshToken
                                    googleClientId
                                    googleClientSecret)
        (execute))))

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
                  (util/pprint+identity response))

                (fn [exception]
                  (println "exception message is: " (.getMessage exception))
                  (util/pprint+identity exception))))

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

    #_(util/pprint+identity [refresh-token-url opts])
    (-> (client/get refresh-token-url opts)
        util/pprint+identity
        (get "Location")))

  #_"4/4AF0Ihuc8cwmtQ7sdRRlw__qqZkRfAnKEwcpPyDPSAZs8ri8hPtRtetsRvRFjLmkdQbMtZbHxeEpxWWoBgTd5_o"

  ;; NOTE Try [http.async.client "1.3.1"]
  #_(let [client (http/create-client)
        opts {"scope" requestScope
              "response_type" "code"
              "access_type" "offline"
              "redirect_uri" redirect-url
              "client_id" oauth-client-id}]

    (util/pprint+identity
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

    (util/pprint+identity ["Sanity 3" access-token-url request-body])
    (-> (client/post access-token-url {:body (json/write-str request-body)})
        util/pprint+identity
        :body
        (json/read-str :key-fn keyword)
        :access_token)))

(comment

  (do
    (def googleClientId "1062410862638-b94modujdnkue3587c86qfn28960v6u1.apps.googleusercontent.com")
    (def googleClientSecret "Agr60XOia8zDsUlDP74CpMAz")
    (def privateKeyPassword "notasecret")
    (def purchaseToken ""))

  (def credential (->google-credential))

  ;; i.
  (pprint (.getRefreshToken credential))
  ;; (.executeRefreshToken credential)
  (.refreshToken credential) ;; ok
  (def accessToken (.getAccessToken credential))
  (pprint (bean credential))

  ;; ii.
  (do
    (def httpTransport (GoogleNetHttpTransport/newTrustedTransport))
    (def jsonFactory (JacksonFactory/getDefaultInstance))
    (def googleProductName "Beatthemarket")
    (def googlePackageName "com.beatthemarket")
    (def subscriptionId "margin_trading_1month"))

  (def publisher (.. (AndroidPublisher$Builder. httpTransport jsonFactory credential)
                     (setApplicationName googleProductName)
                     (build)))

  (def result (.. publisher
                  (purchases)
                  (products)
                  (get googlePackageName subscriptionId purchaseToken)
                  (execute)))

  )

(defn verify-payment-workflow [conn
                               {:keys [googleClientId googleClientSecret googleProductName googlePackageName
                                       oauth-client-id oauth-client-secret
                                       productRequestScope subscriptionRequestScope] :as payment-config}
                               {:keys [productId token]}]

  (let [requestScope (cond
                       (some payments.core/subscriptions #{productId}) subscriptionRequestScope
                       (some payments.core/products #{productId}) productRequestScope
                       :else (throw (Exception. (format "Invalid productId %s" productId))))

        ;; credential (->google-credential requestScope)
        credential (->google-credential oauth-client-id oauth-client-secret)
        ]

    ;; NOTE Trying w/out access token; just from service account keyfile : Error
    ;; "400 Bad Request\n{\n  \"error\" : \"invalid_scope\",\n  \"error_description\" : \"Invalid OAuth scope or ID token audience provided.\"\n}"
    ;; (def googlePackageName "com.beatthemarket")
    ;; (def googleProductName "Beatthemarket")
    ;; (def subscriptionId "margin_trading_1month")
    ;; (def purchaseToken
    ;;   "onhhinbenecfbpohlgpkpica.AO-J1OyHDwGfi22SvG2VdeGdrR9nz0D3WY_YPda6qr7yssmdQ6oX2PKEiKfaN4B9LVx1LJSgkLVfuWbho2ReugyGThDgNy66a4EltFlLGVwZ4JK_CfTE5ypYz7E0SGuyO4wQNItR4hUP")
    ;;
    ;; (def httpTransport (GoogleNetHttpTransport/newTrustedTransport))
    ;; (def jsonFactory (JacksonFactory/getDefaultInstance))
    ;; (def publisher (.. (AndroidPublisher$Builder. httpTransport jsonFactory credential)
    ;;                    (setApplicationName googleProductName)
    ;;                    (build)))
    ;;
    ;; (def result (util/pprint+identity
    ;;               (.. publisher
    ;;                   (purchases)
    ;;                   (products)
    ;;                   (get googlePackageName subscriptionId purchaseToken)
    ;;                   (execute))))

    ;; (util/pprint+identity (.refreshToken credential))
    ;; (util/pprint+identity (bean credential))

    ;; (util/pprint+identity (.executeRefreshToken credential))
    ;; (util/pprint+identity (bean credential))

    ;; i.
    ;; (util/pprint+identity (doto credential
    ;;                         (.setServiceAccountPrivateKeyFromP12File (File. "resources/beatthemarket-c13f8-a87cc30b3d77.json"))
    ;;                         (.getRefreshToken)))
    ;; (util/pprint+identity (bean credential))

    ;; ii.
    ;; (util/pprint+identity (.getRefreshToken credential))
    ;; (util/pprint+identity (bean credential))

    ;; iii.
    ;; (util/pprint+identity (bean (.executeRefreshToken credential)))
    ;; (util/pprint+identity (->refresh-token googleClientId googleClientSecret))

    ;; iv.
    ;; (util/pprint+identity (.refreshToken credential))
    ;; (util/pprint+identity (.getAccessToken credential))

    ;; (util/pprint+identity (.accessToken credential))
    )

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

    (util/pprint+identity
      (.. publisher
          (purchases)
          (products)
          (get googlePackageName productId token)
          (execute)))

    ;; TODO check for error
    ;; TODO on verification, save
    ;; TODO query + return payment info
    ))

(def oauth-client-id "25142477325-4p45f5lckodqg8qrf7sa3h27iv958h27.apps.googleusercontent.com")
(def oauth-client-secret "JZYp3yiicN15p3IlCHNonvNm")

(comment ;; Getting an Access token

  (def access-token
    (let [token-endpoint "https://accounts.google.com/o/oauth2/token"
          request-body {:grant_type "refresh_token"
                        ;; :client_id "1062410862638-b94modujdnkue3587c86qfn28960v6u1.apps.googleusercontent.com"
                        ;; :client_secret "Agr60XOia8zDsUlDP74CpMAz"
                        :client_id oauth-client-id
                        :client_secret oauth-client-secret
                        :refresh_token
                        "1//05uaJkTCWegauCgYIARAAGAUSNwF-L9IrvzRvrDy5WzTAWvN6PJi78-V1xiCrh7HAFDVkpHv3sViTGGItBl3tKKki6JPBfPvETWM"}]

      (util/pprint+identity (client/post token-endpoint {:body (json/write-str request-body)}))))

  (def access-token-string
    (-> access-token
        :body
        (json/read-str :key-fn keyword)
        util/pprint+identity
        :access_token)))

(comment ;; Verifying purchases

  (def purchases
    (let [packageName PACKAGE_NAME
          subscriptionId "account_balance_100k"
          purchaseToken (str "inapp:" packageName ":android.test.purchased")
          apiKey "AIzaSyBKoYq5_kn868hg4d8e3BjbuDqKdo3oIJ8"
          verify-endpoint (format "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/%s/purchases/subscriptions/%s/tokens/%s?key=%s"
                                  packageName subscriptionId purchaseToken apiKey)

          access-token-string "ya29.a0AfH6SMCSc5LtJLR-eTJszu5w4vvchcTB2aJZ_EIFtv0_gSlUaODz5tq_Zji5pCQ5SsQWHiJgW68MeNuMDqR6ljRZHpdMUlgZ4IzHbOnh9oa0CwWqIOdVCcn2H4lbUTa6IDnM7nEbFXNh5EUBttubn2F5LM7qMGI3K2k"
          headers {"Authorization" (format "Bearer %s" access-token-string)
                   "Accept" "application/json"}]

      (util/pprint+identity verify-endpoint)
      (util/pprint+identity headers)

      (client/get verify-endpoint headers)))


  ;; curl \
  ;; 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/[PACKAGENAME]/purchases/subscriptions/[SUBSCRIPTIONID]/tokens/[TOKEN]?key=[YOUR_API_KEY]' \
  ;; --header 'Authorization: Bearer [YOUR_ACCESS_TOKEN]' \
  ;; --header 'Accept: application/json' \
  ;; --compressed

  )

(comment ;; Verify w/ Google Client Library

  (do

    (def PACKAGE_NAME "com.beatthemarket")
    (def sku "product_account_balance_100k")
    (def key "beatthemarket-c13f8-a87cc30b3d77.json")
    ;; (def service-account-email "")
    (def purchaseToken (str "inapp:" PACKAGE_NAME ":android.test.purchased"))

    #_(AndroidPublisherScopes/all)
    #_AndroidPublisherScopes/ANDROIDPUBLISHER
    (def request-scope "https://www.googleapis.com/auth/androidpublisher")

    (def httpTransport (GoogleNetHttpTransport/newTrustedTransport))
    (def jsonFactory (JacksonFactory/getDefaultInstance))
    (def credential (doto (GoogleCredential/fromStream
                            (-> (Thread/currentThread)
                                (.getContextClassLoader)
                                (.getResourceAsStream key)))
                      (.createScoped (Collections/singleton request-scope))))

    (def publisher (.. (AndroidPublisher$Builder. httpTransport jsonFactory credential)
                     (setApplicationName PACKAGE_NAME)
                     (build))))


  (def result (.. publisher
                (purchases)
                (products)
                (get PACKAGE_NAME sku purchaseToken)
                (execute))))

(comment ;; Verify with Google Client Library 2

  (do

    (def googleClientId "1062410862638-b94modujdnkue3587c86qfn28960v6u1.apps.googleusercontent.com")
    (def googleClientSecret "Agr60XOia8zDsUlDP74CpMAz")
    (def googleProductName "Beatthemarket")

    (def accessToken "ya29.a0AfH6SMByPGLgn-PL549C6rybLcjAwA1EGdNgJz0_3wrOFyanGNP4FypLcYelm44JW0ycb4OpzFQ0UMMovg94Dat9fJmoADumKf_r5_lNSFFe-3DM3JCBpaeZrLC6SU6XxZFMHs3AwdPCf-nIG2jiSZ_EG2x4uG_eZz59")
    (def refreshToken "4/4AGDk3-wNikuaNZsKKj-SpTcw2KOcuR5Zk9OJ9dBYXCtMeH1n_UFGSmEuVlQiqMplGoaf1oVBE0aL5IyHP_u69U")

    (def httpTransport (GoogleNetHttpTransport/newTrustedTransport))
    (def jsonFactory (JacksonFactory/getDefaultInstance))
    (def tokenResponse (doto (TokenResponse.)
                         (.setAccessToken accessToken)
                         (.setRefreshToken refreshToken)
                         (.setExpiresInSeconds 3600)
                         (.setScope "https://www.googleapis.com/auth/androidpublisher")
                         (.setTokenType "Bearer")))

    #_(def credential (doto (GoogleCredential/fromStream
                              (-> (Thread/currentThread)
                                  (.getContextClassLoader)
                                  (.getResourceAsStream key)))
                        (.createScoped (Collections/singleton request-scope))))

    (def credential (doto (.. (GoogleCredential$Builder.)
                              (setTransport httpTransport)
                              (setJsonFactory jsonFactory)
                              (setClientSecrets googleClientId googleClientSecret)
                              (build))
                      (.setFromTokenResponse tokenResponse)))

    (def publisher (.. (AndroidPublisher$Builder. httpTransport jsonFactory credential)
                       (setApplicationName googleProductName)
                       (build))))


  (do

    (def googlePackageName "com.beatthemarket")
    (def subscriptionId "margin_trading_1month")
    (def purchaseToken
      #_(str "inapp:" googlePackageName ":android.test.purchased")
      "onhhinbenecfbpohlgpkpica.AO-J1OyHDwGfi22SvG2VdeGdrR9nz0D3WY_YPda6qr7yssmdQ6oX2PKEiKfaN4B9LVx1LJSgkLVfuWbho2ReugyGThDgNy66a4EltFlLGVwZ4JK_CfTE5ypYz7E0SGuyO4wQNItR4hUP"))

  ;; (def purchases (.purchases publisher))
  ;; (def get (.get purchases googlePackageName subscriptionId purchaseToken))
  ;; SubscriptionPurchase subscription = get.execute()
  ;; subscripcion.getValidUntilTimestampMsec()

  (def result (.. publisher
                  (purchases)
                  (products)
                  (get googlePackageName subscriptionId purchaseToken)
                  (execute)))

  ;; purchases.products.get
  ;; purchases.subscriptions.get

  ;; margin_trading_1month
  ;; additional_100k
                       )

(comment ;; Notes

  ;; https://stackoverflow.com/questions/35127086/android-inapp-purchase-receipt-validation-google-play/35138885#35138885
  (def PACKAGE_NAME "Beatthemarket")

  ;; https://stackoverflow.com/questions/43622351/how-to-get-iap-sku-code-from-google-play-console/43622962
  ;; https://play.google.com/console/developers/8038496863446330572/app/4972821987431706072/managed-products
  (def sku "product_account_balance_100k")


  ;; X.i Getting a Refresh token

  ;; Redirect: https://beatthemarket-c13f8.firebaseapp.com/__/auth/handler
  ;; ClientId: 1062410862638-b94modujdnkue3587c86qfn28960v6u1.apps.googleusercontent.com
  ;; Client Secret: Agr60XOia8zDsUlDP74CpMAz


  ;; Generating a refresh token
  ;; https://developers.google.com/android-publisher/authorization#generating_a_refresh_token
  ;; https://accounts.google.com/o/oauth2/auth?scope=https://www.googleapis.com/auth/androidpublisher&response_type=code&access_type=offline&redirect_uri=...&client_id=...


  ;; https://accounts.google.com/o/oauth2/auth?scope=https://www.googleapis.com/auth/androidpublisher&response_type=code&access_type=offline&redirect_uri=https://beatthemarket-c13f8.firebaseapp.com/__/auth/handler&client_id=1062410862638-b94modujdnkue3587c86qfn28960v6u1.apps.googleusercontent.com

  ;; https://beatthemarket-c13f8.firebaseapp.com/__/auth/handler
  ;; ?code= 4/4AE96XXWhqviSmHlPml0HAS0mYtCAbUPyA6_UmMuRee7M-X6NgFvWVmwE9xDODr7pUEXioZMOD1pwP0PuhAHBy0
  ;; &scope= https%3A//www.googleapis.com/auth/androidpublisher


  ;; X. POST Template
  ;; https://accounts.google.com/o/oauth2/token
  ;; grant_type=authorization_code
  ;; code=<the code from the previous step>
  ;; client_id=<the client ID token created in the APIs Console>
  ;; client_secret=<the client secret corresponding to the client ID>
  ;; redirect_uri=<the URI registered with the client ID>


  ;; X. Access token (Refresh token will have an iniital Access token)
  ;; {
  ;;  "access_token": "ya29.a0AfH6SMByPGLgn-PL549C6rybLcjAwA1EGdNgJz0_3wrOFyanGNP4FypLcYelm44JW0ycb4OpzFQ0UMMovg94Dat9fJmoADumKf_r5_lNSFFe-3DM3JCBpaeZrLC6SU6XxZFMHs3AwdPCf-nIG2jiSZ_EG2x4uG_eZz59",
  ;;  "expires_in": 3456,
  ;;  "scope": "https://www.googleapis.com/auth/androidpublisher",
  ;;  "token_type": "Bearer"
  ;;  }




  ;; X. Query Subscriptions & Products
  ;; https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{packageName}/purchases/subscriptions/{subscriptionId}/tokens/{token}
  ;; https://androidpublisher.googleapis.com/androidpublisher/v3/applications/Beatthemarket/purchases/subscriptions/account_balance_100k/tokens/{token}


  ;; X. Using the access token
  ;; https://developers.google.com/android-publisher/authorization#using_the_access_token


  ;; X. How to setup a valid Webhook URL for Google Play (Using the API Efficiently)
  ;; https://developer.android.com/google/play/developer-api.html#practices

  ;; Real-time developer notifications reference guide
  ;; https://developer.android.com/google/play/billing/rtdn-reference

  ;; Google Cloud Pub/Sub, lets you to receive data that can be pushed to a URL
  ;; https://developer.android.com/google/play/billing/getting-ready#configure-rtdn


  ;; X. Testing purchase token
  ;; https://medium.com/androiddevelopers/implementing-linkedpurchasetoken-correctly-to-prevent-duplicate-subscriptions-82dfbf7167da
  ;; https://medium.com/androiddevelopers/subscriptions-101-for-android-apps-b7005a7e93a6
  ;; https://developer.android.com/google/play/billing/test ("Test your Google Play Billing Library integration")



  (def httpTransport (GoogleNetHttpTransport/newTrustedTransport))
  (def jsonFactory (JacksonFactory/getDefaultInstance))
  (def credential (doto (GoogleCredential/fromStream
                          (-> (Thread/currentThread)
                              (.getContextClassLoader)
                              (.getResourceAsStream "beatthemarket-c13f8-a87cc30b3d77.json")))
                    (.createScoped (Collections/singleton AndroidPublisherScopes/ANDROIDPUBLISHER))))

  (def publisher (doto (AndroidPublisher$Builder. httpTransport jsonFactory credential)
                   (.setApplicationName PACKAGE_NAME)
                   (.build)))


  ;; X.
  ;; ProductPurchase product = publisher.purchases().products().get(PACKAGE_NAME sku token).execute();
  ;; Integer purchaseState = product.getPurchaseState();
  ;; product.getPurchaseTimeMillis();
  ;; product.getConsumptionState();
  ;; product.getDeveloperPayload();

  ;; SubscriptionPurchase sub = publisher.purchases().subscriptions().get(PACKAGE_NAME, sku, token).execute();
  ;; sub.getAutoRenewing();
  ;; sub.getCancelReason();

  )
