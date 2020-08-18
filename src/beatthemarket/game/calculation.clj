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

  profit-loss-calculation)



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

(defn calculate-realized-PL-mappingfn [latest-price
                                       {:keys [price pershare-purchase-ratio] :as calculation}]

  (let [pershare-gain-or-loss (- latest-price price)
        realized-profit-loss  (* pershare-gain-or-loss pershare-purchase-ratio)]

    {:latest-price->price   [latest-price price]
     :pershare-gain-or-loss pershare-gain-or-loss
     :realized-profit-loss  realized-profit-loss}))

(defn recalculate-PL-on-decrease-mappingfn [old-account-amount updated-stock-account-amount
                                            latest-price
                                            {:keys [price
                                                    pershare-purchase-ratio] :as calculation}]

  (let [shrinkage                       (/ updated-stock-account-amount old-account-amount)
        updated-pershare-purchase-ratio (* shrinkage pershare-purchase-ratio)
        pershare-gain-or-loss           (- latest-price price)]

    (assoc calculation
           :counter-balance-amount  updated-stock-account-amount
           :pershare-purchase-ratio updated-pershare-purchase-ratio
           :pershare-gain-or-loss   pershare-gain-or-loss
           :running-profit-loss     (* pershare-gain-or-loss updated-pershare-purchase-ratio))))


(defn update-trade-history-on-running-pl! [op game-id stock-id profit-loss profit-loss-calculation]

  (let [{updated-stock-account-amount :stock-account-amount
         latest-price                 :price} profit-loss-calculation

        profit-loss-calculation-with-direction (assoc profit-loss-calculation :counter-balance-direction op)
        updated-trade-history                  (-> (stock->profit-loss game-id stock-id)
                                                   (conj profit-loss-calculation-with-direction))]

    (->> updated-trade-history
         (map (partial recalculate-PL-on-increase-mappingfn updated-stock-account-amount latest-price))
         (replace-trade-state-for-stock! game-id stock-id))

    profit-loss-calculation))

(defn update-trade-history-on-realized-pl! [op game-id game-stock-id profit-loss profit-loss-calculation]

  (let [{updated-stock-account-amount :stock-account-amount
         latest-price                 :price} profit-loss-calculation

        profit-loss-calculation-with-direction (assoc profit-loss-calculation :counter-balance-direction op)
        updated-trade-history                  (-> (stock->profit-loss game-id game-stock-id)
                                                   (conj profit-loss-calculation-with-direction))

        realized-profit-loss (->> (map calculate-realized-PL-mappingfn updated-trade-history)
                                  util/pprint+identity
                                  (reduce (fn [ac {a :realized-profit-loss}]
                                            (+ ac a))
                                          0.0))]

    (->> updated-trade-history
         (map (partial recalculate-PL-on-decrease-mappingfn updated-stock-account-amount latest-price))
         (replace-trade-state-for-stock! game-id game-stock-id))

    [profit-loss-calculation realized-profit-loss]))

(defn- trade-opposite-of-counter-balance-direction? [op profit-loss]

  (let [direction (-> profit-loss last :counter-balance-direction)]
    (if direction
      (not (= op direction))
      false)))

(defn- counter-balance-amount-crossover? [profit-loss-calculation profit-loss]

  (let [counter-balance-amount (-> profit-loss last (get :counter-balance-amount 0))
        at-beginning?          (= 0 counter-balance-amount)]

    (if at-beginning?
      false
      (> (:amount profit-loss-calculation)
         counter-balance-amount))))

(defn realizing-profit-loss?-fn [op profit-loss profit-loss-calculation]

  (let [some-profit-loss? (not (empty? profit-loss))

        trading-in-opposite-direction-of-counter-balance-direction?
        (trade-opposite-of-counter-balance-direction? (:op profit-loss-calculation) profit-loss)]

    (and some-profit-loss?
         trading-in-opposite-direction-of-counter-balance-direction?)))

