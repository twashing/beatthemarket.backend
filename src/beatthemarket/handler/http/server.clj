(ns beatthemarket.handler.http.server
  (:gen-class)
  (:require [clojure.java.io :refer [resource]]
            [clojure.tools.cli :as tools.cli]
            [io.pedestal.http :as server]
            [com.rpl.specter :refer [transform MAP-VALS]]
            [integrant.core :as ig]
            [integrant.repl ]
            [aero.core :as aero]
            [beatthemarket.handler.authentication :as auth]))


(defn read-config [profile resource]
  (aero/read-config resource {:profile profile
                              :resolver aero/resource-resolver}))

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

  (let [{:keys [options _summary _errors]} (tools.cli/parse-opts args cli-options)
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
