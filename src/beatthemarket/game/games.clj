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

            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]

            [beatthemarket.util :as util])
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

                                          user-entity ;; PASSED in as options
                                          accounts
                                          game-level]
                                   :or {game-id (UUID/randomUUID)
                                        stocks-in-game 4}
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
         input-sequence-local    (games.control/stocks->stock-sequences stocks-with-tick-data)
         {:keys [profit-threshold lose-threshold]} (-> integrant.repl.state/config :game/game :levels
                                                       (get saved-game-level))

         current-level (atom {:level            saved-game-level
                              :profit-threshold profit-threshold
                              :lose-threshold   lose-threshold})

         game-control (merge-with #(if %2 %2 %1)
                                  (games.core/default-game-control conn (:db/id user-entity) game-id
                                                                   {:current-level current-level})
                                  {:game                  game                  ;; TODO load
                                   :profit-loss           (or profit-loss {})   ;; TODO replay
                                   :stocks-with-tick-data stocks-with-tick-data ;; TODO load + seek to index
                                   :input-sequence        (or input-sequence input-sequence-local)

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

     ;; (util/pprint+identity (dissoc game-control :input-sequence :stocks-with-tick-data))
     (games.core/register-game-control! game game-control)
     game-control)))

(defn create-game!

  ([conn sink-fn]
   (create-game! conn sink-fn games.control/->data-sequence {}))

  ([conn sink-fn data-sequence-fn {game-level :game-level
                                   accounts   :accounts
                                   :or        {game-level :game-level/one
                                               accounts   (game.core/->game-user-accounts)}
                                   :as        opts}]

   (initialize-game! conn sink-fn data-sequence-fn opts)))


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

#_(defn level-timer->db [minute-and-second-tuple]
    (str minute-and-second-tuple))

#_(defn db->level-timer [minute-and-second-tuple]
    (clojure.edn/read-string minute-and-second-tuple))

#_(defn inputs->historical-data [input-sequence start-position]
    (take start-position input-sequence))

#_(defn inputs->control-chain [{:keys [game input-sequence
                                     stream-stock-tick calculate-profit-loss
                                     collect-profit-loss stream-portfolio-update!
                                     process-transact! transact-profit-loss
                                     check-level-complete]}]

  (->> input-sequence
       (map process-transact!)
       (map stream-stock-tick)
       (map calculate-profit-loss)
       (map collect-profit-loss)
       (map transact-profit-loss)
       (map stream-portfolio-update!)
       (map check-level-complete)))

#_(defn inputs->processing-pipeline [conn {:keys [game input-sequence

                                                  control-channel
                                                  stock-tick-stream
                                                  portfolio-update-stream
                                                  game-event-stream

                                                  process-transact!
                                                  stream-stock-tick calculate-profit-loss
                                                  collect-profit-loss stream-portfolio-update!
                                                  transact-profit-loss
                                                  check-level-complete
                                                  stream-level-update!]}]

    ;; (comp (map process-transact!)) ;; input-sequence
    ;; (comp (map (buy-stock! conn userId gameId stockId stockAmount tickId tickPrice)))

    (comp
      (map calculate-profit-loss)
      (map process-transact!)
      ;; (map collect-profit-loss)
      ;; (map transact-profit-loss)
      (map stream-portfolio-update!)
      (map check-level-complete)
      (map stream-level-update!)))

(defn update-start-position! [conn game-id start-position]

  ;; (util/pprint+identity [game-id start-position])

  (let [{game-db-id :db/id
         old-start-position :game/start-position} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))

        data (cond-> []
               old-start-position (conj [:db/retract game-db-id :game/start-position old-start-position])
               true (conj [:db/add game-db-id :game/start-position start-position]))]

    (persistence.datomic/transact-entities! conn data)))

(defn start-game!

  ([conn user-db-id game-control]
   (start-game! conn user-db-id game-control 0))

  ([conn user-db-id {{game-id :game/id} :game :as game-control} start-position]

   ;; A
   (update-start-position! conn game-id start-position)

   ;; B
   (let [{:keys [control-channel
                 tick-sleep-atom
                 level-timer]} game-control

         [historical-data inputs-at-position] (->> (games.pipeline/stock-tick-pipeline user-db-id game-control)
                                                   (games.control/seek-to-position start-position))]

     (as-> inputs-at-position v
       (games.control/run-iteration v)
       (assoc game-control :iterations v)
       (games.control/run-game! conn v tick-sleep-atom level-timer))

     historical-data)))

(defn start-workbench!

  ([conn user-db-id game-control]
   (start-workbench! conn user-db-id game-control 0))

  ([conn user-db-id
    {{game-id :game/id} :game
     level-timer :level-timer
     tick-sleep-atom :tick-sleep-atom
     game-event-stream :game-event-stream
     control-channel :control-channel
     :as game-control}
    start-position]

   ;; A
   (update-start-position! conn game-id start-position)

   (core.async/go-loop [now (t/now)
                        end (t/plus now (t/seconds @level-timer))]

     (let [remaining (games.control/calculate-remaining-time now end)
           expired? (games.control/time-expired? remaining)

           [{message :event :as controlv} ch] (core.async/alts! [(core.async/timeout @tick-sleep-atom) control-channel])
           {message :event :as controlv} (if (nil? controlv) {:event :continue} controlv)

           [nowA endA] (match [message expired?]
                              [_ false] (games.control/handle-control-event conn game-event-stream controlv now end)
                              [_ true] (games.control/handle-control-event conn game-event-stream {:event :timeout} now end))]

       (when (and nowA endA)
         (recur nowA endA))))

   ;; B
   (let [[historical-data inputs-at-position] (->> (games.pipeline/stock-tick-pipeline user-db-id game-control)
                                                   (games.control/seek-to-position start-position))]

     [historical-data (games.control/run-iteration inputs-at-position)])))
