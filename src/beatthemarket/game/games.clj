(ns beatthemarket.game.games
  (:require [clojure.core.async :as core.async
             :refer [go go-loop chan close! timeout alts! >! <! >!!]]
            [clojure.core.async.impl.protocols]
            [clojure.core.match :refer [match]]
            [clj-time.core :as t]
            [clojure.data.json :as json]
            [datomic.client.api :as d]
            [datascript.core :as ds]
            [com.rpl.specter :refer [transform ALL MAP-VALS]]
            [rop.core :as rop]
            [integrant.core :as ig]
            [integrant.repl.state :as repl.state]
            [beatthemarket.bookkeeping.persistence :as bookkeeping.persistence]
            [beatthemarket.datasource :as datasource]
            [beatthemarket.datasource.core :as datasource.core]
            [beatthemarket.datasource.name-generator :as name-generator]
            [beatthemarket.game.core :as game.core]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.util :as util]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.bookkeeping.core :as bookkeeping])
  (:import [java.util UUID]))


(defmethod ig/init-key :game/games [_ _]
  (atom {}))

(defmethod ig/halt-key! :game/games [_ games]
  (run! (fn [{:keys [stock-stream-channel control-channel]}]

          (println "Closing Game channels...")
          (close! stock-stream-channel)

          (go (>! control-channel :exit))
          (close! control-channel))
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

(defn initialize-game!

  ([conn user-entity sink-fn]

   (initialize-game! conn user-entity sink-fn (->data-sequence)))

  ([conn user-entity sink-fn data-sequence]

   (let [stocks            (game.core/generate-stocks! 4)
         game              (game.core/initialize-game! conn user-entity stocks)
         stocks                (:game/stocks game)
         stocks-with-tick-data (map (partial bind-data-sequence data-sequence) stocks)
         game-control          {:game                  game
                                :profit-loss           {}
                                :stocks-with-tick-data stocks-with-tick-data

                                :paused? (atom false)
                                :tick-sleep-atom (atom (-> integrant.repl.state/config :game/game :tick-sleep-ms))
                                :level-timer-atom (atom (-> integrant.repl.state/config :game/game :level-timer-sec))

                                :stock-tick-xf (map (fn [tick] (println (format ">> STREAM tick / %s" tick)) tick))
                                :calculate-profit-loss-xf (map #_beatthemarket.game.persistence/track-profit-loss!
                                                               (fn [a]
                                                                 (println (format ">> CALCAULATE profit-loss-xf %s" a))
                                                                 a))
                                :portfolio-update-xf (map (fn [profit-loss] (println (format ">> STREAM portfolio-update / %s" profit-loss)) profit-loss))


                                :transact-tick-xf (map #_(partial beatthemarket.persistence.datomic/transact-entities! conn)
                                                       (fn [a]
                                                         (println (format "transact-tick-xf %s" a))
                                                         a))
                                :transact-profit-loss-xf (map #_(partial beatthemarket.persistence.datomic/transact-entities! conn)
                                                              (fn [a]
                                                                (println (format "transact-profit-loss-xf %s" a))
                                                                a))

                                :control-channel      (chan 1)
                                :close-sink-fn        (partial sink-fn nil)
                                :sink-fn              #(sink-fn {:message %})}]

     (register-game-control! game game-control)
     game-control)))

(defn create-game!

  ([conn user-id sink-fn]
   (create-game! conn user-id sink-fn (->data-sequence)))

  ([conn user-id sink-fn data-sequence]
   (let [user-entity (hash-map :db/id user-id)]
     (initialize-game! conn user-entity sink-fn data-sequence))))

;; START
(defn onto-open-chan
  "Clone of clojure.core.async. But only puts to open channels.

   Puts the contents of coll into the supplied channel.

   By default the channel will be closed after the items are copied,
   but can be determined by the close? parameter.

   Returns a channel which will close after the items are copied."
  ([ch coll] (onto-open-chan ch coll true))
  ([ch coll close?]
   (go-loop [vs (seq coll)]

     ;; (println "Channel open? " (not (clojure.core.async.impl.protocols/closed? ch)))
     (if (and vs
              (not (clojure.core.async.impl.protocols/closed? ch))
              (>! ch (first vs)))
       (recur (next vs))
       (when close?
         (close! ch))))))

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

(defn control-streams! [control-channel {:keys [mixer pause-chan]} command]
  (case command
    :exit (core.async/>!! control-channel :exit)
    :pause (core.async/toggle mixer {pause-chan { :pause true } })
    :resume (core.async/toggle mixer {pause-chan { :pause false} })
    (throw (ex-info (format "Invalid command %s" command {})))))

#_(defn stream-stocks! [{:keys [tick-sleep-ms control-channel] :as game-control}
                      {:keys [mixer pause-chan output-chan] :as channel-controls}
                      {:keys [close-sink-fn sink-fn] :as output-fns}
                      game-loop-fn]

  (go-loop []

    (let [[v ch] (core.async/alts! [control-channel
                                    (core.async/timeout tick-sleep-ms)])]

      ;; (println (format "B. go-loop / value / %s" v))
      (case v
        :exit (close-sink-fn)
        (let [vv (<! output-chan)]


          ;; (println (format "Sink value / %s" v))
          (game-loop-fn vv)
          (when vv
            (sink-fn vv)
            (recur)))))))

#_(defn stream-subscription!

  ([game-control channel-controls output-fns]
   (let [game-loop-fn identity]
     (stream-subscription! game-control channel-controls output-fns game-loop-fn)))

  ([game-control channel-controls output-fns game-loop-fn]
   (stream-stocks! game-control channel-controls output-fns game-loop-fn)))

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

                                      stock-tick-xf calculate-profit-loss-xf portfolio-update-xf
                                      transact-tick-xf transact-profit-loss-xf]}
                              {:keys [conn user-db-id]}]

  (let [;; stream-buffer 40
        ;; stock-tick-stream (core.async/chan stream-buffer)
        ;; portfolio-update-stream (core.async/chan stream-buffer)
        ;; game-event-stream (core.async/chan stream-buffer)

        concurrent 1
        plbuffer 1
        ticks-from (core.async/chan plbuffer)
        ticks-to (core.async/chan plbuffer stock-tick-xf) ;; NOTE stream tick
        profit-loss-to (core.async/chan plbuffer)
        profit-loss-transact-to (core.async/chan plbuffer portfolio-update-xf) ;; NOTE stream PL
        ;; game-event-from (core.async/chan plbuffer)
        ;; game-event-to (core.async/chan plbuffer)
        input-seq                       (stocks->stock-sequences conn game user-db-id stocks-with-tick-data)]


    (println "A /" (take 5 stocks-with-tick-data))
    (println "B /" (take 5 input-seq))

    ;; stock-tick-stream
    ;; portfolio-update-stream
    ;; game-event-stream

    ;; A
    ;; (< ticks)       transact-xf              (< ticks)
    ;;                 calculate-profit-loss-xf (< profit-loss)
    ;; (< profit-loss) transact-xf              (< profit-loss)


    (core.async/onto-chan ticks-from input-seq)

    ;; P/L
    (core.async/pipeline-blocking concurrent ticks-to                transact-tick-xf         ticks-from)
    (core.async/pipeline-blocking concurrent profit-loss-to          calculate-profit-loss-xf ticks-to)
    (core.async/pipeline-blocking concurrent profit-loss-transact-to transact-profit-loss-xf  profit-loss-to)
    profit-loss-transact-to))

