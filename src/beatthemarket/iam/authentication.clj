(ns beatthemarket.iam.authentication
  (:require [clojure.string :as s]
            [clojure.data.json :as json]
            [clojure.java.io :refer [resource input-stream]]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [beatthemarket.util :as util])
  (:import [com.google.firebase FirebaseApp FirebaseOptions]
           [com.google.firebase.auth FirebaseAuth FirebaseAuthException FirebaseToken]
           [com.google.auth.oauth2 GoogleCredentials]
           [org.apache.commons.codec.binary Base64]))


(defn initialize-firebase [firebase-database-url service-account-file-name]

  (let [serviceAccounts (-> service-account-file-name
                            resource
                            input-stream)

        ^FirebaseOptions options (.. (com.google.firebase.FirebaseOptions$Builder.)
                                     (setCredentials (GoogleCredentials/fromStream serviceAccounts))
                                     (setDatabaseUrl firebase-database-url)
                                     (build))]

    (try
      (FirebaseApp/initializeApp options)
      (catch IllegalStateException e
        (let [{:keys [message data]} (bean e)]
          (log/info (format "Exception initializing firebase / Message: %s / Data: %s" message data)))))))

(defn verify-id-token [token]
  (.. (FirebaseAuth/getInstance)
      (verifyIdToken token)))

(defn check-authentication
  ([token]
   (check-authentication token (comp bean verify-id-token)))
  ([token verification-fn]
   (try
     (verification-fn token)
     (catch FirebaseAuthException e
       (bean e)))))

(defn authentication?-raw [{:keys [email name uid user_id]}]
  (util/truthy? (or (and email name uid)
                    (and email user_id))))

(defn authenticated?
  ([token]
   (authenticated? token (comp bean verify-id-token)))
  ([token verification-fn]
   (authentication?-raw (check-authentication token verification-fn))))

