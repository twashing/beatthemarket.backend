(ns beatthemarket.handler.graphql.encoder
  (:require [com.walmartlabs.lacinia.schema :as lacinia.schema]
            [beatthemarket.util :as util]))


(def game-level-map
  {"one" :game-level/one
   "two" :game-level/two
   "three" :game-level/three
   "four" :game-level/four
   "five" :game-level/five
   "six" :game-level/six
   "seven" :game-level/seven
   "eight" :game-level/eight
   "nine" :game-level/nine
   "ten" :game-level/ten
   "market" :game-level/market})

(defn stock-tick->graphql [data]
  (clojure.set/rename-keys
    data {:game.stock.tick/id         :stockTickId
          :game.stock.tick/trade-time :stockTickTime
          :game.stock.tick/close      :stockTickClose
          :game.stock/id              :stockId
          :game.stock/name            :stockName}))

(defn profit-loss->graphql [data]

  (-> data
      (clojure.set/rename-keys {:game-id          :gameId
                                :stock-id         :stockId
                                :profit-loss      :profitLoss
                                :profit-loss-type :profitLossType})
      (update :profitLossType #(if (= :realized-profit-loss %)
                                 :realized
                                 :running))))


(defn tag-with-type-wrapped [a]
  (let [t (:type a)]
    (lacinia.schema/tag-with-type a t)))




(defmulti game-event->graphql :event)

(defmethod game-event->graphql :pause [game-event]
  (tag-with-type-wrapped
    (clojure.set/rename-keys game-event {:game-id :gameId})))

(defmethod game-event->graphql :resume [game-event]
  (tag-with-type-wrapped
    (clojure.set/rename-keys game-event {:game-id :gameId})))

(defmethod game-event->graphql :exit [game-event]
  (tag-with-type-wrapped
    (clojure.set/rename-keys game-event {:game-id :gameId})))

(defmethod game-event->graphql :continue [game-event]

  (-> (clojure.set/rename-keys game-event {:game-id :gameId
                                           :remaining-in-minutes :minutesRemaining
                                           :remaining-in-seconds :secondsRemaining})
      (update :level #((clojure.set/map-invert game-level-map) %))
      tag-with-type-wrapped))




(defmulti portfolio-update->graphql #(cond (:bookkeeping.account/id %) :AccountBalance
                                           (:profit-loss-type %) :ProfitLoss))

(defmethod portfolio-update->graphql :AccountBalance [portfolio-update]

  (-> (assoc portfolio-update
             :bookkeeping.account/id (-> portfolio-update :bookkeeping.account/id str)
             :bookkeeping.account/counter-party (-> portfolio-update :bookkeeping.account/counter-party :game.stock/name)
             :type :AccountBalance)
      (clojure.set/rename-keys {:bookkeeping.account/id :id
                                :bookkeeping.account/name :name
                                :bookkeeping.account/balance :balance
                                :bookkeeping.account/amount :amount
                                :bookkeeping.account/counter-party :counterParty})
      tag-with-type-wrapped))

(defmethod portfolio-update->graphql :ProfitLoss [portfolio-update]

  (let [transform-profit-loss-types #({:running-profit-loss :running
                                       :realized-profit-loss :realized} %)]

    (-> (assoc portfolio-update
               :game-id (-> portfolio-update :game-id str)
               :stock-id (-> portfolio-update :stock-id str)
               :profit-loss-type (-> portfolio-update :profit-loss-type transform-profit-loss-types)
               :type :ProfitLoss)
        (clojure.set/rename-keys {:game-id :gameId
                                  :stock-id :stockId
                                  :profit-loss-type :profitLossType
                                  :profit-loss :profitLoss})
        tag-with-type-wrapped)))
