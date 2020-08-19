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

            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.iam.persistence :as iam.persistence]

            [beatthemarket.bookkeeping.core :as bookkeeping]
            [beatthemarket.bookkeeping.persistence :as bookkeeping.persistence]
            [beatthemarket.datasource :as datasource]
            [beatthemarket.datasource.core :as datasource.core]
            [beatthemarket.datasource.name-generator :as name-generator]

            [beatthemarket.game.core :as game.core]
            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.game.persistence :as game.persistence]
            [beatthemarket.game.games.processing :as games.processing]

            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]

            [beatthemarket.util :as util])
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

  ([conn user-db-id userId gameId stockId stockAmount tickId tickPrice]
   (buy-stock! conn userId gameId stockId stockAmount tickId tickPrice true))

  ([conn user-db-id userId gameId stockId stockAmount tickId tickPrice validate?]
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
            [_ _] (let [game-db-id  (util/extract-id (persistence.core/entity-by-domain-id conn :game/id gameId))
                        stock-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game.stock/id stockId))]

                    (bookkeeping/buy-stock! conn game-db-id user-db-id stock-db-id tickId stockAmount tickPrice))))))

(defn sell-stock!

  ([conn user-db-id userId gameId stockId stockAmount tickId tickPrice]
   (sell-stock! conn userId gameId stockId stockAmount tickId tickPrice true))

  ([conn user-db-id userId gameId stockId stockAmount tickId tickPrice validate?]

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
            [_ _] (let [game-db-id  (util/extract-id (persistence.core/entity-by-domain-id conn :game/id gameId))
                        stock-db-id (util/extract-id (util/pprint+identity (persistence.core/entity-by-domain-id conn :game.stock/id stockId)))]

                    (println [game-db-id user-db-id :?stockId stock-db-id tickId stockAmount tickPrice])
                    (bookkeeping/sell-stock! conn game-db-id user-db-id stock-db-id tickId stockAmount tickPrice))))))

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

(defn default-game-control [conn user-id game-id
                            {:keys [control-channel current-level
                                    stock-tick-stream
                                    portfolio-update-stream
                                    game-event-stream]}]

  {:profit-loss                   {}
   :paused?                       false
   :process-transact!             (partial games.processing/process-transact! conn)
   :stream-stock-tick             (partial games.processing/stream-stock-tick game-id)
   :process-transact-profit-loss! (partial games.processing/process-transact-profit-loss! conn)
   :stream-portfolio-update!      (partial games.processing/stream-portfolio-update! portfolio-update-stream)

   :check-level-complete           (partial games.processing/check-level-complete game-id control-channel current-level)
   :process-transact-level-update! (partial games.processing/process-transact-level-update! conn)
   :stream-level-update!           (partial games.processing/stream-level-update! game-event-stream)})

