(ns beatthemarket.game.games.processing
  (:require [io.pedestal.log :as log]
            [integrant.repl.state :as repl.state]
            [com.rpl.specter :refer [select transform ALL MAP-VALS MAP-KEYS]]
            [datomic.client.api :as d]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.game.persistence :as game.persistence]
            [beatthemarket.game.games.state :as game.games.state]
            [clojure.core.async :as core.async]
            [beatthemarket.util :refer [ppi] :as util]))


(defn ->level-status [event gameId profitLoss level]
  {:event event
   :game-id gameId
   :profit-loss profitLoss
   :level level})

(defn group-stock-tick-pairs [stock-tick-pairs]
  (->> (partition 2 stock-tick-pairs)
       (map (fn [[tick stock]]
              (merge (select-keys tick [:game.stock.tick/id :game.stock.tick/trade-time :game.stock.tick/close])
                     (select-keys stock [:game.stock/id :game.stock/name]))))))

(defn stock-tick-by-id [id stock-ticks]
  (first (filter #(= id (:game.stock/id %))
                 stock-ticks)))

(defn latest-chunk-closed? [latest-chunk]
  (-> latest-chunk last :stock-account-amount (= 0)))

(defn recalculate-profit-loss-on-tick-perstock [price profit-loss-perstock]

  (map (partial game.persistence/recalculate-profit-loss-on-tick price) profit-loss-perstock))

(defn recalculate-profitloss-perstock-fn [stock-ticks profit-loss]

  (let [transform-fn (fn [[stock-id v]]

                       (let [{price :game.stock.tick/close :as stock-tick} (stock-tick-by-id stock-id stock-ticks)
                             uv (recalculate-profit-loss-on-tick-perstock price v)]
                         [stock-id uv]))]

    (transform [MAP-VALS ALL] transform-fn profit-loss)))

(def profit-loss-type-entity-map
  {:running-profit-loss :profit-loss/running
   :realized-profit-loss :profit-loss/realized})

(defn profit-loss->entity [conn {:keys [user-id tick-id game-id stock-id profit-loss-type profit-loss]}]

  (let [{game-user-db-id :db/id} (ffirst (iam.persistence/game-user-by-user conn user-id game-id))

        tick-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game.stock.tick/id tick-id))
        stock-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game.stock/id stock-id))
        game-db-id  (util/extract-id (persistence.core/entity-by-domain-id conn :game/id game-id))

        profit-loss-entity (persistence.core/bind-temporary-id
                             {:game.user.profit-loss/amount profit-loss
                              :game.user.profit-loss/tick tick-db-id
                              :game.user.profit-loss/stock stock-db-id
                              :game.user.profit-loss/type (get profit-loss-type-entity-map profit-loss-type)})]

    {:db/id game-user-db-id
     :game.user/profit-loss profit-loss-entity}))


;; A
(defn process-transact! [conn data]

  (log/debug :game.games.processing (format ">> TRANSACT / " (pr-str data)))
  (persistence.datomic/transact-entities! conn data)
  data)

(defn stream-stock-tick [stock-tick-stream stock-ticks]

  (log/info :game.games.processing (format ">> STREAM stock-tick-pairs / %s" stock-ticks))
  (log/debug :game.games.processing (format ">> STREAM stock-tick-pairs / " (pr-str stock-ticks)))
  ;; (ppi stock-tick-stream)
  ;; (ppi stock-ticks)
  (core.async/go (core.async/>! stock-tick-stream stock-ticks))

  stock-ticks)


;; B.i
(defmulti calculate-profit-loss (fn [op _ _ _] op))

(defmethod calculate-profit-loss :tick [_ _ game-id stock-ticks]

  (log/debug :game.games.processing (format ">> calculate-profit-loss on TICK / " (pr-str stock-ticks)))
  (let [updated-profit-loss-calculations
        (-> (game.games.state/inmemory-game-by-id game-id)
            :profit-loss
            ((partial recalculate-profitloss-perstock-fn stock-ticks)))]

    #_(ppi [:updated-profit-loss-calculations updated-profit-loss-calculations])
    (game.persistence/update-profit-loss-state! game-id updated-profit-loss-calculations)
    (hash-map :stock-ticks stock-ticks
              :profit-loss (game.calculation/collect-running-profit-loss game-id updated-profit-loss-calculations))))

(defmethod calculate-profit-loss :buy [op user-id game-id tentry]

  (log/debug :game.games.processing (format ">> calculate-profit-loss on BUY / " (keys tentry)))
  (let [profit-loss (game.calculation/calculate-profit-loss! op user-id tentry)]
    {:tentry tentry :profit-loss profit-loss}))