(defn decode-token [token]
  (as-> token jwt ;; returned-jwt is your full jwt string
    (s/split jwt #"\.") ;; split into the 3 parts of a jwt, header, body, signature
    (take 2 jwt)  ;; get the header and body
    (map #(Base64/decodeBase64 %) jwt) ;; read it into a byte array
    (map #(String. %) jwt) ;; byte array to string
    (map json/read-str jwt)))

(defmethod ig/init-key :firebase/firebase [_ {:keys [firebase-database-url service-account-file-name] :as opts}]
  (initialize-firebase firebase-database-url service-account-file-name)
  opts)


(comment

  (def invalid-jwt "eyJhbGciOiJSUzI1NiIsImtpZCI6IjgyMmM1NDk4YTcwYjc0MjQ5NzI2ZDhmYjYxODlkZWI3NGMzNWM4MGEiLCJ0eXAiOiJKV1QifQ.eyJuYW1lIjoiVGltb3RoeSBXYXNoaW5ndG9uIiwicGljdHVyZSI6Imh0dHBzOi8vbGgzLmdvb2dsZXVzZXJjb250ZW50LmNvbS9hLS9BT2gxNEdnTVpFbGNwZjV5X0dLOXlGR29NYm9PLUUwbng1ZnM0UTlrd0tNUCIsImlzcyI6Imh0dHBzOi8vc2VjdXJldG9rZW4uZ29vZ2xlLmNvbS9iZWF0dGhlbWFya2V0LWMxM2Y4IiwiYXVkIjoiYmVhdHRoZW1hcmtldC1jMTNmOCIsImF1dGhfdGltZSI6MTU5MDY4ODY4NywidXNlcl9pZCI6IlZFRGdMRU9rMWVYWjVqWVVjYzROa2xBVTNLdjIiLCJzdWIiOiJWRURnTEVPazFlWFo1allVY2M0TmtsQVUzS3YyIiwiaWF0IjoxNTkwNjg4Njg3LCJleHAiOjE1OTA2OTIyODcsImVtYWlsIjoidHdhc2hpbmdAZ21haWwuY29tIiwiZW1haWxfdmVyaWZpZWQiOnRydWUsImZpcmViYXNlIjp7ImlkZW50aXRpZXMiOnsiZ29vZ2xlLmNvbSI6WyIxMDE5MTIzMTEyMjc2OTY5NTQyMzgiXSwiZW1haWwiOlsidHdhc2hpbmdAZ21haWwuY29tIl19LCJzaWduX2luX3Byb3ZpZGVyIjoiZ29vZ2xlLmNvbSJ9fQ.F9jmnjx-W6Kx6dpRhTm6tP2RBvpZGYwj6OC2xSAfIyh-yciJz7go9YklPXRDw_yzI-BW5a6FRxRWNudKoLZuw9lRs48qqYE8q1VnduFYtJm6kEd0VMDI6D7yeFmp0HLYlIeVjnT2Xdq4e1PlzO3jnoO1d3RwbNqSEk9wJ2k94mEayXyqpdv5FjwGwTyepf5EjfYsar8_wHDdeyZ3fF9yvTEjwsPand2K7NnH0B68IycYUszEgZLWcCwn_4RuixujG99W6y4QiwYaUpYFORDjuydoN1i0LGb4HWYHG7puk-e91aegvjAOzKaCq3Snfe1VTSMJFmUpq2ohgJICLn_hSAA")

  (def valid-jwt "eyJhbGciOiJSUzI1NiIsImtpZCI6IjgyMmM1NDk4YTcwYjc0MjQ5NzI2ZDhmYjYxODlkZWI3NGMzNWM4MGEiLCJ0eXAiOiJKV1QifQ.eyJuYW1lIjoiVGltb3RoeSBXYXNoaW5ndG9uIiwicGljdHVyZSI6Imh0dHBzOi8vbGgzLmdvb2dsZXVzZXJjb250ZW50LmNvbS9hLS9BT2gxNEdnTVpFbGNwZjV5X0dLOXlGR29NYm9PLUUwbng1ZnM0UTlrd0tNUCIsImlzcyI6Imh0dHBzOi8vc2VjdXJldG9rZW4uZ29vZ2xlLmNvbS9iZWF0dGhlbWFya2V0LWMxM2Y4IiwiYXVkIjoiYmVhdHRoZW1hcmtldC1jMTNmOCIsImF1dGhfdGltZSI6MTU5MDg1NDIxMywidXNlcl9pZCI6IlZFRGdMRU9rMWVYWjVqWVVjYzROa2xBVTNLdjIiLCJzdWIiOiJWRURnTEVPazFlWFo1allVY2M0TmtsQVUzS3YyIiwiaWF0IjoxNTkwODU0MjEzLCJleHAiOjE1OTA4NTc4MTMsImVtYWlsIjoidHdhc2hpbmdAZ21haWwuY29tIiwiZW1haWxfdmVyaWZpZWQiOnRydWUsImZpcmViYXNlIjp7ImlkZW50aXRpZXMiOnsiZ29vZ2xlLmNvbSI6WyIxMDE5MTIzMTEyMjc2OTY5NTQyMzgiXSwiZW1haWwiOlsidHdhc2hpbmdAZ21haWwuY29tIl19LCJzaWduX2luX3Byb3ZpZGVyIjoiZ29vZ2xlLmNvbSJ9fQ.qFn6zNrYU9f4-NEOdihucS8IGxTdRQzpv366yfKSj_jXr_jLIjPvpGlztp1W2lMiYGYwI4__Fyf-77rG8ggYD5temJvylAnPjfQac_ZY8TCbkAjblZjqjJc3FXk55ZypqDAqIFHZSWLifQaS3_M2J0bFK3mCbbwrMM2fmhvK1OAEAsHGAEyXJ-yuz0Lz55MPNQ3PyOqe0cvSShDXBskFOg29mxdH61FY5u5h3fyTksZ4ki3hqM3wn3CrkFsx5mRFftjS7oeiby1CmU7YDGA90yKuii53iMbbibxbUe_pJFKCxCDQfyFVWksO4DNTSjj9LhC1MLBzxxvxfQj8RE-4Xw")

  (initialize-firebase "https://beatthemarket-c13f8.firebaseio.com" "beatthemarket-c13f8-firebase-adminsdk-k3cwr-5129bb442c.json")

  (verify-id-token invalid-jwt)
  (def erroredToken (check-authentication invalid-jwt))
  (let [{:keys [errorCode message]} erroredToken]
    (println [errorCode message]))


  (def ^FirebaseToken decodedToken (verify-id-token valid-jwt))
  (let [{:keys [email name uid]} (bean decodedToken)]
    (println [email name uid]))


  (check-authentication valid-jwt)
  (authenticated? valid-jwt)




  ;; ================
  (require '[integrant.repl.state :as state]
           '[clojure.pprint])

  (def uid (-> state/config :firebase/firebase :admin-user-id))

  (def ^String customToken
    (.. (FirebaseAuth/getInstance)
        (createCustomToken uid)))

  (clojure.pprint/pprint (decode-token customToken))

  (clojure.pprint/pprint (verify-id-token customToken))


  (require '[clj-http.client :as http])


  (defn token->body-payload [customToken]
    (json/write-str {:token             customToken
                     :returnSecureToken true}))

  (def api-key (-> state/config :firebase/firebase :api-key))

  (http/post (format "https://www.googleapis.com/identitytoolkit/v3/relyingparty/verifyCustomToken?key=%s" api-key)
             {:content-type :json
              :body         (token->body-payload customToken)})

  (def result *1)

  (-> result
      :body
      (json/read-str :key-fn keyword)
      :idToken
      check-authentication ;; verify-id-token
      clojure.pprint/pprint))

(comment

  (require '[beatthemarket.test-util :as test-util])
  (initialize-firebase "https://beatthemarket-c13f8.firebaseio.com" "beatthemarket-c13f8-firebase-adminsdk-k3cwr-5129bb442c.json")

  (def email-password-jwt "eyJhbGciOiJSUzI1NiIsImtpZCI6IjFlNjYzOGY4NDlkODVhNWVkMGQ1M2NkNDI1MzE0Y2Q1MGYwYjY1YWUiLCJ0eXAiOiJKV1QifQ.eyJpc3MiOiJodHRwczovL3NlY3VyZXRva2VuLmdvb2dsZS5jb20vYmVhdHRoZW1hcmtldC1jMTNmOCIsImF1ZCI6ImJlYXR0aGVtYXJrZXQtYzEzZjgiLCJhdXRoX3RpbWUiOjE2MDA5NTMxNDAsInVzZXJfaWQiOiI3a2Z0Y05iS2dOZG1MaFNyamQ2Uk5wREtLWjMyIiwic3ViIjoiN2tmdGNOYktnTmRtTGhTcmpkNlJOcERLS1ozMiIsImlhdCI6MTYwMDk1MzE0MCwiZXhwIjoxNjAwOTU2NzQwLCJlbWFpbCI6InJleWtqYXZpazE1MUBnbWFpbC5jb20iLCJlbWFpbF92ZXJpZmllZCI6ZmFsc2UsImZpcmViYXNlIjp7ImlkZW50aXRpZXMiOnsiZW1haWwiOlsicmV5a2phdmlrMTUxQGdtYWlsLmNvbSJdfSwic2lnbl9pbl9wcm92aWRlciI6InBhc3N3b3JkIn19.nBImIuJxAeRr-9nxjyt4GVypCptAUROFjgpQmXJCFuOmzzbU0uDfNx76aDN7HeITlFAt_7pzQYoox8BqHTyfwtaHh0m2Ow4m3bH46888vgb0D3xSJcrdeY-eAK7fiCj0DS_MDjgBdOLhnW79SX0obAvCuvLp2cMh9_pH358vhsAZZ9ugVgAb07L0_Kt4Lewaf1Uk8-jfgGXQqAE316x9tonF9P4h6oVHTxbyBPcMO6aTzwZi2lnv9RV4b9jqcVytsqXpMoCiCpiUy81bE-6IbTWCu5pVO8Wy0AXYYdFKRhWFS4ouM-IW4GMpe_HdvqXrR6Mgl8AKNcV1IsUVF3qL5g")

  (def email-password-jwt id-token)
  (def email-password-jwt (test-util/->id-token))
  (pprint id-token)


  (def ^FirebaseToken decodedToken (verify-id-token email-password-jwt))
  (let [{:keys [email name uid]} (bean decodedToken)]
    (util/ppi [email name uid]))

  (util/ppi (bean decodedToken))

  )
