(ns beatthemarket.integration.payments.google
  (:require [clj-http.client :as client]
            [clojure.data.json :as json]
            [beatthemarket.util :as util])
  (:import [com.google.api.client.json.jackson2 JacksonFactory]
           [com.google.api.client.auth.oauth2 TokenResponse]
           [com.google.api.client.googleapis.javanet GoogleNetHttpTransport]
           [com.google.api.client.googleapis.auth.oauth2 GoogleCredential GoogleCredential$Builder]
           [com.google.api.services.androidpublisher AndroidPublisher AndroidPublisher$Builder AndroidPublisherScopes]
           [java.util Collections]))


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

(defn ->refresh-token [])

(defn ->access-token []

  )

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
        :access_token))

  )

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

  ;; private static HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
  ;; private static JsonFactory JSON_FACTORY = new com.google.api.client.json.jackson2.JacksonFactory();
  ;; private static Long getSubscriptionExpire(String accessToken, String refreshToken, String subscriptionId, String purchaseToken){
  ;;
  ;; try{
  ;;
  ;;     TokenResponse tokenResponse = new TokenResponse();
  ;;     tokenResponse.setAccessToken(accessToken);
  ;;     tokenResponse.setRefreshToken(refreshToken);
  ;;     tokenResponse.setExpiresInSeconds(3600L);
  ;;     tokenResponse.setScope("https://www.googleapis.com/auth/androidpublisher");
  ;;     tokenResponse.setTokenType("Bearer");
  ;;
  ;;     HttpRequestInitializer credential =  new GoogleCredential.Builder().setTransport(HTTP_TRANSPORT)
  ;;             .setJsonFactory(JSON_FACTORY)
  ;;             .setClientSecrets(GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET)
  ;;             .build()
  ;;             .setFromTokenResponse(tokenResponse);
  ;;
  ;;     Androidpublisher publisher = new Androidpublisher.Builder(HTTP_TRANSPORT, JSON_FACTORY, credential).
  ;;             setApplicationName(GOOGLE_PRODUCT_NAME).
  ;;             build();
  ;;
  ;;     Androidpublisher.Purchases purchases = publisher.purchases();
  ;;     Get get = purchases.get(GOOGLE_PACKAGE_NAME, subscriptionId, purchaseToken);
  ;;     SubscriptionPurchase subscripcion = get.execute();
  ;;
  ;;     return subscripcion.getValidUntilTimestampMsec();
  ;;
  ;; }
  ;; catch (IOException e) { e.printStackTrace(); }
  ;; return null;

  ;; (def key "beatthemarket-c13f8-a87cc30b3d77.json")

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
    (def subscriptionId "product_account_balance_100k")
    (def purchaseToken (str "inapp:" googlePackageName ":android.test.purchased")))

  ;; (def purchases (.purchases publisher))
  ;; (def get (.get purchases googlePackageName subscriptionId purchaseToken))
  ;; SubscriptionPurchase subscription = get.execute()
  ;; subscripcion.getValidUntilTimestampMsec()

  (def result (.. publisher
                  (purchases)
                  (products)
                  (get googlePackageName subscriptionId purchaseToken)
                  (execute)))


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
  ;; ?code= 4/4AGDk3-wNikuaNZsKKj-SpTcw2KOcuR5Zk9OJ9dBYXCtMeH1n_UFGSmEuVlQiqMplGoaf1oVBE0aL5IyHP_u69U
  ;; &scope= https://www.googleapis.com/auth/androidpublisher


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
