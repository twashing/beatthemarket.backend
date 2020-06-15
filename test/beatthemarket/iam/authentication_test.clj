(ns beatthemarket.iam.authentication-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer [response-for]]
            [integrant.repl.state :as state]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.iam.authentication :as sut]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture)


(deftest check-authentication-test

  (testing "Invalid JWT"

    (let [invalid-jwt                 "eyJhbGciOiJSUzI1NiIsImtpZCI6IjgyMmM1NDk4YTcwYjc0MjQ5NzI2ZDhmYjYxODlkZWI3NGMzNWM4MGEiLCJ0eXAiOiJKV1QifQ.eyJuYW1lIjoiVGltb3RoeSBXYXNoaW5ndG9uIiwicGljdHVyZSI6Imh0dHBzOi8vbGgzLmdvb2dsZXVzZXJjb250ZW50LmNvbS9hLS9BT2gxNEdnTVpFbGNwZjV5X0dLOXlGR29NYm9PLUUwbng1ZnM0UTlrd0tNUCIsImlzcyI6Imh0dHBzOi8vc2VjdXJldG9rZW4uZ29vZ2xlLmNvbS9iZWF0dGhlbWFya2V0LWMxM2Y4IiwiYXVkIjoiYmVhdHRoZW1hcmtldC1jMTNmOCIsImF1dGhfdGltZSI6MTU5MDY4ODY4NywidXNlcl9pZCI6IlZFRGdMRU9rMWVYWjVqWVVjYzROa2xBVTNLdjIiLCJzdWIiOiJWRURnTEVPazFlWFo1allVY2M0TmtsQVUzS3YyIiwiaWF0IjoxNTkwNjg4Njg3LCJleHAiOjE1OTA2OTIyODcsImVtYWlsIjoidHdhc2hpbmdAZ21haWwuY29tIiwiZW1haWxfdmVyaWZpZWQiOnRydWUsImZpcmViYXNlIjp7ImlkZW50aXRpZXMiOnsiZ29vZ2xlLmNvbSI6WyIxMDE5MTIzMTEyMjc2OTY5NTQyMzgiXSwiZW1haWwiOlsidHdhc2hpbmdAZ21haWwuY29tIl19LCJzaWduX2luX3Byb3ZpZGVyIjoiZ29vZ2xlLmNvbSJ9fQ.F9jmnjx-W6Kx6dpRhTm6tP2RBvpZGYwj6OC2xSAfIyh-yciJz7go9YklPXRDw_yzI-BW5a6FRxRWNudKoLZuw9lRs48qqYE8q1VnduFYtJm6kEd0VMDI6D7yeFmp0HLYlIeVjnT2Xdq4e1PlzO3jnoO1d3RwbNqSEk9wJ2k94mEayXyqpdv5FjwGwTyepf5EjfYsar8_wHDdeyZ3fF9yvTEjwsPand2K7NnH0B68IycYUszEgZLWcCwn_4RuixujG99W6y4QiwYaUpYFORDjuydoN1i0LGb4HWYHG7puk-e91aegvjAOzKaCq3Snfe1VTSMJFmUpq2ohgJICLn_hSAA"
          {:keys [errorCode message]} (sut/check-authentication invalid-jwt)
          expectedErrorCode           "ERROR_INVALID_CREDENTIAL"
          expectedMessage
          "Firebase ID token has expired or is not yet valid. Get a fresh ID token and try again. See https://firebase.google.com/docs/auth/admin/verify-id-tokens for details on how to retrieve an ID token."]

      (are [x y] (= x y)
        errorCode expectedErrorCode
        message   expectedMessage)))

  (testing "Return value for valid JWT"

    (let [jwt             "foobar"
          admin-user-id   (-> state/config :firebase/firebase :admin-user-id)
          verification-fn (constantly
                            {:claims
                             {"aud" "beatthemarket-c13f8" "auth_time" 1590854213 "exp" 1590857813 "iat" 1590854213 "iss" "https://securetoken.google.com/beatthemarket-c13f8" "sub" "VEDgLEOk1eXZ5jYUcc4NklAU3Kv2" "name" "Timothy Washington" "picture" "https://lh3.googleusercontent.com/a-/AOh14GgMZElcpf5y_GK9yFGoMboO-E0nx5fs4Q9kwKMP" "user_id" "VEDgLEOk1eXZ5jYUcc4NklAU3Kv2" "email" "twashing@gmail.com" "email_verified" true "firebase" {"identities" {"google.com" ["101912311227696954238"] "email" ["twashing@gmail.com"]} "sign_in_provider" "google.com"}}
                             :class         com.google.firebase.auth.FirebaseToken
                             :email         "twashing@gmail.com"
                             :emailVerified true
                             :issuer        "https://securetoken.google.com/beatthemarket-c13f8"
                             :name          "Timothy Washington"
                             :picture
                             "https://lh3.googleusercontent.com/a-/AOh14GgMZElcpf5y_GK9yFGoMboO-E0nx5fs4Q9kwKMP"
                             :uid           admin-user-id})

          {:keys [email name uid]} (sut/check-authentication jwt verification-fn)
          expectedEmail            "twashing@gmail.com"
          expectedName             "Timothy Washington"
          expectedUid              admin-user-id]

      (are [x y] (= x y)
        email expectedEmail
        name  expectedName
        uid   expectedUid)

      (testing "authenticated? function"
        (is (sut/authenticated? jwt verification-fn))))))

(deftest authentication-interceptor-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        {status :status} (response-for service
                                       :get "/"
                                       :headers {"Authorization"
                                                 (str "Bearer sample-JWT_String")})
        expected-error-status 401]

    (is (= expected-error-status status))))
