(ns beatthemarket.handler.graphql.core
  (:require [clojure.core.async :as core.async
             :refer [>!!]]
            [datomic.client.api :as d]
            [clojure.data.json :as json]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [integrant.repl.state :as repl.state]
            [io.pedestal.log :as log]
            [com.rpl.specter :refer [transform ALL MAP-KEYS MAP-VALS]]
            [com.walmartlabs.lacinia.resolve :refer [resolve-as]]

            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.datasource.core :as datasource.core]

            [beatthemarket.game.core :as game.core]
            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.game.persistence :as game.persistence]
            [beatthemarket.game.games :as game.games]
            [beatthemarket.game.games.trades :as games.trades]
            [beatthemarket.game.games.core :as games.core]
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
            [beatthemarket.util :refer [ppi] :as util])
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

#_(defn running-games-for-user-device [conn email client-id]

    (d/q '[:find (pull ?g [:db/id
                           :game/id
                           {:game/status [*]}])
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

(defn running-games-for-user-device [conn email]

  (d/q '[:find (pull ?g [:db/id
                         :game/id
                         {:game/status [*]}])
         :in $ ?email
         :where
         [?g :game/start-time]
         [(missing? $ ?g :game/end-time)] ;; game still active?
         (or [?g :game/status :game-status/running]
             [?g :game/status :game-status/paused]) ;; game not exited?
         [?g :game/users ?us]
         [?us :game.user/user ?u]
         [?u :user/email ?email] ;; For a User
         ]
       (d/db conn)
       email))

(defn check-user-device-doesnt-have-running-game? [conn email client-id]

  (when (ffirst
          (d/q '[:find (pull ?g [:db/id
                                 :game/id
                                 :game/start-time
                                 :game/end-time
                                 {:game/status [*]}
                                 {:game/level [*]}
                                 {:game/users [{:game.user/user  [*]}
                                               :game.user/user-client]}])
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

(defn check-user-device-by-game-status [conn email client-id game-status]

  (when-not (ffirst
              (d/q '[:find ?g
                     :in $ ?email ?client-id ?game-status
                     :where
                     [?g :game/start-time]
                     [(missing? $ ?g :game/end-time)]
                     [?g :game/status ?game-status]
                     [?g :game/users ?us]
                     [?us :game.user/user-client ?client-id]
                     [?us :game.user/user ?u]
                     [?u :user/email ?email]]
                   (d/db conn)
                   email client-id game-status))
    (throw (Exception. (format "User device doesn't have a game with status: %s / email: %s / client-id: %s" game-status email client-id)))))

#_(defn check-user-device-has-lost-game? [conn email client-id]
  (check-user-device-by-game-status conn email client-id :game-status/lost))

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


