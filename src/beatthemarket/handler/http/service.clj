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
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clj-time.core :as t]
            [clj-time.coerce :as c]

            [io.pedestal.http.jetty.websockets :as ws]
            [io.pedestal.interceptor.error :as error-int]
            [integrant.core :as ig]
            [beatthemarket.graphql :as graphql]

            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.iam.authentication :as iam.auth]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.datasource :as datasource]
            [beatthemarket.datasource.core :as datasource.core]
            [beatthemarket.util]

            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]

            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]
            [com.walmartlabs.lacinia.pedestal2]
            [com.walmartlabs.lacinia.pedestal :refer [inject]]
            [com.walmartlabs.lacinia.pedestal.subscriptions :as sub]
            [rop.core :as rop])

  (:import [org.eclipse.jetty.websocket.api Session]
           [org.eclipse.jetty.websocket.servlet ServletUpgradeRequest]))


(defn about-page
  [_]
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about-page))))

(defn home-page
  [_]
  (ring-resp/response "Hello World!"))

#_(def service-error-handler
  "References:
   http://pedestal.io/reference/error-handling
   https://stuarth.github.io/clojure/error-dispatch/
   http://pedestal.io/cookbook/index#_how_to_handle_errors"

  (error-int/error-dispatch
    [context ex]

    [{:exception-type :clojure.lang.ExceptionInfo
      :interceptor :beatthemarket.handler.authentication/auth-interceptor}]
    (let [response (-> (ring.util.response/response (.getMessage ex)) :status)]

      (assoc context :response response))

    :else
    (assoc context :io.pedestal.interceptor.chain/error ex)))

#_(def throwing-interceptor
  (interceptor/interceptor {:name ::throwing-interceptor
                            :enter (fn [_ctx]
                                     ;; Simulated processing error
                                     (/ 1 0))
                            :error (fn [ctx ex]
                                     ;; Here's where you'd handle the exception
                                     ;; Remember to base your handling decision
                                     ;; on the ex-data of the exception.

                                     (let [{:keys [_exception-type _exception]} (ex-data ex)]
                                       ;; If you cannot handle the exception, re-attach it to the ctx
                                       ;; using the `:io.pedestal.interceptor.chain/error` key
                                       (assoc ctx ::chain/error ex)))}))

(defroutes legacy-routes
  ;; Defines "/" and "/about" routes with their associated :get handlers.
  ;; The interceptors defined after the verb map (e.g., {:get home-page}
  ;; apply to / and its children (/about).
  [[["/" {:get home-page} ^:interceptors [(body-params/body-params) http/html-body]
     ["/about" {:get about-page}]]]]

  #_[[["/" {:get home-page} ^:interceptors [service-error-handler (body-params/body-params) http/html-body]
       ["/about" {:get about-page}]]]])

(def ws-clients (atom {}))

(defn new-ws-client
  [ws-session send-ch]
  (async/put! send-ch "This will be a text message")
  (swap! ws-clients assoc ws-session send-ch))

;; This is just for demo purposes
#_(defn send-and-close! []
  (let [[ws-session send-ch] (first @ws-clients)]
    (async/put! send-ch "A message from the server")
    ;; And now let's close it down...
    (async/close! send-ch)
    ;; And now clean up
    (swap! ws-clients dissoc ws-session)))

;; Also for demo purposes...
(defn send-message-to-all!
  [message]
  (doseq [[^Session session channel] @ws-clients]
    ;; The Pedestal Websocket API performs all defensive checks before sending,
    ;;  like `.isOpen`, but this example shows you can make calls directly on
    ;;  on the Session object if you need to
    (when (.isOpen session)
      (async/put! channel message))))

(def ws-paths
  {"/ws" {:on-connect (ws/start-ws-connection new-ws-client)
          :on-text (fn [msg] (log/info :msg (str "A client sent - " msg)))
          :on-binary (fn [payload _offset _length] (log/info :msg "Binary Message!" :bytes payload))
          :on-error (fn [t]
                      (println t)
                      (log/error :msg "WS Error happened" :exception t))
          :on-close (fn [_num-code reason-text]
                      (log/info :msg "WS Closed:" :reason reason-text))}})

(def ^:private default-api-path "/api")
(def ^:private default-asset-path "/assets/graphiql")
;; (def ^:private default-subscriptions-path "/ws")


;; NOTE subscription interceptor
#_(def ^:private invoke-count-interceptor
  "Used to demonstrate that subscription interceptor customization works."
  (interceptor/interceptor
    {:name ::invoke-count
     :enter (fn [context]
              ;; (println "invoke-count-interceptor CALLED")
              ;; (clojure.pprint/pprint context)
              context)}))

