(ns beatthemarket.game.calculation
  (:require [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]
            [clojure.core.match :refer [match]]

            [beatthemarket.game.persistence :as game.persistence]
            [beatthemarket.util :refer [ppi] :as util]))


(defn ->profit-loss-event [user-id tick-id game-id stock-id profit-loss-type profit-loss]

  {:user-id user-id
   :tick-id tick-id

   :game-id          game-id
   :stock-id         stock-id
   :profit-loss-type profit-loss-type
   :profit-loss      profit-loss})

(defn group-by-stock [[game-id profit-losses]]

  (for [[stock-id vs] (group-by :stock-id profit-losses)
        :let [profit-loss-amount (->> (reduce (fn [ac {pl :profit-loss}]
                                                (+ ac pl))
                                              0.0
                                              vs)
                                      (format "%.2f") (Float.))]]

    (->profit-loss-event nil nil game-id stock-id :realized-profit-loss profit-loss-amount)))

(defn ->user [email name external-uid game]

  (hash-map
    :user/email email
    :user/name name
    :user/external-uid external-uid
    :user/game game))

(defn ->game-status [id status profit-loss]

  (hash-map
    :game/id id
    :game/status status
    :game.user/profit-loss profit-loss))


(defn collect-realized-profit-loss-all-users-allgames

  ([conn]

   (collect-realized-profit-loss-all-users-allgames conn false))

  ([conn group-by-stock?]

   (->> (d/q '[:find
               (pull ?g [:game/id
                         {:game/status [:db/ident]}])
               (pull ?gus [{:game.user/user [:db/id
                                             :user/email
                                             :user/name
                                             :user/external-uid]}
                           {:game.user/profit-loss [*]}])
               :where
               [?g :game/id]
               [?g :game/users ?gus]
               [?gus :game.user/user ?userDbId]
               [?gus :game.user/profit-loss ?pls]]
             (d/db conn))

        (map (fn [[{game-id :game/id
                   {game-status :db/ident} :game/status :as game-pulled}

                  {{user-db-id :db/id
                    email :user/email
                    name :user/name
                    external-uid :user/external-uid} :game.user/user
                   game-user-profit-loss :game.user/profit-loss
                   :as game-user-pulled}]]

               (let [profit-loss (map (fn [{{tick-id :game.stock.tick/id} :game.user.profit-loss/tick
                                           {stock-id :game.stock/id} :game.user.profit-loss/stock
                                           amount :game.user.profit-loss/amount}]

                                        (->profit-loss-event user-db-id tick-id game-id stock-id :realized-profit-loss amount))
                                      game-user-profit-loss)

                     profit-loss-possibly-grouped (if group-by-stock?
                                                    (group-by-stock [game-id profit-loss])
                                                    profit-loss)

                     game (->game-status game-id game-status profit-loss-possibly-grouped)]

                 (->user email name external-uid game)))))))

(defn collect-realized-profit-loss-allgames

  ([conn user-id]

   (collect-realized-profit-loss-allgames conn user-id  false))

  ([conn user-id group-by-stock?]

   (let [db-result (->> (d/q '[:find ?gameId (pull ?pls [*])
                               :in $ ?user-id
                               :where
                               [?g :game/id ?gameId]
                               [?g :game/users ?gus]
                               [?gus :game.user/user ?user-id]
                               [?gus :game.user/profit-loss ?pls]]
                             (d/db conn)
                             user-id)
                        (map (fn [[game-id
                                  {{tick-id :game.stock.tick/id} :game.user.profit-loss/tick
                                   {stock-id :game.stock/id} :game.user.profit-loss/stock
                                   amount :game.user.profit-loss/amount}]]
                               (->profit-loss-event user-id tick-id game-id stock-id :realized-profit-loss amount))))

         game-grouping (for [[game-id vs] (group-by :game-id db-result)]
                         [game-id vs])]

     (if (not group-by-stock?)

       (->> (map #(apply hash-map %) game-grouping)
            (apply merge))

       (->> (map group-by-stock game-grouping)
            (apply merge))))))