(defn valid-apple-product-id? [product-id]

  (let [products (-> repl.state/system :payment.provider/apple :products)]

    (as-> (vals products) v
      (into #{} v)
      (some v #{product-id})
      (util/exists? v))))

(defn valid-apple-subscription-id? [subscription-id]

  (let [subscriptions (-> repl.state/system :payment.provider/apple :subscriptions)]

    (as-> (vals subscriptions) v
      (into #{} v)
      (some v #{subscription-id})
      (util/exists? v))))


(defn valid-google-product-id? [product-id]

  (let [products (-> repl.state/config :payment.provider/google :products)]

    (as-> (vals products) v
      (into #{} v)
      (some v #{product-id})
      (util/exists? v))))

(defn valid-google-subscription-id? [subscription-id]

  (let [subscriptions (-> repl.state/config :payment.provider/google :subscriptions)]

    (as-> (vals subscriptions) v
      (into #{} v)
      (some v #{subscription-id})
      (util/exists? v))))


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


;; RESOLVERS
(defn resolve-login
  [context _ _]

  (try

    (let [{{{email :email :as checked-authentication} :checked-authentication}
           :request} context

          ;; client-id                                    (check-client-id-exists context)
          conn                                         (-> repl.state/system :persistence/datomic :opts :conn)
          {:keys [db-before db-after tx-data tempids]} (iam.user/conditionally-add-new-user! conn checked-authentication)

          rename-user-key-map {:db/id             :id
                               :user/email        :userEmail
                               :user/name         :userName
                               :user/external-uid :userExternalUid
                               :user/accounts     :userAccounts}

          rename-user-accounts-key-map {:bookkeeping.account/id      :accountId
                                        :bookkeeping.account/name    :accountName
                                        :bookkeeping.account/balance :accountBalance
                                        :bookkeeping.account/amount  :accountAmount}

          base-response {:user (->> [:db/id
                                     :user/email
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


      (run! (fn [[{game-db-id              :db/id
                  {game-status :db/ident} :game/status} :as game-entity]]

              (persistence.datomic/transact-entities! conn
                                                      [[:db/retract  game-db-id :game/status game-status]
                                                       [:db/add      game-db-id :game/status :game-status/exited]
                                                       [:db/add      game-db-id :game/end-time (c/to-date (t/now))]]))
            #_(running-games-for-user-device conn email client-id)
            (running-games-for-user-device conn email))

      (if (util/truthy? (and db-before db-after tx-data tempids))
        (assoc base-response :message :useradded)
        (assoc base-response :message :userexists)))

    (catch Exception e
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

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
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

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
        (log/info :resolver-error (with-out-str (ppi (bean e))))
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
        (let [conditionally-add-insufficient-funds-error-code
              (fn [message]
                (cond->> (hash-map :message message)
                  (clojure.string/starts-with? message "Insufficient Funds ") (conj [{:message "InsufficientFunds"}])))]

          (log/info :resolver-error (with-out-str (ppi (bean e))))

          (->> e bean :localizedMessage
               conditionally-add-insufficient-funds-error-code
               (resolve-as nil)))))))

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
        (do
          (log/info :resolver-error (with-out-str (ppi (bean e))))
          (->> e bean :localizedMessage (hash-map :message) (resolve-as nil)))))))

(defn update-sink-fn! [id-uuid sink-fn]
  (swap! (:game/games repl.state/system)
         update-in [id-uuid :sink-fn] (constantly #(do
                                                     ;; (println "sink-fn CALLED /" %)
                                                     (sink-fn {:message %}))) ))

(defn resolve-user [context {email :email :as args}_]

  (try

    (let [conn (-> repl.state/system :persistence/datomic :opts :conn)
          group-by-stock? true

          filter-subscriptions (fn [{product-id :payment/product-id :as payment}]

                                 (let [subscription? (juxt valid-apple-subscription-id? valid-google-subscription-id? valid-stripe-subscription-id?)]

                                   (->> (subscription? product-id)
                                        (into #{})
                                        (some true?))))

          user (-> (game.calculation/collect-realized-profit-loss-for-user-allgames conn email group-by-stock?)
                   first
                   graphql.encoder/user->graphql)]

      (->> (payments.persistence/user-payments conn email)
           (filter filter-subscriptions)
           (map graphql.encoder/payment-purchase->graphql)
           (assoc user :subscriptions)))

    (catch Throwable e
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

(defn resolve-users [context args _]

  (try

    (let [conn (-> repl.state/system :persistence/datomic :opts :conn)
          group-by-stock? true]

      (->> (game.calculation/collect-realized-profit-loss-all-users-allgames conn group-by-stock?)
           (map graphql.encoder/user->graphql)))

    (catch Throwable e
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

(defn resolve-user-personal-profit-loss [context {:keys [email gameId groupByStock] :as args} _]

  (try

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
             (map graphql.encoder/profit-loss->graphql))))

    (catch Throwable e
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

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

  (try

    (let [conn       (-> repl.state/system :persistence/datomic :opts :conn)
          game-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game/id (UUID/fromString gameId)))
          user-db-id (:db/id (ffirst (beatthemarket.iam.persistence/user-by-email conn email '[:db/id])))]

      (->> (game.calculation/collect-account-balances conn game-db-id user-db-id)
           (map graphql.encoder/portfolio-update->graphql)))

    (catch Throwable e
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

(defn resolve-pause-game [context {gameId :gameId :as args} _]

  ;; NOTE
  ;; ticks saved
  ;; P/Ls saved (realized)
  ;; game-status saved
  ;; tick index stored as no. of ticks saved

  (try

    (log/info :graphql.core.resolve-pause-game args)

    (let [game-id (UUID/fromString gameId)
          event {:type :ControlEvent
                 :event :pause
                 :game-id game-id}]

      (-> (game.games/send-control-event! game-id event)
          (assoc :gameId gameId)))

    (catch Exception e
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

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
        (games.control/resume-game! conn user-db-id game-control)

        ;; Response
        (-> (game.games/send-control-event! game-id event)
            (assoc :gameId gameId))))

    (catch Exception e
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

(defn resolve-restart-game [context {gameId :gameId} _]

  (try

    (let [client-id (check-client-id-exists context)
          conn (-> repl.state/system :persistence/datomic :opts :conn)
          {{{email :email} :checked-authentication} :request} context]

      (let [data-sequence-fn games.control/->data-sequence
            user-db-id       (:db/id (ffirst (beatthemarket.iam.persistence/user-by-email conn email '[:db/id])))
            game-id          (UUID/fromString gameId)

            {{saved-game-level :db/ident} :game/level
             level-timer :game/level-timer}
            (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))

            {:keys [profit-threshold lose-threshold]}
            (-> integrant.repl.state/config :game/game :levels
                (get saved-game-level))

            current-level (atom {:level            saved-game-level
                                 :profit-threshold profit-threshold
                                 :lose-threshold   lose-threshold})

            game-control (merge-with #(if %2 %2 %1)
                                     (games.core/default-game-control conn game-id {})
                                     {:game                  {:game/id game-id}
                                      :short-circuit-game? (atom false)
                                      :tick-sleep-atom (atom (-> integrant.repl.state/config :game/game :tick-sleep-ms))
                                      :level-timer     (atom level-timer)
                                      :current-level   current-level

                                      ;; NOTE
                                      ;; We want tick and realized P/L histories
                                      ;; But we don't want to recalculate (in-memory) P/L for a game restart
                                      :calculate-profit-loss
                                      (fn [_ _ game-id stock-ticks]

                                        (ppi [:A :restart-calculate-profit-loss])

                                        (let [updated-profit-loss-calculations {}]
                                          (game.persistence/update-profit-loss-state! game-id updated-profit-loss-calculations)
                                          (hash-map :stock-ticks stock-ticks
                                                    :profit-loss updated-profit-loss-calculations)))})]

        ;; (ppi [:unapplied-payments-for-user (payments.core/unapplied-payments-for-user conn user-db-id)])
        ;; (ppi [:applied-payments-for-user (payments.core/applied-payments-for-user conn user-db-id)])


        ;; NOTE game status is updated in: resume-game -> run-game
        (games.control/resume-game! conn user-db-id game-control)

        ;; NOTE WTF!!!
        ;; In-memory P/L isn't getting rest... only in PROD >:|
        ;; (beatthemarket.game.persistence/update-profit-loss-state! game-id [])

        {:type   :ControlEvent
         :event  :restart
         :gameId gameId}))

    (catch Exception e
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

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

    (catch Throwable e
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

(defn game-paused? [conn game-id]

  (= :game-status/paused
     (-> (persistence.core/entity-by-domain-id conn :game/id game-id) ffirst :game/status :db/ident)))

(defn resolve-exit-game [context {gameId :gameId} _]

  (try

    (let [game-id (UUID/fromString gameId)
          conn (-> repl.state/system :persistence/datomic :opts :conn)
          event   {:type    :ControlEvent
                   :event   :exit
                   :game-id game-id}

          exit-game-manually (constantly
                               (do
                                 (games.control/update-short-circuit-game! game-id true)
                                 (games.control/exit-game! conn game-id)))]

      (if (game-paused? conn game-id)

        (exit-game-manually)

        (-> (game.games/send-control-event! game-id event)
            (assoc :gameId gameId))))

    (catch Throwable e
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

(defn resolve-games [context {gameId :gameId} _]

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

    (catch Throwable e
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

(defn delete-stripe-customer [context {id :id} parent]

  (try

    (let [{{client :client} :payment.provider/stripe} repl.state/system]
      {:message (:success? (payments.stripe/delete-customer! client id))})

    (catch Throwable e
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

(defn user-payments [context args _]

  (try

    (let [conn (-> repl.state/system :persistence/datomic :opts :conn)
          {{{email :email} :checked-authentication} :request} context]

      (->> (payments.persistence/user-payments conn email)
           (map graphql.encoder/payment-purchase->graphql)))

    (catch Throwable e
      (do
        (log/info :resolver-error (with-out-str (ppi (bean e))))
        (->> e bean :localizedMessage (hash-map :message) (resolve-as nil))))))

(defmulti verify-payment-handler (fn [_ {provider :provider} _] provider))

(defmethod verify-payment-handler "apple" [context {productId :productId
                                                    provider :provider
                                                    token :token :as args} _]

  (let [client-id (check-client-id-exists context)
        {{{email :email} :checked-authentication} :request} context
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        {:keys [verify-receipt-endpoint primary-shared-secret]} (-> repl.state/config :payment.provider/apple :service)
        apple-hash (json/read-str token :key-fn keyword)]

    (->> (payments.apple/verify-payment-workflow conn client-id email verify-receipt-endpoint primary-shared-secret apple-hash)
         (map graphql.encoder/payment-purchase->graphql))))

(defmethod verify-payment-handler "google" [context {product-id :productId :as args} _]

  (let [client-id (check-client-id-exists context)
        {{{email :email} :checked-authentication} :request} context
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        payment-config (-> repl.state/config :payment.provider/google :service)]

    (cond

      (valid-google-product-id? product-id)
      (->> (payments.google/verify-product-payment-workflow conn client-id email payment-config args)
           (map graphql.encoder/payment-purchase->graphql))

      (valid-google-subscription-id? product-id)
      (->> (payments.google/verify-subscription-payment-workflow conn client-id email payment-config args)
           (map graphql.encoder/payment-purchase->graphql))

      :else (throw (Exception. (format "Invalid product ID given %s" product-id))))))

(defmethod verify-payment-handler "stripe" [context {product-id :productId :as args} _]

  ;; (ppi args)
  ;; (ppi #_context (check-client-id-exists context))
  ;; (ppi (-> context :request :checked-authentication))

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
        (log/info :error (with-out-str (ppi (bean e))))
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
          (let [a (graphql.encoder/game-event->graphql game-event)]

            (println [:game-event a])
            a))

        (recur)))

    ;; Return a cleanup fn
    cleanup-fn))