(defn crossing-counter-balance-threshold?-fn [profit-loss profit-loss-calculation]

  (let [trading-in-opposite-direction-of-counter-balance-direction?
        (trade-opposite-of-counter-balance-direction? (:op profit-loss-calculation) profit-loss)

        trade-amount-crosses-over-counter-balance-amount?
        (counter-balance-amount-crossover? profit-loss-calculation profit-loss)]

    (and trading-in-opposite-direction-of-counter-balance-direction?
         trade-amount-crosses-over-counter-balance-amount?)))



#_(defn single->merged-profit-losses! [profit-loss profit-loss-calculation]

  #_(->> profit-loss
       (map (fn [[k v]]
              (if (= game-stock-id k)
                (let [[butlast-chunks latest-chunk] (->> [profit-loss-calculation]
                                                         (concat v)
                                                         profit-loss->chunks
                                                         ((juxt butlast last)))]
                  [k (->> latest-chunk
                          (map (partial recalculate-profit-loss-on-buy stock-account-amount price))
                          (concat butlast-chunks)
                          flatten)])
                [k v])))
       (map #(apply hash-map %))
       (apply merge)))

(defmulti calculate-running-profit-loss! (fn [op _] op))

(defmethod calculate-running-profit-loss! :buy [op data]

  (let [{[{{{game-stock-id :game.stock/id} :bookkeeping.account/counter-party
            stock-account-id               :bookkeeping.account/id
            stock-account-amount           :bookkeeping.account/amount
            stock-account-name             :bookkeeping.account/name} :bookkeeping.credit/account
           price                                                     :bookkeeping.credit/price
           amount                                                    :bookkeeping.credit/amount}] :bookkeeping.tentry/credits} data

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
         :stock-account-name   stock-account-name}

        profit-loss (stock->profit-loss game-id game-stock-id)

        profit-loss-empty? (empty? profit-loss)
        realizing-profit-loss? (realizing-profit-loss?-fn op profit-loss profit-loss-calculation)
        crossing-counter-balance-threshold? (crossing-counter-balance-threshold?-fn profit-loss profit-loss-calculation)]


    ;; (util/pprint+identity [profit-loss profit-loss-calculation])
    (util/pprint+identity [profit-loss-empty? realizing-profit-loss? crossing-counter-balance-threshold?])


    (match [profit-loss-empty? realizing-profit-loss? crossing-counter-balance-threshold?]

           ;; SET :counter-balance-amount :counter-balance-direction
           ;; ADD TO in-memory trades
           ;; RETURN :running-profit-loss
           [true _ _] (create-trade-history! op game-id game-stock-id profit-loss profit-loss-calculation)

           ;; > should only happen on opposite :counter-balance-direction direction
           ;; UPDATE :counter-balance-amount :pershare-purchase-ratio :pershare-gain-or-loss :running-profit-loss
           ;; RESET in-memory trades
           ;; RETURN :realized-profit-loss
           [_ true false] (update-trade-history-on-realized-pl! op game-id game-stock-id profit-loss profit-loss-calculation)

           [_ true true] (do
                           ;; > can only happen on MARGIN
                           ;; UPDATE :counter-balance-amount :pershare-purchase-ratio :pershare-gain-or-loss :running-profit-loss
                           ;; RESET in-memory trades
                           ;; GOTO profit-loss-empty?=true
                           ;; RETURN :running-profit-loss :realized-profit-loss
                           )

           ;; > continuing to trade in :counter-balance-direction
           ;; UPDATE :counter-balance-amount :pershare-purchase-ratio :pershare-gain-or-loss :running-profit-loss
           ;; ADD TO in-memory trades
           ;; RETURN :running-profit-loss
           [false false _] (update-trade-history-on-running-pl! op game-id game-stock-id profit-loss profit-loss-calculation))

    (util/pprint+identity (game->profit-losses game-id))

    ))

(defmethod calculate-running-profit-loss! :sell [_ data])
