(ns beatthemarket.game.games.processing
  (:require [io.pedestal.log :as log]
            [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]
            [beatthemarket.persistence.core :as persistence.core]
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

  #_(let [[butlast-chunks latest-chunk] (->> (game.persistence/profit-loss->chunks profit-loss-perstock)
                                             ((juxt butlast last)))]

      (if (latest-chunk-closed? latest-chunk)

        profit-loss-perstock

        (->> latest-chunk
             (map (partial game.persistence/recalculate-profit-loss-on-tick price))
             (concat butlast-chunks)
             flatten)))

  (map (partial game.persistence/recalculate-profit-loss-on-tick price) profit-loss-perstock))

(defn recalculate-profitloss-perstock-fn [stock-ticks profit-loss]
  (reduce-kv (fn [m k v]
               (if-let [{price :game.stock.tick/close} (stock-tick-by-id k stock-ticks)]
                 (assoc
                   m k
                   (recalculate-profit-loss-on-tick-perstock price v))
                 m))
             {}
             profit-loss))



(def profit-loss-type-entity-map
  {:running-profit-loss :profit-loss/running
   :realized-profit-loss :profit-loss/realized})

(defn game-user-by-user [conn user-id]

  (ffirst
    (d/q '[:find (pull ?u [{:game.user/_user [*]}])
           :in $ ?u
           :where
           [?u]]
         (d/db conn)
         user-id)))

(defn profit-loss->entity [conn {:keys [user-id tick-id game-id stock-id profit-loss-type profit-loss]}]

  (let [{{game-user-db-id :db/id} :game.user/_user} (game-user-by-user conn user-id)

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

  (println (format ">> TRANSACT / " (pr-str data)))
  (persistence.datomic/transact-entities! conn data)
  data)

(defn stream-stock-tick [stock-tick-stream stock-tick-pairs]

  (let [stock-ticks (group-stock-tick-pairs stock-tick-pairs)

        #_(map (fn [a]
                 (-> (update a :game.stock.tick/id str)
                     (update :game.stock/id str)))
               (group-stock-tick-pairs stock-tick-pairs))]

    (log/debug :game.games (format ">> STREAM stock-tick-pairs / %s" stock-ticks))
    (println (format ">> STREAM stock-tick-pairs / " (pr-str stock-ticks)))
    ;; (util/pprint+identity stock-tick-stream)
    ;; (util/pprint+identity stock-ticks)
    (core.async/go (core.async/>! stock-tick-stream stock-ticks))
    ;; (core.async/go (core.async/>! stock-tick-stream wtf))

    stock-ticks))


;; B.i
(defmulti calculate-profit-loss (fn [op _ _ _] op))

(defmethod calculate-profit-loss :tick [_ _ game-id stock-ticks]

  (println (format ">> calculate-profit-loss on TICK / %s" (count stock-ticks)))
  (let [updated-profit-loss-calculations
        (-> repl.state/system :game/games
            deref
            (get game-id)
            :profit-loss
            ((partial recalculate-profitloss-perstock-fn stock-ticks)))]

    (game.persistence/update-profit-loss-state! game-id updated-profit-loss-calculations)
    (hash-map :stock-ticks stock-ticks
              :profit-loss (game.calculation/collect-running-profit-loss game-id updated-profit-loss-calculations))))

(defmethod calculate-profit-loss :buy [op user-id game-id tentry]

  (println (format ">> calculate-profit-loss on BUY / " (keys tentry)))
  (let [profit-loss (game.calculation/calculate-profit-loss! op user-id tentry)]
    {:tentry tentry :profit-loss profit-loss}))

(defmethod calculate-profit-loss :sell [op user-id game-id tentry]

  (println (format ">> calculate-profit-loss on SELL / " (keys tentry)))
  (let [profit-loss (game.calculation/calculate-profit-loss! op user-id tentry)]
    {:tentry tentry :profit-loss profit-loss}))

;; B.ii
(defn process-transact-profit-loss! [conn {profit-loss :profit-loss :as data}]

  (println (format ">> TRANSACT :profit-loss / " (pr-str data)))

  (let [realized-profit-loss (->> (filter #(= :realized-profit-loss (:profit-loss-type %)) profit-loss)
                                  (map (partial profit-loss->entity conn)))]

    ;; (util/pprint+identity profit-loss)

    (when (not (empty? realized-profit-loss))
      (persistence.datomic/transact-entities! conn realized-profit-loss)))
  data)

;; B.iii
(defn stream-portfolio-update! [portfolio-update-stream {:keys [profit-loss] :as result}]

  (println (format ">> STREAM portfolio-update / " (pr-str result)))
  (let [profit-loss (->> result
                         :profit-loss
                         flatten
                         (map #(dissoc % :user-id :tick-id)))]

    (util/pprint+identity profit-loss)

    (when (not (empty? profit-loss))

      (log/debug :game.games (format ">> STREAM portfolio-update / %s" (pr-str profit-loss)))
      (core.async/go (core.async/>! portfolio-update-stream profit-loss)))

    (update result :profit-loss (constantly profit-loss))))


;; C
(defn check-level-complete [game-id control-channel current-level {:keys [profit-loss] :as result}]

  (println (format ">> CHECK level-complete / " (pr-str result)))

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

        game-event-message

        (cond-> {:game-id game-id
                 :level level
                 :profit-loss running+realized-pl}
          profit-threshold-met? (assoc :event :win)
          lose-threshold-met? (assoc :event :lose))]

    ;; (util/pprint+identity game-event-message)
    (when (:event game-event-message)
      (core.async/go (core.async/>! control-channel game-event-message))))

  (assoc result :level-update {}))

(defn process-transact-level-update! [conn {level-update :level-update :as data}]

  (println (format ">> TRANSACT :level-update / " (pr-str level-update)))
  ;; (util/pprint+identity level-update)

  (when (not (empty? level-update))
    (persistence.datomic/transact-entities! conn level-update))
  data)

(defn stream-level-update! [game-event-stream data]

  ;; #_(println (format ">> STREAM level-update! / " (pr-str data)))
  ;; (log/debug :game.games (format ">> stream-level-update! /" data))
  data)
