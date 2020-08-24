(ns beatthemarket.handler.graphql.core
  (:require [clojure.core.async :as core.async
             :refer [>!!]]
            [datomic.client.api :as d]
            [com.rpl.specter :refer [transform ALL MAP-KEYS MAP-VALS]]
            [integrant.repl.state :as repl.state]
            [beatthemarket.util :as util]
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
            [beatthemarket.handler.graphql.encoder :as graphql.encoder]
            [clojure.data.json :as json])
  (:import [java.util UUID]))


(defn coerce-uuid->str [k v]
  (if (= :accountId k)
    (str v)
    v))


;; RESOLVERS
(defn resolve-login
  [context _ _]

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
      (assoc base-response :message :userexists))))

(defn resolve-create-game [context {gameLevel :gameLevel :as args} parent]

  (let [conn                                                (-> repl.state/system :persistence/datomic :opts :conn)
        {{{email :email} :checked-authentication} :request} context
        user-db-id                                          (ffirst
                                                              (d/q '[:find ?e
                                                                     :in $ ?email
                                                                     :where [?e :user/email ?email]]
                                                                   (d/db conn)
                                                                   email))

        mapped-game-level (get graphql.encoder/game-level-map gameLevel)


        data-generators (-> repl.state/config :game/game :data-generators)
        seed (beatthemarket.datasource.core/random-seed) ;; TODO ->> pull seed from DB (if resuming game)
        ;; combined-data-sequence-fn (fn [] (games.control/->data-sequence data-generators seed))
        combined-data-sequence-fn games.control/->data-sequence

        ;; NOTE sink-fn updates once we start to stream a game
        sink-fn                identity
        {{game-id :game/id
          :as     game} :game} (game.games/create-game! conn user-db-id sink-fn mapped-game-level
                                                        combined-data-sequence-fn
                                                        {:accounts (game.core/->game-user-accounts)})]

    (->> (game.games/game->new-game-message game user-db-id)
         (transform [:stocks ALL] #(dissoc % :db/id))
         (transform [:stocks ALL MAP-KEYS] (comp keyword name)))))

