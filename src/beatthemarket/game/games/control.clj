(ns beatthemarket.game.games.control
  (:require [clojure.core.async :as core.async]
            [clojure.core.match :refer [match]]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [datomic.client.api :as d]
            [io.pedestal.log :as log]
            [integrant.repl.state :as repl.state]

            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.bookkeeping.persistence :as bookkeeping.persistence]
            [beatthemarket.datasource :as datasource]
            [beatthemarket.datasource.core :as datasource.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.game.core :as game.core]
            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.game.persistence :as game.persistence]
            ;; [beatthemarket.game.games :as game.games]
            [beatthemarket.game.games.state :as games.state]
            [beatthemarket.game.games.core :as game.games.core]
            [beatthemarket.game.games.state :as game.games.state]
            [beatthemarket.game.games.pipeline :as games.pipeline]
            [beatthemarket.game.games.processing :as games.processing]
            [beatthemarket.integration.payments.core :as integration.payments.core]
            [beatthemarket.util :refer [ppi] :as util])
  (:import [java.util UUID]))


(defn ->data-sequence

  ([]
   (-> repl.state/config
       :game/game
       :data-generators
       ->data-sequence))

  ([data-generators]
   (->data-sequence data-generators (datasource.core/random-seed)))

  ([data-generators seed]
   (apply (partial datasource/->combined-data-sequence seed datasource.core/beta-configurations) data-generators)))

(defn bind-data-sequence

  ([a]
   (bind-data-sequence ->data-sequence a))

  ([data-sequence-fn a]
   (->> (data-sequence-fn)
        (datasource/combined-data-sequence-with-datetime (t/now))
        (map #(conj % (UUID/randomUUID)))
        (assoc a :data-sequence))))

(defn- stocks->partitioned-entities
  "Output should be a partitioned list of {:tick :stock}.
   A count of 2 stocks should yield output that looks like below.

   (({:tick {:game.stock.tick/id #uuid \"2606edd1-b906-496a-a23a-b9e66e9128b8\"
             :game.stock.tick/trade-time 1593185674611
             :db/id \"d7c0bf6e-37be-4c43-94e4-df53d0c93973\"
             :game.stock.tick/close 105.83}
      :stock {:db/id 17592186045441
              :game.stock/id #uuid \"a6053ea6-591a-4ec2-8312-5131d9fe2b68\"
              :game.stock/name \"Honest Toe\"
              :game.stock/symbol \"HONE\"
              :game.stock/price-history
              {:game.stock.tick/id #uuid \"2606edd1-b906-496a-a23a-b9e66e9128b8\"
               :game.stock.tick/trade-time 1593185674611
               :db/id \"d7c0bf6e-37be-4c43-94e4-df53d0c93973\"
               :game.stock.tick/close 105.83}}}
     {:tick {:game.stock.tick/id #uuid \"61a97ae5-01f4-43d0-9c3f-0fb0f8fbd392\"
             :game.stock.tick/trade-time 1593185674642
             :db/id \"faa01f6c-fd1b-4f37-b08e-5c293325c2b1\"
             :game.stock.tick/close 102.29}
      :stock {:db/id 17592186045442
              :game.stock/id #uuid \"d09bda1b-7351-4557-81e8-f7af6f0f7534\"
              :game.stock/name \"Musical Area\"
              :game.stock/symbol \"MUSI\"
              :game.stock/price-history
              {:game.stock.tick/id #uuid \"61a97ae5-01f4-43d0-9c3f-0fb0f8fbd392\"
               :game.stock.tick/trade-time 1593185674642
               :db/id \"faa01f6c-fd1b-4f37-b08e-5c293325c2b1\"
               :game.stock.tick/close 102.29}}})

    ({:tick {:game.stock.tick/id #uuid \"d6918e67-84e8-4b97-aa99-c9c6f5e5ba95\"
             :game.stock.tick/trade-time 1593185675611
             :db/id \"9d942222-8f66-4857-b2e5-42cae2c1f5cd\"
             :game.stock.tick/close 105.64}
      :stock {:db/id 17592186045441
              :game.stock/id #uuid \"a6053ea6-591a-4ec2-8312-5131d9fe2b68\"
              :game.stock/name \"Honest Toe\"
              :game.stock/symbol \"HONE\"
              :game.stock/price-history
              {:game.stock.tick/id #uuid \"d6918e67-84e8-4b97-aa99-c9c6f5e5ba95\"
               :game.stock.tick/trade-time 1593185675611
               :db/id \"9d942222-8f66-4857-b2e5-42cae2c1f5cd\"
               :game.stock.tick/close 105.64}}}
     {:tick {:game.stock.tick/id #uuid \"56ba930d-a6ca-4207-ac32-4ab52dd97f8e\"
             :game.stock.tick/trade-time 1593185675642
             :db/id \"82b99d8b-0f3b-408f-a7ee-81c67ee15eae\"
             :game.stock.tick/close 102.1}
      :stock {:db/id 17592186045442
              :game.stock/id #uuid \"d09bda1b-7351-4557-81e8-f7af6f0f7534\"
              :game.stock/name \"Musical Area\"
              :game.stock/symbol \"MUSI\"
              :game.stock/price-history
              {:game.stock.tick/id #uuid \"56ba930d-a6ca-4207-ac32-4ab52dd97f8e\"
               :game.stock.tick/trade-time 1593185675642
               :db/id \"82b99d8b-0f3b-408f-a7ee-81c67ee15eae\"
               :game.stock.tick/close 102.1}}}))"
  [stocks]
  (->> stocks
       (map #(->> %
                  :data-sequence
                  (map (fn [[m v t]]

                         (let [moment  (str m)
                               value   v
                               tick-id (str t)
                               tick    (persistence.core/bind-temporary-id
                                         (hash-map
                                           :game.stock.tick/trade-time m
                                           :game.stock.tick/close value
                                           :game.stock.tick/id t))

                               stock-with-appended-price-history (-> %
                                                                     (dissoc :data-sequence)
                                                                     (assoc :game.stock/price-history tick))]

                           {:tick tick :stock stock-with-appended-price-history})))))
       (apply interleave)
       (partition (count stocks))))