(defn collect-realized-profit-loss-pergame

  ([conn user-id game-id]

   (collect-realized-profit-loss-pergame conn user-id game-id false))

  ([conn user-id game-id group-by-stock?]

   (let [result (->> (d/q '[:find (pull ?pls [*])
                            :in $ ?gameId ?user-id
                            :where
                            [?g :game/id ?gameId]
                            [?g :game/users ?gus]
                            [?gus :game.user/user ?user-id]
                            [?gus :game.user/profit-loss ?pls]]
                          (d/db conn)
                          game-id user-id)
                     (map first)
                     (map (fn [{{tick-id :game.stock.tick/id} :game.user.profit-loss/tick
                               {stock-id :game.stock/id} :game.user.profit-loss/stock
                               amount :game.user.profit-loss/amount}]
                            (->profit-loss-event user-id tick-id game-id stock-id :realized-profit-loss amount))))]

     (if (not group-by-stock?)

       result

       (for [[stock-id vs] (group-by :stock-id result)
             :let [profit-loss-amount (->> (reduce (fn [ac {pl :profit-loss}]
                                                     (+ ac pl))
                                                   0.0
                                                   vs)
                                           (format "%.2f") (Float.))]]

         (->profit-loss-event nil nil game-id stock-id :realized-profit-loss profit-loss-amount))))))

(defn collect-running-profit-loss

  ([game-id]
   (collect-running-profit-loss game-id (-> repl.state/system :game/games deref (get game-id) :profit-loss)))

  ([game-id profit-loss]

   #_(ppi [game-id profit-loss])
   (let [profit-loss-type :running-profit-loss]
     (for [[user-db-id pls] profit-loss
           [stock-id vs] pls
           :let [profit-loss-amount (->> (reduce (fn [ac {pl profit-loss-type}]
                                                   (+ ac pl))
                                                 0.0
                                                 vs)
                                         (format "%.2f")
                                         (Float.))]]

       (->profit-loss-event nil nil game-id stock-id profit-loss-type profit-loss-amount)))))

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

(defn running-profit-loss-for-game [game-id]
  (-> repl.state/system :game/games deref (get game-id) :profit-loss))

(defn realized-profit-loss-for-game [conn user-id game-id]

  (->> (d/q '[:find (pull ?pls [*])
              :in $ ?gameId ?user-id
              :where
              [?g :game/id ?gameId]
              [?g :game/users ?gus]
              [?gus :game.user/user ?user-id]
              [?gus :game.user/profit-loss ?pls]]
            (d/db conn)
            game-id user-id)
       (map first)
       (map (fn [{{tick-id :game.stock.tick/id} :game.user.profit-loss/tick
                 {stock-id :game.stock/id} :game.user.profit-loss/stock
                 amount :game.user.profit-loss/amount}]
              (->profit-loss-event user-id tick-id game-id stock-id :realized-profit-loss amount)))))

(defn user-stock->profit-loss [user-id game-id game-stock-id]
  (-> repl.state/system :game/games deref (get game-id) :profit-loss (get user-id) (get game-stock-id [])))

(defn update-trade-state-for-user-stock! [user-id game-id stock-id profit-loss profit-loss-calculation]

  (swap! (:game/games repl.state/system)
         (fn [gs]
           (update-in gs [game-id :profit-loss user-id]
                      (fn [pl] (assoc pl stock-id (conj profit-loss profit-loss-calculation)))))))

(defn replace-trade-state-for-stock! [user-id game-id stock-id profit-loss]

  (swap! (:game/games repl.state/system)
         (fn [gs]
           (update-in gs [game-id :profit-loss user-id]
                      (fn [pl] (assoc pl stock-id profit-loss))))))

(defn create-trade-history! [op user-id game-id stock-id profit-loss profit-loss-calculation]

  (->> (assoc profit-loss-calculation
              :counter-balance-amount (:stock-account-amount profit-loss-calculation) ;; (fluctuates on trade)
              :counter-balance-direction op)
       (update-trade-state-for-user-stock! user-id game-id stock-id profit-loss))

  ;; [profit-loss-calculation]

  [(collect-running-profit-loss game-id)])