(defn initialize-game!

  ([conn user-entity accounts sink-fn]
   (initialize-game! conn user-entity accounts sink-fn :game-level/one ->data-sequence {}))

  ([conn user-entity accounts sink-fn game-level data-sequence-fn {:keys [level-timer-sec tick-sleep-ms game-id
                                                                          input-sequence profit-loss

                                                                          process-transact!
                                                                          stream-stock-tick

                                                                          calculate-profit-loss stream-portfolio-update!
                                                                          check-level-complete stream-level-update!]}]

   (let [stocks               (game.core/generate-stocks! 4)
         data-generators (-> integrant.repl.state/config :game/game :data-generators)
         ;; data-seed (datasource.core/random-seed)
         initialize-game-opts {:game-id     (or game-id (UUID/randomUUID))
                               :game-status :game-status/created
                               ;; :data-seed   data-seed
                               }

         {game-id                      :game/id
          stocks                       :game/stocks
          {saved-game-level :db/ident} :game/level :as game}
         (game.core/initialize-game! conn user-entity accounts game-level stocks initialize-game-opts)

         stocks-with-tick-data   (map (fn [{seed :game.stock/data-seed :as stock}]
                                        (bind-data-sequence (partial data-sequence-fn data-generators seed)
                                                            stock))
                                      stocks)
         input-sequence-local    (stocks->stock-sequences stocks-with-tick-data)
         stream-buffer           10
         control-channel         (core.async/chan (core.async/sliding-buffer stream-buffer))
         stock-tick-stream       (core.async/chan (core.async/sliding-buffer stream-buffer))
         portfolio-update-stream (core.async/chan (core.async/sliding-buffer stream-buffer))
         game-event-stream       (core.async/chan (core.async/sliding-buffer stream-buffer))

         {:keys [profit-threshold lose-threshold]} (-> integrant.repl.state/config :game/game :levels
                                                       (get saved-game-level))

         current-level (atom {:level            saved-game-level
                              :profit-threshold profit-threshold
                              :lose-threshold   lose-threshold})

         game-control (merge-with #(if %2 %2 %1)
                                  (default-game-control conn (:db/id user-entity) game-id
                                                        {:control-channel         control-channel
                                                         :current-level           current-level
                                                         :stock-tick-stream       stock-tick-stream
                                                         :portfolio-update-stream portfolio-update-stream})
                                  {:game                  game                  ;; TODO load
                                   ;; :start-position        (atom 0)
                                   :level-timer           (atom [])
                                   :profit-loss           (or profit-loss {})   ;; TODO replay
                                   :stocks-with-tick-data stocks-with-tick-data ;; TODO load + seek to index
                                   :input-sequence        (or input-sequence input-sequence-local)
                                   :paused?               false                 ;; TODO store in DB
                                   :tick-sleep-atom       (atom (or tick-sleep-ms (-> integrant.repl.state/config :game/game :tick-sleep-ms)))
                                   :level-timer-atom      (atom (or level-timer-sec (-> integrant.repl.state/config :game/game :level-timer-sec)))
                                   :current-level         current-level         ;; TODO store in DB

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

(defn level->source-and-destination [level]

  (->> repl.state/config :game/game :levels seq
       (sort-by (comp :order second))
       (partition 2 1)
       (filter (fn [[[level-name _] r]] (= level level-name)))
       first))

(defn update-inmemory-game-level! [game-id level]

  (let [[[source-level-name _ :as source]
         [dest-level-name dest-level-config :as dest]] (level->source-and-destination level)]

    (swap! (:game/games repl.state/system)
           (fn [gs]
             (update-in gs [game-id :current-level] (-> dest-level-config
                                                        (assoc :level dest-level-name)
                                                        (dissoc :order)
                                                        constantly))))))


(defn level-timer->db [minute-and-second-tuple]
  (str minute-and-second-tuple))

(defn db->level-timer [minute-and-second-tuple]
  (clojure.edn/read-string minute-and-second-tuple))


(defn pause-game! [conn game-id]

  ;; Update :game/status :game/level-timer
  (let [level-timer (-> repl.state/system
                        :game/games deref (get game-id)
                        :level-timer deref
                        level-timer->db)

        {game-db-id :db/id
         {game-status :db/ident} :game/status
         game-start-position :game/start-position
         game-level-timer :game/level-timer} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))

        data (cond-> [[:db/retract  game-db-id :game/status game-status]
                      [:db/add      game-db-id :game/status :game-status/paused]]

               game-level-timer (conj [:db/retract game-db-id :game/level-timer game-level-timer])
               true (conj [:db/add game-db-id :game/level-timer level-timer]))]

    (persistence.datomic/transact-entities! conn data)))

(defn exit-game! [conn game-id]

  (let [{game-db-id :db/id
         game-status :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))
        data [[:db/retract  game-db-id :game/status game-status]
              [:db/add      game-db-id :game/status :game-status/exited]]]

    (persistence.datomic/transact-entities! conn data)))

(defn win-game! [conn game-id]

  (let [{game-db-id :db/id
         game-status :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))
        data [[:db/retract  game-db-id :game/status game-status]
              [:db/add      game-db-id :game/status :game-status/won]]]

    (persistence.datomic/transact-entities! conn data)))

(defn lose-game! [conn game-id]

  (let [{game-db-id :db/id
         game-status :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))
        data [[:db/retract  game-db-id :game/status game-status]
              [:db/add      game-db-id :game/status :game-status/lost]]]

    (persistence.datomic/transact-entities! conn data)))

(defn conditionally-level-up! [conn game-id [[source-level-name _ :as source]
                                             [dest-level-name dest-level-config :as dest]]]

  (when dest

    (let [{game-db-id :db/id} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))
          data [[:db/retract  game-db-id :game/level source-level-name]
                [:db/add      game-db-id :game/level dest-level-name]]

          {db-after :db-after} (persistence.datomic/transact-entities! conn data)]

      (if db-after
        (update-inmemory-game-level! game-id dest-level-name)
        (throw (Exception. (format "Couldn't level up from to [%s %s]" source dest)))))))

(defn conditionally-reset-level-time! [conn game-id [[source-level-name _               :as source]
                                                     [dest-level-name dest-level-config :as dest]]]

  (when dest
    (swap! (:game/games repl.state/system)
           (fn [gs]
             (update-in gs [game-id :level-timer-atom] (-> repl.state/config
                                                           :game/game
                                                           :level-timer-sec
                                                           constantly))))))