(defn- partitioned-entities->transaction-entities
  "Should be called from `map`.
   A count of 2 stocks should yield output that looks like below.

   (({:game.stock.tick/id #uuid \"2606edd1-b906-496a-a23a-b9e66e9128b8\"
      :game.stock.tick/trade-time 1593185674611
      :db/id \"89564f97-3ffb-4ce9-8ce2-7107b400fca7\"
      :game.stock.tick/close 105.83}
     {:db/id 17592186045441
      :game.stock/id #uuid \"a6053ea6-591a-4ec2-8312-5131d9fe2b68\"
      :game.stock/name \"Honest Toe\"
      :game.stock/symbol \"HONE\"
      :game.stock/price-history
      {:game.stock.tick/id #uuid \"2606edd1-b906-496a-a23a-b9e66e9128b8\"
       :game.stock.tick/trade-time 1593185674611
       :db/id \"89564f97-3ffb-4ce9-8ce2-7107b400fca7\"
       :game.stock.tick/close 105.83}}
     {:game.stock.tick/id #uuid \"61a97ae5-01f4-43d0-9c3f-0fb0f8fbd392\"
      :game.stock.tick/trade-time 1593185674642
      :db/id \"f94bcfe1-75af-45f7-9371-1819f4180bd2\"
      :game.stock.tick/close 102.29}
     {:db/id 17592186045442
      :game.stock/id #uuid \"d09bda1b-7351-4557-81e8-f7af6f0f7534\"
      :game.stock/name \"Musical Area\"
      :game.stock/symbol \"MUSI\"
      :game.stock/price-history
      {:game.stock.tick/id #uuid \"61a97ae5-01f4-43d0-9c3f-0fb0f8fbd392\"
       :game.stock.tick/trade-time 1593185674642
       :db/id \"f94bcfe1-75af-45f7-9371-1819f4180bd2\"
       :game.stock.tick/close 102.29}})

    ({:game.stock.tick/id #uuid \"d6918e67-84e8-4b97-aa99-c9c6f5e5ba95\"
      :game.stock.tick/trade-time 1593185675611
      :db/id \"90eaecce-04fe-4392-9e32-c182500b84bb\"
      :game.stock.tick/close 105.64}
     {:db/id 17592186045441
      :game.stock/id #uuid \"a6053ea6-591a-4ec2-8312-5131d9fe2b68\"
      :game.stock/name \"Honest Toe\"
      :game.stock/symbol \"HONE\"
      :game.stock/price-history
      {:game.stock.tick/id #uuid \"d6918e67-84e8-4b97-aa99-c9c6f5e5ba95\"
       :game.stock.tick/trade-time 1593185675611
       :db/id \"90eaecce-04fe-4392-9e32-c182500b84bb\"
       :game.stock.tick/close 105.64}}
     {:game.stock.tick/id #uuid \"56ba930d-a6ca-4207-ac32-4ab52dd97f8e\"
      :game.stock.tick/trade-time 1593185675642
      :db/id \"1a46b39d-46a1-4af8-b48e-f5d98f2b8bd8\"
      :game.stock.tick/close 102.1}
     {:db/id 17592186045442
      :game.stock/id #uuid \"d09bda1b-7351-4557-81e8-f7af6f0f7534\"
      :game.stock/name \"Musical Area\"
      :game.stock/symbol \"MUSI\"
      :game.stock/price-history
      {:game.stock.tick/id #uuid \"56ba930d-a6ca-4207-ac32-4ab52dd97f8e\"
       :game.stock.tick/trade-time 1593185675642
       :db/id \"1a46b39d-46a1-4af8-b48e-f5d98f2b8bd8\"
       :game.stock.tick/close 102.1}}))"
  [partitioned-entities]
  (->> partitioned-entities
       (map (juxt :tick :stock))
       (apply concat)))

