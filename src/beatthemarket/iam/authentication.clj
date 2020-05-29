(ns beatthemarket.iam.authentication
  (:require [clojure.data.json :as json]
            [clojure.java.io :refer [resource input-stream]]
            [integrant.core :as ig])
  (:import [com.google.firebase FirebaseApp FirebaseOptions]
           [com.google.firebase.auth FirebaseAuth FirebaseAuthException FirebaseToken]
           [com.google.auth.oauth2 GoogleCredentials]
           [java.io FileInputStream]))



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

(defmethod ig/init-key :firebase/firebase [_ {:keys [firebase-database-url service-account-file-name] :as opts}]
  (initialize-firebase firebase-database-url service-account-file-name))




(comment

  (def invalid-jwt "eyJhbGciOiJSUzI1NiIsImtpZCI6IjgyMmM1NDk4YTcwYjc0MjQ5NzI2ZDhmYjYxODlkZWI3NGMzNWM4MGEiLCJ0eXAiOiJKV1QifQ.eyJuYW1lIjoiVGltb3RoeSBXYXNoaW5ndG9uIiwicGljdHVyZSI6Imh0dHBzOi8vbGgzLmdvb2dsZXVzZXJjb250ZW50LmNvbS9hLS9BT2gxNEdnTVpFbGNwZjV5X0dLOXlGR29NYm9PLUUwbng1ZnM0UTlrd0tNUCIsImlzcyI6Imh0dHBzOi8vc2VjdXJldG9rZW4uZ29vZ2xlLmNvbS9iZWF0dGhlbWFya2V0LWMxM2Y4IiwiYXVkIjoiYmVhdHRoZW1hcmtldC1jMTNmOCIsImF1dGhfdGltZSI6MTU5MDY4ODY4NywidXNlcl9pZCI6IlZFRGdMRU9rMWVYWjVqWVVjYzROa2xBVTNLdjIiLCJzdWIiOiJWRURnTEVPazFlWFo1allVY2M0TmtsQVUzS3YyIiwiaWF0IjoxNTkwNjg4Njg3LCJleHAiOjE1OTA2OTIyODcsImVtYWlsIjoidHdhc2hpbmdAZ21haWwuY29tIiwiZW1haWxfdmVyaWZpZWQiOnRydWUsImZpcmViYXNlIjp7ImlkZW50aXRpZXMiOnsiZ29vZ2xlLmNvbSI6WyIxMDE5MTIzMTEyMjc2OTY5NTQyMzgiXSwiZW1haWwiOlsidHdhc2hpbmdAZ21haWwuY29tIl19LCJzaWduX2luX3Byb3ZpZGVyIjoiZ29vZ2xlLmNvbSJ9fQ.F9jmnjx-W6Kx6dpRhTm6tP2RBvpZGYwj6OC2xSAfIyh-yciJz7go9YklPXRDw_yzI-BW5a6FRxRWNudKoLZuw9lRs48qqYE8q1VnduFYtJm6kEd0VMDI6D7yeFmp0HLYlIeVjnT2Xdq4e1PlzO3jnoO1d3RwbNqSEk9wJ2k94mEayXyqpdv5FjwGwTyepf5EjfYsar8_wHDdeyZ3fF9yvTEjwsPand2K7NnH0B68IycYUszEgZLWcCwn_4RuixujG99W6y4QiwYaUpYFORDjuydoN1i0LGb4HWYHG7puk-e91aegvjAOzKaCq3Snfe1VTSMJFmUpq2ohgJICLn_hSAA")

  (def valid-jwt "eyJhbGciOiJSUzI1NiIsImtpZCI6IjgyMmM1NDk4YTcwYjc0MjQ5NzI2ZDhmYjYxODlkZWI3NGMzNWM4MGEiLCJ0eXAiOiJKV1QifQ.eyJuYW1lIjoiVGltb3RoeSBXYXNoaW5ndG9uIiwicGljdHVyZSI6Imh0dHBzOi8vbGgzLmdvb2dsZXVzZXJjb250ZW50LmNvbS9hLS9BT2gxNEdnTVpFbGNwZjV5X0dLOXlGR29NYm9PLUUwbng1ZnM0UTlrd0tNUCIsImlzcyI6Imh0dHBzOi8vc2VjdXJldG9rZW4uZ29vZ2xlLmNvbS9iZWF0dGhlbWFya2V0LWMxM2Y4IiwiYXVkIjoiYmVhdHRoZW1hcmtldC1jMTNmOCIsImF1dGhfdGltZSI6MTU5MDc4Mjk3OCwidXNlcl9pZCI6IlZFRGdMRU9rMWVYWjVqWVVjYzROa2xBVTNLdjIiLCJzdWIiOiJWRURnTEVPazFlWFo1allVY2M0TmtsQVUzS3YyIiwiaWF0IjoxNTkwNzgyOTc4LCJleHAiOjE1OTA3ODY1NzgsImVtYWlsIjoidHdhc2hpbmdAZ21haWwuY29tIiwiZW1haWxfdmVyaWZpZWQiOnRydWUsImZpcmViYXNlIjp7ImlkZW50aXRpZXMiOnsiZ29vZ2xlLmNvbSI6WyIxMDE5MTIzMTEyMjc2OTY5NTQyMzgiXSwiZW1haWwiOlsidHdhc2hpbmdAZ21haWwuY29tIl19LCJzaWduX2luX3Byb3ZpZGVyIjoiZ29vZ2xlLmNvbSJ9fQ.TUNBCfQpL4vebbecLN7lxLM_yQ5x07N9t9d0K1EQ1xXm7bBGc4sVXhSpee9ASoQ6xPF9h019r7z5Euq-MhHE0EuLT_63sHGV_RJjCYuGelWx8EaDNu07H55edaAkeggv2SpgxHqujeTOwqmzfcMFAUg6MuWLY93LWoW5cs91lPVxQ2RkP42okjPI0fapX9yQNF0icFR86i_KU84wqndaSwd6338L29Xgeli1w4Npo6qfFMkFSeDwAKvm3Z1wxRWw9UVdojfkEr08R-JzDSjRJ9KylZ9h5k9Zm1PTi7bKh4WuTId-WCbDCDlrxRQfcJlIryWT_-mebutrciD8x-Mllg")

  (initialize-firebase "https://beatthemarket-c13f8.firebaseio.com" "beatthemarket-c13f8-firebase-adminsdk-k3cwr-5129bb442c.json")

  (def erroredToken (verify-token invalid-jwt))
  (let [{:keys [errorCode message]} errordToken]
    (println [errorCode message]))


  (def ^FirebaseToken decodedToken (verify-token valid-jwt))
  (let [{:keys [email name uid]} (bean decodedToken)]
    (println [email name uid])))
