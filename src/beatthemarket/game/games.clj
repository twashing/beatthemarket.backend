(ns beatthemarket.game.games
  (:require [clojure.core.async :as core.async :refer [go go-loop chan close! timeout alts! >! <! >!!]]
            [clojure.core.async.impl.protocols]
            [clj-time.core :as t]
            [clojure.data.json :as json]
            [datomic.client.api :as d]
            [com.rpl.specter :refer [transform ALL MAP-VALS]]
            [clojure.core.match :refer [match]]
            [rop.core :as rop]
            [integrant.core :as ig]
            [integrant.repl.state :as repl.state]
            [io.pedestal.log :as log]

            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.iam.persistence :as iam.persistence]

            [beatthemarket.bookkeeping.core :as bookkeeping]
            [beatthemarket.bookkeeping.persistence :as bookkeeping.persistence]
            [beatthemarket.datasource.name-generator :as name-generator]

            [beatthemarket.game.core :as game.core]
            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.game.persistence :as game.persistence]
            [beatthemarket.game.games.processing :as games.processing]
            [beatthemarket.game.games.pipeline :as games.pipeline]
            [beatthemarket.game.games.control :as games.control]
            [beatthemarket.game.games.core :as games.core]
            [beatthemarket.game.games.state :as games.state]
            [beatthemarket.integration.payments.core :as integration.payments.core]

            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]

            [beatthemarket.util :refer [ppi] :as util])
  (:import [java.util UUID]))


(defmethod ig/init-key :game/games [_ _]
  (atom {}))

(defmethod ig/halt-key! :game/games [_ games]
  #_(run! (fn [{:keys [stock-tick-stream
                    portfolio-update-stream
                    game-event-stream
                    control-channel
                    game]}]

          (log/info :game.games "Closing Game channels...")

          (let [{game-id :db/id} game]
            (go (>! control-channel {:type "ControlEvent"
                                     :event :exit
                                     :gameId game-id})))
          (close! control-channel)

          (close! stock-tick-stream)
          (close! portfolio-update-stream)
          (close! game-event-stream))
        (-> games deref vals)))


