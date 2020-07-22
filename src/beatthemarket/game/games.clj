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
  (run! (fn [{:keys [stock-stream-channel control-channel]}]

          ;; (println "Closing Game channels...")
          ;; (close! stock-stream-channel)
          ;;
          ;; (go (>! control-channel :exit))
          ;; (close! control-channel)
          )
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

(defn recalculate-profit-loss-on-tick-perstock [price profit-loss-perstock]

  (let [[butlast-chunks latest-chunk] (->> (game.persistence/profit-loss->chunks profit-loss-perstock)
                                           ((juxt butlast last)))]
    (->> latest-chunk
         (map (partial game.persistence/recalculate-profit-loss-on-tick price))
         (concat butlast-chunks)
         flatten)))

(defn initialize-game!

  ([conn user-entity sink-fn]

   (initialize-game! conn user-entity sink-fn (->data-sequence) nil nil nil))

  ([conn user-entity sink-fn data-sequence stream-stock-tick-xf stream-portfolio-update-xf collect-profit-loss-xf]

   (let [stocks                (game.core/generate-stocks! 4)
         {game-id :game/id
          stocks  :game/stocks
          :as     game}        (game.core/initialize-game! conn user-entity stocks)
         stocks-with-tick-data (map (partial bind-data-sequence data-sequence) stocks)
         stock-tick-by-id      (fn [id stock-ticks]
                                 (first (filter #(= id (:game.stock/id %))
                                                stock-ticks)))

         stream-buffer           1
         stock-tick-stream       (core.async/chan stream-buffer)
         portfolio-update-stream (core.async/chan stream-buffer)
         game-event-stream       (core.async/chan stream-buffer)
         transact-xf             (fn [data]
                                   (beatthemarket.persistence.datomic/transact-entities! conn data)
                                   data)
         game-control
         {:game                  game
          :profit-loss           {}
          :stocks-with-tick-data stocks-with-tick-data

          :paused?          (atom false)
          :tick-sleep-atom  (atom (-> integrant.repl.state/config :game/game :tick-sleep-ms))
          :level-timer-atom (atom (-> integrant.repl.state/config :game/game :level-timer-sec))

          :stock-tick-stream       stock-tick-stream
          :portfolio-update-stream portfolio-update-stream
          :game-event-stream       game-event-stream

          :transact-tick-xf         (map transact-xf)
          :stream-stock-tick-xf     (or stream-stock-tick-xf
                                        (map (fn [stock-tick-pairs]
                                               (let [stock-ticks (group-stock-tick-pairs stock-tick-pairs)]
                                                 ;; (println (format ">> STREAM stock-tick-pairs / %s" stock-ticks))
                                                 (core.async/go (core.async/>! stock-tick-stream stock-ticks))
                                                 stock-ticks))))
          :calculate-profit-loss-xf (map (fn [stock-ticks]
                                           (let [recalculate-perstock-fn
                                                 (fn [profit-loss]
                                                   (reduce-kv (fn [m k v]
                                                                (if-let [{price :game.stock.tick/close}
                                                                         (stock-tick-by-id k stock-ticks)]
                                                                  (assoc
                                                                    m k
                                                                    (recalculate-profit-loss-on-tick-perstock price v))
                                                                  m))
                                                              {}
                                                              profit-loss))

                                                 updated-profit-loss-calculations
                                                 (-> repl.state/system :game/games
                                                     deref
                                                     (get game-id) :profit-loss
                                                     recalculate-perstock-fn)]

                                             (->> updated-profit-loss-calculations
                                                  (game.persistence/track-profit-loss-wholesale! game-id)
                                                  (#(get % game-id))
                                                  :profit-loss))))
          :collect-profit-loss-xf     (or collect-profit-loss-xf
                                          (map (fn [profit-loss] (game.calculation/collect-running-profit-loss game-id profit-loss))))
          :transact-profit-loss-xf    (map identity) ;; (map transact-xf)
          :stream-portfolio-update-xf (or stream-portfolio-update-xf
                                          (map (fn [running-profit-loss]
                                                 ;; (println (format ">> STREAM portfolio-update / %s" running-profit-loss))
                                                 (core.async/go (core.async/>! portfolio-update-stream running-profit-loss))
                                                 running-profit-loss)))

          :control-channel (chan 1)
          :close-sink-fn   (partial sink-fn nil)
          :sink-fn         #(sink-fn {:message %})}]

     (register-game-control! game game-control)
     game-control)))

(defn create-game!

  ([conn user-id sink-fn]
   (create-game! conn user-id sink-fn (->data-sequence) nil nil nil))

  ([conn user-id sink-fn data-sequence stream-stock-tick-xf stream-portfolio-update-xf collect-profit-loss-xf]
   (let [user-entity (hash-map :db/id user-id)]
     (initialize-game! conn user-entity sink-fn data-sequence stream-stock-tick-xf stream-portfolio-update-xf collect-profit-loss-xf))))

;; START
(defn game->new-game-message [game user-id]

  (let [game-stocks        (:game/stocks game)
        game-subscriptions (:game.user/subscriptions (game.core/game-user-by-user-id game user-id))]

    (-> (transform [MAP-VALS ALL :game.stock/id] str
                   {:stocks        game-stocks
                    :subscriptions game-subscriptions})
        (assoc :id (str (:game/id game))))))

(defn narrow-stock-tick-pairs-by-subscription [stock-tick-pairs {input-stock-id :game.stock/id}]

  (for [{{each-stock-tick-id :game.stock.tick/id} :game.stock/price-history
         each-stock-id                            :game.stock/id :as e}      stock-tick-pairs
        {binding-stock-tick-id                    :game.stock.tick/id :as f} stock-tick-pairs
        :when                                                                (and (= input-stock-id each-stock-id)
                                                                                  (= binding-stock-tick-id each-stock-tick-id))]

    [e f]))

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
  (->> stocks-with-tick-data
       stocks->partitioned-entities
       (map partitioned-entities->transaction-entities)))

(defn chain-control-pipeline [{:keys [game stocks-with-tick-data
                                      stream-stock-tick-xf calculate-profit-loss-xf
                                      collect-profit-loss-xf stream-portfolio-update-xf
                                      transact-tick-xf transact-profit-loss-xf]}
                              {:keys [conn user-db-id]}]

  (let [concurrent              1
        plbuffer                1
        input-seq               (stocks->stock-sequences conn game user-db-id stocks-with-tick-data)

        ticks-from              (core.async/chan plbuffer)
        ticks-to                (core.async/chan plbuffer stream-stock-tick-xf)       ;; NOTE stream tick
        profit-loss-to          (core.async/chan plbuffer collect-profit-loss-xf)
        profit-loss-transact-to (core.async/chan plbuffer stream-portfolio-update-xf) ;; NOTE stream PL
        ]

    ;; P/L
    (core.async/onto-chan ticks-from input-seq)

    (core.async/pipeline-blocking concurrent ticks-to                transact-tick-xf         ticks-from)
    (core.async/pipeline-blocking concurrent profit-loss-to          calculate-profit-loss-xf ticks-to)
    (core.async/pipeline-blocking concurrent profit-loss-transact-to transact-profit-loss-xf  profit-loss-to)
    profit-loss-transact-to))

(defn- format-remaining-time [{:keys [remaining-in-minutes remaining-in-seconds]}]
  (format "%s:%s" remaining-in-minutes remaining-in-seconds))

(defn- time-expired? [{:keys [remaining-in-minutes remaining-in-seconds]}]
  (and (= 0 remaining-in-minutes) (< remaining-in-seconds 1)))

(defn- calculate-remaining-time [now end]
  (let [interval (t/interval now end)]
    {:interval interval
     :remaining-in-minutes (t/in-minutes interval)
     :remaining-in-seconds (rem (t/in-seconds interval) 60)}))

(defn pause-game? [{paused? :paused?} pause?]

  ;; TODO stream a :game-event
  (reset! paused? pause?))

(defn handle-control-event [control remaining]

  ;; TODO stream
  ;; :game-event
  ;; :timer-event

  ;; TODO handle events
  ;; :exit :win :lose :timeout
  )

(defn run-game! [{:keys [control-channel profit-loss-transact-to]} pause-atom tick-sleep-atom level-timer-atom]

  (let [start (t/now)]

    (go-loop [now start
              end (t/plus start (t/seconds @level-timer-atom))]

      (let [remaining (calculate-remaining-time now end)
            [controlv ch] (core.async/alts! [(core.async/timeout @tick-sleep-atom)
                                             control-channel])

            ;; pause
            ;; control
            ;; timer
            [nowA endA] (match [@pause-atom controlv (time-expired? remaining)]

                               [true :exit _] (do
                                                (handle-control-event controlv remaining)
                                                (println (format "< Paused > %s / Exiting..." (format-remaining-time remaining))))
                               [true _ _] (let [new-end (t/plus end (t/seconds 1))
                                                remaining (calculate-remaining-time now new-end)]
                                            (println (format "< Paused > %s" (format-remaining-time remaining)))
                                            [(t/now) new-end])

                               [_ :exit _] (do (handle-control-event controlv remaining)
                                               (println (format "Running %s / Exiting" (format-remaining-time remaining))))
                               [_ :win _] (do (handle-control-event controlv remaining)
                                              (println (format "Win %s" (format-remaining-time remaining))))
                               [_ :lose _] (do (handle-control-event controlv remaining)
                                               (println (format "Lose %s" (format-remaining-time remaining))))
                               [_ _ false] (let [v (<! profit-loss-transact-to)]

                                             ;; TODO stream
                                             ;; :stock-tick
                                             ;; :timer-event

                                             (println (format "Running %s / %s" (format-remaining-time remaining) v))
                                             (when v
                                               [(t/now) end]))
                               [_ _ true] (do (handle-control-event :timeout remaining)
                                              (println (format "Running %s / TIME'S UP!!" (format-remaining-time remaining)))))]

        (when (and nowA endA)
          (recur nowA endA)))))

  profit-loss-transact-to)

(defn start-game! [conn user-db-id game-control]

  (let [{:keys [control-channel
                paused?
                tick-sleep-atom
                level-timer-atom]} game-control]

    (as-> game-control gc
      (chain-control-pipeline gc {:conn conn :user-db-id user-db-id})
      (assoc game-control :profit-loss-transact-to gc)
      (run-game! gc paused? tick-sleep-atom level-timer-atom))))
