(ns beatthemarket.game.persistence
  (:require [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]
            [beatthemarket.util :as util]))


(def profit-loss (atom {}))

(defn track-profit-loss-by-stock-id! [game-id stock-id updated-profit-loss-calculations]
  (swap! (:game/games repl.state/system)
         #(update-in % [game-id :profit-loss stock-id] (constantly updated-profit-loss-calculations))))

(defn recalculate-profit-loss-on-buy [updated-credit-account-amount
                                      {:keys [amount pershare-gain-or-loss] :as calculation}]

  (let [pershare-purchase-ratio (/ amount updated-credit-account-amount)
        A                       (* pershare-gain-or-loss pershare-purchase-ratio)]

    (assoc calculation :running-aggregate-profit-loss (* A updated-credit-account-amount))))

(defn recalculate-profit-loss-on-sell [old-account-amount updated-debit-account-amount
                                      {:keys [amount pershare-gain-or-loss] :as calculation}]

  (let [account-amount-new-old-ratio (/ updated-debit-account-amount old-account-amount)
        pershare-purchase-ratio (/ (* amount account-amount-new-old-ratio) updated-debit-account-amount)
        A                       (* pershare-gain-or-loss pershare-purchase-ratio)]

    (assoc calculation :running-aggregate-profit-loss (* A updated-debit-account-amount))))

(defn game-id-by-account-id [conn account-id]
  (-> (d/q '[:find (pull ?e [{:user/_accounts
                              [{:game.user/_user
                                [{:game/_users [:game/id]}]}]}])
             :in $ ?account-id
             :where
             [?e :bookkeeping.account/id ?account-id]]
           (d/db conn)
           account-id)
      flatten first
      :user/_accounts :game.user/_user :game/_users :game/id))

(defn calculate-running-aggregate-profit-loss-on-BUY! [data]

  (let [tentry-buys-by-account
        (filter (comp :bookkeeping.account/counter-party :bookkeeping.credit/account :bookkeeping.tentry/credits)
                data)

        [{{{{price-history :game.stock/price-history
             game-stock-id :game.stock/id}            :bookkeeping.account/counter-party
            credit-account-id                         :bookkeeping.account/id
            credit-account-amount                     :bookkeeping.account/amount
            credit-account-name                       :bookkeeping.account/name} :bookkeeping.credit/account
           price                                               :bookkeeping.credit/price
           amount                                              :bookkeeping.credit/amount} :bookkeeping.tentry/credits}]
        tentry-buys-by-account]

    ;; Calculate i. pershare price ii. pershare amount (Purchase amt / total amt)
    (when credit-account-id
      (let [conn (-> repl.state/system :persistence/datomic :opts :conn)
            game-id (game-id-by-account-id conn credit-account-id)

            {latest-price :game.stock.tick/close} (->> price-history (sort-by :game.stock.tick/trade-time) last)
            pershare-gain-or-loss                 (- latest-price price)
            pershare-purchase-ratio               (/ amount credit-account-amount)
            A                                     (* pershare-gain-or-loss pershare-purchase-ratio)
            running-profit-loss                   (* A credit-account-amount)

            profit-loss-calculation
            {:credit-account-id     credit-account-id
             :credit-account-amount credit-account-amount
             :credit-account-name   credit-account-name

             :latest-price latest-price
             :buy-price    price
             :amount       amount

             :pershare-gain-or-loss         pershare-gain-or-loss
             :pershare-purchase-ratio       pershare-purchase-ratio
             :A                             A
             :running-profit-loss           running-profit-loss
             :running-aggregate-profit-loss running-profit-loss}

            profit-loss (-> repl.state/system :game/games deref (get game-id) :profit-loss)
            updated-profit-loss-calculations
            (as-> profit-loss pl
              (get pl game-stock-id)
              (map (partial recalculate-profit-loss-on-buy credit-account-amount) pl)
              (concat pl [profit-loss-calculation]))]

        (track-profit-loss-by-stock-id! game-id game-stock-id updated-profit-loss-calculations)))))

(defn calculate-running-aggregate-profit-loss-on-SELL! [data]

  (let [tentry-sells-by-account
        (filter (comp :bookkeeping.account/counter-party :bookkeeping.debit/account :bookkeeping.tentry/debits)
                data)

        [{{{{price-history :game.stock/price-history
             game-stock-id :game.stock/id}           :bookkeeping.account/counter-party
            debit-account-id                         :bookkeeping.account/id
            debit-account-amount                     :bookkeeping.account/amount
            debit-account-name                       :bookkeeping.account/name} :bookkeeping.debit/account
           price                                               :bookkeeping.debit/price
           amount                                              :bookkeeping.debit/amount} :bookkeeping.tentry/debits}]
        tentry-sells-by-account]

    ;; Calculate i. pershare price ii. pershare amount (Purchase amt / total amt)
    (when debit-account-id
      (let [conn (-> repl.state/system :persistence/datomic :opts :conn)
            game-id (game-id-by-account-id conn debit-account-id)

            {latest-price :game.stock.tick/close} (->> price-history (sort-by :game.stock.tick/trade-time) last)
            pershare-gain-or-loss                 (- latest-price price)
            realized-profit-loss                  (* pershare-gain-or-loss amount)
            old-account-amount                    (+ amount debit-account-amount)

            profit-loss-calculation
            {:debit-account-id     debit-account-id
             :debit-account-amount debit-account-amount
             :debit-account-name   debit-account-name

             :latest-price latest-price
             :sell-price   price
             :amount       amount

             :pershare-gain-or-loss pershare-gain-or-loss
             :realized-profit-loss  realized-profit-loss}

            profit-loss (-> repl.state/system :game/games deref (get game-id) :profit-loss)
            updated-profit-loss-calculations
            (as-> profit-loss pl
              (get pl game-stock-id)
              (map (partial recalculate-profit-loss-on-sell old-account-amount debit-account-amount) pl)
              (concat pl [profit-loss-calculation]))]

        (track-profit-loss-by-stock-id! game-id game-stock-id updated-profit-loss-calculations)))))

(defn track-profit-loss! [data]

  (let [buys-fn (comp :bookkeeping.account/counter-party :bookkeeping.credit/account :bookkeeping.tentry/credits first)
        sells-fn (comp :bookkeeping.account/counter-party :bookkeeping.debit/account :bookkeeping.tentry/debits first)]

    (cond

      ;; collect BUYS by stock account
      (buys-fn data) (util/pprint+identity (calculate-running-aggregate-profit-loss-on-BUY! data))

      ;; collect SELLS by stock account
      (sells-fn data) (util/pprint+identity (calculate-running-aggregate-profit-loss-on-SELL! data)))))