(defn transition-level! [conn game-id level]

  (let [source-and-destination (level->source-and-destination level)]

    (conditionally-level-up! conn game-id source-and-destination)
    (conditionally-reset-level-time! conn game-id source-and-destination)))


(defmulti handle-control-event (fn [_ _ {m :event} _ _] m))

(defmethod handle-control-event :pause [_ game-event-stream
                                        {game-id :game-id :as control}
                                        now end]

  ;; (pause-game! game-id)

  (let [remaining (calculate-remaining-time now end)]
    (log/info :game.games (format "< Paused > %s" (format-remaining-time remaining)))
    (core.async/>!! game-event-stream control))

  [])

#_(defmethod handle-control-event :paused [_ game-event-stream
                                         {game-id :game-id :as control}
                                         now end]

  (let [remaining (calculate-remaining-time now end)]
    (log/info :game.games (format "< Paused > %s" (format-remaining-time remaining))))

  [now end])

#_(defmethod handle-control-event :resume [_ game-event-stream
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

(defn update-inmemory-game-timer! [game-id minute-second-tuple]

  (swap! (:game/games repl.state/system)
         (fn [gs]
           (update-in gs [game-id :level-timer] (constantly minute-second-tuple)))))

(defmethod handle-control-event :continue [_ game-event-stream {game-id :game-id :as control} now end]

  (let [remaining-time (select-keys (calculate-remaining-time now end)
                                    [:remaining-in-minutes :remaining-in-seconds])]

    ;; A
    (update-inmemory-game-timer! game-id (vals remaining-time))

    ;; B
    (core.async/>!! game-event-stream (merge remaining-time control)))

  [(t/now) end])


(defn inputs->historical-data [input-sequence start-position]
  (take start-position input-sequence))

