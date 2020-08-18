(ns beatthemarket.game.games.processing
  (:require [io.pedestal.log :as log]
            [integrant.repl.state :as repl.state]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.game.persistence :as game.persistence]
            [clojure.core.async :as core.async]
            [beatthemarket.util :as util]))



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

  (let [[butlast-chunks latest-chunk] (->> (game.persistence/profit-loss->chunks profit-loss-perstock)
                                           ((juxt butlast last)))]

    (if (latest-chunk-closed? latest-chunk)

      profit-loss-perstock

      (->> latest-chunk
           (map (partial game.persistence/recalculate-profit-loss-on-tick price))
           (concat butlast-chunks)
           flatten))))

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




(defn process-transact! [conn data]

  (println (format ">> TRANSACT / " (pr-str data)))
  (persistence.datomic/transact-entities! conn data)
  data)

(defn process-transact-profit-loss! [conn {profit-loss :profit-loss :as data}]

  (println (format ">> TRANSACT :profit-loss / " (pr-str profit-loss)))
  (when (not (empty? profit-loss))
    (persistence.datomic/transact-entities! conn profit-loss))
  data)

(defn process-transact-level-update! [conn {level-update :level-update :as data}]

  (println (format ">> TRANSACT :level-update / " (pr-str level-update)))
  (when (not (empty? level-update))
    (persistence.datomic/transact-entities! conn level-update))
  data)


(defn stream-stock-tick [stock-tick-stream stock-tick-pairs]

  (let [stock-ticks (group-stock-tick-pairs stock-tick-pairs)]

    ;; (log/debug :game.games (format ">> STREAM stock-tick-pairs / %s" stock-ticks))
    (println (format ">> STREAM stock-tick-pairs / " (pr-str stock-ticks)))
    ;; (core.async/go (core.async/>! stock-tick-stream stock-ticks))
    stock-ticks))

#_(defn calculate-profit-loss [game-id stock-ticks]

    (println (format ">> calculate-profit-loss / %s" (count stock-ticks)))
    (let [updated-profit-loss-calculations
          (-> repl.state/system :game/games
              deref
              (get game-id)
              :profit-loss
              ((partial recalculate-profitloss-perstock-fn stock-ticks)))]

      (->> updated-profit-loss-calculations
           (game.persistence/update-profit-loss-state! game-id)
           (#(get % game-id))
           :profit-loss
           #_(hash-map :stock-ticks stock-ticks :profit-loss))))

(defmulti calculate-profit-loss (fn [op _ _] op))

(defmethod calculate-profit-loss :tick [_ game-id stock-ticks]

  (println (format ">> calculate-profit-loss on TICK / " (count stock-ticks)))
  {:stock-ticks stock-ticks :profit-loss {}})

(defmethod calculate-profit-loss :buy [op game-id tentry]


  (println (format ">> calculate-profit-loss on BUY / " (keys tentry)))
  (game.calculation/calculate-running-profit-loss! op tentry)

  {:tentry tentry :profit-loss {}})

(defmethod calculate-profit-loss :sell [op game-id tentry]


  (println (format ">> calculate-profit-loss on SELL / " (keys tentry)))
  (game.calculation/calculate-running-profit-loss! op tentry)

  {:tentry tentry :profit-loss {}})

#_(defn collect-profit-loss [game-id {:keys [profit-loss] :as result}]

  result
  #_(->> (game.calculation/collect-running-profit-loss game-id profit-loss)
       (assoc result :profit-loss)))

(defn stream-portfolio-update! [portfolio-update-stream {:keys [profit-loss] :as result}]

  (println (format ">> STREAM portfolio-update / " (pr-str result)))

  #_(when (not (empty? profit-loss))

    (log/debug :game.games (format ">> STREAM portfolio-update / %s" (pr-str profit-loss)))
    (core.async/go (core.async/>! portfolio-update-stream profit-loss)))
  result)

(defn check-level-complete [game-id control-channel current-level {:keys [profit-loss] :as result}]

  (println (format ">> CHECK level-complete / " (pr-str result)))
  #_(let [{profit-threshold :profit-threshold
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
      (core.async/go (core.async/>! control-channel game-event-message))))

  (assoc result :level-update {}))

(defn stream-level-update! [game-event-stream data]

  (println (format ">> STREAM level-update! / " (pr-str data)))
  ;; (log/debug :game.games (format ">> stream-level-update! /" data))
  data)
