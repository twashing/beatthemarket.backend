(ns beatthemarket.handler.http.service
  (:require [clojure.java.io :refer [resource]]
            [clojure.edn :as edn]
            [clojure.string :as s]
            [integrant.repl.state :as repl.state]
            [io.pedestal.http :as http]
            [io.pedestal.log :as log]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route.definition :refer [defroutes]]

            [ring.util.response :as ring-resp]
            [clojure.core.async :as core.async]
            [clojure.data.json :as json]
            [clj-time.core :as t]
            [clj-time.coerce :as c]

            [io.pedestal.http.jetty.websockets :as ws]
            [io.pedestal.interceptor.error :as error-int]
            [integrant.core :as ig]
            [beatthemarket.handler.graphql.core :as graphql.core]

            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.iam.authentication :as iam.auth]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.datasource :as datasource]
            [beatthemarket.datasource.core :as datasource.core]
            [beatthemarket.util :refer [ppi] :as util]

            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]

            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as lacinia.util]
            [com.walmartlabs.lacinia.pedestal2]
            [com.walmartlabs.lacinia.pedestal :refer [inject]]
            [com.walmartlabs.lacinia.pedestal.subscriptions :as sub]
            [markdown.core :refer [md-to-html-string]]
            [rop.core :as rop])

  (:import [org.eclipse.jetty.websocket.api Session]
           [org.eclipse.jetty.websocket.common WebSocketSession]
           [org.eclipse.jetty.websocket.servlet ServletUpgradeRequest]))


(def ws-clients (atom {}))

(defn new-ws-client [ws-session send-ch]

  (core.async/put! send-ch "This will be a text message")

  ;; (.setIdleTimeout ^WebSocketSession session timeout-ms)
  (log/info :ws-session ws-session)

  (swap! ws-clients assoc ws-session send-ch)
  (log/info :ws-clients (format "WS Clients / %s / %s" (count @ws-clients) @ws-clients)))

(def ws-paths
  {"/ws" {:on-connect (ws/start-ws-connection new-ws-client)
          :on-text (fn [msg] (log/info :msg (str "A client sent - " msg)))
          :on-binary (fn [payload _offset _length] (log/info :msg "Binary Message!" :bytes payload))
          :on-error (fn [t]
                      (log/error :msg "WS Error happened" :exception t))
          :on-close (fn [_num-code reason-text]
                      (log/info :msg "WS Closed:" :reason reason-text))}})

(def ^:private health-check-path "/health")
(def ^:private privacy-policy-path "/privacy")
(def ^:private terms-and-conditions-path "/terms")
(def ^:private support-url-path "/support")

(def ^:private default-api-path "/api")
(def ^:private default-asset-path "/assets/graphiql")

(defn health-handler [request]
  {:status 200 :body "ok"})

(defn privacy-handler [request]

  (ring-resp/content-type {:status 200
                           :body (-> "PRIVACY.md" resource slurp md-to-html-string)}
                          "text/html; charset=UTF-8"))

(defn terms-handler [request]

  (ring-resp/content-type {:status 200
                           :body (-> "TERMS.md" resource slurp md-to-html-string)}
                          "text/html; charset=UTF-8"))

(defn support-handler [request]

  (ring-resp/content-type {:status 200
                           :body (-> "SUPPORT.md" resource slurp md-to-html-string)}
                          "text/html; charset=UTF-8"))


