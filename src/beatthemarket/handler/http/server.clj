(ns beatthemarket.handler.http.server
  (:gen-class)
  (:require [clojure.java.io :refer [resource]]
            [clojure.edn :as edn]
            [clojure.tools.cli :as tools.cli]
            [clojure.tools.logging :as log]
            [io.pedestal.http :as server]
            [io.pedestal.http.route :refer [expand-routes]]
            [io.pedestal.interceptor :as interceptor]
            [com.walmartlabs.lacinia.pedestal2 :as pedestal :refer [default-service]]
            [com.walmartlabs.lacinia.schema :as schema]
            [com.walmartlabs.lacinia.util :as util]
            [unilog.config  :refer [start-logging!]]
            [integrant.core :as ig]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [beatthemarket.handler.http.service :as service]
            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.nrepl]
            [beatthemarket.iam.authentication]
            [aero.core :as aero]))


(def logging-config
  {:level   :info
   :console true
   :appenders [{:appender :rolling-file
                :rolling-policy {:type :fixed-window
                                 :max-index 5}
                :triggering-policy {:type :size-based
                                    :max-size 5120}
                :pattern  "%p [%d] %t - %c %m%n"
                :file     "logs/beatthemarket.log"}]
   :overrides  {"org.apache.http"      :debug
                "org.apache.http.wire" :error}})

(start-logging! logging-config)


(defn ^:private resolve-hello
  [context args value]
  "Hello, Clojurians!")

(defn ^:private resolve-login
  [context args value]

  ;; TODO conditionally saves new user
  :user

  ;; TODO
  {:status 200}

  "login CALLED")

(defn ^:private stream-new-game
  [context args source-stream]

  ;; TODO play
  ;;   creates a new game

  ;;   creates a list of market stocks
  ;;   picks a default stock

  ;;   subscribes user to default stock
  ;;   pushes Portfolio positions + value to client
  ;;   streams the default stock to client
  [:game :level :user :book :stock :subscription]

  (let [{:keys [message]} args

        runnable ^Runnable (fn []
                             (source-stream {:message message})
                             (Thread/sleep 50)
                             (source-stream nil))]

    (.start (Thread. runnable "stream-ping-thread"))
    ;; Return a cleanup fn:
    (constantly nil)))


;; NOTE subscription resolver
(def *ping-subscribes (atom 0))
(def *ping-cleanups (atom 0))
(def *ping-context (atom nil))
(defn ^:private stream-ping
  [context args source-stream]
  (swap! *ping-subscribes inc)
  (reset! *ping-context context)
  (let [{:keys [message count]} args
        runnable ^Runnable (fn []
                             (dotimes [i count]

                               ;; (println "Sanity check / " [i count])
                               (source-stream {:message (str message " #" (inc i))
                                               :timestamp (System/currentTimeMillis)})
                               (Thread/sleep 50))

                             (source-stream nil))]
    (.start (Thread. runnable "stream-ping-thread")))
  ;; Return a cleanup fn:
  #(swap! *ping-cleanups inc))

(defn ^:private lacinia-schema []

  (-> "schema.lacinia.edn"
      resource
      slurp
      edn/read-string
      (util/attach-resolvers {:resolve-hello resolve-hello
                              :resolve-login resolve-login})
      (util/attach-streamers {:stream-ping stream-ping
                              :stream-new-game stream-new-game})
      ;; trace
      schema/compile))

#_(defn log-handler [request]

  (println "Sanity check")
  ;; (log/info :log request)
  request)

#_(def log-request
  (interceptor/interceptor
    {:name ::log
     :enter (fn [context]
              (println "Sanity 1 / " context)
              (assoc context :request (log-handler (:request context))))}))

#_(defn log-interceptor
  [service-map]
  (update service-map :io.pedestal.http/interceptors conj log-request))

(defmethod ig/init-key :server/server [_ {:keys [service]}]

  (let [compiled-schema (lacinia-schema)
        options (merge {:graphiql true}
                       (service/options-builder compiled-schema))]

    (-> (service/default-service compiled-schema options)

        server/default-interceptors
        ;; conditionally-apply-dev-interceptor
        auth/auth-interceptor
        server/create-server
        server/start)))

(defmethod ig/halt-key! :server/server [_ server]
  (server/stop server))

;; NOTE taken from a suggestion from an Integrant issue
;; https://github.com/weavejester/integrant/issues/12#issuecomment-283415380
(defmethod aero/reader 'ig/ref [_ _ value]
  (ig/ref value))

(def default-environment "production")
(def cli-options
  [["-p" "--profile NAME"
    (format "Environment profile name must be `development`, or `production`.
             Order of precedence is below:

             A. Checks command line argument
             B. First looks for value in `ENVIRONMENT`.
             C. If not provided, will default to `%s`."
            default-environment)
    :parse-fn keyword
    :validate [#{:development :production}]]])

(defn- get-env-variable [name default-val]
  (or (System/getenv name) default-val))

(defn- process-parsed-options [options]
  (letfn [(missing? [m k]
            (not (contains? m k)))]

    (cond-> options
      (missing? options :profile)
      (assoc :profile
             (keyword (get-env-variable "ENVIRONMENT" default-environment))) )))

(defn -main
  "The entry-point for 'lein run'"
  [& args]

  (let [{:keys [options summary errors]} (tools.cli/parse-opts args cli-options)
        {profile :profile} (process-parsed-options options)]

    (println "\nCreating your server...")

    (integrant.repl/set-prep!
      (constantly (-> "config.edn"
                      resource
                      (aero.core/read-config {:profile profile})
                      :integrant)))

    (integrant.repl/go)))

(comment ;; Main


  (-main "-p" "production")


  (-> "integrant-config.edn"
      resource
      (aero.core/read-config {:profile :dev})
      :integrant)


  (binding [*data-readers* {'ig/ref ig/ref}]
    (-> "integrant-config.edn"
        resource
        (aero.core/read-config {:profile :dev}))))