;; trade batch increase
(defn recalculate-PL-on-increase-mappingfn [op
                                            updated-stock-account-amount
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
           :counter-balance-direction op
           :running-profit-loss running-profit-loss)))


;; trade batch  decrease
(defn calculate-resolved-PL-mappingfn [latest-price
                                       {:keys [price pershare-purchase-ratio counter-balance-amount] :as calculation}]

  (let [pershare-gain-or-loss (- latest-price price)
        A                     (* pershare-gain-or-loss pershare-purchase-ratio)
        resolved-profit-loss  (* A counter-balance-amount)]

    {:latest-price->price     [latest-price price]
     :pershare-purchase-ratio pershare-purchase-ratio
     :pershare-gain-or-loss   pershare-gain-or-loss
     :realized-profit-loss    resolved-profit-loss}))

(defn calculate-resolved-PL [op user-id tick-id game-id game-stock-id profit-loss profit-loss-calculation]

  (let [{updated-stock-account-amount :stock-account-amount
         latest-price                 :price} profit-loss-calculation
        trade-history (user-stock->profit-loss user-id game-id game-stock-id)]

    (->> (map (partial calculate-resolved-PL-mappingfn latest-price) trade-history)
         (reduce (fn [ac {a :realized-profit-loss}]
                   (+ ac a))
                 0.0)
         (->profit-loss-event user-id tick-id game-id game-stock-id :realized-profit-loss))))

(defn calculate-realized-PL-mappingfn [latest-price
                                       {:keys [amount price pershare-purchase-ratio] :as calculation}]

  (let [pershare-gain-or-loss (- latest-price price)
        A                     (* pershare-gain-or-loss pershare-purchase-ratio)
        realized-profit-loss  (* A amount)]

    {:latest-price->price     [latest-price price]
     :pershare-purchase-ratio pershare-purchase-ratio
     :pershare-gain-or-loss   pershare-gain-or-loss
     :realized-profit-loss    realized-profit-loss}))

(defn calculate-realized-PL [op user-id tick-id game-id game-stock-id profit-loss profit-loss-calculation]

  (let [{updated-stock-account-amount :stock-account-amount
         latest-price                 :price} profit-loss-calculation
        trade-history (user-stock->profit-loss user-id game-id game-stock-id)]

    (->> (map (partial calculate-realized-PL-mappingfn latest-price) trade-history)
         (reduce (fn [ac {a :realized-profit-loss}]
                   (+ ac a))
                 0.0)
         (->profit-loss-event user-id tick-id game-id game-stock-id :realized-profit-loss))))

(defn recalculate-PL-on-decrease-mappingfn [updated-stock-account-amount
                                            latest-price
                                            {:keys [price counter-balance-amount
                                                    pershare-purchase-ratio] :as calculation}]

  (let [updated-stock-account-amount (Math/abs updated-stock-account-amount)
        old-account-amount           (Math/abs counter-balance-amount)

        shrinkage                       (/ updated-stock-account-amount old-account-amount)
        updated-pershare-purchase-ratio (* shrinkage pershare-purchase-ratio)
        pershare-gain-or-loss           (- latest-price price)
        A                               (* pershare-gain-or-loss updated-pershare-purchase-ratio)
        running-profit-loss             (* A updated-stock-account-amount)]

    (assoc calculation
           :latest-price->price     [latest-price price]
           :shrinkage               shrinkage

           :pershare-purchase-ratio updated-pershare-purchase-ratio
           :pershare-gain-or-loss   pershare-gain-or-loss
           :counter-balance-amount  updated-stock-account-amount
           :running-profit-loss     running-profit-loss)))