(defn auth-request-handler-ws [context]

  (let [token (if (-> context :request :authorization)
                (-> context :request :authorization)
                (-> context :connection-params :token))

        id-token (-> token
                     (s/split #"Bearer ")
                     last)

        user-exists? (fn [{id-token :id-token :as input}]

                       (let [decoded-token (second (iam.auth/decode-token id-token))
                             email         (get decoded-token "email")

                             conn (-> repl.state/system :persistence/datomic :opts :conn)]

                         (if (iam.user/user-exists? (iam.persistence/user-by-email conn email))
                           (rop/succeed input)
                           (rop/fail (ex-info "User hasn't yet been created" decoded-token)))))

        authenticated? (fn [{id-token :id-token}]

                         (let [{:keys [errorCode message] :as checked-authentication} (iam.auth/check-authentication id-token)]

                           (if (every? util/exists? [errorCode message])
                             (rop/fail (ex-info message checked-authentication))
                             (rop/succeed {:checked-authentication checked-authentication}))))

        result (rop/>>= {:id-token id-token}
                        user-exists?
                        authenticated?)]

    (if (= clojure.lang.ExceptionInfo (type result))

      (let [{:keys [message data]} (bean result)]
        (throw (ex-info message data)))

      (let [{checked-authentication :checked-authentication} result]
        (assoc-in context [:request :checked-authentication] checked-authentication)))))

(def auth-subscription-request-interceptor
  (interceptor/interceptor
    {:name ::auth-request
     :enter auth-request-handler-ws
     :error (fn [context ^Throwable t]
              (let [{:keys [id response-data-ch]} (:request context)

                    ;; Strip off the wrapper exception added by Pedestal
                    ;; payload (#'sub/construct-exception-payload (.getCause t))
                    payload (#'sub/construct-exception-payload t)]
                (core.async/put! response-data-ch {:type :error
                                                   :id id
                                                   :payload payload})
                (core.async/close! response-data-ch)))}))

(defn options-builder
  [compiled-schema]
  {:subscription-interceptors
   (-> (sub/default-subscription-interceptors compiled-schema nil)
       (inject auth-subscription-request-interceptor :before ::sub/query-parser))

   :init-context
   (fn [ctx ^ServletUpgradeRequest req _resp]

     (let [auth-h (.getHeader req "Authorization")]
       (assoc-in ctx [:request :authorization] auth-h)))})

(defn default-service
  "Taken from com.walmartlabs.lacinia.pedestal2/default-service:
  See docs there."
  [compiled-schema options]
  (let [{:keys [health-path privacy-path terms-path support-path
                api-path ide-path asset-path app-context port]

         :or {health-path health-check-path
              privacy-path privacy-policy-path
              terms-path terms-and-conditions-path
              support-path support-url-path

              api-path default-api-path
              ide-path "/ide"
              asset-path default-asset-path}} options

        interceptors (com.walmartlabs.lacinia.pedestal2/default-interceptors compiled-schema app-context)

        full-routes (route/expand-routes
                      (into #{[health-path :get [health-handler] :route-name :health]
                              [privacy-path :get [privacy-handler] :route-name :privacy]
                              [terms-path :get [terms-handler] :route-name :terms]
                              [support-path :get [support-handler] :route-name :support]

                              [api-path :post interceptors :route-name ::graphql-api]
                              [ide-path :get (com.walmartlabs.lacinia.pedestal2/graphiql-ide-handler options)
                               :route-name ::graphiql-ide]}
                            (com.walmartlabs.lacinia.pedestal2/graphiql-asset-routes asset-path)))]

    (-> (merge options {::http/routes full-routes})
        com.walmartlabs.lacinia.pedestal2/enable-graphiql
        (com.walmartlabs.lacinia.pedestal2/enable-subscriptions compiled-schema options))))

(defn ^:private lacinia-schema []

  (-> "schema.lacinia.edn"
      resource slurp edn/read-string
      (lacinia.util/attach-resolvers {:resolve-login                     graphql.core/resolve-login
                                      :resolve-create-game               graphql.core/resolve-create-game
                                      :resolve-start-game                graphql.core/resolve-start-game
                                      :resolve-buy-stock                 graphql.core/resolve-buy-stock
                                      :resolve-sell-stock                graphql.core/resolve-sell-stock
                                      :resolve-account-balances          graphql.core/resolve-account-balances
                                      :resolve-user                      graphql.core/resolve-user
                                      :resolve-users                     graphql.core/resolve-users
                                      :resolve-user-personal-profit-loss graphql.core/resolve-user-personal-profit-loss
                                      :resolve-user-market-profit-loss   graphql.core/resolve-user-market-profit-loss
                                      :resolve-pause-game                graphql.core/resolve-pause-game
                                      :resolve-resume-game               graphql.core/resolve-resume-game
                                      :resolve-restart-game              graphql.core/resolve-restart-game
                                      :resolve-exit-game                 graphql.core/resolve-exit-game
                                      :resolve-games                     graphql.core/resolve-games

                                      :user-payments            graphql.core/user-payments
                                      :verify-payment           graphql.core/verify-payment
                                      :create-stripe-customer   graphql.core/create-stripe-customer
                                      :delete-stripe-customer   graphql.core/delete-stripe-customer})
      (lacinia.util/attach-streamers {:stream-stock-ticks       graphql.core/stream-stock-ticks
                                      :stream-portfolio-updates graphql.core/stream-portfolio-updates
                                      :stream-game-events       graphql.core/stream-game-events})
      schema/compile))


(defmethod ig/init-key :service/service [_ {:keys [env join? hostname port keep-alive-ms]}]

  (let [options {:env           env
                 :keep-alive-ms keep-alive-ms
                 ::http/join?   join?

                 ;; Uncomment next line to enable CORS support, add
                 ;; string(s) specifying scheme, host and port for
                 ;; allowed source(s):
                 ;;
                 ;; "http://localhost:8080"
                 ;;
                 ;; ::http/allowed-origins ["scheme://host:port"]
                 ::http/allowed-origins {:creds true :allowed-origins (constantly true)}


                 ;; Root for resource interceptor that is available by default.
                 ::http/resource-path     "/public"
                 ::http/type              :jetty
                 ::http/container-options {:context-configurator #(ws/add-ws-endpoints % ws-paths)}
                 ::http/host              hostname
                 ::http/port              port}

        compiled-schema (lacinia-schema)
        options' (merge options
                        {:graphiql true}
                        (options-builder compiled-schema))]

    (default-service compiled-schema options')))
