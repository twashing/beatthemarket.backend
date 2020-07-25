(ns beatthemarket.graphql
  (:require [clojure.core.async :as core.async
             :refer [>!!]]
            [datomic.client.api :as d]
            [com.rpl.specter :refer [transform ALL MAP-VALS]]
            [integrant.repl.state :as repl.state]
            [beatthemarket.util :as util]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.game.core :as game.core]
            [beatthemarket.game.games :as game.games]
            [clojure.data.json :as json])
  (:import [java.util UUID]))


;; RESOLVERS
(defn resolve-login
  [context _ _]

  (let [{{checked-authentication :checked-authentication}
         :request}                                   context
        conn                                         (-> repl.state/system :persistence/datomic :opts :conn)
        {:keys [db-before db-after tx-data tempids]} (iam.user/conditionally-add-new-user! conn checked-authentication)]

    (if (util/truthy? (and db-before db-after tx-data tempids))
      {:message :useradded}
      {:message :userexists})))

(defn resolve-create-game [context args _]

  (let [conn                                                (-> repl.state/system :persistence/datomic :opts :conn)
        {{{email :email} :checked-authentication} :request} context
        user-db-id                                          (ffirst
                                                              (d/q '[:find ?e
                                                                     :in $ ?email
                                                                     :where [?e :user/email ?email]]
                                                                   (d/db conn)
                                                                   email))

        ;; NOTE sink-fn updates once we start to stream a game
        sink-fn                identity
        {{game-id :game/id
          :as     game} :game} (game.games/create-game! conn user-db-id sink-fn)]

    (->> (game.games/game->new-game-message game user-db-id)
         (transform [:stocks ALL] #(json/write-str (dissoc % :db/id))))))

(defn resolve-start-game [context {game-id :id} _]

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

    (game.games/start-game! conn user-db-id game-control)
    {:message :gamestarted}))

(defn resolve-buy-stock [context args _]

  ;; (println "resolve-buy-stock CALLED /" args)
  (let [{{{userId :uid} :checked-authentication} :request} context
        conn                                               (-> repl.state/system :persistence/datomic :opts :conn)
        {{:keys [gameId stockId stockAmount tickId tickPrice]} :input} args
        gameId (UUID/fromString gameId)
        stockId (UUID/fromString stockId)
        tickId (UUID/fromString tickId)
        tickPrice (Float. tickPrice)]

    (try
      (if (:bookkeeping.tentry/id (game.games/buy-stock! conn userId gameId stockId stockAmount tickId tickPrice))
        {:message "Ack"}
        {:message (ex-info "Error / resolve-buy-stock / INCOMPLETE /" {})})
      (catch Throwable e
        {:message (ex-info "Error / resolve-buy-stock /" (bean e) e)}))))

(defn resolve-sell-stock [context args _]

  (println "resolve-sell-stock CALLED /" args)
  (let [{{{userId :uid} :checked-authentication} :request} context
        conn                                               (-> repl.state/system :persistence/datomic :opts :conn)
        {{:keys [gameId stockId stockAmount tickId tickPrice]} :input} args
        gameId (UUID/fromString gameId)
        stockId (UUID/fromString stockId)
          tickId (UUID/fromString tickId)
        tickPrice (Float. tickPrice)]

    (try
      (if (:bookkeeping.tentry/id (game.games/sell-stock! conn userId gameId stockId stockAmount tickId tickPrice))
        {:message "Ack"}
        {:message (ex-info "Error / resolve-sell-stock / INCOMPLETE /" {})})
      (catch Throwable e
        {:message (ex-info "Error / resolve-sell-stock /" (bean e) e)}))))

(defn update-sink-fn! [id-uuid sink-fn]
  (swap! (:game/games repl.state/system)
         update-in [id-uuid :sink-fn] (constantly #(do
                                                     ;; (println "sink-fn CALLED /" %)
                                                     (sink-fn {:message %}))) ))

(defn resolve-account-balances [context args _])

(defn resolve-stock-history [context args _])


;; STREAMERS
(defn stream-stock-ticks [context {id :gameId :as args} source-stream]

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
                                                                :stock-tick-stream)
        cleanup-fn                                          (constantly (core.async/close! stock-tick-stream))]

    (core.async/go-loop []
      (when-let [stock-ticks (core.async/<! stock-tick-stream)]
        (->> stock-ticks
             (map #(clojure.set/rename-keys %
                                            {:game.stock.tick/id :stockTickId
                                             :game.stock.tick/trade-time :stockTickTime
                                             :game.stock.tick/close :stockTickClose
                                             :game.stock/id :stockId
                                             :game.stock/name :stockName}))
             source-stream)
        (recur)))

    ;; Return a cleanup fn
    cleanup-fn))

(defn stream-portfolio-updates [context {id :gameId :as args} source-stream]
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
        cleanup-fn                                          (constantly (core.async/close! portfolio-update-stream))]

    (core.async/go-loop []
      (when-let [stock-ticks (core.async/<! portfolio-update-stream)]
        (->> stock-ticks
             (map #(clojure.set/rename-keys %
                                            {:game.stock.tick/id         :stockTickId
                                             :game.stock.tick/trade-time :stockTickTime
                                             :game.stock.tick/close      :stockTickClose
                                             :game.stock/id              :stockId
                                             :game.stock/name            :stockName}))
             source-stream)
        (recur)))

    ;; Return a cleanup fn
    cleanup-fn))

(defn stream-game-events [context {id :gameId :as args} source-stream]
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
        cleanup-fn                                          (constantly (core.async/close! game-event-stream))]

    (core.async/go-loop []
      (when-let [stock-ticks (core.async/<! game-event-stream)]
        (->> stock-ticks
             (map #(clojure.set/rename-keys %
                                            {:game.stock.tick/id         :stockTickId
                                             :game.stock.tick/trade-time :stockTickTime
                                             :game.stock.tick/close      :stockTickClose
                                             :game.stock/id              :stockId
                                             :game.stock/name            :stockName}))
             source-stream)
        (recur)))

    ;; Return a cleanup fn
    cleanup-fn))
