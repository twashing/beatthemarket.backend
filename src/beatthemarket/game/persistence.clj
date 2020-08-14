(ns beatthemarket.game.persistence
  (:require [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]
            [beatthemarket.util :as util])
  (:import [java.util UUID]))


(defn update-profit-loss-state! [game-id updated-profit-loss-calculations]

  (swap! (:game/games repl.state/system)
         (fn [gs]
           (update-in gs [game-id :profit-loss] (constantly updated-profit-loss-calculations)))))

(defn recalculate-profit-loss-on-tick [latest-price
                                       {:keys [trade-price
                                               stock-account-amount
                                               pershare-purchase-ratio] :as calculation}]

  ;; NOTE Poor man's filter to just recalculate running P/L
  (if (and trade-price stock-account-amount pershare-purchase-ratio)

    (let [pershare-gain-or-loss (- latest-price trade-price)
          A                     (* pershare-gain-or-loss pershare-purchase-ratio)
          running-profit-loss   (* A stock-account-amount)]

      (assoc calculation
             :latest-price->trade-price [latest-price trade-price]
             :pershare-gain-or-loss     pershare-gain-or-loss
             :running-profit-loss       running-profit-loss))

    calculation))

(defn recalculate-profit-loss-on-buy [updated-stock-account-amount
                                      latest-trade-price
                                      {:keys [amount trade-price] :as calculation}]

  (let [pershare-purchase-ratio (/ amount updated-stock-account-amount)
        pershare-gain-or-loss   (- latest-trade-price trade-price)]

    (assoc calculation
           :latest-price->trade-price     [latest-trade-price trade-price]
           :pershare-gain-or-loss         pershare-gain-or-loss)))

(defn recalculate-profit-loss-on-sell [old-account-amount updated-stock-account-amount latest-trade-price
                                       {:keys [amount trade-price] :as calculation}]

  (let [pershare-gain-or-loss (- latest-trade-price trade-price)]

    (if (= 0 updated-stock-account-amount)

      (assoc calculation
             :latest-price->trade-price     [latest-trade-price trade-price]
             :pershare-gain-or-loss         pershare-gain-or-loss)

      (let [account-amount-new-old-ratio (/ updated-stock-account-amount old-account-amount)
            pershare-purchase-ratio (/ (* amount account-amount-new-old-ratio) updated-stock-account-amount)
            A                       (* pershare-gain-or-loss pershare-purchase-ratio)]

        (assoc calculation
               :pershare-purchase-ratio   pershare-purchase-ratio
               :latest-price->trade-price [latest-trade-price trade-price]
               :pershare-gain-or-loss     pershare-gain-or-loss)))))

(defn game-id-by-account-id [conn account-id]
  (-> (d/q '[:find (pull ?e [{:game.user/_accounts
                              [{:game/_users [:game/id]}]}])
             :in $ ?account-id
             :where
             [?e :bookkeeping.account/id ?account-id]]
           (d/db conn)
           account-id)
      flatten first
      :game.user/_accounts :game/_users :game/id))