(defn format-remaining-time [{:keys [remaining-in-minutes remaining-in-seconds]}]
  (format "%s:%s" remaining-in-minutes remaining-in-seconds))

(defn time-expired? [{:keys [remaining-in-minutes remaining-in-seconds]}]
  (and (= 0 remaining-in-minutes) (< remaining-in-seconds 1)))

(defn calculate-remaining-time [now end]
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
          (recur nowA endA))))))

#_(defn chain-stock-sequence-controls! [conn
                                      {:keys [game stocks-with-tick-data tick-sleep-ms
                                              control-channel stock-stream-channel
                                              close-sink-fn sink-fn] :as game-control}
                                      input-seq]

  (let [input-chan (chan 1)]

    (core.async/onto-chan input-chan input-seq)

    ;; A. controls to pause , resume
    (let [mixer (core.async/mix stock-stream-channel)]

      (core.async/admix mixer input-chan)
      (assoc game-control
             :input-chan input-chan
             :mixer mixer
             :output-chan stock-stream-channel))))

(defn start-game!

  ([conn user-db-id game-control]
   (start-game! conn user-db-id game-control identity))

  ([conn user-db-id game-control game-loop-fn]

   (let [{:keys [control-channel
                 paused?
                 tick-sleep-atom
                 level-timer-atom]} game-control]

     (as-> game-control gc
       (chain-control-pipeline gc {:conn conn :user-db-id user-db-id})
       (assoc game-control :profit-loss-transact-to gc)
       (run-game! gc paused? tick-sleep-atom level-timer-atom)))))

;; CALCULATION
(defn collect-realized-profit-loss [game-id]

  (let [profit-loss (-> repl.state/system :game/games deref (get game-id) :profit-loss)]
    (->> (for [[k vs] profit-loss]
           [k (->> (filter :realized-profit-loss vs)
                   (reduce (fn [ac {pl :realized-profit-loss}]
                             (+ ac pl))
                           0.0)
                   (format "%.2f")
                   (Float.))])
         flatten
         (apply hash-map))))