(defmethod calculate-profit-loss :sell [op user-id game-id tentry]

  (log/debug :game.games.processing (format ">> calculate-profit-loss on SELL / " (keys tentry)))
  (let [profit-loss (game.calculation/calculate-profit-loss! op user-id tentry)]
    {:tentry tentry :profit-loss profit-loss}))

;; B.ii
(defn process-transact-profit-loss! [conn {profit-loss :profit-loss :as data}]

  (log/debug :game.games.processing (format ">> TRANSACT :profit-loss / " (pr-str data)))
  ;; (ppi data)
  (let [realized-profit-loss (->> (filter #(= :realized-profit-loss (:profit-loss-type %)) profit-loss)
                                  (map (partial profit-loss->entity conn)))]

    ;; (ppi [:realized-profit-loss realized-profit-loss])
    (when (not (empty? realized-profit-loss))
      (persistence.datomic/transact-entities! conn realized-profit-loss)))
  data)

;; B.iii
(defn stream-portfolio-update! [portfolio-update-stream {:keys [profit-loss] :as data}]

  (log/debug :game.games.processing (format ">> STREAM portfolio-update / " (pr-str data)))
  (let [profit-loss (->> data
                         :profit-loss
                         flatten
                         (map #(dissoc % :user-id :tick-id)))]

    ;; (ppi profit-loss)

    (when (not (empty? profit-loss))

      (log/debug :game.games.processing (format ">> STREAM portfolio-update / " (pr-str profit-loss)))
      (core.async/go (core.async/>! portfolio-update-stream profit-loss)))

    (update data :profit-loss (constantly profit-loss))))


;; C
(defn- level->source-and-destination* [level]

  (->> repl.state/config :game/game :levels seq
       (sort-by (comp :order second))
       (partition 2 1)
       (filter (fn [[[level-name _] r]] (= level level-name)))
       first))

(defn- update-inmemory-game-level!* [game-id level]

  (let [[[source-level-name _ :as source]
         [dest-level-name dest-level-config :as dest]] (level->source-and-destination* level)]

    ;; (log/debug :game.games.processing "Site B: Updating new level in memory / " dest-level-name)
    (swap! (:game/games repl.state/system)
           (fn [gs]
             (update-in gs [game-id :current-level] (-> dest-level-config
                                                        (assoc :level dest-level-name)
                                                        (dissoc :order)
                                                        atom
                                                        constantly))))))

(defn check-level-complete [conn user-db-id game-id control-channel {:keys [profit-loss] :as data}]

  (log/debug :game.games.processing (format ">> CHECK level-complete / " (pr-str data)))
  ;; (log/debug :game.games.processing [:check-level-complete user-db-id game-id control-channel data])
  ;; (ppi profit-loss)

  (let [current-level (:current-level (game.games.state/inmemory-game-by-id game-id))

        {profit-threshold :profit-threshold
         lose-threshold :lose-threshold
         level :level} (deref current-level)

        running-pl (if-let [pl (-> profit-loss first :profit-loss)]
                     pl
                     0.0)

        realized-pl (reduce (fn [ac {pl :profit-loss}]
                              (+ ac pl))
                            0.0
                            (game.calculation/realized-profit-loss-for-game conn user-db-id game-id))

        profit-threshold-met? (> realized-pl profit-threshold)
        lose-threshold-met? (< running-pl (* -1 lose-threshold))

        game-event-message (cond-> (->level-status nil game-id nil level)
                             profit-threshold-met? (assoc :event :win
                                                          :profit-loss realized-pl)
                             lose-threshold-met? (assoc :event :lose
                                                        :profit-loss running-pl))]

    (ppi [[:running running-pl (* -1 lose-threshold) lose-threshold-met?]
            [:realized realized-pl (> realized-pl profit-threshold)]
            [:current-level (deref current-level)]])


    (when (:event game-event-message)
      ;;(ppi game-event-message)
      (when (= :win (:event game-event-message))
        (update-inmemory-game-level!* game-id level))
      (core.async/go (core.async/>! control-channel game-event-message))))

  (assoc data :level-update {}))

(defn process-transact-level-update! [conn {level-update :level-update :as data}]

  ;; (log/debug :game.games.processing (format ">> TRANSACT :level-update / " (pr-str level-update)))
  ;; (ppi level-update)
  #_(when (not (empty? level-update))
    (persistence.datomic/transact-entities! conn level-update))
  data)

(defn stream-level-update! [game-event-stream data]

  (log/debug :game.games.processing (format ">> STREAM level-update! / " (pr-str data)))
  ;; (log/debug :game.games.processing (format ">> stream-level-update! /" data))
  data)
