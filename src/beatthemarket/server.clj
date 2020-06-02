(ns beatthemarket.server
  (:gen-class)
  (:require [clojure.java.io :refer [resource]]
            [clojure.tools.cli :as tools.cli]
            [io.pedestal.http :as server]
            [unilog.config  :refer [start-logging!]]
            [integrant.core :as ig]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [beatthemarket.service :as service]
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


(defmethod ig/init-key :server/server [_ {:keys [service]}]

  (let [conditionally-apply-dev-interceptor
        (fn [service-map]
          (if (-> service :env (= :development))
            (server/dev-interceptors service-map)
            service-map))]

    (-> service
        server/default-interceptors
        conditionally-apply-dev-interceptor
        auth/auth-interceptor

        ;; TODO

        ;; A
        ;; https://lacinia-pedestal.readthedocs.io/en/stable/overview.html
        ;; com.walmartlabs.lacinia.pedestal/service-map (deprecated. use default-service)
        ;; com.walmartlabs.lacinia.pedestal2/default-service

        ;; B
        ;; integrate other interceptors
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

(defn ^:private resolve-hello
  [context args value]
  "Hello, Clojurians!")

(defn ^:private hello-schema []

  (-> "schema.lacinia.edn"
      resource
      slurp
      edn/read-string
      (util/attach-resolvers {:resolve-hello resolve-hello})
      schema/compile))

(comment ;; Lacinia

  (require '[clojure.edn :as edn]
           '[com.walmartlabs.lacinia.pedestal2 :as pedestal :refer [default-service]]
           '[com.walmartlabs.lacinia.schema :as schema]
           '[com.walmartlabs.lacinia.util :as util]
           '[io.pedestal.http :as http]
           '[io.pedestal.http.jetty.websockets :as ws])

  ;; Legacy
  (let [service {:env :production
                 ::http/routes service/routes
                 ::http/resource-path "/public"
                 ::http/type :jetty
                 ::http/container-options {:context-configurator #(ws/add-ws-endpoints % service/ws-paths)}
                 ::http/port 8080}]
    (-> service
        io.pedestal.http/default-interceptors
        auth/auth-interceptor
        pprint))

  ;; Lacinia
  (-> (hello-schema)
      (pedestal/default-service {:graphiql true})
      pprint)
  )
