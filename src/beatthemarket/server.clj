(ns beatthemarket.server
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


(defn ^:private resolve-hello
  [context args value]
  "Hello, Clojurians!")

(defn ^:private lacinia-schema []

  (-> "schema.lacinia.edn"
      resource
      slurp
      edn/read-string
      (util/attach-resolvers {:resolve-hello resolve-hello})
      schema/compile))

(defn log-handler [request]

  (println "Sanity check")
  ;; (log/info :log request)
  request)

(def log-request
  (interceptor/interceptor
    {:name ::log
     :enter (fn [context]
              (assoc context :request (log-handler (:request context))))}))

(defn pprint+identity [e]
  (clojure.pprint/pprint e)
  e)

#_(defn inject-lacinia-configuration [context]

  ;; Legacy keys
  ;; (:env
  ;;  :io.pedestal.http/route
  ;;  :io.pedestal.http/port
  ;;  :io.pedestal.http/type
  ;;  :io.pedestal.http/container-options  ;;
  ;;  :io.pedestal.http/resource-path
  ;;  :io.pedestal.http/interceptors)

  ;; Lacinia keys
  ;; (:env
  ;;  :io.pedestal.http/routes
  ;;  :io.pedestal.http/port
  ;;  :io.pedestal.http/type
  ;;  :io.pedestal.http/container-options
  ;;  :io.pedestal.http/join?
  ;;  :io.pedestal.http/secure-headers)

  ;; Common keys
  ;; :io.pedestal.http/routes ;; [ok] concat these
  ;; :io.pedestal.http/port ;; [ok] pick 8080
  ;; :io.pedestal.http/type ;; [ok] same
  ;; :io.pedestal.http/container-options ;; [ok] WS connection + messages are handled by lacinia

  (let [{routes :io.pedestal.http/routes
         container-options :io.pedestal.http/container-options}
        (-> (lacinia-schema)
            (pedestal/default-service {:graphiql true
                                       :subscription-interceptors [log-request]
                                       })
            (select-keys [:io.pedestal.http/routes :io.pedestal.http/container-options]))

        lacinia-routes (->> (expand-routes routes)
                            (map (fn [e]
                                   (update e :path-parts #(into [] (cons "" %)))))

                            ;; (map trace)
                            ;; pprint+identity
                            )]

    (-> (update context :io.pedestal.http/routes concat lacinia-routes)
        (assoc :io.pedestal.http/container-options container-options))))

#_(defmethod ig/init-key :server/server [_ {:keys [service]}]

    (let [conditionally-apply-dev-interceptor
          (fn [service-map]
            (if (-> service :env (= :development))
              (server/dev-interceptors service-map)
              service-map))]

      (-> service
          server/default-interceptors
          conditionally-apply-dev-interceptor
          auth/auth-interceptor
          inject-lacinia-configuration

          ;; TODO

          ;; A
          ;; https://lacinia-pedestal.readthedocs.io/en/stable/overview.html
          ;; com.walmartlabs.lacinia.pedestal/service-map (deprecated. use default-service)
          ;; com.walmartlabs.lacinia.pedestal2/default-service

          ;; B
          ;; integrate other interceptors
          server/create-server
          server/start)))

(defmethod ig/init-key :server/server [_ {:keys [service]}]

  (-> (lacinia-schema)
      #_(pedestal/default-service {:graphiql true
                                   :subscription-interceptors [log-request]})
      (service/default-service {:graphiql true
                                :subscription-interceptors [log-request]})
      server/create-server
      server/start))


#_(defmethod ig/init-key :server/server [_ {:keys [service]}]

  (let [conditionally-apply-dev-interceptor
        (fn [service-map]
          (if (-> service :env (= :development))
            (server/dev-interceptors service-map)
            service-map))]

    (-> service
        server/default-interceptors
        conditionally-apply-dev-interceptor
        auth/auth-interceptor
        inject-lacinia-configuration

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