(defn stocks->stock-sequences [stocks-with-tick-data]
  (->> (stocks->partitioned-entities stocks-with-tick-data)
       (map partitioned-entities->transaction-entities)))

(defn stocks->stocks-with-tick-data [stocks data-sequence-fn data-generators]

  (map (fn [{seed :game.stock/data-seed :as stock}]
         (bind-data-sequence (partial data-sequence-fn data-generators seed)
                             stock))
       stocks))

(defn format-remaining-time [{:keys [remaining-in-minutes remaining-in-seconds]}]
  (format "%s:%s" remaining-in-minutes remaining-in-seconds))

(defn time-expired? [{:keys [remaining-in-minutes remaining-in-seconds]}]
  (and (= 0 remaining-in-minutes) (< remaining-in-seconds 1)))


(defn set-game-status! [conn game-id game-status-dest]

  (let [{game-db-id :db/id
         {game-status :db/ident} :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))

        data [[:db/retract  game-db-id :game/status game-status]
              [:db/add      game-db-id :game/status game-status-dest]]]

    (persistence.datomic/transact-entities! conn data)))

(defn conditionally-update-game-timer! [conn game-id level-timer]

  (let [{game-db-id :db/id
         game-level-timer :game/level-timer} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))]

    (if-not (= game-level-timer level-timer)

      (persistence.datomic/transact-entities! conn
                                              (cond-> []
                                                game-level-timer (conj [:db/retract game-db-id :game/level-timer game-level-timer])
                                                true             (conj [:db/add game-db-id :game/level-timer level-timer]))))))

(defn pause-game! [conn game-id]

  ;; A
  (set-game-status! conn game-id :game-status/paused)

  ;; B
  (let [level-timer (-> repl.state/system
                        :game/games deref (get game-id)
                        :level-timer deref)]

    (conditionally-update-game-timer! conn game-id level-timer)))

(defn exit-game! [conn game-id]

  (let [{game-db-id              :db/id
         {game-status :db/ident} :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))
        data [[:db/retract  game-db-id :game/status game-status]
              [:db/add      game-db-id :game/status :game-status/exited]
              [:db/add      game-db-id :game/end-time (c/to-date (t/now))]]

        level-timer (-> repl.state/system
                        :game/games deref (get game-id)
                        :level-timer deref)]

    ;; A
    (conditionally-update-game-timer! conn game-id level-timer)

    ;; B
    (persistence.datomic/transact-entities! conn data))

  ;; NOTE !! DANGER !! doing this on a market game, will disconnect all clients
  ;; Unsubscribe clients, close streams
  (let [{:keys [stock-tick-stream
                portfolio-update-stream
                game-event-stream]}
        (select-keys repl.state/system [:stock-tick-stream :portfolio-update-stream :game-event-stream])]

    (run! #(when % (core.async/close! %))
          [stock-tick-stream portfolio-update-stream game-event-stream])))

(defn conditionally-win-game! [conn game-id]

  (let [{game-db-id :db/id
         {game-level :db/ident} :game/level
         {game-status :db/ident} :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))
        data [[:db/retract  game-db-id :game/status game-status]
              [:db/add      game-db-id :game/status :game-status/won]]]

    (when (= :game-level/ten game-level)
      (persistence.datomic/transact-entities! conn data))))

(defn lose-game! [conn game-id]

  (let [{game-db-id :db/id
         {game-status :db/ident} :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))
        data [[:db/retract  game-db-id :game/status game-status]
              [:db/add      game-db-id :game/status :game-status/lost]]]

    (persistence.datomic/transact-entities! conn data)))

