(ns beatthemarket.handler.authentication
  (:require [clojure.tools.logging :as log]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.helpers :as interceptor.helpers]
            [integrant.core :as ig]
            [ring.util.response]
            [beatthemarket.iam.authentication :as iam.auth]
            [beatthemarket.util :refer [exists?]]))


(defn request->token [request]
  (-> request :headers
      (get "authorization")
      (clojure.string/split #"Bearer ")
      last))

(defn auth-request-handler [request]

  (log/debug :auth-request request)

  (let [{:keys [errorCode message] :as checked-authentication} (iam.auth/check-authentication (request->token request))]

    ;; (println "auth-request-handler CALLED / " checked-authentication)

    (if (every? exists? [errorCode message])
      (throw (ex-info message checked-authentication))
      (assoc request :checked-authentication checked-authentication))))

(def auth-request
  "Authenticate the request"

  #_(interceptor.helpers/on-request
      ::auth-interceptor
      (fn [request]

      (log/debug :auth-request request)

      #_(let [{:keys [errorCode message] :as error} (iam.auth/check-authentication (request->token request))]
        (if (every? exists? [errorCode message])
          (throw (ex-info message error))
          request))))

  (interceptor/interceptor
    {:name ::auth-interceptor

     :enter (fn [context]
              (assoc context :request (auth-request-handler (:request context))))

     :error (fn [context ex]
              (let [response (-> (ring.util.response/response (.getMessage ex))
                                 (assoc :status 401))]
                (assoc context :response response)))}))

(defn auth-interceptor
  [service-map]
  (update service-map :io.pedestal.http/interceptors conj auth-request))
