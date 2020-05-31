(ns beatthemarket.handler.authentication
  (:require [clojure.tools.logging :as log]
            [io.pedestal.interceptor :as pedestal.interceptor]
            [io.pedestal.interceptor.helpers :as interceptor]
            [integrant.core :as ig]
            [beatthemarket.iam.authentication :as iam.auth]
            [beatthemarket.util :refer [exists?]]))


(defn request->token [request]
  (-> request
      :headers
      (get "authorization")
      (clojure.string/split #"Bearer ")
      last))

(def auth-request
  "Authenticate the request"
  (interceptor/on-request
    ::auth-interceptor
    (fn [request]

      (log/debug :auth-request request)
      ;; (pprint request)

      (let [{:keys [errorCode message] :as error} (iam.auth/check-authentication (request->token request))]
        (if (every? exists? [errorCode message])
          (throw (ex-info message error))
          request)))))

(defn auth-interceptor
  [service-map]
  (update service-map :io.pedestal.http/interceptors conj auth-request))
