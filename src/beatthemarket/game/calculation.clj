(ns beatthemarket.game.calculation
  (:require [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]
            [clojure.core.match :refer [match]]

            [beatthemarket.game.persistence :as game.persistence]
            [beatthemarket.util :as util]))


(defn collect-profit-loss

  ([profit-loss-type game-id]
   (collect-profit-loss profit-loss-type game-id (-> repl.state/system :game/games deref (get game-id) :profit-loss)))

  ([profit-loss-type game-id profit-loss]

   (for [[k vs] profit-loss]
     {:game-id          game-id
      :stock-id         k
      :profit-loss-type profit-loss-type
      :profit-loss      (->> (filter profit-loss-type vs)
                             (reduce (fn [ac {pl profit-loss-type}]
                                       (+ ac pl))
                                     0.0)
                             (format "%.2f") (Float.))})))

(defn collect-realized-profit-loss

  ([game-id]
   (collect-realized-profit-loss game-id (-> repl.state/system :game/games deref (get game-id) :profit-loss)))

  ([game-id profit-loss]
   (collect-profit-loss :realized-profit-loss game-id profit-loss)))

(defn collect-running-profit-loss

  ([game-id]
   (collect-running-profit-loss game-id (-> repl.state/system :game/games deref (get game-id) :profit-loss)))

  ([game-id profit-loss]
   (collect-profit-loss :running-profit-loss game-id profit-loss)))

(defn collect-account-balances [conn game-id user-id]

  (->> (d/q '[:find (pull ?ua [:bookkeeping.account/id
                               :bookkeeping.account/name
                               :bookkeeping.account/balance
                               :bookkeeping.account/amount
                               {:bookkeeping.account/counter-party
                                [:game.stock/name]}])
              :in $ ?game-id ?user-id
              :where
              [?game-id]
              [?game-id :game/users ?gus]
              [?gus :game.user/user ?user-id]
              [?gus :game.user/accounts ?ua]]
            (d/db conn)
            game-id user-id)
       (map first)))


(defn game->profit-losses [game-id]

  (as-> (deref (:game/games repl.state/system)) gs
    (get gs game-id)
    (:profit-loss gs)))

(defn stock->profit-loss [game-id game-stock-id]
  (-> (game->profit-losses game-id)
      (get game-stock-id [])))

(defn update-trade-state-for-stock! [game-id stock-id profit-loss profit-loss-calculation]

  (swap! (:game/games repl.state/system)
         (fn [gs]
           (update-in gs [game-id :profit-loss]
                      (fn [pl] (assoc pl stock-id (conj profit-loss profit-loss-calculation)))))))

(defn replace-trade-state-for-stock! [game-id stock-id profit-loss]

  (swap! (:game/games repl.state/system)
         (fn [gs]
           (update-in gs [game-id :profit-loss]
                      (fn [pl] (assoc pl stock-id profit-loss))))))

(defn create-trade-history! [op game-id stock-id profit-loss profit-loss-calculation]

  (->> (assoc profit-loss-calculation
              :counter-balance-amount (:stock-account-amount profit-loss-calculation) ;; (fluctuates on trade)
              :counter-balance-direction op)
       (update-trade-state-for-stock! game-id stock-id profit-loss))

  ;; [profit-loss-calculation]

  [(collect-running-profit-loss game-id)])


;; trade batch increase
(defn recalculate-PL-on-increase-mappingfn [updated-stock-account-amount
                                            latest-price
                                            {:keys [amount price] :as calculation}]

  ;; NOTE Calculaing difference between A and B
  ;; (- -25 -20) ;; -5
  ;; (- -10 14)  ;; -24
  ;; (- 25 20)   ;; 5
  (let [amount (Math/abs amount)
        updated-stock-account-amount (Math/abs updated-stock-account-amount)

        pershare-purchase-ratio (/ amount updated-stock-account-amount)
        pershare-gain-or-loss   (- latest-price price)
        A                       (* pershare-gain-or-loss pershare-purchase-ratio)
        running-profit-loss     (* A updated-stock-account-amount)]

    (assoc calculation
           :latest-price->price     [latest-price price]

           :pershare-purchase-ratio pershare-purchase-ratio
           :pershare-gain-or-loss pershare-gain-or-loss
           :counter-balance-amount updated-stock-account-amount
           :running-profit-loss running-profit-loss)))


