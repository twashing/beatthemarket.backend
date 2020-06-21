(ns beatthemarket.test-util
  (:gen-class)
  (:require [clojure.test :as t :refer [is]]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.java.io :refer [resource]]
            [clojure.data.json :as json]
            [io.pedestal.test :refer [response-for]]
            [integrant.core :as ig]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.repl.state :as repl.state]
            [clojure.tools.logging :as log]
            [clojure.core.async :refer [timeout alt!! chan put!]]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [gniazdo.core :as g]
            [expound.alpha :as expound]
            [datomic.client.api :as d]
            [compute.datomic-client-memdb.core :as memdb]
            [beatthemarket.iam.authentication :as iam.auth]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.game.game :as game]
            [beatthemarket.migration.core :as migration.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.state.core :as state.core]
            [beatthemarket.util :as util])
  (:import [com.google.firebase.auth FirebaseAuth]))


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

  ([f] (component-prep-fixture :test f))

  ([profile f]

   ;; Prep components and load namespaces
   (state.core/set-prep+load-namespaces profile)

   (f)))

(defn component-fixture [f]

  (beatthemarket.state.core/init-components)
  (f)
  (beatthemarket.state.core/halt-components))

(defn migration-fixture [f]

  (migration.core/apply-norms!)

  (f))

(defn login-assertion [service id-token]

  (let [expected-status 200
        expected-body {:data {:login "user-added"}}
        expected-headers {"Content-Type" "application/json"}

        {status :status
         body :body
         headers :headers}
        (response-for service
                      :post "/api"
                      :body "{\"query\": \"{ login }\"}"
                      :headers {"Content-Type" "application/json"
                                "Authorization" (format "Bearer %s" id-token)})

        body-parsed (json/read-str body :key-fn keyword)]

    (t/are [x y] (= x y)
      expected-status status
      expected-body body-parsed
      expected-headers headers)))


;; Firebase Token Helpers

;; Idea taken from this SO answer
;; https://stackoverflow.com/a/51346783/375616
(defn token->body-payload [customToken]
  (json/write-str {:token customToken
                   :returnSecureToken true}))

(defn verify-custom-token [api-key customToken]
  (http/post (format "https://www.googleapis.com/identitytoolkit/v3/relyingparty/verifyCustomToken?key=%s" api-key)
             {:content-type :json
              :body (token->body-payload customToken)}))

(defn generate-custom-token [uid]
  (.. (FirebaseAuth/getInstance)
      (createCustomToken uid)))

(defn ->id-token []

  (let [{uid :admin-user-id
         api-key :api-key} (-> repl.state/config :firebase/firebase)

        customToken (generate-custom-token uid)]

    (-> (verify-custom-token api-key customToken)
        :body
        (json/read-str :key-fn keyword)
        :idToken)))



  ;; USER
(defn generate-user [conn]

  (let [id-token               (->id-token)
        checked-authentication (iam.auth/check-authentication id-token)
        add-user-db-result     (iam.user/conditionally-add-new-user! conn checked-authentication)]
    (ffirst
      (d/q '[:find ?e
             :in $ ?email
             :where [?e :user/email ?email]]
           (d/db conn)
           (-> checked-authentication
               :claims (get "email"))))))


;; DOMAIN
(defn generate-stocks

  ([conn] (generate-stocks conn 1))

  ([conn no-of-stocks]

   (let [stocks (game/generate-stocks no-of-stocks)]

     (persistence.datomic/transact-entities! conn stocks)

     (d/q '[:find ?e
            :in $ [?stock-id ...]
            :where [?e :game.stock/id ?stock-id]]
          (d/db conn)
          (map :game.stock/id stocks)))))


;; GraphQL
(def ws-uri "ws://localhost:8080/ws")
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
     *messages-ch* ([message] message) (timeout timeout-ms) ::timed-out)))

(defmacro expect-message
  [expected]
  `(is (= ~expected
          (<message!!))))

(defn subscriptions-fixture

  ([] (subscriptions-fixture ws-uri))

  ([uri]
   (fn [f]
     (log/debug :reason ::test-start :uri uri)
     (let [id-token (->id-token)
           messages-ch (chan 10)
           session (try
                     (g/connect uri
                       :on-receive (fn [message-text]
                                     (log/debug :reason ::receive :message message-text)
                                     (put! messages-ch (json/read-str message-text :key-fn keyword)))
                       :on-connect (fn [a]
                                     (log/debug :reason ::connected))
                       :on-close #(log/debug :reason ::closed :code %1 :message %2)
                       :on-error #(log/error :reason ::unexpected-error
                                             :exception %)
                       :headers {"Authorization" (format "Bearer %s" id-token)})

                     (catch Exception e
                       (println (ex-data e))
                       (throw e)))]

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

(defmethod persistence.datomic/close-db-connection! :test [{client :client}]
  (persistence.datomic/close-db-connection-local! client))