(defn resolve-start-game [context {game-id :id :as args} _]

  (let [{{{email :email} :checked-authentication} :request} context
        conn                                                (-> repl.state/system :persistence/datomic :opts :conn)
        user-db-id                                          (ffirst
                                                              (d/q '[:find ?e
                                                                     :in $ ?email
                                                                     :where [?e :user/email ?email]]
                                                                   (d/db conn)
                                                                   email))
        gameId (UUID/fromString game-id)
        game-control (->> repl.state/system :game/games deref (#(get % gameId)))]

    (->> (game.games/start-game! conn user-db-id game-control (get args :startPosition 0))
         (map :stock-ticks)
         (map #(map graphql.encoder/stock-tick->graphql %)))))

(defn resolve-buy-stock [context args _]

  ;; (println "resolve-buy-stock CALLED /" args)
  (let [{{{userId :uid
           email :email} :checked-authentication} :request} context
        {{:keys [gameId stockId stockAmount tickId tickPrice]} :input} args
        conn                                               (-> repl.state/system :persistence/datomic :opts :conn)
        game-control                                       (->> repl.state/system :game/games deref (#(get % (UUID/fromString gameId))))
        user-db-id                                         (ffirst
                                                             (d/q '[:find ?e
                                                                    :in $ ?email
                                                                    :where [?e :user/email ?email]]
                                                                  (d/db conn)
                                                                  email))
        gameId (UUID/fromString gameId)
        stockId (UUID/fromString stockId)
        tickId (UUID/fromString tickId)
        tickPrice (Float. tickPrice)]

    (try
      (if ((comp :bookkeeping.tentry/id :tentry first)
           (games.pipeline/buy-stock-pipeline game-control conn userId gameId stockId stockAmount tickId (Float. tickPrice) false))

        {:message "Ack"}
        {:message (ex-info "Error / resolve-buy-stock / INCOMPLETE /" {})})
      (catch Throwable e
        {:message (ex-info "Error / resolve-buy-stock /" (bean e) e)}))))

(defn resolve-sell-stock [context args _]

  ;; (println "resolve-sell-stock CALLED /" args)
  (let [{{{userId :uid
           email :email} :checked-authentication} :request} context
        {{:keys [gameId stockId stockAmount tickId tickPrice]} :input} args
        conn                                               (-> repl.state/system :persistence/datomic :opts :conn)
        game-control                                       (->> repl.state/system :game/games deref (#(get % (UUID/fromString gameId))))
        user-db-id                                          (ffirst
                                                              (d/q '[:find ?e
                                                                     :in $ ?email
                                                                     :where [?e :user/email ?email]]
                                                                   (d/db conn)
                                                                   email))
        gameId (UUID/fromString gameId)
        stockId (UUID/fromString stockId)
          tickId (UUID/fromString tickId)
        tickPrice (Float. tickPrice)]

    (try
      (if ((comp :bookkeeping.tentry/id :tentry first)
           (games.pipeline/sell-stock-pipeline game-control conn userId gameId stockId stockAmount tickId (Float. tickPrice) false))

        {:message "Ack"}
        {:message (ex-info "Error / resolve-sell-stock / INCOMPLETE /" {})})
      (catch Throwable e
        {:message (ex-info "Error / resolve-sell-stock /" (bean e) e)}))))

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

(defn resolve-user-personal-profit-loss [context {email :email gameId :gameId :as args} _]

  (let [game-id (UUID/fromString gameId)

        realized-profit-loss (map graphql.encoder/profit-loss->graphql
                                  (game.calculation/collect-realized-profit-loss game-id))
        running-profit-loss (map graphql.encoder/profit-loss->graphql
                                 (game.calculation/collect-running-profit-loss game-id))]

    (concat realized-profit-loss running-profit-loss)))

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

  (let [conn (-> repl.state/system :persistence/datomic :opts :conn)]

    (->> (d/q '[:find (pull ?uas [*])
                          :in $ ?gameId ?email
                          :where
                          [?g :game/id ?gameId]
                          [?g :game/users ?gus]
                          [?gus :game.user/user ?gu]
                          [?gu :user/email ?email]
                          [?gus :game.user/accounts ?uas]]
                        (d/db conn)
                        (UUID/fromString gameId) email)
                   (map first)
                   (transform [ALL identity] #(dissoc % :db/id))
                   (transform [ALL identity] #(clojure.set/rename-keys % {:bookkeeping.account/id :id
                                                                          :bookkeeping.account/name :name
                                                                          :bookkeeping.account/balance :balance
                                                                          :bookkeeping.account/amount :amount
                                                                          :bookkeeping.account/counter-party :counterParty})))))

(defn resolve-pause-game [context {gameId :gameId} _]

  ;; TODO
  ;; [ok] save ticks
  ;; save P/Ls
  ;; save game-status
  ;; ? save tick index (check if we can count the number of ticks that have been saved)
  ;; ? Can we replay stock-ticks + buys + sells

  (let [game-id (UUID/fromString gameId)
        event {:type :ControlEvent
               :event :pause
               :game-id game-id}]

    (-> (game.games/send-control-event! game-id event)
        (assoc :gameId gameId))))

(defn resolve-resume-game [context {gameId :gameId} _]

  ;; TODO
  ;; load ticks
  ;; load P/Ls
  ;; load game-status
  ;; seek to tick index

  (let [game-id (UUID/fromString gameId)
        event {:type :ControlEvent
               :event :resume
               :game-id game-id}]

    (-> (game.games/send-control-event! game-id event)
        (assoc :gameId gameId))))

(defn resolve-exit-game [context {gameId :gameId} _]

  (let [game-id (UUID/fromString gameId)
        event {:type :ControlEvent
               :event  :exit
               :gameId game-id}]

    (-> (game.games/send-control-event! game-id event)
        (assoc :gameId gameId))))

(defn resolve-list-games [context {gameId :gameId} _]

  (let [{{{email :email :as checked-authentication} :checked-authentication}
         :request}                                   context]

    ;; TODO
    [{:gameId gameId
      :status :running
      :profitLoss 0.0}]))


; STREAMERS

;; https://lacinia-pedestal.readthedocs.io/en/latest/subscriptions.html#overview
;; When a streamer passes nil to the callback, a clean shutdown of the subscription occurs; the client is sent a completion message. The completion message informs the client that the stream of events has completed, and that it should not attempt to reconnect.
(defn stream-stock-ticks [context {id :gameId :as args} source-stream]

  (println "stream-stock-ticks CALLED")
  (let [conn                                                (-> repl.state/system :persistence/datomic :opts :conn)
        {{{email :email} :checked-authentication} :request} context
        user-db-id                                          (ffirst
                                                              (d/q '[:find ?e
                                                                     :in $ ?email
                                                                     :where [?e :user/email ?email]]
                                                                   (d/db conn)
                                                                   email))
        id-uuid                                             (UUID/fromString id)
        stock-tick-stream                                   (-> repl.state/system
                                                                :game/games
                                                                deref
                                                                (get (UUID/fromString id))
                                                                :stock-tick-stream
                                                                ;; util/pprint+identity
                                                                )
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
        user-db-id                                          (ffirst
                                                              (d/q '[:find ?e
                                                                     :in $ ?email
                                                                     :where [?e :user/email ?email]]
                                                                   (d/db conn)
                                                                   email))
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
        user-db-id                                          (ffirst
                                                              (d/q '[:find ?e
                                                                     :in $ ?email
                                                                     :where [?e :user/email ?email]]
                                                                   (d/db conn)
                                                                   email))
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
