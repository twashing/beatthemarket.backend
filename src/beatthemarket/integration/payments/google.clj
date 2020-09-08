(ns beatthemarket.integration.payments.google
  (:require [clj-http.client :as client]
            [beatthemarket.util :as util])
  (:import [com.google.api.client.googleapis.javanet GoogleNetHttpTransport]
           [com.google.api.client.json.jackson2 JacksonFactory]
           [com.google.api.client.googleapis.auth.oauth2 GoogleCredential]
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



(comment

  ;; https://stackoverflow.com/questions/35127086/android-inapp-purchase-receipt-validation-google-play/35138885#35138885
  (def PACKAGE_NAME "Beatthemarket")

  ;; https://stackoverflow.com/questions/43622351/how-to-get-iap-sku-code-from-google-play-console/43622962
  ;; https://play.google.com/console/developers/8038496863446330572/app/4972821987431706072/managed-products
  (def sku "product_account_balance_100k")

  ;; Generating a refresh token
  ;; https://developers.google.com/android-publisher/authorization#generating_a_refresh_token
  ;; https://accounts.google.com/o/oauth2/auth?scope=https://www.googleapis.com/auth/androidpublisher&response_type=code&access_type=offline&redirect_uri=...&client_id=...


  ;; X.
  ;; URL: https://accounts.google.com/o/oauth2/auth?scope=https://www.googleapis.com/auth/androidpublisher&response_type=code&access_type=offline&redirect_uri=https://beatthemarket-c13f8.firebaseapp.com/__/auth/handler&client_id=1062410862638-b94modujdnkue3587c86qfn28960v6u1.apps.googleusercontent.com

  ;; Redirect: https://beatthemarket-c13f8.firebaseapp.com/__/auth/handler
  ;; ClientId: 1062410862638-b94modujdnkue3587c86qfn28960v6u1.apps.googleusercontent.com

  ;; X. Result...
  ;; https://beatthemarket-c13f8.firebaseapp.com/__/auth/handler?code=4/3wHauA2NvknOhmkpN5sklcAGGiQee3hXH3pdpf3SH2Ys_YDTxPjZdNSZqAZrqh10yoKOq9aUwDZahe8v17LoLjw&scope=https://www.googleapis.com/auth/androidpublisher
  ;; code= 4/3wHauA2NvknOhmkpN5sklcAGGiQee3hXH3pdpf3SH2Ys_YDTxPjZdNSZqAZrqh10yoKOq9aUwDZahe8v17LoLjw
  ;; scope= https://www.googleapis.com/auth/androidpublisher


  ;; X. POST Template
  ;; https://accounts.google.com/o/oauth2/token
  ;; grant_type=authorization_code
  ;; code=<the code from the previous step>
  ;; client_id=<the client ID token created in the APIs Console>
  ;; client_secret=<the client secret corresponding to the client ID>
  ;; redirect_uri=<the URI registered with the client ID>

  ;; X. POST results
  ;; https://accounts.google.com/o/oauth2/token
  ;; grant_type=authorization_code
  ;; code=<the code from the previous step>
  ;; client_id=<the client ID token created in the APIs Console>
  ;; client_secret=<the client secret corresponding to the client ID>
  ;; redirect_uri=<the URI registered with the client ID>


  ;; X. Access token
  ;; {
  ;;  "access_token": "ya29.a0AfH6SMC0x5njFXxJB0odXgY-D6dVrALQjYioCbW1bxPMPW6jbr-5-iG8FHnOaoGAM8Kp_UDigasUr6d4ylG_L6zBBZRp2PreJ28VCwGiUJSXl7XVoH1krr2e4oTGq3wTB0RuRtz_-hII0NlGM9ywj_fGcEi6VNApzE8",
  ;;  "expires_in": 3599,
  ;;  "refresh_token": "1//05uaJkTCWegauCgYIARAAGAUSNwF-L9IrvzRvrDy5WzTAWvN6PJi78-V1xiCrh7HAFDVkpHv3sViTGGItBl3tKKki6JPBfPvETWM",
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
                              (.getResourceAsStream "api-8038496863446330572-265555-671e2c17a86e.json")))
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