(defn conditionally-level-up! [conn game-id [[source-level-name _ :as source]
                                             [dest-level-name
                                              {tick-sleep :tick-sleep-ms} :as dest]]]

  (when dest

    (games.state/update-inmemory-tick-sleep-atom! game-id tick-sleep)

    (let [{game-db-id :db/id} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))
          data [[:db/retract  game-db-id :game/level source-level-name]
                [:db/add      game-db-id :game/level dest-level-name]]

          ;; _ (println "Site A: Transacting new level")
          {db-after :db-after} (persistence.datomic/transact-entities! conn data)]

      (if-not db-after
        (throw (Exception. (format "Couldn't level up from to [%s %s]" source dest)))))))

(defn conditionally-reset-level-time! [conn game-id [[source-level-name _               :as source]
                                                     [dest-level-name dest-level-config :as dest]]]

  (when dest
    (swap! (:game/games repl.state/system)
           (fn [gs]
             (update-in gs [game-id :level-timer] (-> repl.state/config
                                                      :game/game
                                                      :level-timer-sec
                                                      constantly))))))

(defn transition-level! [conn game-id level]

  (let [source-and-destination (game.games.state/level->source-and-destination level)]

    (conditionally-level-up! conn game-id source-and-destination)
    (conditionally-reset-level-time! conn game-id source-and-destination)))

(defn flush-channel! [ch timeout threshold]

  (core.async/go-loop [c 0]

    (when (<= c threshold)
      (let [[v _] (core.async/alts! [ch (core.async/timeout timeout)])]
        (when (not (nil? v))
          (recur (inc c)))))))


(defmulti handle-control-event (fn [_ _ {m :event} _ _] m))

(defmethod handle-control-event :pause [conn game-event-stream
                                        {game-id :game-id :as control}
                                        now end]

  (pause-game! conn game-id)

  (let [remaining (games.state/calculate-remaining-time now end)]
    (log/info :game.games (format "< Paused > %s" (format-remaining-time remaining)))
    (core.async/go
      (core.async/>! game-event-stream (assoc control :type :ControlEvent))))

  [])

(defmethod handle-control-event :exit [conn game-event-stream {:keys [game-id message] :as control} now end]

  (let [remaining (games.state/calculate-remaining-time now end)]

    (exit-game! conn game-id)

    (log/info :game.games (format "%sExiting / Time Remaining / %s"
                                  (if message message "")
                                  (format-remaining-time remaining)))
    (core.async/go
      (core.async/>! game-event-stream (assoc control :type :ControlEvent)))

    (flush-channel! game-event-stream 1000 5)

    []))

(defmethod handle-control-event :win [conn game-event-stream {:keys [game-id level] :as control} now end]

  (let [level-timer (-> repl.state/config :game/game :level-timer-sec)
        now (t/now)
        end (t/plus now (t/seconds level-timer))
        remaining (games.state/calculate-remaining-time now end)]

    (transition-level! conn game-id level)
    (conditionally-win-game! conn game-id)

    (log/info :game.games (format "Win %s" (format-remaining-time remaining)))
    (core.async/go
      (core.async/>! game-event-stream (assoc control :type :LevelStatus)))

    [now end]))

(defmethod handle-control-event :lose [conn game-event-stream {game-id :game-id :as control} now end]

  (let [remaining (games.state/calculate-remaining-time now end)]

    (lose-game! conn game-id)

    (log/info :game.games (format "Lose %s" (format-remaining-time remaining)))
    (core.async/go (core.async/>! game-event-stream (assoc control :type :LevelStatus)))

    ;; (flush-channel! game-event-stream 1000 5)
    []))

(defmethod handle-control-event :timeout [conn game-event-stream {game-id :game-id :as control} now end]

  (let [remaining (games.state/calculate-remaining-time now end)
        current-game (-> repl.state/system :game/games deref
                         (get game-id))
        current-level (-> current-game :current-level deref :level)
        timeout-timer-event {:event   :continue
                             :game-id game-id
                             :level   current-level
                             :minutesRemaining 0
                             :secondsRemaining 0
                             :type :LevelTimer}

        user-id (-> current-game :user :db/id)
        profit-loss (reduce (fn [ac {profit-loss :profit-loss}]
                              (+ ac profit-loss))
                            0
                            (game.calculation/realized-profit-loss-for-game conn user-id game-id))

        lose-event (games.processing/->level-status :lose game-id profit-loss current-level)]

    (log/info :game.games (format "Running %s / TIME'S UP!!" (format-remaining-time remaining)))

    (core.async/go (core.async/>! game-event-stream timeout-timer-event))
    (handle-control-event conn game-event-stream lose-event now end)))

