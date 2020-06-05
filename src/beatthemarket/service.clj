(ns beatthemarket.service
  (:require [io.pedestal.http :as http]
            [io.pedestal.log :as log]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route.definition :refer [defroutes]]
            ;; [io.pedestal.http :as server]
            [ring.util.response :as ring-resp]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [clj-time.core :as t]
            [clj-time.coerce :as c]

            [io.pedestal.http.jetty.websockets :as ws]
            [io.pedestal.interceptor.error :as error-int]
            [integrant.core :as ig]
            [beatthemarket.datasource :as datasource]
            [beatthemarket.datasource.core :as datasource.core]

            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.interceptor.chain :as chain]

            [com.walmartlabs.lacinia.pedestal2]
            [com.walmartlabs.lacinia.pedestal :refer [inject]]
            [com.walmartlabs.lacinia.pedestal.subscriptions :as s])

  (:import [org.eclipse.jetty.websocket.api Session]))


(defn about-page
  [request]
  (ring-resp/response (format "Clojure %s - served from %s"
                              (clojure-version)
                              (route/url-for ::about-page))))

(defn home-page
  [request]
  (ring-resp/response "Hello World!"))

(def service-error-handler
  "References:
   http://pedestal.io/reference/error-handling
   https://stuarth.github.io/clojure/error-dispatch/
   http://pedestal.io/cookbook/index#_how_to_handle_errors"

  (error-int/error-dispatch
    [context ex]

    [{:exception-type :clojure.lang.ExceptionInfo
      :interceptor :beatthemarket.handler.authentication/auth-interceptor}]
    (let [response (-> (ring.util.response/response (.getMessage ex)) :status 401)]

      (assoc context :response response))

    :else
    (assoc context :io.pedestal.interceptor.chain/error ex)))

(def throwing-interceptor
  (interceptor/interceptor {:name ::throwing-interceptor
                            :enter (fn [ctx]
                                     ;; Simulated processing error
                                     (/ 1 0))
                            :error (fn [ctx ex]
                                     ;; Here's where you'd handle the exception
                                     ;; Remember to base your handling decision
                                     ;; on the ex-data of the exception.

                                     (let [{:keys [exception-type exception]} (ex-data ex)]
                                       ;; If you cannot handle the exception, re-attach it to the ctx
                                       ;; using the `:io.pedestal.interceptor.chain/error` key
                                       (assoc ctx ::chain/error ex)))}))

(defroutes routes
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
(defn send-and-close! []
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
          :on-binary (fn [payload offset length] (log/info :msg "Binary Message!" :bytes payload))
          :on-error (fn [t] (log/error :msg "WS Error happened" :exception t))
          :on-close (fn [num-code reason-text]
                      (log/info :msg "WS Closed:" :reason reason-text))}})

(def ^:private default-api-path "/api")
(def ^:private default-asset-path "/assets/graphiql")
(def ^:private default-subscriptions-path "/ws")

;; NOTE subscription interceptor
(def ^:private invoke-count-interceptor
  "Used to demonstrate that subscription interceptor customization works."
  (interceptor/interceptor
    {:name ::invoke-count
     :enter (fn [context]
              ;; (println "invoke-count-interceptor CALLED / " context)
              context)}))

(defn options-builder
  [compiled-schema]
  {:subscription-interceptors
   ;; Add ::invoke-count, and ensure it executes before ::execute-operation.
   (-> (s/default-subscription-interceptors compiled-schema nil)
       (inject invoke-count-interceptor :before ::s/execute-operation))

   ;; :init-context
   ;; (fn [ctx ^ServletUpgradeRequest req resp]
   ;;   (reset! *invoke-count 0)
   ;;   (reset! *user-agent nil)
   ;;   (assoc-in ctx [:request :user-agent] (.getHeader (.getHttpServletRequest req) "User-Agent")))
   })

(defn default-service
  "Taken from com.walmartlabs.lacinia.pedestal2/default-service:

   Returns a default Pedestal service map, with subscriptions and GraphiQL enabled.

   The defaults put the GraphQL API at `/api` and the GraphiQL IDE at `/ide` (and subscriptions endpoint
   at `/ws`).

   Unlike earlier versions of lacinia-pedestal, only POST is supported, and the content type must
   be `application/json`.

   compiled-schema is either the schema or a function returning the schema.

   options is a map combining options needed by [[graphiql-ide-route]] and [[listener-fn-factory]].

   It may also contain keys :app-context and :port (which defaults to 8888).

   This is useful for initial development and exploration, but applications with any more needs should construct
   their service map directly."
  [compiled-schema options]
  (let [{:keys [api-path ide-path asset-path app-context port]
         :or {api-path default-api-path
              ide-path "/ide"
              asset-path default-asset-path
              port 8888}} options
        interceptors (com.walmartlabs.lacinia.pedestal2/default-interceptors compiled-schema app-context)

        lacinia-routes
        (concat routes
                (route/expand-routes
                  (into #{[api-path :post interceptors :route-name ::graphql-api]
                          [ide-path :get (com.walmartlabs.lacinia.pedestal2/graphiql-ide-handler options) :route-name ::graphiql-ide]}
                        (com.walmartlabs.lacinia.pedestal2/graphiql-asset-routes asset-path))))]

    (-> {:env :dev
         ::http/routes lacinia-routes
         ::http/port port
         ::http/type :jetty
         ::http/join? false}
        com.walmartlabs.lacinia.pedestal2/enable-graphiql
        (com.walmartlabs.lacinia.pedestal2/enable-subscriptions compiled-schema options))))

(defmethod ig/init-key :service/service [_ {:keys [env join? hostname port] :as opts}]
  {:env env
   ::http/join? join?

   ;; You can bring your own non-default interceptors. Make
   ;; sure you include routing and set it up right for
   ;; dev-mode. If you do, many other keys for configuring
   ;; default interceptors will be ignored.
   ;; ::http/interceptors []
   ::http/routes routes

   ;; Uncomment next line to enable CORS support, add
   ;; string(s) specifying scheme, host and port for
   ;; allowed source(s):
   ;;
   ;; "http://localhost:8080"
   ;;
   ;;::http/allowed-origins ["scheme://host:port"]

   ;; Root for resource interceptor that is available by default.
   ::http/resource-path "/public"

   ;; Either :jetty, :immutant or :tomcat (see comments in project.clj)
   ::http/type :jetty
   ::http/container-options {:context-configurator #(ws/add-ws-endpoints % ws-paths)}

   ::http/host hostname
   ::http/port port})

(defn coerce-to-client [[time price]]
  (-> (vector (c/to-long time) price)
      json/write-str))

(defn stream-stock-data []
  (->> (datasource/->combined-data-sequence datasource.core/beta-configurations)
       (datasource/combined-data-sequence-with-datetime (t/now))
       (map coerce-to-client)
       (take 100)
       (run! send-message-to-all!)))

(comment

  (->> (datasource/->combined-data-sequence datasource.core/beta-configurations)
       (datasource/combined-data-sequence-with-datetime (t/now))
       (map coerce-to-client)
       (take 30)
       pprint)

  (stream-stock-data))
