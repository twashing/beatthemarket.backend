(ns beatthemarket.iam.authentication
  (:require [clojure.data.json :as json]
            [clojure.java.io :refer [resource input-stream]])
  (:import [com.google.firebase FirebaseApp FirebaseOptions]
           [com.google.firebase.auth FirebaseAuth FirebaseAuthException FirebaseToken]
           [com.google.auth.oauth2 GoogleCredentials]
           [java.io FileInputStream]))


(def invalid-jwt "eyJhbGciOiJSUzI1NiIsImtpZCI6IjgyMmM1NDk4YTcwYjc0MjQ5NzI2ZDhmYjYxODlkZWI3NGMzNWM4MGEiLCJ0eXAiOiJKV1QifQ.eyJuYW1lIjoiVGltb3RoeSBXYXNoaW5ndG9uIiwicGljdHVyZSI6Imh0dHBzOi8vbGgzLmdvb2dsZXVzZXJjb250ZW50LmNvbS9hLS9BT2gxNEdnTVpFbGNwZjV5X0dLOXlGR29NYm9PLUUwbng1ZnM0UTlrd0tNUCIsImlzcyI6Imh0dHBzOi8vc2VjdXJldG9rZW4uZ29vZ2xlLmNvbS9iZWF0dGhlbWFya2V0LWMxM2Y4IiwiYXVkIjoiYmVhdHRoZW1hcmtldC1jMTNmOCIsImF1dGhfdGltZSI6MTU5MDY4ODY4NywidXNlcl9pZCI6IlZFRGdMRU9rMWVYWjVqWVVjYzROa2xBVTNLdjIiLCJzdWIiOiJWRURnTEVPazFlWFo1allVY2M0TmtsQVUzS3YyIiwiaWF0IjoxNTkwNjg4Njg3LCJleHAiOjE1OTA2OTIyODcsImVtYWlsIjoidHdhc2hpbmdAZ21haWwuY29tIiwiZW1haWxfdmVyaWZpZWQiOnRydWUsImZpcmViYXNlIjp7ImlkZW50aXRpZXMiOnsiZ29vZ2xlLmNvbSI6WyIxMDE5MTIzMTEyMjc2OTY5NTQyMzgiXSwiZW1haWwiOlsidHdhc2hpbmdAZ21haWwuY29tIl19LCJzaWduX2luX3Byb3ZpZGVyIjoiZ29vZ2xlLmNvbSJ9fQ.F9jmnjx-W6Kx6dpRhTm6tP2RBvpZGYwj6OC2xSAfIyh-yciJz7go9YklPXRDw_yzI-BW5a6FRxRWNudKoLZuw9lRs48qqYE8q1VnduFYtJm6kEd0VMDI6D7yeFmp0HLYlIeVjnT2Xdq4e1PlzO3jnoO1d3RwbNqSEk9wJ2k94mEayXyqpdv5FjwGwTyepf5EjfYsar8_wHDdeyZ3fF9yvTEjwsPand2K7NnH0B68IycYUszEgZLWcCwn_4RuixujG99W6y4QiwYaUpYFORDjuydoN1i0LGb4HWYHG7puk-e91aegvjAOzKaCq3Snfe1VTSMJFmUpq2ohgJICLn_hSAA")


