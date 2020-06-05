(ns beatthemarket.test-util
  (:gen-class)
  (:require [clojure.test :refer [is]]
            [clojure.java.io :refer [resource]]
            [integrant.core :as ig]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [clojure.tools.logging :as log]
            [clojure.core.async :refer [timeout alt!! chan put!]]
            [clojure.data.json :as json]
            [gniazdo.core :as g]))


(integrant.repl/set-prep!
  (constantly (-> "config.edn"
                  resource
                  (aero.core/read-config {:profile :development})
                  :integrant)))

(defn component-fixture [f]
  (halt)
  (go)

  (f))

(def ws-uri "ws://localhost:8888/graphql-ws")
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

