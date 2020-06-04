(ns beatthemarket.server-test
  (:require [clojure.test :refer :all]
            [clojure.java.io :refer [resource]]
            [aero.core :as aero]
            [io.pedestal.http :as server]
            [integrant.core :as ig]
            [integrant.repl.state :as state]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [io.pedestal.test :refer [response-for]]
            [beatthemarket.server :as sut]
            [beatthemarket.handler.authentication :as auth]))


(defn server-fixture [f]

  (halt)
  (go)

  ;; (let [profile :development
  ;;       config (-> "config.edn"
  ;;                  resource
  ;;                  (aero.core/read-config {:profile profile})
  ;;                  :integrant)
  ;;
  ;;       {service :service/service} (ig/init config [:service/service])]
  ;;
  ;;   (->> service
  ;;        io.pedestal.http/default-interceptors
  ;;        sut/inject-lacinia-configuration))


  (f))

(use-fixtures :each server-fixture)


(deftest inject-lacinia-configuration-test

  (let [profile :development
        config (-> "config.edn"
                   resource
                   (aero.core/read-config {:profile profile})
                   :integrant)

        {service :service/service} (ig/init config [:service/service])

        expected-paths
        '([""]
          ["api"]
          ["ide"]
          ["" "about"]
          ["assets" "graphiql" :path]
          ["assets" "graphiql" :path])]


    (->> service
         io.pedestal.http/default-interceptors
         auth/auth-interceptor
         sut/inject-lacinia-configuration
         :io.pedestal.http/routes
         (map :path-parts)
         sort
         (= expected-paths)
         is)))

(deftest subscription-handler-test

  ;; (-> state/system :server/server pprint)
  ;; (-> state/system :server/server :io.pedestal.http/routes pprint)

  (with-redefs [auth/auth-request-handler identity]

    (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
            {code :status} (trace (response-for service
                                                :post "/api"
                                                :body "{\"query\": \"{ hello }\"}"
                                                :headers {"Content-Type" "application/json"}))]

        (is true))


    #_(let [service (-> state/system :server/server :io.pedestal.http/service-fn)
          {code :status} (trace (response-for service
                                              :get "/about"))]

        (is true))

    #_(let [service (-> state/system :server/server :io.pedestal.http/service-fn)
            {code :status} (trace (response-for service
                                                :get "/"))]

        (is true)))
  )

;; ? Keep Lacinia
;;
;; test error-handling
;; add auth-handling
;;
;; test WS handling
;; send a homepage
;; make a WS connection (from client)
;; test error-handling
;; add auth-handling

;; {:path-parts [""],
;;  :path-params [],
;;  :interceptors
;;  [{:name :io.pedestal.http.body-params/body-params,
;;    :enter
;;    #function[io.pedestal.interceptor.helpers/on-request/fn--9773],
;;    :leave nil,
;;    :error nil}
;;   {:name :io.pedestal.http/html-body,
;;    :enter nil,
;;    :leave
;;    #function[io.pedestal.interceptor.helpers/on-response/fn--9790],
;;    :error nil}
;;   {:name :beatthemarket.service/home-page,
;;    :enter #function[io.pedestal.interceptor/fn--581/fn--582],
;;    :leave nil,
;;    :error nil}],
;;  :path "/",
;;  :method :get,
;;  :path-re #"/\Q\E",
;;  :route-name :beatthemarket.service/home-page}
