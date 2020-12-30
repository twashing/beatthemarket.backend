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
            [clojure.core.async :as core.async :refer [timeout alt!! chan put!]]
            [clojure.data.json :as json]
            [clj-http.client :as http]
            [clj-time.core :as tt]
            [gniazdo.core :as g]
            [expound.alpha :as expound]
            [datomic.client.api :as d]

            [beatthemarket.iam.authentication :as iam.auth]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.game.core :as game.core]
            [beatthemarket.game.games.pipeline :as games.pipeline]
            [beatthemarket.game.games.control :as games.control]
            [beatthemarket.migration.core :as migration.core]
            [beatthemarket.migration.schema-init :as schema-init]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.persistence.datomic.environments :as datomic.environments]
            [beatthemarket.persistence.generators :as persistence.generators]
            [beatthemarket.state.core :as state.core]
            [beatthemarket.util :refer [ppi] :as util])
  (:import [com.google.firebase.auth FirebaseAuth]
           [java.util UUID]))


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

  (state.core/init-components)
  (f)
  (state.core/halt-components))

(defn migration-fixture [f]

  (migration.core/run-migrations
    (-> repl.state/system :persistence/datomic :opts :conn)
    #{:default :development})

  (f))

(defn login-assertion [service id-token]

  (let [expected-status 200
        expected-body-message "useradded"
        expected-headers {"Content-Type" "application/json"}

        expected-user-keys #{:id :userEmail :userExternalUid :userAccounts}
        expected-user-account-keys #{:accountId :accountName :accountBalance :accountAmount}

        {status :status
         body :body
         headers :headers}
        (response-for service
                      :post "/api"
                      :body "{\"query\": \"mutation Login { login { message user }} \" }"
                      :headers {"Content-Type" "application/json"
                                "Authorization" (format "Bearer %s" id-token)})
        {{{user :user
           message :message} :login} :data :as body-parsed} (json/read-str body :key-fn keyword)
        user-parsed (json/read-str user :key-fn keyword)]


    (->> (keys user-parsed)
         (into #{})
         (= expected-user-keys)
         is)

    (->> user-parsed :userAccounts
         (map keys)
         (map #(into #{} %))
         (map #(= expected-user-account-keys %))
         (every? true?)
         is)

    (t/are [x y] (= x y)
      expected-status status
      expected-headers headers
      expected-body-message message)

    user-parsed))


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
(defn generate-user! [conn]

  (let [id-token               (->id-token)
        checked-authentication (iam.auth/check-authentication id-token)]

    (as-> checked-authentication obj
      (iam.user/conditionally-add-new-user! conn obj)
      (:db-after obj)
      (d/q '[:find ?e
             :in $ ?email
             :where [?e :user/email ?email]]
           obj
           (-> checked-authentication
               :claims (get "email")))
      (ffirst obj)
      (persistence.core/pull-entity conn obj))))


;; DOMAIN
(defn generate-stocks!

  ([conn] (generate-stocks! conn 1))

  ([conn no-of-stocks]

   (let [stocks (game.core/generate-stocks! no-of-stocks)]

     (persistence.datomic/transact-entities! conn stocks)

     (d/q '[:find (pull ?e [*])
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
  (g/send-msg *session* (json/write-str data))
  #_(g/send-msg *session* (json/write-str (ppi data)) ))

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
       *messages-ch* ([message] message) (timeout timeout-ms) ::timed-out)
   #_(ppi
     (alt!!
       *messages-ch* ([message] message) (timeout timeout-ms) ::timed-out))))

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


;; Miscellaneous
(defn to-coll [ch]

  (loop [coll []]
    (let [[v ch] (core.async/alts!! [(core.async/timeout 100) ch])]
      (if-not v
        coll
        (recur (conj coll v))))))

(defn consume-subscriptions

  ([] (consume-subscriptions 1000))

  ([timeout]
   (let [subscriptions (atom [])]
     (loop [r (<message!! timeout)]
       (if (= ::timed-out r)
         @subscriptions
         (do
           (swap! subscriptions #(conj % r))
           (recur (<message!! timeout))))))))

(defn stock-buy-happy-path []

  (let [service (-> repl.state/system :server/server :io.pedestal.http/service-fn)
        id-token (->id-token)
        gameLevel 1
        client-id (UUID/randomUUID)]

    (send-init {:client-id (str client-id)})

    (login-assertion service id-token)

    (send-data {:id   987
                :type :start
                :payload
                {:query "mutation CreateGame($gameLevel: Int!) {
                                       createGame(gameLevel: $gameLevel) {
                                         id
                                         stocks { id name symbol }
                                       }
                                     }"
                 :variables {:gameLevel gameLevel}}}))

  (<message!! 1000)

  (let [{:keys [stocks id] :as createGameAck} (-> (<message!! 1000) :payload :data :createGame)]

    (send-data {:id   988
                :type :start
                :payload
                {:query "mutation StartGame($id: String!) {
                                         startGame(id: $id) {
                                           stockTickId
                                           stockTickTime
                                           stockTickClose
                                           stockId
                                           stockName
                                         }
                                       }"
                 :variables {:id id}}})

    (<message!! 1000)
    (<message!! 1000)

    (send-data {:id   989
                :type :start
                :payload
                {:query "subscription StockTicks($gameId: String!) {
                                           stockTicks(gameId: $gameId) {
                                             stockTickId
                                             stockTickTime
                                             stockTickClose
                                             stockId
                                             stockName
                                         }
                                       }"
                 :variables {:gameId id}}})

    (<message!! 1000)

    ;; (ppi "consume-subscriptions...")
    (let [latest-tick (->> (consume-subscriptions)
                           ;; ppi
                           (filter #(= 989 (:id %)))
                           last)
          [{stockTickId :stockTickId
            stockTickTime :stockTickTime
            stockTickClose :stockTickClose
            stockId :stockId
            stockName :stockName}]
          (-> latest-tick :payload :data :stockTicks)]

      (send-data {:id   990
                  :type :start
                  :payload
                  {:query "mutation BuyStock($input: BuyStock!) {
                                         buyStock(input: $input) {
                                           message
                                         }
                                     }"
                   :variables {:input {:gameId      id
                                       :stockId     stockId
                                       :stockAmount 100
                                       :tickId      stockTickId
                                       :tickPrice   stockTickClose}}}})

      (<message!! 1000)
      (<message!! 1000))

    createGameAck))

(defn consume-until

  ([message-id]
   (consume-until message-id 1000))

  ([message-id timeout]
   (consume-until message-id timeout 4))

  ([message-id timeout threshold]

   (loop [{id :id :as message} (<message!! timeout)
          count 1]

     (let [{type :type} message]

       (if (or (> count threshold)
               (and (= message-id id)
                    (= "data" type)))
         message
         (recur (<message!! timeout) (inc count)))))))

(defn consume-messages

  ([channel container]
   (consume-messages channel container 1000))

  ([channel container default-timeout]

   (core.async/go-loop []

     (let [[message _] (core.async/alts! [(core.async/timeout default-timeout) channel])]
       (when message
         (swap! container #(conj % message))
         (recur))))))

(defn local-transact-stock! [{conn :conn
                              userId :userId
                              game-id :gameId
                              stockId :stockId
                              {level-timer :level-timer
                               :as game-control} :game-control}
                             {{tickId      :game.stock.tick/id
                               tickPrice   :game.stock.tick/close
                               op          :op
                               stockAmount :stockAmount} :local-transact-input :as v}]

  (case op
    :buy (games.pipeline/buy-stock-pipeline game-control conn userId game-id stockId stockAmount tickId (Float. tickPrice) false)
    :sell (games.pipeline/sell-stock-pipeline game-control conn userId game-id stockId stockAmount tickId (Float. tickPrice) false)
    :noop (games.pipeline/stock-tick-pipeline game-control))

  (let [now (tt/now)
        end (tt/plus now (tt/seconds @level-timer))]
    (games.control/step-control conn game-control now end))
  v)

(defn run-trades! [iterations stock-id opts ops ops-count]

  (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

              (let [stock-tick (util/narrow-stock-ticks stock-id stock-ticks)]
                (assoc v :local-transact-input (merge stock-tick op))))
            (take ops-count iterations)
            (take ops-count ops))
       (map #(local-transact-stock! opts %))
       doall))

(defn step-game-iterations [conn game-control now end iterations]

  (rest (iterate (fn [iters]
                   (games.control/step-game conn game-control now end iters)
                   (rest iters))
                 iterations)))