(defn update-short-circuit-game! [game-id short-circuit-game?]

  (swap! (:game/games repl.state/system)
         (fn [gs]
           (update-in gs [game-id :short-circuit-game?] (constantly short-circuit-game?)))))

(defmethod handle-control-event :continue [_ game-event-stream {game-id :game-id :as control} now end]

  (let [remaining-time (games.state/calculate-remaining-time now end)]

    (log/info :game.games (format "Continue Game %s Remaining %s:%s"
                                  game-id
                                  (:remaining-in-minutes remaining-time)
                                  (:remaining-in-seconds remaining-time)))

    ;; A
    (game.games.state/update-inmemory-game-timer! game-id (-> remaining-time :interval t/in-seconds))

    ;; B
    (core.async/go
      (core.async/>! game-event-stream
                     (-> remaining-time
                         (select-keys [:remaining-in-minutes :remaining-in-seconds])
                         (merge control)))))

  [(t/now) end])

(defmethod handle-control-event :additional_5_minutes [conn game-event-stream {:keys [game-id] :as control} now end]

  (let [additional-time 5
        end' (t/plus end (t/minutes additional-time))
        remaining-time (games.state/calculate-remaining-time now end')]

    (game.games.state/update-inmemory-game-timer! game-id (-> remaining-time :interval t/in-seconds))
    (log/info :game.games (format "Additional 5 minutes %s" (format-remaining-time remaining-time)))

    (handle-control-event conn game-event-stream (assoc control :event :continue) now end')))

(defn step-iteration [iterations]
  (ffirst iterations))

(defn step-control [conn
                    {{game-id :game/id} :game
                     level-timer        :level-timer
                     control-channel    :control-channel
                     game-event-stream  :game-event-stream}
                    now end]

  (let [remaining       (games.state/calculate-remaining-time now end)
        tick-sleep-atom (:tick-sleep-atom (game.games.state/inmemory-game-by-id game-id))

        [{event :event
          :as   controlv} ch] (core.async/alts!! [(core.async/timeout @tick-sleep-atom) control-channel])]


    ;; TODO i. If this is a market, and ii. there are no players, :pause
    (log/info :game.games (format "game-loop %s:%s / %s"
                                  (:remaining-in-minutes remaining)
                                  (:remaining-in-seconds remaining)
                                  (if controlv controlv :running)))

    (let [expired? (time-expired? remaining)]

      (match [event expired?]

             [:pause _] (handle-control-event conn game-event-stream
                                              (assoc controlv :message "< Paused >")
                                              (t/now) end)

             [(_ :guard #{:exit :win :lose :additional_5_minutes}) _]
             (handle-control-event conn game-event-stream controlv now end)

             [_ false] (let [current-level (-> repl.state/system :game/games deref
                                               (get game-id)
                                               :current-level deref)
                             controlv      {:event   :continue
                                            :game-id game-id
                                            :level   (:level current-level)
                                            :type    :LevelTimer}]

                         (handle-control-event conn game-event-stream controlv now end))

             [_ true] (handle-control-event conn game-event-stream (assoc controlv
                                                                          :event :timeout
                                                                          :game-id game-id) now end)))))

(defn step-game [conn game-control now end iters]

  (step-iteration iters)
  (step-control conn game-control now end))

(defn run-game! [conn
                 {{game-id :game/id} :game
                  level-timer :level-timer
                  iterations :iterations
                  control-channel :control-channel
                  game-event-stream :game-event-stream :as game-control}]

  ;; A
  (let [{game-db-id :db/id
         game-status :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))
        data [[:db/retract  game-db-id :game/status (:db/ident game-status)]
              [:db/add      game-db-id :game/status :game-status/running]]]

    (persistence.datomic/transact-entities! conn data))

  ;; B
  (core.async/go-loop [now (t/now)
                       end (t/plus now (t/seconds @level-timer))
                       iters iterations]

    (let [[now end]           (step-game conn game-control now end iters)
          short-circuit-game? (-> repl.state/system :game/games deref (get game-id)
                                  :short-circuit-game? deref)]

      (when (and now end (not short-circuit-game?))
        (recur now end (next iters))))))

(defn extract-tick-and-trade [conn {price-history :game.stock/price-history :as stock}]

  (map #(let [trade (if (:bookkeeping.debit/_tick %)
                      (let [tentry (->> % :bookkeeping.debit/_tick :bookkeeping.tentry/_debits :db/id
                                        (persistence.core/pull-entity conn))]
                        (if (-> tentry :bookkeeping.tentry/debits first
                                :bookkeeping.debit/account :bookkeeping.account/name (= "Cash"))
                          (assoc tentry :op :buy)
                          (assoc tentry :op :sell)))
                      :noop)]
          [(dissoc % :bookkeeping.debit/_tick) trade])
       price-history))

(defn get-inmemory-profit-loss [game-id]
  (-> repl.state/system :game/games deref (get game-id) :profit-loss))

(defn update-level!-then->game-control-replay
  [conn game {{user-db-id :db/id} :user
              control-channel :control-channel
              current-level :current-level
              stock-tick-stream :stock-tick-stream
              portfolio-update-stream :portfolio-update-stream
              game-event-stream :game-event-stream
              sink-fn :sink-fn
              :as game-control} data-sequence-fn]

  (let [{game-db-id             :db/id
         game-id                :game/id
         game-status            :game/status
         {game-level :db/ident} :game/level
         game-timer             :game/level-timer
         game-start-position    :game/start-position
         game-stocks            :game/stocks} game

        data-generators       (-> integrant.repl.state/config :game/game :data-generators)
        stocks-with-tick-data (stocks->stocks-with-tick-data game-stocks data-sequence-fn data-generators)
        input-sequence-local  (stocks->stock-sequences stocks-with-tick-data)]


    ;; X. Update :game/level
    (game.games.state/update-inmemory-game-level! game-id game-level)

    (merge-with #(if %2 %2 %1)
                game-control
                (game.games.core/default-game-control conn game-id
                                                      (assoc game-control :current-level game-level))
                {:game game
                 :profit-loss {}

                 :level-timer           (atom game-timer)
                 :stocks-with-tick-data stocks-with-tick-data
                 :input-sequence        input-sequence-local
                 :tick-sleep-atom       (atom (-> integrant.repl.state/config :game/game :tick-sleep-ms))
                 :short-circuit-game?   (atom false)
                 :current-level         current-level

                 ;; :level-timer-sec          5
                 ;; :accounts                 (game.core/->game-user-accounts)
                 :process-transact!        identity
                 :process-transact-profit-loss! identity
                 :process-transact-level-update! identity
                 :stream-stock-tick        identity
                 #_(fn [stock-tick-pairs]
                     (games.processing/group-stock-tick-pairs stock-tick-pairs))
                 :calculate-profit-loss
                 (fn [{op :op :as a}]
                   (games.processing/calculate-profit-loss (or op :tick) user-db-id game-id a))

                 :stream-portfolio-update! identity
                 :stream-level-update!     identity

                 :close-sink-fn (partial sink-fn nil)
                 :sink-fn       #(sink-fn {:event %})})))

(defn ->game-with-decorated-price-history [conn game-id]

  (d/q '[:find (pull ?e [:db/id
                         :game/id
                         :game/start-position
                         :game/level-timer

                         {:game/status [*]}
                         {:game/level [*]}
                         {:game/users [*]}

                         {:game/stocks [:db/id
                                        :game.stock/id
                                        :game.stock/name
                                        :game.stock/data-seed
                                        :game.stock/symbol
                                        {:game.stock/price-history [:db/id
                                                                    :game.stock.tick/id
                                                                    {:bookkeeping.debit/_tick
                                                                     [:bookkeeping.tentry/_debits]}
                                                                    :game.stock.tick/trade-time
                                                                    :game.stock.tick/close]}]}])
         :in $ ?game-id
         :where
         [?e :game/id ?game-id]]
       (d/db conn)
       game-id))

(defn seek-to-position [start xs]

  (let [seekfn (juxt (partial take start) (partial drop start))]
    (seekfn xs)))

(defn run-iteration [control-chain]

  (let [first+rest (juxt first rest)
        f          (fn [[x xs]] (first+rest xs))]
    (iterate f (first+rest control-chain))))


(defn resume-common!

  ([conn game-id user-db-id game-control]

   (resume-common! conn game-id user-db-id game-control ->data-sequence))

  ([conn game-id user-db-id {:keys [control-channel
                                    stock-tick-stream
                                    portfolio-update-stream
                                    sink-fn] :as game-control} data-sequence-fn]

   ;; TODO
   ;; >> return historical data <<

   (let [{game-db-id          :db/id
          {game-status :db/ident} :game/status
          game-level          :game/level
          game-timer          :game/level-timer
          game-start-position :game/start-position
          game-stocks         :game/stocks :as game-entity}
         (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))

         ;; Game Control
         user-entity {:db/id user-db-id}
         game-control-replay (update-level!-then->game-control-replay conn game-entity
                                                                      (assoc game-control :user user-entity)
                                                                      data-sequence-fn)

         ;; Re-play game

         ;; TODO get game start time
         ;; _ (ppi [:start-time (game.games/game-start-time conn game-id)])
         ;; _ (ppi [:as-of
         ;;         (d/as-of
         ;;           (d/db conn)
         ;;           (game.games/game-start-time conn game-id))])

         #_[db-as-of (d/as-of
                       (d/db conn)
                       (game.games/game-start-time conn game-id))]

         game-with-decorated-price-history (->game-with-decorated-price-history conn game-id)
         tick-index                        (-> game-with-decorated-price-history ffirst
                                               :game/stocks first
                                               :game.stock/price-history count)
         ticks-and-trade-all               (->> game-with-decorated-price-history ffirst :game/stocks
                                                (map (partial extract-tick-and-trade conn)))]


     ;; (ppi [:game-with-decorated-price-history game-with-decorated-price-history])

     ;; A. Apply payments
     (integration.payments.core/apply-unapplied-payments-for-user conn user-entity game-entity)


     #_(when (:calculate-profit-loss game-control)
       (game.persistence/update-profit-loss-state! game-id {}))


     ;; (ppi [:ticks-and-trade-all ticks-and-trade-all])
     ;; (ppi [:A.i :inmemory-profit-loss :BEFORE (:profit-loss (game.games.state/inmemory-game-by-id game-id))])


     ;; TODO - Isn't actually rerunning
     ;; B. Replay ticks & trades
     #_(run! (fn [ticks-and-trade]

             ;; (ppi [:WTF (map second ticks-and-trade)])
             ;; (doall (games.pipeline/replay-stock-trades-pipeline game-control-replay (map second ticks-and-trade)))
             ;; (ppi [:replay :input-sequence (:input-sequence game-control-replay)])
             ;; (ppi [:replay :maybe-tentries (map second ticks-and-trade)])

             (doall
               (map #(list %1 %2)
                    (games.pipeline/stock-tick-pipeline game-control-replay)
                    (games.pipeline/replay-stock-trades-pipeline game-control-replay (map second ticks-and-trade)))))
           ticks-and-trade-all)

     ;; (ppi [:A.ii :inmemory-profit-loss :AFTER (:profit-loss  (game.games.state/inmemory-game-by-id game-id))])


     ;; Re-start game
     (let [data-generators (-> integrant.repl.state/config :game/game :data-generators)

           ;; game-control initial
           {:keys [tick-sleep-atom level-timer] :as game-control-live}
           (as-> game-control-replay v
             (game.games.core/default-game-control conn game-id v)
             (merge-with #(if %2 %2 %1)
                         game-control-replay
                         v
                         {:level-timer (atom (or (-> (game.games.state/inmemory-game-by-id game-id) :level-timer deref)
                                                 game-timer))})
             (update-in v [:input-sequence] (fn [_]
                                              (->> (stocks->stocks-with-tick-data game-stocks data-sequence-fn data-generators)
                                                   stocks->stock-sequences
                                                   (seek-to-position tick-index)
                                                   second))))

           ;; _ (ppi [:B :get-inmemory-profit-loss (get-inmemory-profit-loss game-id)])

           inputs-at-position (games.pipeline/stock-tick-pipeline game-control-live)
           game-control-live (->> (run-iteration inputs-at-position)
                                  (assoc game-control-live :iterations)
                                  (#(assoc % :profit-loss (get-inmemory-profit-loss game-id))))]

       (games.state/register-game-control! game-entity game-control-live)
       game-control-live))))

