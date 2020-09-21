(ns beatthemarket.handler.graphql.core
  (:require [clojure.core.async :as core.async
             :refer [>!!]]
            [datomic.client.api :as d]
            [clojure.data.json :as json]
            [integrant.repl.state :as repl.state]
            [com.rpl.specter :refer [transform ALL MAP-KEYS MAP-VALS]]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as]]

            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.datasource.core :as datasource.core]

            [beatthemarket.game.core :as game.core]
            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.game.games.trades :as games.trades]
            [beatthemarket.game.games :as game.games]
            [beatthemarket.game.games.control :as games.control]
            [beatthemarket.game.games.pipeline :as games.pipeline]
            [beatthemarket.bookkeeping.core :as bookkeeping]
            [beatthemarket.integration.payments.persistence :as payments.persistence]
            [beatthemarket.integration.payments.core :as payments.core]
            [beatthemarket.integration.payments.apple :as payments.apple]
            [beatthemarket.integration.payments.apple.persistence :as payments.apple.persistence]
            [beatthemarket.integration.payments.google :as payments.google]
            [beatthemarket.integration.payments.stripe :as payments.stripe]
            [beatthemarket.handler.graphql.encoder :as graphql.encoder]
            [beatthemarket.util :as util])
  (:import [java.util UUID]))


(defn coerce-uuid->str [k v]
  (if (= :accountId k)
    (str v)
    v))

(defn user-games [conn email]

  (d/q '[:find (pull ?g [:game/id
                         :game/start-time
                         :game/end-time
                         :game/status
                         {:game/users [:game.user/user-client
                                       :game.user/user]}])
         :in $ ?email
         :where
         [?g :game/start-time]
         [(missing? $ ?g :game/end-time)] ;; game still active?
         (not (or [?g :game/status :game-status/won]
                  [?g :game/status :game-status/lost]
                  [?g :game/status :game-status/exited])) ;; game not exited?
         [?g :game/users ?us]
         [?us :game.user/user-client ?client-id]  ;; For a Device
         [?us :game.user/user ?u]
         [?u :user/email ?email] ;; For a User
         ]
       (d/db conn) email))

(defn check-user-device-doesnt-have-running-game? [conn email client-id]

  (when (ffirst
          (d/q '[:find (pull ?g [*])
                 :in $ ?email ?client-id
                 :where
                 [?g :game/start-time]
                 [(missing? $ ?g :game/end-time)] ;; game still active?
                 (or [?g :game/status :game-status/running]
                     [?g :game/status :game-status/paused]) ;; game not exited?
                 [?g :game/users ?us]
                 [?us :game.user/user-client ?client-id]  ;; For a Device
                 [?us :game.user/user ?u]
                 [?u :user/email ?email] ;; For a User
                 ]
               (d/db conn)
               email client-id))
    (throw (Exception. (format "User device has a running game / email %s / client-id %s" email client-id)))))

(defn check-user-device-has-created-game? [conn email client-id]

  (when-not (ffirst
              (d/q '[:find ?g
                     :in $ ?email ?client-id
                     :where
                     [?g :game/start-time]
                     [(missing? $ ?g :game/end-time)] ;; game still active?
                     [?g :game/status :game-status/created] ;; game created?
                     [?g :game/users ?us]
                     [?us :game.user/user-client ?client-id]  ;; For a Device
                     [?us :game.user/user ?u]
                     [?u :user/email ?email] ;; For a User
                     ]
                   (d/db conn)
                   email client-id))
    (throw (Exception. (format "User device doesn't have a created game / email %s / client-id %s" email client-id)))))

(defn check-user-device-has-paused-game? [conn email client-id]

  (when-not (ffirst
              (d/q '[:find ?g
                     :in $ ?email ?client-id
                     :where
                     [?g :game/start-time]
                     [(missing? $ ?g :game/end-time)] ;; game still active?
                     [?g :game/status :game-status/paused] ;; game paused?
                     [?g :game/users ?us]
                     [?us :game.user/user-client ?client-id]  ;; For a Device
                     [?us :game.user/user ?u]
                     [?u :user/email ?email] ;; For a User
                     ]
                   (d/db conn)
                   email client-id))
    (throw (Exception. (format "User device doesn't have a paused game / email %s / client-id %s" email client-id)))))