;; trade batch  decrease
(defn ->proft-loss-event [user-id tick-id game-id stock-id profit-loss-type profit-loss]

  {:user-id user-id
   :tick-id tick-id

   :game-id          game-id
   :stock-id         stock-id
   :profit-loss-type profit-loss-type
   :profit-loss      profit-loss})

(defn calculate-realized-PL-mappingfn [latest-price
                                       {:keys [price pershare-purchase-ratio] :as calculation}]

  (let [pershare-gain-or-loss (- latest-price price)
        realized-profit-loss  (* pershare-gain-or-loss pershare-purchase-ratio)]

    {:latest-price->price   [latest-price price]
     :pershare-purchase-ratio pershare-purchase-ratio
     :pershare-gain-or-loss pershare-gain-or-loss
     :realized-profit-loss  realized-profit-loss}))

(defn calculate-realized-PL [op user-id tick-id game-id game-stock-id profit-loss profit-loss-calculation]

  (let [{updated-stock-account-amount :stock-account-amount
         latest-price                 :price} profit-loss-calculation
        trade-history (stock->profit-loss game-id game-stock-id)]

    (->> (map (partial calculate-realized-PL-mappingfn latest-price) trade-history)
         ;; util/pprint+identity
         (reduce (fn [ac {a :realized-profit-loss}]
                   (+ ac a))
                 0.0)
         (->proft-loss-event user-id tick-id game-id game-stock-id :realized-profit-loss)
         ;; util/pprint+identity
         )))

(defn recalculate-PL-on-decrease-mappingfn [updated-stock-account-amount
                                            latest-price
                                            {:keys [price counter-balance-amount
                                                    pershare-purchase-ratio] :as calculation}]

  (let [updated-stock-account-amount (Math/abs updated-stock-account-amount)
        old-account-amount (Math/abs counter-balance-amount)

        shrinkage                       (/ updated-stock-account-amount old-account-amount)
        updated-pershare-purchase-ratio (* shrinkage pershare-purchase-ratio)
        pershare-gain-or-loss           (- latest-price price)
        running-profit-loss             (* pershare-gain-or-loss updated-pershare-purchase-ratio)]

    (assoc calculation
           :latest-price->price     [latest-price price]
           :shrinkage               shrinkage

           :pershare-purchase-ratio updated-pershare-purchase-ratio
           :pershare-gain-or-loss   pershare-gain-or-loss
           :counter-balance-amount  updated-stock-account-amount
           :running-profit-loss     running-profit-loss)))

(defn update-trade-history-on-running-pl! [op game-id stock-id profit-loss profit-loss-calculation]

  (let [{updated-stock-account-amount :stock-account-amount
         latest-price                 :price} profit-loss-calculation

        profit-loss-calculation-with-direction (assoc profit-loss-calculation :counter-balance-direction op)
        updated-trade-history                  (-> (stock->profit-loss game-id stock-id)
                                                   (conj profit-loss-calculation-with-direction))

        updated-profit-loss (map (partial recalculate-PL-on-increase-mappingfn updated-stock-account-amount latest-price)
                                 updated-trade-history)
        running-profit-loss (collect-running-profit-loss game-id)]

    (replace-trade-state-for-stock! game-id stock-id updated-profit-loss)

    [running-profit-loss]))

(defn update-trade-history-on-realized-pl! [op user-id tick-id game-id game-stock-id profit-loss profit-loss-calculation]

  (let [{updated-stock-account-amount :stock-account-amount
         latest-price                 :price} profit-loss-calculation

        trade-history (stock->profit-loss game-id game-stock-id)

        updated-profit-loss (map (partial recalculate-PL-on-decrease-mappingfn updated-stock-account-amount latest-price)
                                 trade-history)

        realized-profit-loss (calculate-realized-PL op user-id tick-id game-id game-stock-id profit-loss profit-loss-calculation)
        running-profit-loss (collect-running-profit-loss game-id)]

    (replace-trade-state-for-stock! game-id game-stock-id updated-profit-loss)

    [running-profit-loss realized-profit-loss]))


;; RESET in-memory trades

;; > if crossing over (can only happen on MARGIN)
;; UPDATE :counter-balance-amount :pershare-purchase-ratio :pershare-gain-or-loss :running-profit-loss
;; GOTO profit-loss-empty?=true
;; RETURN :running-profit-loss :realized-profit-loss

