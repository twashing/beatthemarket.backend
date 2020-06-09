(ns beatthemarket.test-util
  (:gen-class)
  (:require [clojure.test :as t :refer [is]]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.java.io :refer [resource]]
            [integrant.core :as ig]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [clojure.tools.logging :as log]
            [clojure.core.async :refer [timeout alt!! chan put!]]
            [clojure.data.json :as json]
            [gniazdo.core :as g]
            [expound.alpha :as expound]
            [compute.datomic-client-memdb.core :as memdb]
            [beatthemarket.handler.http.server :refer [set-prep+load-namespaces]]
            [beatthemarket.persistence.datomic :as persistence.datomic]))


;; Spec Helpers
(set! s/*explain-out* expound/printer)

(defmacro defspec-test
  ([name sym-or-syms]
   `(defspec-test ~name ~sym-or-syms nil))
  ([name sym-or-syms opts]
   (when t/*load-tests*
     `(def ~(vary-meta name assoc
                       :test `(fn []
                                (let [check-results# (stest/check ~sym-or-syms ~opts)
                                      checks-passed?# (every? nil? (map :failure check-results#))]
                                  (if checks-passed?#
                                    (t/do-report {:type    :pass
                                                  :message (str "Generative tests pass for "
                                                                (join ", " (map :sym check-results#)))})
                                    (doseq [failed-check# (filter :failure check-results#)
                                            :let [r# (stest/abbrev-result failed-check#)
                                                  failure# (:failure r#)]]
                                      (t/do-report
                                        {:type     :fail
                                         :message  (with-out-str (s/explain-out failure#))
                                         :expected (->> r# :spec rest (apply hash-map) :ret)
                                         :actual   (if (instance? Throwable failure#)
                                                     failure#
                                                     (:stest/val failure#))})))
                                  checks-passed?#)))
        (fn [] (t/test-var (var ~name)))))))


;; Fixtures
(defn component-prep-fixture

  ([f] (component-prep-fixture :production f))

  ([profile f]

   ;; Prep components and load namespaces
   (set-prep+load-namespaces profile)

   (f)))

(defn component-fixture [f]
  (halt)
  (go)

  ;; Create schema
  (-> integrant.repl.state/system
      :persistence/datomic :conn
      persistence.datomic/transact-schema!)

  (f))


;; GraphQL
(def ws-uri "ws://localhost:8080/graphql-ws")
(def ^:dynamic *messages-ch* nil)
(def ^:dynamic *session* nil)
(def *subscriber-id (atom 0))

(defn send-data
  [data]
  (log/debug :reason ::send-data :data data)
  (g/send-msg *session* (json/write-str data) ))

(defn send-init
  ([]
   (send-init nil))
  ([payload]
   (send-data {:type :connection_init
               :payload payload})))

(defn <message!!
  ([]
   (<message!! 75))
  ([timeout-ms]
   (alt!!
     *messages-ch* ([message] message)

     (timeout timeout-ms) ::timed-out)))

(defmacro expect-message
  [expected]
  `(is (= ~expected
          (<message!!))))

(defn subscriptions-fixture
  ([]
   (subscriptions-fixture ws-uri))
  ([uri]
   (fn [f]
     (log/debug :reason ::test-start :uri uri)
     (let [messages-ch (chan 10)
           session (g/connect uri
                              :on-receive (fn [message-text]
                                            (log/debug :reason ::receive :message message-text)
                                            (put! messages-ch (json/read-str message-text :key-fn keyword)))
                              :on-connect (fn [_] (log/debug :reason ::connected))
                              :on-close #(log/debug :reason ::closed :code %1 :message %2)
                              :on-error #(log/error :reason ::unexpected-error
                                                    :exception %))]

       (binding [*session* session
                 ;; New messages channel on each test as well, to ensure failed tests don't cause
                 ;; cascading failures.
                 *messages-ch* messages-ch]
         (try
           (f)
           (finally
             (log/debug :reason ::test-end)
             (g/close session))))))))


;; Datomic
(defmethod persistence.datomic/->datomic-client :test [opts]
  (persistence.datomic/->datomic-client-local opts))