(defn resume-game!

  ([conn user-db-id game-control]

   (resume-game! conn user-db-id game-control ->data-sequence))

  ([conn user-db-id {control-channel         :control-channel
                     stock-tick-stream       :stock-tick-stream
                     portfolio-update-stream :portfolio-update-stream
                     sink-fn                 :sink-fn
                     {game-id :game/id}      :game :as game-control} data-sequence-fn]

   ;; :game/start-position
   ;; :game/status #:db{:id 17592186045430 :ident :game-status/paused}
   ;; :game/level-timer "[]"
   ;; :game/level #:db{:id 17592186045417 :ident :game-level/one}

   ;; :game.user/profit-loss
   ;; :game.stock/data-seed

   ;; :bookkeeping.debit/tick '(:of :bookkeeping.credit/tick)
   ;; :game.stock.tick/id

   ;; TODO
   ;; Restore level-timer
   ;; :game/level-timer
   ;; @ level-timer

   ;; TODO
   ;; >> return historical data <<


   (let [{:keys [tick-sleep-atom] :as game-control-live}
         (resume-common! conn game-id user-db-id game-control data-sequence-fn)]

     (run-game! conn game-control-live))))

(defn resume-workbench!

  ([conn game-id user-db-id game-control]

   (resume-workbench! conn game-id user-db-id game-control ->data-sequence))

  ([conn game-id user-db-id {:keys [control-channel
                                    stock-tick-stream
                                    portfolio-update-stream
                                    sink-fn] :as game-control} data-sequence-fn]

   ;; :game/start-position
   ;; :game/status #:db{:id 17592186045430 :ident :game-status/paused}
   ;; :game/level-timer "[]"
   ;; :game/level #:db{:id 17592186045417 :ident :game-level/one}

   ;; :game.user/profit-loss
   ;; :game.stock/data-seed

   ;; :bookkeeping.debit/tick '(:of :bookkeeping.credit/tick)
   ;; :game.stock.tick/id

   ;; TODO
   ;; Restore level-timer

   ;; TODO
   ;; >> return historical data <<

   (resume-common! conn game-id user-db-id game-control data-sequence-fn)

   ;; NOTE Kludged duplicate of setting status in run-game!
   (let [{game-db-id :db/id
          game-status :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))
         data [[:db/retract  game-db-id :game/status (:db/ident game-status)]
               [:db/add      game-db-id :game/status :game-status/running]]]

     (persistence.datomic/transact-entities! conn data))))


