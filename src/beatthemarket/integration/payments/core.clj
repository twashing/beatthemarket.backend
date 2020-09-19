(ns beatthemarket.integration.payments.core
  (:require [integrant.repl.state :as repl.state]
            [beatthemarket.game.persistence :as game.persistence]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.util :as util]))


(def subscriptions #{"margin_trading_1month"})
(def products #{"additional_100k"
                "additional_200k"
                "additional_300k"
                "additional_400k"
                "additional_5_minutes"})

#_(defn apply-payment-functionality! [provider user-payments]

  ;; provider-comparator (keyword "payment.provider" provider)
  ;; {provider-type :db/ident} :payment/provider-type

  user-payments)

(defn credit-cash-account [amount account]
  (update account :bookkeeping.account/balance #(+ % amount)))

(defmulti apply-feature (fn [feature conn email payment-entity game-entity] feature))

(defmethod apply-feature :margin_trading_1month [_ conn email
                                                 payment-entity
                                                 {game-id :game/id :as game-entity}]

  ;; TODO store margin trading in DB
  #_(swap! (:game/games repl.state/system)
         (fn [gs]
           (update-in gs [game-id :level-timer] (constantly time-in-seconds))))
  )

(defmethod apply-feature :additional_100k [_ conn email
                                           payment-entity
                                           {game-id :game/id :as game-entity}]

  (->> (game.persistence/cash-account-for-user-game conn email game-id)
       ffirst
       (credit-cash-account 100000.0)
       (persistence.datomic/transact-entities! conn)))

(defmethod apply-feature :additional_200k [_ conn email
                                           payment-entity
                                           {game-id :game/id :as game-entity}]

  (->> (game.persistence/cash-account-for-user-game conn email game-id)
       ffirst
       (credit-cash-account 200000.0)
       (persistence.datomic/transact-entities! conn)))

(defmethod apply-feature :additional_300k [_ conn email
                                           payment-entity
                                           {game-id :game/id :as game-entity}]

  (->> (game.persistence/cash-account-for-user-game conn email game-id)
       ffirst
       (credit-cash-account 300000.0)
       (persistence.datomic/transact-entities! conn)))

(defmethod apply-feature :additional_400k [_ conn email
                                           payment-entity
                                           {game-id :game/id :as game-entity}]

  (->> (game.persistence/cash-account-for-user-game conn email game-id)
       ffirst
       (credit-cash-account 400000.0)
       (persistence.datomic/transact-entities! conn)))

;; TODO
(defmethod apply-feature :additional_5_minutes [_ conn email payment-entity game-entity])



(defn subscription-lookup [subscriptions]
  (clojure.set/map-invert subscriptions))

(defn product-lookup [products]
  (clojure.set/map-invert products))


(comment

  (let [product-id "prod_I1RCtpy369Bu4g"
        {{subscriptions :subscriptions
          products :products} :payment.provider/stripe} repl.state/system]

    (cond
      ((subscription-lookup subscriptions) product-id) :a
      ((product-lookup products) product-id) :b)))