(defn profit-loss->chunks [profit-loss]
  (->> profit-loss
       (partition-by #(= 0 (:stock-account-amount %)))
       (partition-all 2)
       (map flatten)))

(defn calculate-running-aggregate-profit-loss-on-BUY! [data]

  (let [{[{{{game-stock-id :game.stock/id} :bookkeeping.account/counter-party
            credit-account-id              :bookkeeping.account/id
            stock-account-amount           :bookkeeping.account/amount
            credit-account-name            :bookkeeping.account/name} :bookkeeping.credit/account
           price                                                      :bookkeeping.credit/price
           amount                                                     :bookkeeping.credit/amount}] :bookkeeping.tentry/credits} data]

    ;; Calculate i. pershare price ii. pershare amount (Purchase amt / total amt)
    (when credit-account-id
      (let [conn (-> repl.state/system :persistence/datomic :opts :conn)

            game-id                 (game-id-by-account-id conn credit-account-id)
            pershare-gain-or-loss   (- price price)
            pershare-purchase-ratio (/ amount stock-account-amount)
            A                       (* pershare-gain-or-loss pershare-purchase-ratio)
            running-profit-loss     (* A stock-account-amount)

            profit-loss-calculation
            {:op                   :BUY
             :credit-account-id    credit-account-id
             :stock-account-amount stock-account-amount
             :credit-account-name  credit-account-name

             :latest-price->trade-price [price price]
             :trade-price               price
             :amount                    amount

             :pershare-gain-or-loss   pershare-gain-or-loss
             :pershare-purchase-ratio pershare-purchase-ratio
             :A                       A}

            profit-loss
            (as-> (deref (:game/games repl.state/system)) gs
              (get gs game-id)
              (:profit-loss gs)
              (assoc gs game-stock-id (get gs game-stock-id [])))

            updated-profit-loss-calculations
            (->> profit-loss
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
                 (apply merge))]

        (update-profit-loss-state! game-id updated-profit-loss-calculations)))))

(defn calculate-running-aggregate-profit-loss-on-SELL! [data]

  (let [{[{{{;; price-history :game.stock/price-history
             game-stock-id :game.stock/id}           :bookkeeping.account/counter-party
            debit-account-id                         :bookkeeping.account/id
            stock-account-amount                     :bookkeeping.account/amount
            debit-account-name                       :bookkeeping.account/name} :bookkeeping.debit/account
           price                                               :bookkeeping.debit/price
           amount                                              :bookkeeping.debit/amount}] :bookkeeping.tentry/debits} data]

    ;; Calculate i. pershare price ii. pershare amount (Purchase amt / total amt)
    (when debit-account-id
      (let [conn    (-> repl.state/system :persistence/datomic :opts :conn)
            game-id (game-id-by-account-id conn debit-account-id)

            pershare-gain-or-loss                 (- price price)
            realized-profit-loss                  (* pershare-gain-or-loss amount)
            old-account-amount                    (+ amount stock-account-amount)

            profit-loss-calculation
            {:op                   :SELL
             :debit-account-id     debit-account-id
             :stock-account-amount stock-account-amount
             :debit-account-name   debit-account-name

             :latest-price->trade-price [price price]
             :trade-price  price
             :amount       amount}

            profit-loss
            (as-> (deref (:game/games repl.state/system)) gs
              (get gs game-id)
              (:profit-loss gs)
              (assoc gs game-stock-id (get gs game-stock-id [])))

            calculate-realized-profit-loss
            (fn [chunk]
              (let [[buys {sell-amount :amount :as sell}] ((juxt butlast last) chunk)

                    realized-profit-loss
                    (->> (reduce (fn [ac {:keys [pershare-purchase-ratio pershare-gain-or-loss]}]
                                   (+ ac (* sell-amount (* pershare-purchase-ratio pershare-gain-or-loss))))
                                 0.0
                                 buys)
                         (format "%.2f")
                         (Float.))]
                (concat buys [(assoc sell :realized-profit-loss realized-profit-loss)])))

            updated-profit-loss-calculations
            (->> profit-loss
                 (map (fn [[k v]]
                        (if (= game-stock-id k)
                          (let [[butlast-chunks latest-chunk] (->> [profit-loss-calculation]
                                                                   (concat v)
                                                                   profit-loss->chunks
                                                                   ((juxt butlast last)))]
                            [k (->> latest-chunk
                                    (map (partial recalculate-profit-loss-on-sell old-account-amount stock-account-amount price))
                                    calculate-realized-profit-loss
                                    (concat butlast-chunks)
                                    flatten)])
                          [k v])))
                 (map #(apply hash-map %))
                 (apply merge))]

        (update-profit-loss-state! game-id updated-profit-loss-calculations)))))


;; > Profit Calculation Use Cases
;;
;; Single buy / sell (multiple times)
;; Multiple buy / single sell
;; Multiple buy / multiple sell (multiple times)
(defn track-profit-loss! [data]

  (let [buys-fn (comp :bookkeeping.account/counter-party :bookkeeping.credit/account first :bookkeeping.tentry/credits)
        sells-fn (comp :bookkeeping.account/counter-party :bookkeeping.debit/account first :bookkeeping.tentry/debits)]

    (cond

      ;; collect BUYS by stock account
      (buys-fn data) (calculate-running-aggregate-profit-loss-on-BUY! data)

      ;; collect SELLS by stock account
      (sells-fn data) (calculate-running-aggregate-profit-loss-on-SELL! data))

    data))