(defn disconnect-from-game! [])

(defn connect-to-game! [])

(defn check-user-does-not-have-running-game [conn user-db-id game-id]

  (when-let [user-games (flatten (iam.persistence/game-user-by-user
                                   conn user-db-id game-id '[{:game/_users
                                                              [:db/id
                                                               :game/id
                                                               :game/status
                                                               :game/users]}]))]

    (when (->> (map (comp :db/ident :game/status :game/_users :game.user/_user) user-games)
               (into #{})
               (some #{:game-status/running}))

      (throw (Exception. "User has a running game / :game/id %s" "asdf")))

    user-games))

(defn user-joined-game? [user-games game-id user-db-id]

  (util/exists? (for [user-game user-games
                      game-user (-> user-game :game.user/_user :game/_users :game/users)
                      :let [gid (-> user-game :game.user/_user :game/_users :game/id)
                            uid (-> game-user :game.user/user :db/id)]
                      :when (and (= gid game-id)
                                 (= uid user-db-id))]
                  game-user)))


(defn join-game! [conn user-db-id game-id
                  {{game-stocks :game/stocks :as game} :game :as game-control}
                  data-sequence-fn]

  ;; (ppi "join-game! / A check-user-does-not-have-running-game /")
  (let [user-games (check-user-does-not-have-running-game conn user-db-id game-id)]

    ;; (ppi "join-game! / B user-joined-game? /")
    (when-not (user-joined-game? user-games game-id user-db-id)

      ;; Join
      (->> (game.core/conditionally-add-game-users game
                                                   {:user     {:db/id user-db-id}
                                                    :accounts (game.core/->game-user-accounts)})
           (persistence.datomic/transact-entities! conn)))

    (set-game-status! conn game-id :game-status/running)


    ;; TODO
    ;; Stream
    ;; No explicit connect-to-game (if already joined)
    ;; ... just start and stop a GQL subscription

    ;; stream-stock-ticks
    ;; stream-portfolio-updates
    ;; stream-game-events

    ;; ! Can stream (connect-to-game) only if they've joined game
    ;; ! disconnect-from-game just stops subscription; noop if already disconnected

    (let [data-generators (-> integrant.repl.state/config :game/game :data-generators)
          tick-index      (-> (->game-with-decorated-price-history conn game-id) ffirst
                              :game/stocks first
                              :game.stock/price-history count)

          ;; game-control market
          {input-sequence :input-sequence :as game-control-market}
          (as-> (game.games.core/default-game-control conn game-id (assoc game-control :user {:db/id user-db-id})) v
            (merge-with #(if %2 %2 %1) game-control v)
            (update-in v
                       [:input-sequence]
                       (fn [_]
                         (->> (stocks->stocks-with-tick-data game-stocks data-sequence-fn data-generators)
                              stocks->stock-sequences
                              (seek-to-position tick-index)
                              second))))

          inputs-at-position (games.pipeline/join-market-pipeline conn user-db-id game-id game-control-market)]

      (->> (run-iteration inputs-at-position)
           (assoc game-control-market :iterations)))))
