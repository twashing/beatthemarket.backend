(ns beatthemarket.handler.graphql.encoder
  (:require [com.walmartlabs.lacinia.schema :as lacinia.schema]
            [beatthemarket.util :refer [ppi] :as util]))


(def game-level-map
  {1 :game-level/one
   2 :game-level/two
   3 :game-level/three
   4 :game-level/four
   5 :game-level/five
   6 :game-level/six
   7 :game-level/seven
   8 :game-level/eight
   9 :game-level/nine
   10 :game-level/ten
   100 :game-level/market})

(def payment-provider-map
  {:payment.provider/apple "apple"
   :payment.provider/google "google"
   :payment.provider/stripe "stripe"})

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
      (update :gameId str)
      (update :profitLossType #(if (= :realized-profit-loss %)
                                 :realized
                                 :running))))


(defn tag-with-type-wrapped [a]
  (let [t (:type a)]
    (lacinia.schema/tag-with-type a t)))


(defmulti game-event->graphql :event)

(defmethod game-event->graphql :pause [game-event]
  (-> (clojure.set/rename-keys game-event {:game-id :gameId})
      (update :gameId str)
      tag-with-type-wrapped))

(defmethod game-event->graphql :resume [game-event]
  (-> (clojure.set/rename-keys game-event {:game-id :gameId})
      (update :gameId str)
      tag-with-type-wrapped))

(defmethod game-event->graphql :exit [game-event]
  (-> (clojure.set/rename-keys game-event {:game-id :gameId})
      (update :gameId str)
      tag-with-type-wrapped))

(defmethod game-event->graphql :continue [game-event]

  (-> (clojure.set/rename-keys game-event {:game-id              :gameId
                                           :remaining-in-minutes :minutesRemaining
                                           :remaining-in-seconds :secondsRemaining})
      (update :gameId str)
      (update :level #((clojure.set/map-invert game-level-map) %))
      tag-with-type-wrapped))

(defmethod game-event->graphql :win [game-event]

  (-> (clojure.set/rename-keys game-event {:game-id     :gameId
                                           :profit-loss :profitLoss})
      (update :gameId str)
      (update :level #((clojure.set/map-invert game-level-map) %))
      tag-with-type-wrapped))

(defmethod game-event->graphql :lose [game-event]

  (-> (clojure.set/rename-keys game-event {:game-id     :gameId
                                           :profit-loss :profitLoss})
      (update :gameId str)
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
        (update :gameId str)
        tag-with-type-wrapped)))


(defn payment-purchase->graphql [{payment-id                :payment/id
                                  product-id                :payment/product-id
                                  {provider-type :db/ident} :payment/provider-type
                                  :as                       payment}]
  {:paymentId (str payment-id)
   :productId product-id
   :provider (get payment-provider-map provider-type)})

(defn stripe-customer->graphql [customer]
  (select-keys customer [:id :email]))
