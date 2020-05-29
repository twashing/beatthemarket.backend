(ns beatthemarket.iam.authentication
  (:require [clojure.data.json :as json])
  (:import [org.apache.commons.codec.binary Base64]))


(def example-jwt "eyJhbGciOiJSUzI1NiIsImtpZCI6IjgyMmM1NDk4YTcwYjc0MjQ5NzI2ZDhmYjYxODlkZWI3NGMzNWM4MGEiLCJ0eXAiOiJKV1QifQ.eyJuYW1lIjoiVGltb3RoeSBXYXNoaW5ndG9uIiwicGljdHVyZSI6Imh0dHBzOi8vbGgzLmdvb2dsZXVzZXJjb250ZW50LmNvbS9hLS9BT2gxNEdnTVpFbGNwZjV5X0dLOXlGR29NYm9PLUUwbng1ZnM0UTlrd0tNUCIsImlzcyI6Imh0dHBzOi8vc2VjdXJldG9rZW4uZ29vZ2xlLmNvbS9iZWF0dGhlbWFya2V0LWMxM2Y4IiwiYXVkIjoiYmVhdHRoZW1hcmtldC1jMTNmOCIsImF1dGhfdGltZSI6MTU5MDY4ODY4NywidXNlcl9pZCI6IlZFRGdMRU9rMWVYWjVqWVVjYzROa2xBVTNLdjIiLCJzdWIiOiJWRURnTEVPazFlWFo1allVY2M0TmtsQVUzS3YyIiwiaWF0IjoxNTkwNjg4Njg3LCJleHAiOjE1OTA2OTIyODcsImVtYWlsIjoidHdhc2hpbmdAZ21haWwuY29tIiwiZW1haWxfdmVyaWZpZWQiOnRydWUsImZpcmViYXNlIjp7ImlkZW50aXRpZXMiOnsiZ29vZ2xlLmNvbSI6WyIxMDE5MTIzMTEyMjc2OTY5NTQyMzgiXSwiZW1haWwiOlsidHdhc2hpbmdAZ21haWwuY29tIl19LCJzaWduX2luX3Byb3ZpZGVyIjoiZ29vZ2xlLmNvbSJ9fQ.F9jmnjx-W6Kx6dpRhTm6tP2RBvpZGYwj6OC2xSAfIyh-yciJz7go9YklPXRDw_yzI-BW5a6FRxRWNudKoLZuw9lRs48qqYE8q1VnduFYtJm6kEd0VMDI6D7yeFmp0HLYlIeVjnT2Xdq4e1PlzO3jnoO1d3RwbNqSEk9wJ2k94mEayXyqpdv5FjwGwTyepf5EjfYsar8_wHDdeyZ3fF9yvTEjwsPand2K7NnH0B68IycYUszEgZLWcCwn_4RuixujG99W6y4QiwYaUpYFORDjuydoN1i0LGb4HWYHG7puk-e91aegvjAOzKaCq3Snfe1VTSMJFmUpq2ohgJICLn_hSAA")


(comment ;; https://stackoverflow.com/questions/38732656/decode-jwt-in-clojure-java

  #_(-> example-jwt ;; returned-jwt is your full jwt string
        (clojure.string/split #"\.") ;; split into the 3 parts of a jwt, header, body, signature
        second ;; get the body
        Base64/decodeBase64 ;; read it into a byte array
        String. ;; byte array to string
        json/read-str ;; make it into a sensible clojure map
        pprint)

  (as-> example-jwt jwt ;; returned-jwt is your full jwt string
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

  (-> example-jwt
      clj-jwt.core/str->jwt
      clj-jwt.core/verify))


(comment

  ;; https://github.com/funcool/buddy-sign
  ;; https://www.bradcypert.com/using-json-web-tokens-with-clojure/

  (import '[com.google.firebase FirebaseApp FirebaseOptions]
          '[com.google.firebase.auth FirebaseAuth FirebaseAuthException FirebaseToken]
          '[com.google.auth.oauth2 GoogleCredentials]
          '[java.io FileInputStream])


  ;; A
  (def ^FileInputStream serviceAccount
    (FileInputStream. "./beatthemarket-c13f8-firebase-adminsdk-k3cwr-5129bb442c.json"));

  (def ^FirebaseOptions options
    (.. (com.google.firebase.FirebaseOptions$Builder.)
      (setCredentials (GoogleCredentials/fromStream serviceAccount) )
      (setDatabaseUrl "https://beatthemarket-c13f8.firebaseio.com")
      (build)))

  (FirebaseApp/initializeApp options)

  ;; B
  (try

    (def ^FirebaseToken decodedToken
      (.. (FirebaseAuth/getInstance)
          (verifyIdToken example-jwt)))

    (catch FirebaseAuthException e

      (println (.getErrorCode e))
      (println (.getMessage e))))

  (def ^String uid (.getUid decodedToken))
  (println uid)


  )