(defn reset-trade-history-on-realized-pl! [op user-id tick-id game-id game-stock-id profit-loss profit-loss-calculation]

  (let [{updated-stock-account-amount :stock-account-amount
         latest-price                 :price} profit-loss-calculation

        trade-history (stock->profit-loss game-id game-stock-id)
        realized-profit-loss (calculate-realized-PL op user-id tick-id game-id game-stock-id profit-loss profit-loss-calculation)]

    #_(util/pprint+identity "WTF / Realized !!")
    #_(util/pprint+identity profit-loss)
    #_(util/pprint+identity profit-loss-calculation)


    (replace-trade-state-for-stock! game-id game-stock-id [])

    [realized-profit-loss]))

(defn reset-trade-history-on-crossover-pl! [op user-id tick-id game-id game-stock-id profit-loss profit-loss-calculation]

  (let [{updated-stock-account-amount :stock-account-amount
         latest-price                 :price} profit-loss-calculation

        trade-history (stock->profit-loss game-id game-stock-id)

        running-profit-loss-calculations [(assoc profit-loss-calculation :pershare-purchase-ratio 1)]
        realized-profit-loss (calculate-realized-PL op user-id tick-id game-id game-stock-id profit-loss profit-loss-calculation)
        running-profit-loss (collect-running-profit-loss game-id)]

    #_(util/pprint+identity "WTF / Crossover !!")
    #_(util/pprint+identity profit-loss)
    #_(util/pprint+identity profit-loss-calculation)

    (replace-trade-state-for-stock! game-id game-stock-id running-profit-loss-calculations)

    [running-profit-loss realized-profit-loss]))


;; dispatch predicates
(defn- trade-opposite-of-counter-balance-direction? [op profit-loss]

  (let [direction (-> profit-loss last :counter-balance-direction)]
    (if direction
      (not (= op direction))
      false)))

(defn- counter-balance-amount-match-or-crossover? [profit-loss-calculation profit-loss]

  (let [counter-balance-amount (-> profit-loss last (get :counter-balance-amount 0))
        at-beginning?          (= 0 counter-balance-amount)]

    (if at-beginning?
      false
      (>= (:amount profit-loss-calculation)
          counter-balance-amount))))

(defn- realizing-profit-loss?-fn [op profit-loss profit-loss-calculation]

  (let [some-profit-loss? (not (empty? profit-loss))

        trading-in-opposite-direction-of-counter-balance-direction?
        (trade-opposite-of-counter-balance-direction? (:op profit-loss-calculation) profit-loss)]

    (and some-profit-loss?
         trading-in-opposite-direction-of-counter-balance-direction?)))

(defn- match-or-crossing-counter-balance-threshold?-fn [profit-loss profit-loss-calculation]

  (let [trading-in-opposite-direction-of-counter-balance-direction?
        (trade-opposite-of-counter-balance-direction? (:op profit-loss-calculation) profit-loss)

        trade-amount-crosses-over-counter-balance-amount?
        (counter-balance-amount-match-or-crossover? profit-loss-calculation profit-loss)]

    (and trading-in-opposite-direction-of-counter-balance-direction?
         trade-amount-crosses-over-counter-balance-amount?)))

(defn calculate-profit-loss-common! [op user-id tick-id game-id stock-id profit-loss-calculation]

  (let [profit-loss (stock->profit-loss game-id stock-id)

        profit-loss-empty? (empty? profit-loss)
        realizing-profit-loss? (realizing-profit-loss?-fn op profit-loss profit-loss-calculation)
        match-or-crossing-counter-balance-threshold?
        (match-or-crossing-counter-balance-threshold?-fn profit-loss profit-loss-calculation)]

    ;; (util/pprint+identity [profit-loss-empty? realizing-profit-loss? match-or-crossing-counter-balance-threshold?])

    (let [result (match [profit-loss-empty? realizing-profit-loss? match-or-crossing-counter-balance-threshold?]

                        ;; SET :counter-balance-amount :counter-balance-direction
                        ;; ADD TO in-memory trades
                        ;; RETURN :running-profit-loss
                        [true _ _] (create-trade-history! op game-id stock-id profit-loss profit-loss-calculation)

                        ;; > should only happen on opposite :counter-balance-direction direction
                        ;; UPDATE :counter-balance-amount :pershare-purchase-ratio :pershare-gain-or-loss :running-profit-loss
                        ;; RESET in-memory trades
                        ;; RETURN :realized-profit-loss
                        [_ true false] (update-trade-history-on-realized-pl! op user-id tick-id game-id stock-id profit-loss profit-loss-calculation)

                        ;; RESET in-memory trades

                        ;; > if crossing over (can only happen on MARGIN)
                        ;; UPDATE :counter-balance-amount :pershare-purchase-ratio :pershare-gain-or-loss :running-profit-loss
                        ;; GOTO profit-loss-empty?=true
                        ;; RETURN :running-profit-loss :realized-profit-loss
                        [_ true true] (if (= (:amount profit-loss-calculation) (-> profit-loss last (get :counter-balance-amount 0)))
                                        (reset-trade-history-on-realized-pl! op user-id tick-id game-id stock-id profit-loss profit-loss-calculation)
                                        (reset-trade-history-on-crossover-pl! op user-id tick-id game-id stock-id profit-loss profit-loss-calculation))

                        ;; > continuing to trade in :counter-balance-direction
                        ;; UPDATE :counter-balance-amount :pershare-purchase-ratio :pershare-gain-or-loss :running-profit-loss
                        ;; ADD TO in-memory trades
                        ;; RETURN :running-profit-loss
                        [false false _] (update-trade-history-on-running-pl! op game-id stock-id profit-loss profit-loss-calculation))]

      #_(util/pprint+identity (game->profit-losses game-id))
      result)))