;; CREATE
(defn initialize-game!

  ([conn sink-fn]

   (initialize-game! conn sink-fn games.control/->data-sequence {:game-level :game-level/one}))

  ([conn sink-fn data-sequence-fn {:keys [client-id game-id

                                          stocks-in-game
                                          stagger-wavelength?
                                          level-timer-sec tick-sleep-ms
                                          input-sequence profit-loss

                                          process-transact!

                                          control-channel
                                          stock-tick-stream
                                          portfolio-update-stream
                                          game-event-stream

                                          stream-stock-tick
                                          calculate-profit-loss stream-portfolio-update!
                                          check-level-complete stream-level-update!

                                          user ;; PASSED in as options
                                          accounts
                                          game-level]
                                   :or {game-id        (UUID/randomUUID)
                                        stocks-in-game 4
                                        stagger-wavelength? (-> integrant.repl.state/config :game/game :stagger-wavelength?)}
                                   :as opts}]

   (let [data-generators      (-> integrant.repl.state/config :game/game :data-generators)
         initialize-game-opts (merge opts
                                     {:game-id     game-id
                                      :game-status :game-status/created
                                      :stocks      (game.core/generate-stocks! stocks-in-game)})

         {game-id                      :game/id
          stocks                       :game/stocks
          {saved-game-level :db/ident} :game/level :as game}
         (game.core/initialize-game! conn initialize-game-opts)

         stocks-with-tick-data   (games.control/stocks->stocks-with-tick-data stocks data-sequence-fn data-generators)

         vary-wavelength-window 10
         vary-wavelength-windows (iterate (partial + vary-wavelength-window) vary-wavelength-window)

         stocks-with-tick-data-with-staggered-wavelength
         (if-not stagger-wavelength?
           stocks-with-tick-data
           (map (fn [stocks window]
                  (update-in stocks [:data-sequence] (fn [ds] (drop window ds))))
                stocks-with-tick-data
                vary-wavelength-windows))

         input-sequence-local    (games.control/stocks->stock-sequences stocks-with-tick-data-with-staggered-wavelength)
         {:keys [profit-threshold lose-threshold]} (-> integrant.repl.state/config :game/game :levels
                                                       (get saved-game-level))

         current-level (atom {:level            saved-game-level
                              :profit-threshold profit-threshold
                              :lose-threshold   lose-threshold})

         game-control (merge-with #(if %2 %2 %1)
                                  opts
                                  (games.core/default-game-control conn game-id
                                                                   (assoc opts :current-level current-level))
                                  {:game                  game                  ;; TODO load
                                   :profit-loss           (or profit-loss {})   ;; TODO replay
                                   :stocks-with-tick-data stocks-with-tick-data-with-staggered-wavelength ;; TODO load + seek to index
                                   :input-sequence        (or input-sequence input-sequence-local)

                                   :short-circuit-game? (atom false)
                                   :cash-position-at-game-start (atom 0.0)
                                   :tick-sleep-atom (atom
                                                      (or tick-sleep-ms
                                                          (-> integrant.repl.state/config :game/game :tick-sleep-ms)))
                                   :level-timer     (atom
                                                      (or level-timer-sec
                                                          (-> integrant.repl.state/config :game/game :level-timer-sec)))
                                   :current-level   current-level

                                   :control-channel         control-channel
                                   :stock-tick-stream       stock-tick-stream
                                   :portfolio-update-stream portfolio-update-stream
                                   :game-event-stream       game-event-stream

                                   :process-transact!        process-transact!
                                   :stream-stock-tick        stream-stock-tick
                                   :calculate-profit-loss    calculate-profit-loss
                                   :stream-portfolio-update! stream-portfolio-update!
                                   :check-level-complete     check-level-complete
                                   :stream-level-update!     stream-level-update!

                                   :close-sink-fn (partial sink-fn nil)
                                   :sink-fn       #(sink-fn {:event %})})]

     (games.state/register-game-control! game game-control)
     game-control)))

(defn create-game!

  ([conn sink-fn]
   (create-game! conn sink-fn games.control/->data-sequence {}))

  ([conn sink-fn data-sequence-fn {game-level :game-level
                                   accounts   :accounts
                                   :or        {game-level :game-level/one
                                               accounts   (game.core/->game-user-accounts)}
                                   :as        opts}]

   (initialize-game! conn sink-fn data-sequence-fn (assoc opts
                                                          :game-level game-level
                                                          :accounts accounts))))


;; START
(defn game->new-game-message [game user-id]

  (let [game-stocks (:game/stocks game)]

    (as-> {:stocks game-stocks} v
      (transform [MAP-VALS ALL :game.stock/id] str v)
      (assoc v :id (str (:game/id game))))))

(defn send-control-event! [game-id event]
  (->> repl.state/system :game/games deref (#(get % game-id))
       :control-channel
       (#(core.async/go (core.async/>!! % event))))
  event)

(defn game-status [conn game-id]
  (ffirst
    (d/q '[:find ?game-status
           :in $ ?game-id
           :where
           [?e :game/id ?game-id]
           [?e :game/status ?game-status]]
         (d/db conn)
         game-id)))

(defn game-paused? [game-id]
  (= (game-status game-id)
     :game-status/paused))

(defn update-start-position! [conn game-id start-position]

  (let [{game-db-id :db/id
         old-start-position :game/start-position} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))

        data (cond-> []
               old-start-position (conj [:db/retract game-db-id :game/start-position old-start-position])
               true (conj [:db/add game-db-id :game/start-position start-position]))]

    (persistence.datomic/transact-entities! conn data)))

(defn start-game!

  ([conn game-control]
   (start-game! conn game-control 0))

  ([conn
    {{user-db-id :db/id :as user-entity} :user
     {game-id :game/id :as game-entity} :game :as game-control}
    start-position]

   ;; A
   (integration.payments.core/apply-unapplied-payments-for-user conn user-entity game-entity)
   (integration.payments.core/apply-previous-games-unused-payments-for-user conn user-entity game-entity)
   (update-start-position! conn game-id start-position)


   ;; B
   (let [{cash-position-at-game-start :bookkeeping.account/balance}
         (bookkeeping.persistence/cash-account-by-game-user conn user-db-id game-id)]

     (games.state/update-inmemory-cash-position-at-game-start! game-id cash-position-at-game-start))


   ;; C
   (let [{:keys [control-channel tick-sleep-atom]} game-control
         [historical-data inputs-at-position] (->> (games.pipeline/stock-tick-pipeline game-control)
                                                   (games.control/seek-to-position start-position))]

     ;; TODO
     ;; games.control/step-game
     ;; games.control/run-game!
     (as-> inputs-at-position v
       (games.control/run-iteration v)
       (assoc game-control :iterations v)
       (games.control/run-game! conn v tick-sleep-atom))

     historical-data)))

(defn game-workbench-loop [conn
                           {{game-id :game/id} :game
                            control-channel :control-channel
                            game-event-stream :game-event-stream
                            level-timer :level-timer}
                           tick-sleep-atom]

  (core.async/go-loop [now (t/now)
                       end (t/plus now (t/seconds @level-timer))]

    (let [remaining (games.state/calculate-remaining-time now end)]

      (log/info :game.games (format "game-workbench-loop %s:%s"
                                    (:remaining-in-minutes remaining)
                                    (:remaining-in-seconds remaining)))

      (let [remaining (games.state/calculate-remaining-time now end)
            expired? (games.control/time-expired? remaining)

            [{message :event :as controlv} ch] (core.async/alts! [(core.async/timeout @tick-sleep-atom) control-channel])
            {message :event :as controlv} (if (nil? controlv) {:event :continue} controlv)

            short-circuit-game? (-> repl.state/system :game/games deref (get game-id) :short-circuit-game? deref)
            [nowA endA] (match [message expired?]
                               [_ false] (games.control/handle-control-event conn game-event-stream controlv now end)
                               [_ true] (games.control/handle-control-event conn game-event-stream {:game-id game-id
                                                                                                    :event :timeout} now end))]

        (when (and nowA
                   endA
                   (not short-circuit-game?))
          (recur nowA endA))))))

(defn start-game!-workbench

  ([conn game-control]
   (start-game!-workbench conn game-control 0))

  ([conn
    {{user-db-id :db/id :as user-entity} :user
     {game-id :game/id :as game-entity} :game
     level-timer :level-timer
     tick-sleep-atom :tick-sleep-atom
     game-event-stream :game-event-stream
     control-channel :control-channel
     :as game-control}
    start-position]

   ;; A
   (integration.payments.core/apply-unapplied-payments-for-user conn user-entity game-entity)
   (integration.payments.core/apply-previous-games-unused-payments-for-user conn user-entity game-entity)
   (update-start-position! conn game-id start-position)
   (game-workbench-loop conn game-control tick-sleep-atom)

   ;; B
   (let [{cash-position-at-game-start :bookkeeping.account/balance}
         (bookkeeping.persistence/cash-account-by-game-user conn user-db-id game-id)]

     (games.state/update-inmemory-cash-position-at-game-start! game-id cash-position-at-game-start))

   ;; C
   (let [[historical-data inputs-at-position] (->> (games.pipeline/stock-tick-pipeline game-control)
                                                   (games.control/seek-to-position start-position))]

     [historical-data (games.control/run-iteration inputs-at-position)])))

(defn start-market!

  ([conn game-control]
   (start-market! conn game-control 0))

  ([conn {{game-id :game/id} :game :as game-control} start-position]

   ;; A
   (update-start-position! conn game-id start-position)

   ;; B
   (let [{:keys [control-channel
                 tick-sleep-atom]} game-control

         [historical-data inputs-at-position] (->> (games.pipeline/market-stock-tick-pipeline game-control)
                                                   (games.control/seek-to-position start-position))]

     (as-> inputs-at-position v
       (games.control/run-iteration v)
       (assoc game-control :iterations v)
       (games.control/run-game! conn v tick-sleep-atom))

     historical-data)))

(defn start-market!-workbench

  ([conn game-control]
   (start-market!-workbench conn game-control 0))

  ([conn {{game-id :game/id} :game :as game-control} start-position]

   ;; A
   (update-start-position! conn game-id start-position)

   ;; B
   (let [{:keys [control-channel
                 tick-sleep-atom]} game-control

         [historical-data inputs-at-position] (->> (games.pipeline/market-stock-tick-pipeline game-control)
                                                   (games.control/seek-to-position start-position))]

     [historical-data (games.control/run-iteration inputs-at-position)])))
