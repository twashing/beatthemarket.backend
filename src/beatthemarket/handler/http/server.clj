(ns beatthemarket.handler.http.server
  (:gen-class)
  (:require [clojure.java.io :refer [resource]]
            [clojure.tools.cli :as tools.cli]
            [clojure.tools.logging :as log]
            [io.pedestal.http :as server]
            [io.pedestal.http.route :refer [expand-routes]]
            [io.pedestal.interceptor :as interceptor]
            [com.walmartlabs.lacinia.pedestal2 :as pedestal :refer [default-service]]
            [com.rpl.specter :refer [select transform MAP-VALS]]
            [integrant.core :as ig]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [aero.core :as aero]
            [beatthemarket.handler.http.service :as service]
            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.util :refer [pprint+identity]]))


(defn read-config [profile resource]
  (aero.core/read-config resource {:profile profile}))

(defn inject-environment [profile config]
  (transform [MAP-VALS] #(assoc % :env profile) config))

(defn set-prep+load-namespaces [profile]

  (integrant.repl/set-prep!
    (constantly (->> "config.edn"
                     resource
                     (read-config profile)
                     :integrant
                     (inject-environment profile))))

  (ig/load-namespaces {:beatthemarket.handler.http/service :service/service
                       :beatthemarket.handler.http/server :server/server
                       :beatthemarket.iam/authentication :firebase/firebase
                       :beatthemarket.persistence/datomic :persistence/datomic
                       :beatthemarket.state/nrepl :nrepl/nrepl
                       :beatthemarket.state/logging :logging/logging}))


(defmethod ig/init-key :server/server [_ {:keys [service]}]

  (-> service
      server/default-interceptors
      ;; conditionally-apply-dev-interceptor
      auth/auth-interceptor
      server/create-server
      server/start))

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

    (set-prep+load-namespaces profile)
    (integrant.repl/go)))

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