(defn auth-request-handler-ws [context]

  (let [id-token (-> context :connection-params  :token 
                     (s/split #"Bearer ") beatthemarket.util/pprint+identity
                     last)

        user-exists? (fn [{id-token :id-token :as input}]

                       (let [decoded-token (second (iam.auth/decode-token id-token))
                             email         (get decoded-token "email")

                             conn (-> repl.state/system :persistence/datomic :opts :conn)]

                         (if (iam.user/user-exists? (iam.persistence/user-by-email conn email))
                           (rop/succeed input)
                           (rop/fail (ex-info "User hasn't yet been created" decoded-token)))))

        authenticated? (fn [{id-token :id-token}]

                         ;; (println "Sanity id-token / " input)
                         (let [{:keys [errorCode message] :as checked-authentication} (iam.auth/check-authentication id-token)]

                           (if (every? beatthemarket.util/exists? [errorCode message])
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

(def auth-request-interceptor
  (interceptor/interceptor
    {:name ::auth-request
     :enter auth-request-handler-ws}))

(defn options-builder
  [compiled-schema]
  {:subscription-interceptors
   (-> (sub/default-subscription-interceptors compiled-schema nil)
       (inject auth-request-interceptor :before ::sub/query-parser))

   :init-context
   (fn [ctx ^ServletUpgradeRequest req _resp]

     (let [auth-h (.getHeader req "Authorization")]
       (assoc-in ctx [:request :authorization] auth-h)))})

(defn default-service
  "Taken from com.walmartlabs.lacinia.pedestal2/default-service:
  See docs there."
  [compiled-schema options]
  (let [{:keys [api-path ide-path asset-path app-context port]
         :or {api-path default-api-path
              ide-path "/ide"
              asset-path default-asset-path}} options

        interceptors (com.walmartlabs.lacinia.pedestal2/default-interceptors compiled-schema app-context)

        full-routes
        (concat legacy-routes
                (route/expand-routes
                  (into #{[api-path :post interceptors :route-name ::graphql-api]
                          [ide-path :get (com.walmartlabs.lacinia.pedestal2/graphiql-ide-handler options) :route-name ::graphiql-ide]}
                        (com.walmartlabs.lacinia.pedestal2/graphiql-asset-routes asset-path))))]

    (-> (merge options {::http/routes full-routes})
        com.walmartlabs.lacinia.pedestal2/enable-graphiql
        (com.walmartlabs.lacinia.pedestal2/enable-subscriptions compiled-schema options))))

(defn ^:private lacinia-schema []

  (-> "schema.lacinia.edn"
      resource slurp edn/read-string
      (util/attach-resolvers {:resolve-login                     graphql/resolve-login
                              :resolve-create-game               graphql/resolve-create-game
                              :resolve-start-game                graphql/resolve-start-game
                              :resolve-buy-stock                 graphql/resolve-buy-stock
                              :resolve-sell-stock                graphql/resolve-sell-stock
                              :resolve-account-balances          graphql/resolve-account-balances
                              :resolve-stock-time-series         graphql/resolve-stock-time-series
                              :resolve-user                      graphql/resolve-user
                              :resolve-users                     graphql/resolve-users
                              :resolve-user-personal-profit-loss graphql/resolve-user-personal-profit-loss
                              :resolve-user-market-profit-loss   graphql/resolve-user-market-profit-loss})
      (util/attach-streamers {:stream-stock-ticks       graphql/stream-stock-ticks
                              :stream-portfolio-updates graphql/stream-portfolio-updates
                              :stream-game-events       graphql/stream-game-events})
      schema/compile))


(defmethod ig/init-key :service/service [_ {:keys [env join? hostname port]}]

  (let [options {:env         env
                 ::http/join? join?
                 ;; ::http/routes legacy-routes

                 ;; Uncomment next line to enable CORS support, add
                 ;; string(s) specifying scheme, host and port for
                 ;; allowed source(s):
                 ;;
                 ;; "http://localhost:8080"
                 ;;
                 ;;::http/allowed-origins ["scheme://host:port"]

                 ;; Root for resource interceptor that is available by default.
                 ::http/resource-path     "/public"
                 ::http/type              :jetty
                 ::http/container-options {:context-configurator #(ws/add-ws-endpoints % ws-paths)}

                 ::http/host hostname
                 ::http/port port}

        compiled-schema (lacinia-schema)
        options' (merge options
                        {:graphiql true}
                        (options-builder compiled-schema))]

    (default-service compiled-schema options')))

#_(defn coerce-to-client [[time price]]
  (json/write-str (vector (c/to-long time) price)))

#_(defn stream-stock-data []
  (->> (datasource/->combined-data-sequence datasource.core/beta-configurations)
       (datasource/combined-data-sequence-with-datetime (t/now))
       (map coerce-to-client)
       (take 100)
       (run! send-message-to-all!)))

#_(comment

  (->> (datasource/->combined-data-sequence datasource.core/beta-configurations)
       (datasource/combined-data-sequence-with-datetime (t/now))
       (map coerce-to-client)
       (take 30)
       clojure.pprint/pprint)

  (stream-stock-data))