(defn inputs->control-chain [{:keys [game input-sequence
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

(defn run-iteration [control-chain]

  (let [first+rest (juxt first rest)
        f          (fn [[x xs]] (first+rest xs))]
    (iterate f (first+rest control-chain))))

(defn run-game! [conn
                 {{game-id :game/id} :game
                  current-level :current-level
                  iterations :iterations
                  control-channel :control-channel
                  game-event-stream :game-event-stream}
                 tick-sleep-atom level-timer-atom]

  ;; A
  (let [{game-db-id :db/id
         game-status :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))
        data [[:db/retract  game-db-id :game/status game-status]
              [:db/add      game-db-id :game/status :game-status/running]]]

    (persistence.datomic/transact-entities! conn data))

  ;; B
  (core.async/go-loop [now (t/now)
                       end (t/plus now (t/seconds @level-timer-atom))
                       iters iterations]

    (let [remaining                            (calculate-remaining-time now end)
          [{event :event
            :as   controlv} ch] (core.async/alts! [(core.async/timeout @tick-sleep-atom)
                                                   control-channel])]

      (log/debug :game.games (format "game-loop %s:%s / %s"
                                     (:remaining-in-minutes remaining)
                                     (:remaining-in-seconds remaining)
                                     (if controlv controlv :running)))

      (let [x        (ffirst iters)
            expired? (time-expired? remaining)

            [nowA endA] (match [event expired?]

                               [:pause _] (let [new-end   (t/plus end (t/seconds 1))
                                                remaining (calculate-remaining-time now new-end)]

                                            (handle-control-event conn game-event-stream
                                                                  (assoc controlv :message "< Paused >")
                                                                  (t/now) new-end))

                               [(_ :guard #{:exit :win :lose}) _] (handle-control-event conn game-event-stream controlv now end)

                               [_ false] (let [controlv {:event   :continue
                                                         :game-id game-id
                                                         :level   (:level @current-level)
                                                         :type :LevelTimer}]

                                           (handle-control-event conn game-event-stream controlv now end))

                               [_ true] (handle-control-event conn game-event-stream {:event :timeout} now end))]

        (when (and nowA endA)
          (recur nowA endA (next iters)))))))

(defn seek-to-position [start xs]
  (let [seekfn (juxt (partial take start) (partial drop start))]
    (seekfn xs)))

(defn update-start-position! [conn game-id start-position]

  (util/pprint+identity [game-id start-position])

  (let [{game-db-id :db/id
         old-start-position :game/start-position} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))

        ;; data [[:db/retract  game-db-id :game/start-position old-start-position]
        ;;       [:db/add      game-db-id :game/start-position start-position]]

        data (cond-> []
               old-start-position (conj [:db/retract game-db-id :game/start-position old-start-position])
               true (conj [:db/add game-db-id :game/start-position start-position]))]

    (persistence.datomic/transact-entities! conn (util/pprint+identity data))))

(defn start-game!

  ([conn user-db-id game-control]
   (start-game! conn user-db-id game-control 0))

  ([conn user-db-id {{game-id :game/id} :game :as game-control} start-position]

   ;; A
   (update-start-position! conn game-id start-position)

   ;; B
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

(defn resume-game! [conn game-id]

  (let [{game-db-id :db/id
         game-status :game/status
         game-level :game/level
         game-timer :game/level-timer} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))
        data [[:db/retract  game-db-id :game/status game-status]
              [:db/add      game-db-id :game/status :game-status/running]]]

    ;; Update :game/status
    (persistence.datomic/transact-entities! conn data)

    ;; Update :game/level
    (update-inmemory-game-level! game-id game-level)

    ;; TODO
    ;; Restore level-timer
    ;; :game/level-timer

    ;; TODO
    ;; restore start-position
    ;; (update-start-position! conn game-id start-position)

    ;; TODO
    ;; Replay to current position >> check :running-profit-loss restored <<

    ;; TODO
    ;; run-game!
    ))

(defn inputs->processing-pipeline [conn {:keys [game input-sequence

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


(defn- calculate-profitloss-and-checklevel-pipeline [op
                                                     user-db-id
                                                     {{game-id :game/id} :game

                                                      process-transact-profit-loss! :process-transact-profit-loss!
                                                      stream-portfolio-update! :stream-portfolio-update!

                                                      check-level-complete :check-level-complete
                                                      process-transact-level-update! :process-transact-level-update!
                                                      stream-level-update! :stream-level-update!}
                                                     input]

  (->> (map (partial games.processing/calculate-profit-loss op user-db-id game-id) input)
       (map process-transact-profit-loss!)
       (map stream-portfolio-update!)

       (map check-level-complete)
       (map process-transact-level-update!)
       (map stream-level-update!)))

(defn- stock-tick-and-stream-pipeline [{:keys [stock-tick-stream
                                               process-transact!
                                               stream-stock-tick]}
                                       input]

  (->> (map process-transact! input)
       (map stream-stock-tick)))

(defn stock-tick-pipeline [user-db-id {input-sequence :input-sequence :as game-control}]
  (->> (stock-tick-and-stream-pipeline game-control input-sequence)
       (calculate-profitloss-and-checklevel-pipeline :tick user-db-id game-control)))

(defn start-workbench!

  ([conn user-db-id game-control]
   (start-workbench! conn user-db-id game-control 0))

  ([conn user-db-id
    {{game-id :game/id} :game
     level-timer :level-timer-atom
     tick-sleep-atom :tick-sleep-atom
     game-event-stream :game-event-stream
     control-channel :control-channel
     :as game-control}
    start-position]

   ;; (util/pprint+identity start-position)
   ;; (util/pprint+identity (:game game-control))

   ;; A

   (update-start-position! conn game-id start-position)

   #_(core.async/go-loop [now (t/now)
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
   (let [[historical-data inputs-at-position] (->> (stock-tick-pipeline user-db-id game-control)
                                                   (seek-to-position start-position))]

     [historical-data (run-iteration inputs-at-position)])))

(defn buy-stock-pipeline

  ([game-control conn userId gameId stockId stockAmount tickId tickPrice]
   (buy-stock-pipeline conn userId gameId stockId stockAmount tickId tickPrice true))

  ([game-control conn userId gameId stockId stockAmount tickId tickPrice validate?]

   ;; (println [conn userId gameId stockId stockAmount tickId tickPrice validate?])
   (let [user-db-id  (util/extract-id (iam.persistence/user-by-external-uid conn userId))]

     (->> (buy-stock! conn user-db-id userId gameId stockId stockAmount tickId tickPrice validate?)
          list
          ;; (map #(bookkeeping/track-profit-loss+stream-portfolio-update! conn gameId game-db-id user-db-id %))
          (calculate-profitloss-and-checklevel-pipeline :buy user-db-id game-control)
          doall
          ;; util/pprint+identity
          ))))

(defn sell-stock-pipeline

  ([game-control conn userId gameId stockId stockAmount tickId tickPrice]
   (sell-stock-pipeline conn userId gameId stockId stockAmount tickId tickPrice true))

  ([game-control conn userId gameId stockId stockAmount tickId tickPrice validate?]

   (let [user-db-id  (util/extract-id (iam.persistence/user-by-external-uid conn userId))]

     (->> (sell-stock! conn user-db-id userId gameId stockId stockAmount tickId tickPrice validate?)
          list
          (calculate-profitloss-and-checklevel-pipeline :sell user-db-id game-control)
          doall))))
