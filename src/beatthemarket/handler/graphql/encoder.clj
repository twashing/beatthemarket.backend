(ns beatthemarket.handler.graphql.encoder
  (:require [com.walmartlabs.lacinia.schema :as lacinia.schema]))


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

#_:ControlEvent
#_{:description "Possible control events that the client can send to the server"
   :fields
   {:event {:type (non-null :ControlEventType)}
    :gameId {:type (non-null String)}}}


#_:LevelTimer
#_{:description "Timer countdown events, streamed from server to client"
   :fields
   {:gameId {:type (non-null String)}
    :level {:type (non-null String)}
    :minutesRemaining {:type (non-null Int)}
    :secondsRemaining {:type (non-null Int)}}}


#_:LevelStatus
#_{:description "Possible level status updates that the server can send to the client"
   :fields
   {:event {:type (non-null :LevelStatusType)}
    :gameId {:type (non-null String)}
    :profitLoss {:type (non-null String)}
    :level {:type (non-null String)} }}

#_:ProfitLoss
#_{:description "Container for portfolio updates"
   :fields
   {:profitLoss {:type (non-null Float)}
    :stockId    {:type (non-null String)}
    :gameId     {:type (non-null String)}
    :profitLossType {:type (non-null :ProfitLossType)}}}

#_:AccountBalance
#_{:description "Account Balance message"
   :fields
   {:id           {:type (non-null String)}
    :name         {:type (non-null String)}
    :balance      {:type (non-null Float)}
    :counterParty {:type String :description "Name of the stock attached to this account"}
    :amount       {:type Int :description "The amount of units (stock shares) of the counterparty"}}}
