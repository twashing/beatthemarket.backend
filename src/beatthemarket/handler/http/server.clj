(ns beatthemarket.handler.http.server
  (:gen-class)
  (:require [clojure.java.io :refer [resource]]
            [clojure.tools.cli :as tools.cli]
            [io.pedestal.http :as server]
            [com.rpl.specter :refer [transform MAP-VALS]]
            [integrant.core :as ig]
            [integrant.repl]
            [aero.core :as aero]
            [beatthemarket.state.core :as state.core]
            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.util :refer [ppi] :as util]

            [beatthemarket.migration.core :as migration.core]))


(defmethod ig/init-key :server/server [_ {:keys [service]}]

  (-> service
      server/default-interceptors
      ;; conditionally-apply-dev-interceptor
      auth/auth-interceptor
      server/create-server
      server/start))

(defmethod ig/halt-key! :server/server [_ server]
  (server/stop server))

(def default-environment "production")
(def cli-options
  [["-p" "--profile NAME"
    (format "Environment profile name must be `production`, `development`, or `test`.
             Order of precedence is below:

             A. Checks command line argument
             B. First looks for value in `ENVIRONMENT`.
             C. If not provided, will default to `%s`."
            default-environment)
    :parse-fn keyword
    :validate [#{:production :development :test}]]

   ["-m" "--market-trading" "Run with Market Trading mode enabled"]])

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

  (let [{:keys [options _summary _errors]} (tools.cli/parse-opts args cli-options)
        {profile         :profile
         market-trading? :market-trading} (process-parsed-options options)]

    (set! *warn-on-reflection* true)
    (println (format "\nCreating your server... %s" profile))

    (state.core/set-prep profile)
    (state.core/init-components)
    (migration.core/run-migrations)))


(comment ;; Main


  (-main "-p" "production")


  (-> "integrant-config.edn"
      resource
      (aero.core/read-config {:profile :development})
      :integrant)

  (binding [*data-readers* {'ig/ref ig/ref}]
    (-> "integrant-config.edn"
        resource
        (aero.core/read-config {:profile :development}))))