(defn update-trade-history-on-running-pl! [op user-id game-id stock-id profit-loss profit-loss-calculation]

  (let [{updated-stock-account-amount :stock-account-amount
         latest-price                 :price} profit-loss-calculation

        ;; profit-loss-calculation-with-direction (assoc profit-loss-calculation :counter-balance-direction op)
        updated-trade-history                  (-> (user-stock->profit-loss user-id game-id stock-id)
                                                   (conj profit-loss-calculation))

        updated-profit-loss (map (partial recalculate-PL-on-increase-mappingfn op updated-stock-account-amount latest-price)
                                 updated-trade-history)
        running-profit-loss (collect-running-profit-loss game-id)]

    (replace-trade-state-for-stock! user-id game-id stock-id updated-profit-loss)

    [running-profit-loss]))

(defn update-trade-history-on-realized-pl! [op user-id tick-id game-id stock-id profit-loss profit-loss-calculation]

  (let [{updated-stock-account-amount :stock-account-amount
         latest-price                 :price} profit-loss-calculation

        updated-trade-history (user-stock->profit-loss user-id game-id stock-id)
        #_(-> (user-stock->profit-loss user-id game-id stock-id)
              (conj (assoc profit-loss-calculation :counter-balance-amount updated-stock-account-amount)))

        updated-profit-loss (map (partial recalculate-PL-on-decrease-mappingfn updated-stock-account-amount latest-price)
                                 updated-trade-history)

        realized-profit-loss (calculate-realized-PL op user-id tick-id game-id stock-id profit-loss profit-loss-calculation)
        running-profit-loss (collect-running-profit-loss game-id)]

    (replace-trade-state-for-stock! user-id game-id stock-id updated-profit-loss)

    [running-profit-loss realized-profit-loss]))

(defn reset-trade-history-on-resolved-pl! [op user-id tick-id game-id game-stock-id profit-loss profit-loss-calculation]

  (let [{updated-stock-account-amount :stock-account-amount
         latest-price                 :price} profit-loss-calculation

        realized-profit-loss (calculate-resolved-PL op user-id tick-id game-id game-stock-id profit-loss profit-loss-calculation)]

    ;; (ppi "WTF / Resolved !!")
    ;; (ppi profit-loss)
    ;; (ppi profit-loss-calculation)


    (replace-trade-state-for-stock! user-id game-id game-stock-id [])

    [realized-profit-loss]))

(defn reset-trade-history-on-crossover-pl! [op user-id tick-id game-id game-stock-id profit-loss profit-loss-calculation]

  (let [{updated-stock-account-amount :stock-account-amount
         latest-price                 :price} profit-loss-calculation

        running-profit-loss-calculations [(assoc profit-loss-calculation
                                                 :pershare-purchase-ratio 1
                                                 :counter-balance-amount updated-stock-account-amount
                                                 :counter-balance-direction op)]
        realized-profit-loss (calculate-realized-PL op user-id tick-id game-id game-stock-id profit-loss profit-loss-calculation)
        running-profit-loss (collect-running-profit-loss game-id)]

    #_(ppi "WTF / Crossover !!")
    #_(ppi profit-loss)
    #_(ppi profit-loss-calculation)

    (replace-trade-state-for-stock! user-id game-id game-stock-id running-profit-loss-calculations)

    [running-profit-loss realized-profit-loss]))


;; dispatch predicates
(defn- trade-opposite-of-counter-balance-direction? [op profit-loss]

  (let [direction (-> profit-loss last :counter-balance-direction)]

    #_(ppi [:trade-opposite-of-counter-balance-direction? [op profit-loss]
                             (and direction (not (= op direction)))])

    (and direction (not (= op direction)))))

(defn- realizing-profit-loss?-fn [op profit-loss profit-loss-calculation]

  (let [some-profit-loss? (not (empty? profit-loss))

        trading-in-opposite-direction-of-counter-balance-direction?
        (trade-opposite-of-counter-balance-direction? (:op profit-loss-calculation) profit-loss)]

    (and some-profit-loss?
         trading-in-opposite-direction-of-counter-balance-direction?)))

