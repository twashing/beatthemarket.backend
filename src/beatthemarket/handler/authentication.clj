(ns beatthemarket.handler.authentication
  (:require [clojure.tools.logging :as log]
            [clojure.string :as s]
            [ring.util.response]
            [io.pedestal.interceptor :as interceptor]
            [com.walmartlabs.lacinia.util :refer [as-error-map]]
            [beatthemarket.iam.authentication :as iam.auth]
            [beatthemarket.util :refer [exists?]]))


(defn request->token [request]
  (-> request :headers
      (get "authorization")
      (s/split #"Bearer ")
      last))

(defn auth-request-handler [request]

  (log/debug :auth-request request)

  (if (= {:uri "/health"
          :request-method :get}
         (select-keys request [:uri :request-method]))

    request

    (let [{:keys [errorCode message] :as checked-authentication} (iam.auth/check-authentication (request->token request))]

      (if (every? exists? [errorCode message])
        (throw (ex-info message checked-authentication))
        (assoc request :checked-authentication checked-authentication)))))

(def auth-request
  "Authenticate the request"

  (interceptor/interceptor
    {:name ::auth-interceptor

     :enter (fn [context]
              (assoc context :request (auth-request-handler (:request context))))

     :error (fn [context ex]
              (let [response (-> (ring.util.response/response {:errors [(-> (as-error-map ex)
                                                                            (dissoc :extensions))]})
                                 (assoc :status 401))]
                (assoc context :response response)))}))

(defn auth-interceptor
  [service-map]
  (update service-map :io.pedestal.http/interceptors conj auth-request))
