(ns beatthemarket.game.games
  (:require [clojure.core.async :as core.async
             :refer [go go-loop chan close! timeout alts! >! <! >!!]]
            [clojure.core.async.impl.protocols]
            [clojure.core.match :refer [match]]
            [clj-time.core :as t]
            [clojure.data.json :as json]
            [datomic.client.api :as d]
            [com.rpl.specter :refer [transform ALL MAP-VALS]]
            [rop.core :as rop]
            [integrant.core :as ig]
            [integrant.repl.state :as repl.state]
            [io.pedestal.log :as log]

            [beatthemarket.bookkeeping.persistence :as bookkeeping.persistence]
            [beatthemarket.datasource :as datasource]
            [beatthemarket.datasource.core :as datasource.core]
            [beatthemarket.datasource.name-generator :as name-generator]
            [beatthemarket.game.core :as game.core]
            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.game.persistence :as game.persistence]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.util :as util]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.bookkeeping.core :as bookkeeping])
  (:import [java.util UUID]))


(defmethod ig/init-key :game/games [_ _]
  (atom {}))

(defmethod ig/halt-key! :game/games [_ games]
  (run! (fn [{:keys [stock-tick-stream
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

;; BUY | SELL
(defn- game-iscurrent-and-belongsto-user? [{:keys [conn gameId userId] :as inputs}]
  (if (util/exists?
        (d/q '[:find (pull ?e [*])
               :in $ ?game-id ?user-id
               :where
               [?e :game/id ?game-id]
               [?e :game/start-time]
               [(missing? $ ?e :game/end-time)]
               [?e :game/users ?us]
               [?us :game.user/user ?u]
               [?u :user/external-uid ?user-id]]
             (d/db conn)
             gameId userId))
    (rop/succeed inputs)
    (rop/fail (ex-info "Game isn't current or doesn't belong to user" inputs))))

(defn- submitted-price-matches-tick? [{:keys [conn tickId tickPrice] :as inputs}]
  (let [{tick-price :game.stock.tick/close :as tick}
        (ffirst
          (d/q '[:find (pull ?e [*])
                 :in $ ?tick-id
                 :where
                 [?e :game.stock.tick/id ?tick-id]]
               (d/db conn)
               tickId))]

    (if (= tickPrice tick-price)
      (rop/succeed inputs)
      (let [message (format "Submitted price [%s] does not match price from tickId" tickPrice)]
        (rop/fail (ex-info message tick))))))

(defn stock->tick-history [conn stockId]
  (->> stockId
       (d/q '[:find (pull ?e [*])
              :in $ ?stock-id
              :where
              [?e :game.stock/id ?stock-id]]
            (d/db conn))
       ffirst
       :game.stock/price-history
       (sort-by :game.stock.tick/trade-time >)))

(defn- latest-tick? [{:keys [conn tickId stockId] :as inputs}]

  (let [latest-tick-threshold (get (:game/game integrant.repl.state/config) :latest-tick-threshold 2)
        tick-history-sorted (stock->tick-history conn stockId)
        latest-tick-comparator (->> (take latest-tick-threshold tick-history-sorted)
                                    (map :game.stock.tick/id)
                                    set)]

    (if (some latest-tick-comparator [tickId])
      (rop/succeed inputs)
      (let [message (format "Submitted tick [%s] is not the latest" tickId)]
        (rop/fail (ex-info message {:tick-history-sorted
                                    (take 5 tick-history-sorted)}))))))

(defn buy-stock!

  ([conn userId gameId stockId stockAmount tickId tickPrice]
   (buy-stock! conn userId gameId stockId stockAmount tickId tickPrice true))

  ([conn userId gameId stockId stockAmount tickId tickPrice validate?]
   (let [validation-inputs {:conn        conn
                            :userId      userId
                            :gameId      gameId
                            :stockId     stockId
                            :stockAmount stockAmount
                            :tickId      tickId
                            :tickPrice   tickPrice}]

     (match [validate? (rop/>>= validation-inputs
                                game-iscurrent-and-belongsto-user?
                                submitted-price-matches-tick?
                                latest-tick?)]

            [true (result :guard #(= clojure.lang.ExceptionInfo (type %)))] (throw result)
            [_ _] (let [extract-id  (comp :db/id ffirst)
                        user-db-id  (extract-id (iam.persistence/user-by-external-uid conn userId))
                        game-db-id  (extract-id (persistence.core/entity-by-domain-id conn :game/id gameId))
                        stock-db-id (extract-id (persistence.core/entity-by-domain-id conn :game.stock/id stockId))]

                    (bookkeeping/buy-stock! conn game-db-id user-db-id stock-db-id stockAmount tickPrice))))))

(defn sell-stock!

  ([conn userId gameId stockId stockAmount tickId tickPrice]
   (sell-stock! conn userId gameId stockId stockAmount tickId tickPrice true))

  ([conn userId gameId stockId stockAmount tickId tickPrice validate?]

   (let [validation-inputs {:conn conn
                            :userId userId
                            :gameId gameId
                            :stockId stockId
                            :stockAmount stockAmount
                            :tickId tickId
                            :tickPrice tickPrice}]

     (match [validate? (rop/>>= validation-inputs
                                game-iscurrent-and-belongsto-user?
                                submitted-price-matches-tick?
                                latest-tick?)]

            [true (result :guard #(= clojure.lang.ExceptionInfo (type %)))] (throw result)
            [_ _] (let [extract-id  (comp :db/id ffirst)
                        user-db-id  (extract-id (iam.persistence/user-by-external-uid conn userId))
                        game-db-id  (extract-id (persistence.core/entity-by-domain-id conn :game/id gameId))
                        stock-db-id (extract-id (persistence.core/entity-by-domain-id conn :game.stock/id stockId))]

                    (bookkeeping/sell-stock! conn game-db-id user-db-id stock-db-id stockAmount tickPrice))))))

;; CREATE
(defn register-game-control! [game game-control]
  (swap! (:game/games repl.state/system)
         assoc (:game/id game) game-control))

(defn ->data-sequence

  ([]
   (-> integrant.repl.state/config
       :game/game
       :data-generators
       ->data-sequence))

  ([data-sequence]
   (apply (partial datasource/->combined-data-sequence datasource.core/beta-configurations) data-sequence)))

(defn- bind-data-sequence

  ([a]
   (bind-data-sequence (->data-sequence) a))

  ([data-sequence a]
   (->> data-sequence
        (datasource/combined-data-sequence-with-datetime (t/now))
        (map #(conj % (UUID/randomUUID)))
        (assoc a :data-sequence))))

(defn group-stock-tick-pairs [stock-tick-pairs]
  (->> (partition 2 stock-tick-pairs)
       (map (fn [[tick stock]]
              (merge (select-keys tick [:game.stock.tick/id :game.stock.tick/trade-time :game.stock.tick/close])
                     (select-keys stock [:game.stock/id :game.stock/name]))))))

(defn- latest-chunk-closed? [latest-chunk]
  (-> latest-chunk last :stock-account-amount (= 0)))

(defn recalculate-profit-loss-on-tick-perstock [price profit-loss-perstock]

  (let [[butlast-chunks latest-chunk] (->> (game.persistence/profit-loss->chunks profit-loss-perstock)
                                           ((juxt butlast last)))]

    (if (latest-chunk-closed? latest-chunk)

      profit-loss-perstock

      (->> latest-chunk
           (map (partial game.persistence/recalculate-profit-loss-on-tick price))
           (concat butlast-chunks)
           flatten))))

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

(defn stocks->stock-sequences [conn game user-id stocks-with-tick-data]
  (map partitioned-entities->transaction-entities
       (stocks->partitioned-entities stocks-with-tick-data)))


(defn stock-tick-by-id [id stock-ticks]
  (first (filter #(= id (:game.stock/id %))
                 stock-ticks)))

(defn recalculate-profitloss-perstock-fn [stock-ticks profit-loss]
  (reduce-kv (fn [m k v]
               (if-let [{price :game.stock.tick/close}
                        (stock-tick-by-id k stock-ticks)]
                 (assoc
                   m k
                   (recalculate-profit-loss-on-tick-perstock price v))
                 m))
             {}
             profit-loss))

(defn initialize-game!

  ([conn user-entity accounts sink-fn]

   (initialize-game! conn user-entity accounts sink-fn :game-level/one ->data-sequence {}))

  ([conn user-entity accounts sink-fn game-level data-sequence-fn {:keys [level-timer-sec tick-sleep-ms game-id
                                                                          input-sequence profit-loss
                                                                          stream-stock-tick-mappingfn stream-portfolio-update-mappingfn
                                                                          collect-profit-loss-mappingfn check-level-complete-mappingfn]}]

   (let [stocks               (game.core/generate-stocks! 4)
         initialize-game-opts {:game-id game-id}

         {game-id :game/id
          stocks  :game/stocks
          {saved-game-level :db/ident} :game/level
          :as     game}        (game.core/initialize-game! conn user-entity accounts game-level stocks initialize-game-opts)
         stocks-with-tick-data (map #(bind-data-sequence (data-sequence-fn) %) stocks)

         stream-buffer           10
         control-channel         (core.async/chan (core.async/sliding-buffer stream-buffer))
         stock-tick-stream       (core.async/chan (core.async/sliding-buffer stream-buffer))
         portfolio-update-stream (core.async/chan (core.async/sliding-buffer stream-buffer))
         game-event-stream       (core.async/chan (core.async/sliding-buffer stream-buffer))
         transact-mappingfn      (fn [data]
                                   (persistence.datomic/transact-entities! conn data)
                                   data)
         {:keys [profit-threshold lose-threshold]} (-> integrant.repl.state/config :game/game :levels
                                                       (get saved-game-level))

         current-level (atom {:level saved-game-level
                              :profit-threshold profit-threshold
                              :lose-threshold lose-threshold})

         game-control
         {:game                  game
          :profit-loss           (or profit-loss {})
          :stocks-with-tick-data stocks-with-tick-data
          :input-sequence   (or input-sequence
                                (stocks->stock-sequences
                                  conn game (:db/id user-entity) stocks-with-tick-data))
          :paused?          false
          :tick-sleep-atom  (atom (or tick-sleep-ms (-> integrant.repl.state/config :game/game :tick-sleep-ms)))
          :level-timer-atom (atom (or level-timer-sec (-> integrant.repl.state/config :game/game :level-timer-sec)))
          :current-level    current-level

          :control-channel         control-channel
          :stock-tick-stream       stock-tick-stream
          :portfolio-update-stream portfolio-update-stream
          :game-event-stream       game-event-stream

          :transact-tick-mappingfn         transact-mappingfn
          :stream-stock-tick-mappingfn     (or stream-stock-tick-mappingfn
                                               (fn [stock-tick-pairs]

                                                 (let [stock-ticks (group-stock-tick-pairs stock-tick-pairs)]
                                                   (log/debug :game.games (format ">> STREAM stock-tick-pairs / %s" stock-ticks))
                                                   (core.async/go (core.async/>! stock-tick-stream stock-ticks))
                                                   stock-ticks)))
          :calculate-profit-loss-mappingfn (fn [stock-ticks]

                                             (let [updated-profit-loss-calculations
                                                   (-> repl.state/system :game/games
                                                       deref
                                                       (get game-id)
                                                       :profit-loss
                                                       ((partial recalculate-profitloss-perstock-fn stock-ticks)))]

                                               (->> updated-profit-loss-calculations
                                                    (game.persistence/track-profit-loss-wholesale! game-id)
                                                    (#(get % game-id))
                                                    :profit-loss
                                                    (hash-map :stock-ticks stock-ticks :profit-loss))))
          :collect-profit-loss-mappingfn     (or collect-profit-loss-mappingfn
                                                 (fn [{:keys [profit-loss] :as result}]

                                                   (->> (game.calculation/collect-running-profit-loss game-id profit-loss)
                                                        (assoc result :profit-loss))))
          :transact-profit-loss-mappingfn    identity ;; (map transact-mappingfn)
          :stream-portfolio-update-mappingfn (or stream-portfolio-update-mappingfn
                                                 (fn [{:keys [profit-loss] :as result}]

                                                   (log/debug :game.games (format ">> STREAM portfolio-update / %s" result))
                                                   (when profit-loss
                                                     (core.async/go (core.async/>! portfolio-update-stream profit-loss)))
                                                   result))

          :check-level-complete-mappingfn (or check-level-complete-mappingfn
                                              (fn [{:keys [profit-loss]  :as result}]

                                                (log/debug :game.games (format ">> CHECK level-complete /"))
                                                (let [{profit-threshold :profit-threshold
                                                       lose-threshold :lose-threshold
                                                       level :level} (deref current-level)

                                                      running-pl (->> profit-loss
                                                                      (filter #(= :running-profit-loss (:profit-loss-type %)))
                                                                      (reduce #(+ %1 (:profit-loss %2)) 0.0))

                                                      realized-pl (->> profit-loss
                                                                       (filter #(= :realized-profit-loss (:profit-loss-type %)))
                                                                       (reduce #(+ %1 (:profit-loss %2)) 0.0))

                                                      running+realized-pl (+ running-pl realized-pl)

                                                      profit-threshold-met? (> running+realized-pl profit-threshold)
                                                      lose-threshold-met? (< running+realized-pl (* -1 lose-threshold))

                                                      game-event-message (cond-> {:game-id game-id
                                                                                  :level level
                                                                                  :profit-loss running+realized-pl}
                                                                           profit-threshold-met? (assoc :event :win)
                                                                           lose-threshold-met? (assoc :event :lose))]

                                                  (when (:event game-event-message)
                                                    ;; (util/pprint+identity game-event-message)
                                                    (core.async/go (core.async/>! control-channel game-event-message))))

                                                result))

          :close-sink-fn   (partial sink-fn nil)
          :sink-fn         #(sink-fn {:event %})}]

     (register-game-control! game game-control)
     game-control)))

(defn create-game!

  ([conn user-id sink-fn]
   (create-game! conn user-id sink-fn :game-level/one))

  ([conn user-id sink-fn game-level]
   (create-game! conn user-id sink-fn game-level ->data-sequence {:accounts (game.core/->game-user-accounts)}))

  ([conn user-id sink-fn game-level data-sequence-fn {accounts :accounts :as opts}]
   (let [user-entity (hash-map :db/id user-id)]
     (initialize-game! conn user-entity accounts sink-fn game-level data-sequence-fn opts))))

;; START
(defn game->new-game-message [game user-id]

  (let [game-stocks (:game/stocks game)]

    (as-> {:stocks game-stocks} v
      (transform [MAP-VALS ALL :game.stock/id] str v)
      (assoc v :id (str (:game/id game))))))

(defn- format-remaining-time [{:keys [remaining-in-minutes remaining-in-seconds]}]
  (format "%s:%s" remaining-in-minutes remaining-in-seconds))

(defn- time-expired? [{:keys [remaining-in-minutes remaining-in-seconds]}]
  (and (= 0 remaining-in-minutes) (< remaining-in-seconds 1)))

(defn- calculate-remaining-time [now end]
  (let [interval (t/interval now end)]
    {:interval interval
     :remaining-in-minutes (t/in-minutes interval)
     :remaining-in-seconds (rem (t/in-seconds interval) 60)}))

(defn send-control-event! [game-id event]
  (->> repl.state/system :game/games deref (#(get % game-id))
       :control-channel
       (#(core.async/go (core.async/>!! % event))))
  event)

(defn game-paused? [game-id]
  (->> repl.state/system :game/games deref (#(get % game-id)) :paused?))

(defn pause-game! [game-id]
  (swap! (:game/games repl.state/system)
         #(update-in % [game-id :paused?] (constantly true))))

(defn resume-game! [game-id]
  (swap! (:game/games repl.state/system)
         #(update-in % [game-id :paused?] (constantly false))))

(defn conditionally-level-up! [conn game-id [[source-level-name _
                                              :as source]
                                             [dest-level-name dest-level-config
                                              :as dest]]]

  (when dest

    (let [{game-db-id :db/id} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))
          data [[:db/retract  game-db-id :game/level source-level-name]
                [:db/add      game-db-id :game/level dest-level-name]]

          {db-after :db-after} (persistence.datomic/transact-entities! conn data)]

      (if db-after
        (swap! (:game/games repl.state/system)
               (fn [gs]
                 (update-in gs [game-id :current-level] (-> dest-level-config
                                                            (assoc :level dest-level-name)
                                                            (dissoc :order)
                                                            constantly))))
        (throw (Exception. (format "Couldn't level up from to [%s %s]" source dest)))))))

(defn conditionally-reset-level-time! [conn game-id [[source-level-name _
                                                      :as source]
                                                     [dest-level-name dest-level-config
                                                      :as dest]]]

  (when dest
    (swap! (:game/games repl.state/system)
           (fn [gs]
             (update-in gs [game-id :level-timer-atom] (-> repl.state/config
                                                           :game/game
                                                           :level-timer-sec
                                                           constantly))))))

(defn transition-level! [conn game-id level]

  (let [a (->> repl.state/config :game/game :levels seq
               (sort-by (comp :order second))
               (partition 2 1)
               (filter (fn [[[level-name _] r]] (= level level-name)))
               first)]

    (conditionally-level-up! conn game-id a)
    (conditionally-reset-level-time! conn game-id a)))


(defmulti handle-control-event (fn [_ _ {m :event} _ _] m))

(defmethod handle-control-event :pause [_ game-event-stream
                                        {game-id :game-id :as control}
                                        now end]

  (pause-game! game-id)

  (let [remaining (calculate-remaining-time now end)]
    (log/info :game.games (format "< Paused > %s" (format-remaining-time remaining)))
    (core.async/>!! game-event-stream control))

  [now end])

(defmethod handle-control-event :paused [_ game-event-stream
                                        {game-id :game-id :as control}
                                        now end]

  (let [remaining (calculate-remaining-time now end)]
    (log/info :game.games (format "< Paused > %s" (format-remaining-time remaining))))

  [now end])

(defmethod handle-control-event :resume [_ game-event-stream
                                         {game-id :game-id :as control}
                                         now end]
  (resume-game! game-id)

  (let [remaining (calculate-remaining-time now end)]

    (log/info :game.games (format "< Resumed > %s" (format-remaining-time remaining)))
    (core.async/>!! game-event-stream control))
  [now end])

(defmethod handle-control-event :exit [_ game-event-stream {m :message :as control} now end]

  (let [remaining (calculate-remaining-time now end)]

    (log/info :game.games (format "%sExiting / Time Remaining / %s" (if m m "") (format-remaining-time remaining)))
    (core.async/>!! game-event-stream control))
  [])

(defmethod handle-control-event :win [conn game-event-stream {:keys [game-id level] :as control} now end]

  (let [remaining (calculate-remaining-time now end)]

    (transition-level! conn game-id level)
    (log/info :game.games (format "Win %s" (format-remaining-time remaining)))
    (core.async/>!! game-event-stream control)
    [now end]))

(defmethod handle-control-event :lose [conn game-event-stream control now end]

  (let [remaining (calculate-remaining-time now end)]
    (log/info :game.games (format "Lose %s" (format-remaining-time remaining)))
    (core.async/>!! game-event-stream control)
    (handle-control-event conn game-event-stream {:event :exit :game-id (:game-id control)} now end)))

(defmethod handle-control-event :timeout [_ game-event-stream control now end]

  (let [remaining (calculate-remaining-time now end)]
    (log/info :game.games (format "Running %s / TIME'S UP!!" (format-remaining-time remaining))))
  [])

(defmethod handle-control-event :continue [_ game-event-stream control now end]

  (-> (calculate-remaining-time now end)
      (select-keys [:remaining-in-minutes :remaining-in-seconds])
      (merge control)
      (#(core.async/>!! game-event-stream %)))

  [(t/now) end])


(defn inputs->historical-data [input-sequence start-position]
  (take start-position input-sequence))

(defn inputs->control-chain [{:keys [game input-sequence
                                     stream-stock-tick-mappingfn calculate-profit-loss-mappingfn
                                     collect-profit-loss-mappingfn stream-portfolio-update-mappingfn
                                     transact-tick-mappingfn transact-profit-loss-mappingfn
                                     check-level-complete-mappingfn]}]

  (->> input-sequence
       (map transact-tick-mappingfn)
       (map stream-stock-tick-mappingfn)
       (map calculate-profit-loss-mappingfn)
       (map collect-profit-loss-mappingfn)
       (map transact-profit-loss-mappingfn)
       (map stream-portfolio-update-mappingfn)
       (map check-level-complete-mappingfn)))

(defn run-iteration [control-chain]

  (let [first+next (juxt first rest)
        f          (fn [[x xs]] (first+next xs))]
    (iterate f (first+next control-chain))))

(defn run-game! [conn
                 {{game-id :game/id} :game
                  current-level :current-level
                  iterations :iterations
                  control-channel :control-channel
                  game-event-stream :game-event-stream}
                 tick-sleep-atom level-timer-atom]

  (core.async/go-loop [now (t/now)
                       end (t/plus now (t/seconds @level-timer-atom))
                       iters iterations]

    (let [remaining                            (calculate-remaining-time now end)
          [{event :event
            :as   controlv} ch] (core.async/alts! [(core.async/timeout @tick-sleep-atom)
                                                   control-channel])]

      (log/info :game.games (format "game-loop %s / %s"
                                    (select-keys remaining [:remaining-in-minutes :remaining-in-seconds])
                                    (if controlv controlv :running)))

      (let [x        (ffirst iters)
            paused?  (game-paused? game-id)
            expired? (time-expired? remaining)

            [nowA endA] (match [paused? event expired?]

                               [true (_ :guard #{:resume :exit}) _] (handle-control-event conn game-event-stream controlv now end)

                               [true _ _] (let [new-end   (t/plus end (t/seconds 1))
                                                remaining (calculate-remaining-time now new-end)
                                                controlv  {:event   :paused
                                                           :game-id game-id
                                                           :level   (:level @current-level)
                                                           :type :ControlEvent}]

                                            (handle-control-event conn game-event-stream
                                                                  (assoc controlv :message "< Paused >")
                                                                  (t/now) new-end))

                               [_ :pause _] (let [new-end   (t/plus end (t/seconds 1))
                                                  remaining (calculate-remaining-time now new-end)]

                                              (handle-control-event conn game-event-stream
                                                                    (assoc controlv :message "< Paused >")
                                                                    (t/now) new-end))

                               [_ (_ :guard #{:resume :exit :win :lose}) _] (handle-control-event conn game-event-stream controlv now end)

                               [_ _ false] (let [controlv {:event   :continue
                                                           :game-id game-id
                                                           :level   (:level @current-level)
                                                           :type :LevelTimer}]

                                             (handle-control-event conn game-event-stream controlv now end))

                               [_ _ true] (handle-control-event conn game-event-stream {:event :timeout} now end))]

        (when (and nowA endA)
          (recur nowA endA (next iters)))))))

(defn seek-to-position [start xs]
  (let [seekfn (juxt (partial take start) (partial drop start))]
    (seekfn xs)))

(defn start-game!

  ([conn user-db-id game-control]
   (start-game! conn user-db-id game-control 0))

  ([conn user-db-id game-control start-position]

   (let [{:keys [control-channel
                 tick-sleep-atom
                 level-timer-atom]} game-control

         [historical-data inputs-at-position] (->> (inputs->control-chain game-control)
                                                   (seek-to-position start-position))]

     (as-> inputs-at-position v
       (run-iteration v)
       (assoc game-control :iterations v)
       (run-game! conn v tick-sleep-atom level-timer-atom))

     historical-data)))

(defn start-workbench!

  ([conn user-db-id game-control]
   (start-workbench! conn user-db-id game-control 0))

  ([conn user-db-id
    {level-timer :level-timer-atom
     tick-sleep-atom :tick-sleep-atom
     game-event-stream :game-event-stream
     control-channel :control-channel
     :as game-control}
    start-position]

   ;; A
   (core.async/go-loop [now (t/now)
                        end (t/plus now (t/seconds @level-timer))]

     (let [remaining (calculate-remaining-time now end)
           expired? (time-expired? remaining)

           [{message :event :as controlv} ch] (core.async/alts! [(core.async/timeout @tick-sleep-atom) control-channel])
           {message :event :as controlv} (if (nil? controlv) {:event :continue} controlv)

           [nowA endA] (match [message expired?]
                              [_ false] (handle-control-event conn game-event-stream controlv now end)
                              [_ true] (handle-control-event conn game-event-stream {:event :timeout} now end))]

       (when (and nowA endA)
         (recur nowA endA))))

   ;; B
   (let [[historical-data inputs-at-position] (->> (inputs->control-chain game-control)
                                                   (seek-to-position start-position))]

     [historical-data (run-iteration inputs-at-position)])))