(comment ;; https://stackoverflow.com/questions/38732656/decode-jwt-in-clojure-java

  (import '[org.apache.commons.codec.binary Base64])

  #_(-> invalid-jwt ;; returned-jwt is your full jwt string
        (clojure.string/split #"\.") ;; split into the 3 parts of a jwt, header, body, signature
        second ;; get the body
        Base64/decodeBase64 ;; read it into a byte array
        String. ;; byte array to string
        json/read-str ;; make it into a sensible clojure map
        pprint)

  (as-> invalid-jwt jwt ;; returned-jwt is your full jwt string
    (clojure.string/split jwt #"\.") ;; split into the 3 parts of a jwt, header, body, signature
    (take 2 jwt) ;; second ;; get the body
    (map #(Base64/decodeBase64 %) jwt) ;; read it into a byte array
    (map #(String. %) jwt) ;; byte array to string
    (map json/read-str jwt) ;; make it into a sensible clojure map
    (pprint jwt))


  ;; ? Still authorized
  ;; ! validate claims
  ;; ! verify token


  ;; time lib
  ;;   - auth_time
  ;;   - expiry


  ;; CLAIMS

  ;; exp - expiry
  ;; iat - Issued At
  ;; auth_time (custom claim - synonym for iat)

  ;; aud - audience: Make sure beatthemarket app is the correct audience (projectId)
  ;; sub - subject: ? How to determine if subject is who they say they are
  ;; iss - issuer: Make sure the correct app ID URL is the issuer



  ;; Where's the Firebase Project (or app) public key, so we can verify a token?
  ;; In the Firebase console (Settings > Service Accounts), I see a way to generate a custom private key file for your service account.
  ;; But there must already bey a key pair used, since the firebaseui lib gets an idToken, without a custom private key file
  ;;
  ;; i) See:
  ;;   https://stackoverflow.com/questions/37634305/firebase-token-verification
  ;;   https://stackoverflow.com/questions/37762359/firebase-authenticate-with-backend-server
  ;;
  ;; ii) Maybe this is handled by Adding the Firebase Admin SDK to your server?
  ;;   https://firebase.google.com/docs/admin/setup#java
  ;;   https://stackoverflow.com/a/37492640/375616
  ;;
  ;; sub (subject)
  ;;   How is the subject string generated?
  ;;   And how can a server verify it against Firebase's API?
  ;;
  ;; iss (issuer)
  ;;   Where in the Firebase console (or SDK), can I find this value?
  ;;   Or should we just use concatenate googles token URL + my project ID?
  ;;   https://securetoken.google.com/<projectId>
  ;;
  ;; How is the `kid` header parameter used to hint which key was used to secure the JSON Web Signature?
  ;;   https://tools.ietf.org/html/rfc7515#section-4.1.4
  )

(comment ;; https://github.com/liquidz/clj-jwt

  (require 'clj-jwt.core)

  (-> invalid-jwt
      clj-jwt.core/str->jwt
      clj-jwt.core/verify))

(comment

  ;; https://github.com/funcool/buddy-sign
  ;; https://www.bradcypert.com/using-json-web-tokens-with-clojure/
  )

(comment

  ;; Verify JWT using the Firebase Admin SDK on the server
  ;;   https://firebase.google.com/docs/admin/setup#java
  ;;   https://stackoverflow.com/a/37492640/375616
  ;;   https://firebase.google.com/docs/auth/admin/verify-id-tokens#web
  ;;   https://firebase.google.com/docs/auth/admin/verify-id-tokens#java
  ;;   https://firebase.google.com/docs/reference/admin (Admin SDK Reference)



  ;; A
  (def ^FileInputStream serviceAccount
    (FileInputStream. "./beatthemarket-c13f8-firebase-adminsdk-k3cwr-5129bb442c.json"));

  (def ^FirebaseOptions options
    (.. (com.google.firebase.FirebaseOptions$Builder.)
      (setCredentials (GoogleCredentials/fromStream serviceAccounts) )
      (setDatabaseUrl "https://beatthemarket-c13f8.firebaseio.com")
      (build)))

  (FirebaseApp/initializeApp options)

  ;; B
  (try

    (def ^FirebaseToken decodedToken
      (.. (FirebaseAuth/getInstance)
          (verifyIdToken invalid-jwt)))

    (catch FirebaseAuthException e

      (println (.getErrorCode e))
      (println (.getMessage e))))

  (def ^String uid (.getUid decodedToken))
  (println uid))

(defn initialize-firebase [firebase-database-url service-account-file-name]

  (let [serviceAccounts (-> service-account-file-name
                            resource
                            input-stream)

        ^FirebaseOptions options (.. (com.google.firebase.FirebaseOptions$Builder.)
                                     (setCredentials (GoogleCredentials/fromStream serviceAccounts))
                                     (setDatabaseUrl firebase-database-url)
                                     (build))]

    (FirebaseApp/initializeApp options)))

(defn verify-token [token]
  (try
    (.. (FirebaseAuth/getInstance)
        (verifyIdToken token))
    (catch FirebaseAuthException e
      (bean e))))

(comment

  (def valid-jwt "eyJhbGciOiJSUzI1NiIsImtpZCI6IjgyMmM1NDk4YTcwYjc0MjQ5NzI2ZDhmYjYxODlkZWI3NGMzNWM4MGEiLCJ0eXAiOiJKV1QifQ.eyJuYW1lIjoiVGltb3RoeSBXYXNoaW5ndG9uIiwicGljdHVyZSI6Imh0dHBzOi8vbGgzLmdvb2dsZXVzZXJjb250ZW50LmNvbS9hLS9BT2gxNEdnTVpFbGNwZjV5X0dLOXlGR29NYm9PLUUwbng1ZnM0UTlrd0tNUCIsImlzcyI6Imh0dHBzOi8vc2VjdXJldG9rZW4uZ29vZ2xlLmNvbS9iZWF0dGhlbWFya2V0LWMxM2Y4IiwiYXVkIjoiYmVhdHRoZW1hcmtldC1jMTNmOCIsImF1dGhfdGltZSI6MTU5MDc4Mjk3OCwidXNlcl9pZCI6IlZFRGdMRU9rMWVYWjVqWVVjYzROa2xBVTNLdjIiLCJzdWIiOiJWRURnTEVPazFlWFo1allVY2M0TmtsQVUzS3YyIiwiaWF0IjoxNTkwNzgyOTc4LCJleHAiOjE1OTA3ODY1NzgsImVtYWlsIjoidHdhc2hpbmdAZ21haWwuY29tIiwiZW1haWxfdmVyaWZpZWQiOnRydWUsImZpcmViYXNlIjp7ImlkZW50aXRpZXMiOnsiZ29vZ2xlLmNvbSI6WyIxMDE5MTIzMTEyMjc2OTY5NTQyMzgiXSwiZW1haWwiOlsidHdhc2hpbmdAZ21haWwuY29tIl19LCJzaWduX2luX3Byb3ZpZGVyIjoiZ29vZ2xlLmNvbSJ9fQ.TUNBCfQpL4vebbecLN7lxLM_yQ5x07N9t9d0K1EQ1xXm7bBGc4sVXhSpee9ASoQ6xPF9h019r7z5Euq-MhHE0EuLT_63sHGV_RJjCYuGelWx8EaDNu07H55edaAkeggv2SpgxHqujeTOwqmzfcMFAUg6MuWLY93LWoW5cs91lPVxQ2RkP42okjPI0fapX9yQNF0icFR86i_KU84wqndaSwd6338L29Xgeli1w4Npo6qfFMkFSeDwAKvm3Z1wxRWw9UVdojfkEr08R-JzDSjRJ9KylZ9h5k9Zm1PTi7bKh4WuTId-WCbDCDlrxRQfcJlIryWT_-mebutrciD8x-Mllg")

  (initialize-firebase "https://beatthemarket-c13f8.firebaseio.com" "beatthemarket-c13f8-firebase-adminsdk-k3cwr-5129bb442c.json")

  (def erroredToken (verify-token invalid-jwt))
  (let [{:keys [errorCode message]} errordToken]
    (println [errorCode message]))


  (def ^FirebaseToken decodedToken (verify-token valid-jwt))
  (let [{:keys [email name uid]} (bean decodedToken)]
    (println [email name uid]))

  )