(defn- matching-counter-balance-threshold?-fn [profit-loss profit-loss-calculation]

  (if (empty? profit-loss)

    false

    (let [trading-in-opposite-direction-of-counter-balance-direction?
          (trade-opposite-of-counter-balance-direction? (:op profit-loss-calculation) profit-loss)


          trade-amount-matches-counter-balance-amount? (= (-> profit-loss-calculation :amount (#(Math/abs %)))
                                                          (-> profit-loss last (get :counter-balance-amount) (#(Math/abs %))))]

      (and trading-in-opposite-direction-of-counter-balance-direction?
           trade-amount-matches-counter-balance-amount?))))

(defn- crossing-counter-balance-threshold?-fn [profit-loss profit-loss-calculation]

  (if (empty? profit-loss)

    false

    (let [trading-in-opposite-direction-of-counter-balance-direction?
          (trade-opposite-of-counter-balance-direction? (:op profit-loss-calculation) profit-loss)


          trade-amount-crosses-over-counter-balance-amount? (> (-> profit-loss-calculation :amount (#(Math/abs %)))
                                                               (-> profit-loss last (get :counter-balance-amount) (#(Math/abs %))))]

      (and trading-in-opposite-direction-of-counter-balance-direction?
           trade-amount-crosses-over-counter-balance-amount?))))

(defn calculate-profit-loss-common! [op user-id tick-id game-id stock-id profit-loss-calculation]

  (let [profit-loss (user-stock->profit-loss user-id game-id stock-id)

        ;; i.
        profit-loss-empty? (empty? profit-loss)

        ;; ii.
        realizing-profit-loss? (realizing-profit-loss?-fn op profit-loss profit-loss-calculation)

        ;; iii.
        matching-counter-balance-threshold? (matching-counter-balance-threshold?-fn profit-loss profit-loss-calculation)

        ;; iv
        crossing-counter-balance-threshold? (crossing-counter-balance-threshold?-fn profit-loss profit-loss-calculation)]


    (ppi [profit-loss-empty? realizing-profit-loss? matching-counter-balance-threshold? crossing-counter-balance-threshold?])

    (match [profit-loss-empty? realizing-profit-loss? matching-counter-balance-threshold? crossing-counter-balance-threshold?]

           ;; SET :counter-balance-amount :counter-balance-direction
           ;; ADD TO in-memory trades
           ;; RETURN :running-profit-loss
           [true _ _ _] (create-trade-history! op user-id game-id stock-id profit-loss profit-loss-calculation)

           ;; > should only happen on opposite :counter-balance-direction direction
           ;; UPDATE :counter-balance-amount :pershare-purchase-ratio :pershare-gain-or-loss :running-profit-loss
           ;; RESET in-memory trades
           ;; RETURN :realized-profit-loss
           [_ true false false] (update-trade-history-on-realized-pl! op user-id tick-id game-id stock-id profit-loss profit-loss-calculation)

           ;; RESET in-memory trades

           ;; > if crossing over (can only happen on MARGIN)
           ;; UPDATE :counter-balance-amount :pershare-purchase-ratio :pershare-gain-or-loss :running-profit-loss
           ;; GOTO profit-loss-empty?=true
           ;; RETURN :running-profit-loss :realized-profit-loss
           [_ true true false] (reset-trade-history-on-resolved-pl! op user-id tick-id game-id stock-id profit-loss profit-loss-calculation)

           [_ true false true] (reset-trade-history-on-crossover-pl! op user-id tick-id game-id stock-id profit-loss profit-loss-calculation)

           ;; > continuing to trade in :counter-balance-direction
           ;; UPDATE :counter-balance-amount :pershare-purchase-ratio :pershare-gain-or-loss :running-profit-loss
           ;; ADD TO in-memory trades
           ;; RETURN :running-profit-loss
           [false false _ _] (update-trade-history-on-running-pl! op user-id game-id stock-id profit-loss profit-loss-calculation))))

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
        pershare-purchase-ratio (if-not (zero? stock-account-amount) (/ amount stock-account-amount) 0)
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