(defmulti calculate-profit-loss! (fn [op _ _] op))

(defmethod calculate-profit-loss! :buy [op user-id data]

  (let [{[{{{game-stock-id :game.stock/id} :bookkeeping.account/counter-party
            stock-account-id               :bookkeeping.account/id
            stock-account-amount           :bookkeeping.account/amount
            stock-account-name             :bookkeeping.account/name} :bookkeeping.credit/account
           {tick-id :game.stock.tick/id}                              :bookkeeping.credit/tick
           price                                                      :bookkeeping.credit/price
           amount                                                     :bookkeeping.credit/amount}] :bookkeeping.tentry/credits} data

        conn                    (-> repl.state/system :persistence/datomic :opts :conn)
        game-id                 (game.persistence/game-id-by-account-id conn stock-account-id)
        pershare-gain-or-loss   (- price price)
        pershare-purchase-ratio (/ amount stock-account-amount)
        A                       (* pershare-gain-or-loss pershare-purchase-ratio)
        running-profit-loss     (* A stock-account-amount)

        profit-loss-calculation
        {:op     :buy
         :amount amount
         :price  price

         :pershare-purchase-ratio pershare-purchase-ratio ;; (fluctuates on trade)
         :pershare-gain-or-loss   pershare-gain-or-loss   ;; (fluctuates on tick)
         :running-profit-loss     running-profit-loss

         :stock-account-id     stock-account-id
         :stock-account-amount stock-account-amount
         :stock-account-name   stock-account-name}]

    (calculate-profit-loss-common! op user-id tick-id game-id game-stock-id profit-loss-calculation)))

(defmethod calculate-profit-loss! :sell [op user-id data]

  (let [{[{{{game-stock-id :game.stock/id} :bookkeeping.account/counter-party
            stock-account-id               :bookkeeping.account/id
            stock-account-amount           :bookkeeping.account/amount
            stock-account-name             :bookkeeping.account/name} :bookkeeping.debit/account
           {tick-id :game.stock.tick/id}                              :bookkeeping.debit/tick
           price                                                      :bookkeeping.debit/price
           amount                                                     :bookkeeping.debit/amount}] :bookkeeping.tentry/debits} data

        conn                    (-> repl.state/system :persistence/datomic :opts :conn)
        game-id                 (game.persistence/game-id-by-account-id conn stock-account-id)
        pershare-gain-or-loss   (- price price)
        pershare-purchase-ratio (if (zero? stock-account-amount)
                                  stock-account-amount
                                  (/ amount stock-account-amount))
        A                       (* pershare-gain-or-loss pershare-purchase-ratio)
        running-profit-loss     (* A stock-account-amount)

        profit-loss-calculation
        {:op     :sell
         :amount amount
         :price  price

         :pershare-purchase-ratio pershare-purchase-ratio ;; (fluctuates on trade)
         :pershare-gain-or-loss   pershare-gain-or-loss   ;; (fluctuates on tick)
         :running-profit-loss     running-profit-loss

         :stock-account-id     stock-account-id
         :stock-account-amount stock-account-amount
         :stock-account-name   stock-account-name}]

    (calculate-profit-loss-common! op user-id tick-id game-id game-stock-id profit-loss-calculation)))