(defn check-user-device-not-already-joined? [conn email client-id game-id]

  (when (ffirst
          (d/q '[:find ?g
                 :in $ ?email ?client-id ?game-id
                 :where
                 [?g :game/id ?game-id]
                 [?g :game/start-time]
                 [(missing? $ ?g :game/end-time)] ;; game still active?
                 (not (or [?g :game/status :game-status/won]
                          [?g :game/status :game-status/lost]
                          [?g :game/status :game-status/exited])) ;; game not exited?
                 [?g :game/users ?us]
                 [?us :game.user/user-client ?client-id]  ;; For a Device
                 [?us :game.user/user ?u]
                 [?u :user/email ?email] ;; For a User
                 ]
               (d/db conn)
               email client-id game-id))
    (throw (Exception. (format "User device has already joined this game / email %s / client-id %s" email client-id)))))

(defn check-client-id-exists [context]

  (if-let [client-id (or (-> context :request :headers (get "client-id"))
                         (-> context :com.walmartlabs.lacinia/connection-params :client-id))]
    (UUID/fromString client-id)
    (throw (Exception. "Missing :client-id in your connection_init"))))


;; RESOLVERS
(defn resolve-login
  [context _ _]

  (try

    (let [{{{email :email :as checked-authentication} :checked-authentication}
           :request}                                   context
          conn                                         (-> repl.state/system :persistence/datomic :opts :conn)
          {:keys [db-before db-after tx-data tempids]} (iam.user/conditionally-add-new-user! conn checked-authentication)

          rename-user-key-map {:user/email :userEmail
                               :user/name :userName
                               :user/external-uid :userExternalUid
                               :user/accounts :userAccounts}

          rename-user-accounts-key-map {:bookkeeping.account/id :accountId
                                        :bookkeeping.account/name :accountName
                                        :bookkeeping.account/balance :accountBalance
                                        :bookkeeping.account/amount :accountAmount}

          base-response {:user (->> [:user/email
                                     :user/name
                                     :user/external-uid
                                     {:user/accounts [:bookkeeping.account/id
                                                      :bookkeeping.account/name
                                                      :bookkeeping.account/balance
                                                      :bookkeeping.account/amount]}]
                                    (persistence.core/entity-by-domain-id
                                      conn :user/email email)
                                    ffirst
                                    (transform identity #(clojure.set/rename-keys % rename-user-key-map))
                                    (transform [:userAccounts ALL] #(clojure.set/rename-keys % rename-user-accounts-key-map))
                                    (#(json/write-str % :value-fn coerce-uuid->str)))}]

      (if (util/truthy? (and db-before db-after tx-data tempids))
        (assoc base-response :message :useradded)
        (assoc base-response :message :userexists)))

    (catch Exception e
      (->> e bean :localizedMessage (hash-map :message) (resolve-as nil)))))

(defn resolve-create-game [context {gameLevel :gameLevel :as args} parent]

  (try

    (let [client-id (check-client-id-exists context)
          conn (-> repl.state/system :persistence/datomic :opts :conn)
          {{{email :email} :checked-authentication} :request} context]

      (check-user-device-doesnt-have-running-game? conn email client-id)

      (let [user-entity (-> (beatthemarket.iam.persistence/user-by-email conn email '[:db/id :user/email])
                            ffirst
                            (select-keys [:db/id :user/email]))
            mapped-game-level (get graphql.encoder/game-level-map gameLevel)


            data-generators (-> repl.state/config :game/game :data-generators)
            combined-data-sequence-fn games.control/->data-sequence

            ;; NOTE sink-fn updates once we start to stream a game
            sink-fn                identity
            {{game-id :game/id
              :as     game} :game} (game.games/create-game! conn sink-fn combined-data-sequence-fn
                                                            {:user user-entity
                                                             :accounts (game.core/->game-user-accounts)
                                                             :game-level mapped-game-level
                                                             :client-id client-id})]

        (->> (game.games/game->new-game-message game (:db/id user-entity))
             (transform [:stocks ALL] #(dissoc % :db/id))
             (transform [:stocks ALL MAP-KEYS] (comp keyword name)))))

    (catch Exception e
      (->> e bean :localizedMessage (hash-map :message) (resolve-as nil)))))

(defn resolve-start-game [context {game-id :id :as args} _]

  (try

    (let [client-id (check-client-id-exists context)
          conn (-> repl.state/system :persistence/datomic :opts :conn)
          {{{email :email} :checked-authentication} :request} context]

      (check-user-device-doesnt-have-running-game? conn email client-id)

      (let [user-db-id (:db/id (ffirst (beatthemarket.iam.persistence/user-by-email conn email '[:db/id])))
            gameId (UUID/fromString game-id)
            game-control (->> repl.state/system :game/games deref (#(get % gameId)))]

        (->> (game.games/start-game! conn game-control (get args :startPosition 0))
             (map :stock-ticks)
             (map #(map graphql.encoder/stock-tick->graphql %)))))

    (catch Exception e
      (do
        ;; (util/ppi (bean e))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

(defn resolve-buy-stock [context args _]

  (println "resolve-buy-stock CALLED /" args)

  (let [{{{userId :uid
           email :email} :checked-authentication} :request} context
        {{:keys [gameId stockId stockAmount tickId tickPrice]} :input} args
        conn                                               (-> repl.state/system :persistence/datomic :opts :conn)
        game-control                                       (->> repl.state/system :game/games deref (#(get % (UUID/fromString gameId))))
        user-db-id                                         (:db/id (ffirst (beatthemarket.iam.persistence/user-by-email conn email '[:db/id])))
        gameId (UUID/fromString gameId)
        stockId (UUID/fromString stockId)
        tickId (UUID/fromString tickId)
        tickPrice (Float. tickPrice)]

    (try

      (if ((comp :bookkeeping.tentry/id :tentry first)
           (games.pipeline/buy-stock-pipeline game-control conn userId gameId stockId stockAmount tickId (Float. tickPrice) false))

        {:message "Ack"}
        (resolve-as nil {:message "Error / resolve-buy-stock / INCOMPLETE /"}))

      (catch Throwable e
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

(defn resolve-sell-stock [context args _]

  ;; (println "resolve-sell-stock CALLED /" args)
  (let [{{{userId :uid
           email :email} :checked-authentication} :request} context
        {{:keys [gameId stockId stockAmount tickId tickPrice]} :input} args
        conn                                               (-> repl.state/system :persistence/datomic :opts :conn)
        game-control                                       (->> repl.state/system :game/games deref (#(get % (UUID/fromString gameId))))
        user-db-id                                          (:db/id (ffirst (beatthemarket.iam.persistence/user-by-email conn email '[:db/id])))
        gameId (UUID/fromString gameId)
        stockId (UUID/fromString stockId)
          tickId (UUID/fromString tickId)
        tickPrice (Float. tickPrice)]

    (try

      (if ((comp :bookkeeping.tentry/id :tentry first)
           (games.pipeline/sell-stock-pipeline game-control conn userId gameId stockId stockAmount tickId (Float. tickPrice) false))

        {:message "Ack"}
        (resolve-as nil {:message "Error / resolve-sell-stock / INCOMPLETE /"}))

      (catch Throwable e
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

(defn update-sink-fn! [id-uuid sink-fn]
  (swap! (:game/games repl.state/system)
         update-in [id-uuid :sink-fn] (constantly #(do
                                                     ;; (println "sink-fn CALLED /" %)
                                                     (sink-fn {:message %}))) ))

(defn resolve-user [context {email :email :as args}_]

  (let [conn (-> repl.state/system :persistence/datomic :opts :conn)]

    (->> (iam.persistence/user-by-email conn email)
         ffirst
         (transform [identity] #(dissoc % :db/id))
         (transform [identity] #(clojure.set/rename-keys % {:user/email :userEmail
                                                            :user/name :userName
                                                            :user/external-uid :userExternalUid})))))

(defn resolve-users [context args _]

  (let [conn (-> repl.state/system :persistence/datomic :opts :conn)
        users (d/q '[:find (pull ?e [*])
                     :in $
                     :where
                     [?e :user/email]]
                   (d/db conn))]

    (->> (map first users)
         (transform [ALL identity] #(dissoc % :db/id))
         (transform [ALL identity] #(clojure.set/rename-keys % {:user/email :userEmail
                                                                :user/name :userName
                                                                :user/external-uid :userExternalUid})))))

(defn resolve-user-personal-profit-loss [context {:keys [email gameId groupByStock] :as args} _]

  (let [conn       (-> repl.state/system :persistence/datomic :opts :conn)
        user-db-id (:db/id (ffirst (beatthemarket.iam.persistence/user-by-email conn email '[:db/id])))
        group-by-stock? (if groupByStock groupByStock false)]


    ;; ? Snapshot (Running + Realized P/L)
    ;; Realized P/L (all, per game)
    ;; Realized P/L (per stock, per game)
    ;; (highest score, most recent score)

    (if gameId

      (let [game-id (UUID/fromString gameId)]

        (->> (game.calculation/collect-realized-profit-loss-pergame conn user-db-id game-id group-by-stock?)
             (map graphql.encoder/profit-loss->graphql)))

      (->> (game.calculation/collect-realized-profit-loss-allgames conn user-db-id group-by-stock?)
           (map graphql.encoder/profit-loss->graphql)))))

(defn resolve-user-market-profit-loss [context {email :email} _]

  "Lists out a User's amrket Profit/Loss, per stock"
  [:ProfitLoss]

  [{:profitLoss 56123.73
    :stockId "stockid1"
    :gameId "marketid1"}
   {:profitLoss 1293.73
    :stockId "stockid2"
    :gameId "marketid1"}
   {:profitLoss -10460.73
    :stockId "stockid3"
    :gameId "marketid1"}])

(defn resolve-account-balances [context {gameId :gameId email :email} _]

  (let [conn       (-> repl.state/system :persistence/datomic :opts :conn)
        game-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game/id (UUID/fromString gameId)))
        user-db-id (:db/id (ffirst (beatthemarket.iam.persistence/user-by-email conn email '[:db/id])))]

    (->> (game.calculation/collect-account-balances conn game-db-id user-db-id)
         (map graphql.encoder/portfolio-update->graphql))))

(defn resolve-pause-game [context {gameId :gameId} _]

  ;; NOTE
  ;; ticks saved
  ;; P/Ls saved (realized)
  ;; game-status saved
  ;; tick index stored as no. of ticks saved

  (let [game-id (UUID/fromString gameId)
        event {:type :ControlEvent
               :event :pause
               :game-id game-id}]

    (-> (game.games/send-control-event! game-id event)
        (assoc :gameId gameId))))

(defn resolve-resume-game [context {gameId :gameId} _]

  ;; NOTE
  ;; load ticks
  ;; load P/Ls
  ;; load game-status
  ;; seek to tick index
  ;; ! replay stock-ticks + buys + sells

  (try

    (let [client-id (check-client-id-exists context)
          conn (-> repl.state/system :persistence/datomic :opts :conn)
          {{{email :email} :checked-authentication} :request} context]

      (check-user-device-has-paused-game? conn email client-id)

      (let [data-sequence-fn games.control/->data-sequence
            user-db-id (:db/id (ffirst (beatthemarket.iam.persistence/user-by-email conn email '[:db/id])))
            game-id (UUID/fromString gameId)
            game-control (->> repl.state/system :game/games deref (#(get % game-id)))

            event {:type :ControlEvent
                   :event :resume
                   :game-id game-id}]


        ;; Resume Game
        (games.control/resume-game! conn game-id user-db-id game-control data-sequence-fn)


        ;; Response
        (-> (game.games/send-control-event! game-id event)
            (assoc :gameId gameId)))
      )

    (catch Exception e
      (->> e bean :localizedMessage (hash-map :message) (resolve-as nil)))))

(defn resolve-join-game [context {gameId :gameId} _]

  (try

    (let [client-id (check-client-id-exists context)
          game-id (UUID/fromString gameId)
          conn (-> repl.state/system :persistence/datomic :opts :conn)
          {{{email :email} :checked-authentication} :request} context]

      (check-user-device-not-already-joined? conn email client-id)

      (let [data-sequence-fn games.control/->data-sequence
            user-db-id (:db/id (ffirst (beatthemarket.iam.persistence/user-by-email conn email '[:db/id])))
            game-id (UUID/fromString gameId)
            game-control (->> repl.state/system :game/games deref (#(get % game-id)))

            event {:type :ControlEvent
                   :event :join
                   :game-id game-id}]


        ;; Join Game
        (games.control/join-game! conn user-db-id game-id game-control)


        ;; Response
        (-> (game.games/send-control-event! game-id event)
            (assoc :gameId gameId))))

    (catch Exception e
      (->> e bean :localizedMessage (hash-map :message) (resolve-as nil)))))

(defn resolve-exit-game [context {gameId :gameId} _]

  (let [game-id (UUID/fromString gameId)
        event {:type :ControlEvent
               :event  :exit
               :game-id game-id}]

    (-> (game.games/send-control-event! game-id event)
        (assoc :gameId gameId))))

(defn resolve-list-games [context {gameId :gameId} _]

  (let [{{{email :email :as checked-authentication} :checked-authentication}
         :request}                                   context]

    ;; TODO
    [{:gameId gameId
      :status :running
      :profitLoss 0.0}]))

(defn create-stripe-customer [context {email :email} parent]

  (try

    (let [{{client :client} :payment.provider/stripe} repl.state/system]
      (->> (payments.stripe/conditionally-create-customer! client email)
           :customers
           (map graphql.encoder/stripe-customer->graphql)))

    (catch Exception e
      (->> e bean :localizedMessage (hash-map :message) (resolve-as nil)))))

(defn delete-stripe-customer [context {id :id} parent]

  (try

    (let [{{client :client} :payment.provider/stripe} repl.state/system]
      {:message (:success? (payments.stripe/delete-customer! client id))})

    (catch Exception e
      (->> e bean :localizedMessage (hash-map :message) (resolve-as nil)))))

(defn user-payments [context args _]

  (try

    (let [conn (-> repl.state/system :persistence/datomic :opts :conn)
          {{{email :email} :checked-authentication} :request} context]

      (->> (payments.persistence/user-payments conn)
           (map graphql.encoder/payment-purchase->graphql)))

    (catch Exception e
      (->> e bean :localizedMessage (hash-map :message) (resolve-as nil)))))

(defmulti verify-payment-handler (fn [_ {provider :provider} _] provider))

(defmethod verify-payment-handler "apple" [context {productId :productId
                                                    provider :provider
                                                    token :token :as args} _]

  ;; (util/ppi args)
  (let [client-id (check-client-id-exists context)
        {{{email :email} :checked-authentication} :request} context
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        {:keys [verify-receipt-endpoint primary-shared-secret]} (-> repl.state/config :payment.provider/apple :service)
        apple-hash (json/read-str token :key-fn keyword)]

    (->> (payments.apple/verify-payment-workflow conn client-id email verify-receipt-endpoint primary-shared-secret apple-hash)
         (map graphql.encoder/payment-purchase->graphql))))

(defmethod verify-payment-handler "google" [context {productId :productId
                                                     provider :provider
                                                     token :token :as args} _]

  (let [client-id (check-client-id-exists context)
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        payment-config (-> repl.state/config :payment.provider/google)]

    ;; (util/ppi ["Sanity 2" payment-config args])
    (->> (payments.google/verify-payment-workflow conn payment-config args)
         (map graphql.encoder/payment-purchase->graphql))

    []))

(defn valid-stripe-product-id? [product-id]

  (let [products (-> repl.state/system :payment.provider/stripe :products)]

    (as-> (vals products) v
      (into #{} v)
      (some v #{product-id})
      (util/exists? v))))

(defn valid-stripe-subscription-id? [subscription-id]

  (let [subscriptions (-> repl.state/system :payment.provider/stripe :subscriptions)]

    (as-> (vals subscriptions) v
      (into #{} v)
      (some v #{subscription-id})
      (util/exists? v))))

(defmethod verify-payment-handler "stripe" [context {product-id :productId
                                                     provider :provider
                                                     :as args} _]

  ;; (util/ppi args)
  ;; (util/ppi #_context (check-client-id-exists context))
  ;; (util/ppi (-> context :request :checked-authentication))

  (let [client-id                                           (check-client-id-exists context)
        {{{email :email} :checked-authentication} :request} context
        {{{conn :conn} :opts} :persistence/datomic
         component            :payment.provider/stripe}     repl.state/system]

    ;; (println (format "Valid product %s" (valid-stripe-product-id? product-id)))
    ;; (println (format "Valid subscription %s" (valid-stripe-subscription-id? product-id)))
    ;; (update args :token #(json/read-str % :key-fn keyword))

     (cond

       (valid-stripe-product-id? product-id)
       (->> (update args :token #(json/read-str % :key-fn keyword))
            (payments.stripe/verify-product-workflow conn client-id email component)
            (map graphql.encoder/payment-purchase->graphql))

       (valid-stripe-subscription-id? product-id)
       (->> (update args :token #(json/read-str % :key-fn keyword))
            (payments.stripe/verify-subscription-workflow conn client-id email component)
            (map graphql.encoder/payment-purchase->graphql))

       :else (throw (Exception. (format "Invalid product ID given %s" product-id))))))

(defn verify-payment [context args parent]

  (try
    (verify-payment-handler context args parent)
    (catch Exception e
      (do
        (util/ppi (bean e))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))


; STREAMERS

;; https://lacinia-pedestal.readthedocs.io/en/latest/subscriptions.html#overview
;; When a streamer passes nil to the callback, a clean shutdown of the subscription occurs; the client is sent a completion message. The completion message informs the client that the stream of events has completed, and that it should not attempt to reconnect.


;; TODO Connect to streams on a peruser basis
;; stock-tick-stream
;; portfolio-update-stream
;; game-event-stream
(defn stream-stock-ticks [context {id :gameId :as args} source-stream]

  (println "stream-stock-ticks CALLED")
  (let [conn                                                (-> repl.state/system :persistence/datomic :opts :conn)
        {{{email :email} :checked-authentication} :request} context
        user-db-id                                          (ffirst (beatthemarket.iam.persistence/user-by-email conn email '[:db/id]))
        id-uuid                                             (UUID/fromString id)
        stock-tick-stream                                   (-> repl.state/system
                                                                :game/games
                                                                deref
                                                                (get (UUID/fromString id))
                                                                :stock-tick-stream)
        cleanup-fn                                          (constantly
                                                              (do
                                                                (println "stream-stock-ticks CLEANUP")
                                                                :noop #_(core.async/close! stock-tick-stream)))]

    (core.async/go-loop []
      (when-let [stock-ticks (core.async/<! stock-tick-stream)]
        (source-stream (map graphql.encoder/stock-tick->graphql stock-ticks))
        (recur)))

    ;; Return a cleanup fn
    cleanup-fn))

(defn stream-portfolio-updates [context {id :gameId :as args} source-stream]

  (println "stream-portfolio-updates CALLED")
  (let [conn                                                (-> repl.state/system :persistence/datomic :opts :conn)
        {{{email :email} :checked-authentication} :request} context
        user-db-id                                          (:db/id (ffirst (beatthemarket.iam.persistence/user-by-email conn email '[:db/id])))
        id-uuid                                             (UUID/fromString id)
        portfolio-update-stream                             (-> repl.state/system
                                                                :game/games
                                                                deref
                                                                (get (UUID/fromString id))
                                                                :portfolio-update-stream)
        cleanup-fn                                          (constantly

                                                              (do
                                                                (println "stream-portfolio-updates CLEANUP")
                                                                :noop #_(core.async/close! portfolio-update-stream)))]

    (core.async/go-loop []

      (when-let [portfolio-update (core.async/<! portfolio-update-stream)]

        (when-not (empty? portfolio-update)
          (source-stream
            (map graphql.encoder/portfolio-update->graphql portfolio-update)))

        (recur)))

    ;; Return a cleanup fn
    cleanup-fn))

(defn stream-game-events [context {id :gameId :as args} source-stream]

  (println "stream-game-events CALLED")
  (let [conn                                                (-> repl.state/system :persistence/datomic :opts :conn)
        {{{email :email} :checked-authentication} :request} context
        user-db-id                                          (:db/id (ffirst (beatthemarket.iam.persistence/user-by-email conn email '[:db/id])))
        id-uuid                                             (UUID/fromString id)
        game-event-stream                                   (-> repl.state/system
                                                                :game/games
                                                                deref
                                                                (get (UUID/fromString id))
                                                                :game-event-stream)
        cleanup-fn                                          (constantly
                                                              (do
                                                                (println "stream-game-events CLEANUP")
                                                                :noop #_(core.async/close! game-event-stream)))]

    (core.async/go-loop []
      (when-let [game-event (core.async/<! game-event-stream)]

        (source-stream
          (graphql.encoder/game-event->graphql game-event))

        (recur)))

    ;; Return a cleanup fn
    cleanup-fn))
