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
      :useradded
      :userexists)))

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
          :as     game} :game} (game.games/create-game! conn user-db-id sink-fn)
        message                (game.games/game->new-game-message game user-db-id)]

    {:message message}))

(defn resolve-start-game [context args _]
  :gamestarted)

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
(defn stream-stock-ticks
  [context {id :id :as args} source-stream]

  (let [conn                                                (-> repl.state/system :persistence/datomic :opts :conn)
        {{{email :email} :checked-authentication} :request} context
        user-db-id                                          (ffirst
                                                              (d/q '[:find ?e
                                                                     :in $ ?email
                                                                     :where [?e :user/email ?email]]
                                                                   (d/db conn)
                                                                   email))
        id-uuid                                             (UUID/fromString id)
        _                                                   (update-sink-fn! id-uuid source-stream)
        {game            :game
         control-channel :control-channel
         :as             game-control}                                  (-> repl.state/system :game/games deref (get id-uuid))
        game-user-subscription                              (-> game
                                                                :game/users first
                                                                :game.user/subscriptions first)

        game-loop-fn        identity
        {{:keys               [mixer
                               pause-chan
                               input-chan
                               output-chan] :as channel-controls}
         :channel-controls} (game.games/start-game! conn user-db-id game-control game-loop-fn)]


    ;; TODO
    ;; [ok] Register channel-controls

    ;; GQL responses no longer in a :message

    ;; Make a control to :exit a game
    ;; Make a control to :pause | :resume a game

    ;; STREAMS
    ;; put stock ticks into a channel
    ;; put portfoliio updates into a channel
    ;; put game events into a channel


    (core.async/<!! (core.async/timeout 10000))
    ;; (game.games/control-streams! control-channel channel-controls :exit)

    ;; D Return a cleanup fn
    (constantly nil)))


(defn stream-portfolio-updates [context args source-stream]
  )

(defn stream-game-events [context args source-stream]
  )



;; NOTE subscription resolver
(def *ping-subscribes (atom 0))
(def *ping-cleanups (atom 0))
(def *ping-context (atom nil))

(defn stream-ping
  [context args source-stream]

  (swap! *ping-subscribes inc)
  (reset! *ping-context context)
  (let [{:keys [message count]} args
        runnable                ^Runnable (fn []
                                            (dotimes [i count]

                                              ;; (println "Sanity check / stream-ping / " [i count])
                                              (source-stream (util/pprint+identity {:message   (str message " #" (inc i))
                                                                                    :timestamp (System/currentTimeMillis)}))
                                              (Thread/sleep 50))

                                            (source-stream nil))]
    (.start (Thread. runnable "stream-ping-thread")))

  ;; Return a cleanup fn:
  #(swap! *ping-cleanups inc))
