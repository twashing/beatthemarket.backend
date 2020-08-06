(ns beatthemarket.bookkeeping.core
  (:require [datomic.client.api :as d]
            [com.rpl.specter :refer [select transform pred ALL MAP-VALS]]
            [rop.core :as rop]
            [integrant.repl.state :as repl.state]
            [beatthemarket.bookkeeping.persistence :as bookkeeping.persistence]
            [beatthemarket.game.persistence :as game.persistence]
            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.util :as util :refer [exists?]]
            [clojure.core.async :as core.async])
  (:import [java.util UUID]))


(defn ->portfolio

  ([] (->portfolio nil))

  ([journals]
   (cond-> (hash-map :bookkeeping.portfolio/id (UUID/randomUUID))
     (exists? journals) (assoc :bookkeeping.portfolio/journals journals))))

(defn ->journal

  ([] (->journal []))

  ([entries]
   (cond-> (hash-map :bookkeeping.journal/id (UUID/randomUUID))
     (exists? entries) (assoc :bookkeeping.journal/entries entries))))

(defn ->account

  ([name type orientation] (->account name type orientation 0.0 0 nil))

  ([name type orientation balance amount counter-party]

   (cond-> (hash-map
             :bookkeeping.account/id (UUID/randomUUID)
             :bookkeeping.account/name name
             :bookkeeping.account/type type
             :bookkeeping.account/balance balance
             :bookkeeping.account/amount amount
             :bookkeeping.account/orientation orientation)
     (exists? counter-party) (assoc :bookkeeping.account/counter-party counter-party))))

(defn ->tentry

  ([] (->tentry nil nil))
  ([debits credits]
   (cond-> (hash-map :bookkeeping.tentry/id (UUID/randomUUID))
     (exists? debits)  (assoc :bookkeeping.tentry/debits debits)
     (exists? credits) (assoc :bookkeeping.tentry/credits credits))))

(defn ->debit [account value price amount]

  (cond-> (hash-map
            :bookkeeping.debit/id (UUID/randomUUID)
            :bookkeeping.debit/account account
            :bookkeeping.debit/value value
            ;; :db/ensure :bookkeeping.debit/validate
            )

    (exists? price)  (assoc :bookkeeping.debit/price price)
    (exists? amount) (assoc :bookkeeping.debit/amount amount)))

(defn ->credit [account value price amount]

  (cond-> (hash-map
            :bookkeeping.credit/id (UUID/randomUUID)
            :bookkeeping.credit/account account
            :bookkeeping.credit/value value
            ;; :db/ensure :bookkeeping.credit/validate
            )

    (exists? price)  (assoc :bookkeeping.credit/price price)
    (exists? amount) (assoc :bookkeeping.credit/amount amount)))

(defn ->stock-account-name [n]
  (format "STOCK.%s" n))


;; TODO Make a user-accounts-balanced? function
;; TODO ensure tentry transactions can't put accounts into negative balance
(defn tentry-balanced?

  "LHS
   :bookkeeping.debit/account
   :bookkeeping.account/orientation
   :db/ident :bookkeeping.account.orientation/debit

   :bookkeeping.credit/account
   :bookkeeping.account/orientation
   :db/ident :bookkeeping.account.orientation/credit

   RHS
   :bookkeeping.credit/account
   :bookkeeping.account/orientation
   :db/ident :bookkeeping.account.orientation/debit

   :bookkeeping.debit/account
   :bookkeeping.account/orientation
   :db/ident :bookkeeping.account.orientation/credit"
  [tentry]

  (let [{:keys [:bookkeeping.tentry/debits :bookkeeping.tentry/credits]} tentry

        lhs-debit-values  (->> debits
                               (filter (fn [debit]
                                         (-> debit
                                             :bookkeeping.debit/account
                                             :bookkeeping.account/orientation
                                             (#(= :bookkeeping.account.orientation/debit (:db/ident %))))))
                               (map :bookkeeping.debit/value))
        lhs-credit-values (->> credits
                               (filter (fn [credit]
                                         (-> credit
                                             :bookkeeping.credit/account
                                             :bookkeeping.account/orientation
                                             (#(= :bookkeeping.account.orientation/credit (:db/ident %))))))
                               (map :bookkeeping.credit/value))
        lhs               (apply + (concat lhs-debit-values lhs-credit-values))


        rhs-debit-values  (->> debits
                               (filter (fn [debit]
                                         (-> debit
                                             :bookkeeping.debit/account
                                             :bookkeeping.account/orientation
                                             (#(= :bookkeeping.account.orientation/credit (:db/ident %))))))
                               (map :bookkeeping.debit/value))
        rhs-credit-values (->> credits
                               (filter (fn [credit]
                                         (-> credit
                                             :bookkeeping.credit/account
                                             :bookkeeping.account/orientation
                                             (#(= :bookkeeping.account.orientation/debit (:db/ident %))))))
                               (map :bookkeeping.credit/value))
        rhs               (apply + (concat rhs-debit-values rhs-credit-values))]

    (= lhs rhs)))

(defn value-equals-price-times-amount? [tentry]

  (let [{:keys [:bookkeeping.tentry/debits :bookkeeping.tentry/credits]} tentry

        value-equals-price-times-amount-debit?
        #(if (or (:bookkeeping.debit/price %)
                 (:bookkeeping.debit/amount %))

           (and (and (:bookkeeping.debit/price %)
                     (:bookkeeping.debit/amount %))

                (= (:bookkeeping.debit/value %)
                   (Float. (format "%.2f" (* (:bookkeeping.debit/price %)
                                             (:bookkeeping.debit/amount %))))))
           true)

        value-equals-price-times-amount-credit?
        #(if (or (:bookkeeping.credit/price %)
                 (:bookkeeping.credit/amount %))

           (and (and (:bookkeeping.credit/price %)
                     (:bookkeeping.credit/amount %))

                (= (:bookkeeping.credit/value %)
                   (Float. (format "%.2f" (* (:bookkeeping.credit/price %)
                                             (:bookkeeping.credit/amount %))))))
           true)]

    (and (every? value-equals-price-times-amount-debit? debits)
         (every? value-equals-price-times-amount-credit? credits))))

(defn create-stock-account! [conn game-entity user-entity stock-entity]

  (let [starting-balance 0.0
        starting-amount  0
        counter-party    (select-keys stock-entity [:db/id])
        account          (persistence.core/bind-temporary-id
                           (apply ->account
                                  [(->> stock-entity :game.stock/name ->stock-account-name)
                                   :bookkeeping.account.type/asset
                                   :bookkeeping.account.orientation/debit
                                   starting-balance
                                   starting-amount
                                   counter-party]))

        game-user-with-account (->> (bookkeeping.persistence/pull-game-user
                                      conn
                                      (:db/id user-entity)
                                      (:game/id game-entity))
                                    (transform [:game.user/accounts] #(conj % account)))

        entities               [account game-user-with-account]]

    (as-> entities ent
      (persistence.datomic/transact-entities! conn ent)
      (:db-after ent)
      (d/q '[:find ?e
             :in $ ?account-id
             :where [?e :bookkeeping.account/id ?account-id]]
           ent
           (-> account :bookkeeping.account/id))
      (ffirst ent)
      (persistence.core/pull-entity conn ent))))

(defn conditionally-create-stock-account! [conn
                                           {game-db-id :db/id :as game-entity}
                                           {user-db-id :db/id :as user-entity}
                                           {stock-db-id :db/id :as stock-entity}]

  (let [stock-account-result-set
        (d/q '[:find (pull ?gua [*])
               :in $ ?game-db-id ?game-user ?stock-db-id
               :where
               [?game-db-id]
               [?game-db-id :game/users ?gus]
               [?gus :game.user/user ?game-user]
               [?gus :game.user/accounts ?gua]
               [?gua :bookkeeping.account/counter-party ?stock-db-id]]
             (d/db conn)
             game-db-id user-db-id stock-db-id)]

    (if (exists? stock-account-result-set)
      (ffirst stock-account-result-set)
      (create-stock-account! conn game-entity user-entity stock-entity))))

(defn- game-exists? [{:keys [conn game-id] :as inputs}]
  (let [game-pulled (try (persistence.core/pull-entity conn game-id)
                         (catch Throwable e nil))]
    (if (exists? game-pulled)
      (rop/succeed (assoc inputs :game-pulled game-pulled))
      (rop/fail (ex-info "No game bound to id" inputs)))))

(defn- user-exists? [{:keys [conn user-id] :as inputs}]
  (let [user-pulled (try (iam.persistence/user-by-id conn user-id)
                         (catch Throwable e nil))]
    (if (exists? user-pulled)
      (rop/succeed (assoc inputs :user-pulled user-pulled))
      (rop/fail (ex-info "No user bound to id" inputs)))))

(defn- stock-exists? [{:keys [conn stock-id] :as inputs}]
  (let [stock-pulled (try (persistence.core/pull-entity conn stock-id)
                          (catch Throwable e nil))]
    (if (exists? stock-pulled)
      (rop/succeed (assoc inputs :stock-pulled stock-pulled))
      (rop/fail (ex-info "No stock bound to id" inputs)))))

(defn- cash-account-has-sufficient-funds? [conn debit-value {user-pulled :user-pulled
                                                             game-pulled :game-pulled :as inputs}]
  (let [{cash-account-starting-balance :bookkeeping.account/balance :as cash-account}
        (bookkeeping.persistence/cash-account-by-game-user conn (:db/id user-pulled) (:game/id game-pulled))]

    (if (> cash-account-starting-balance debit-value)
      (rop/succeed inputs)
      (rop/fail (ex-info (format "Insufficient funds [%s] for purchase value [%s]"
                                 cash-account-starting-balance
                                 debit-value)
                         inputs)))))

(defn- stock-account-exists? [conn {stock-id :stock-id :as inputs}]
  (try
    (if-let [stock-account
             (ffirst (d/q '[:find (pull ?stock-id
                                        [:game.stock/id
                                         {:bookkeeping.account/_counter-party [*]}])
                            :in $ ?stock-id
                            :where
                            [?stock-id]]
                          (d/db conn)
                          stock-id))]
      (rop/succeed (assoc inputs :stock-account stock-account)))
    (catch Throwable e (rop/fail (ex-info (format "No stock entity [%s]" stock-id)
                                          inputs)))))

(defn- stock-account-has-sufficient-shares? [{:keys [conn stock-id stock-amount stock-account] :as inputs}]
  (if-let [initial-account-amount
           (-> stock-account
               :bookkeeping.account/_counter-party
               :bookkeeping.account/amount)]

    (if (>= initial-account-amount stock-amount)
      (rop/succeed inputs)
      (rop/fail
        (ex-info (format "Insufficient amount of stock [%s] to sell" initial-account-amount)
                 inputs)))

    (rop/fail
      (ex-info (format "Cannot find corresponding account for stockId [%s]" stock-id)
               inputs))))

(defn track-profit-loss+stream-portfolio-update! [conn gameId game-db-id user-id tentry]

  (game.persistence/track-profit-loss! tentry)

  (let [portfolio-update-stream (-> repl.state/system :game/games deref
                                    (get gameId) :portfolio-update-stream)

        running-profit-loss (->> repl.state/system :game/games
                                 deref
                                 (#(get % gameId)) :profit-loss
                                 (game.calculation/collect-running-profit-loss gameId))

        account-balances (game.calculation/collect-account-balances conn game-db-id user-id)]

    (core.async/go
      (core.async/>! portfolio-update-stream running-profit-loss)
      (core.async/>! portfolio-update-stream account-balances)))

  tentry)

(defn buy-stock! [conn game-db-id user-db-id stock-db-id stock-amount stock-price]

  (let [validation-inputs {:conn         conn
                           :game-id      game-db-id
                           :user-id      user-db-id
                           :stock-id     stock-db-id
                           :stock-amount stock-amount
                           :stock-price  stock-price}

        debit-value (Float. (format "%.2f" (* stock-amount stock-price)))
        result      (rop/>>= validation-inputs
                             game-exists?
                             user-exists?
                             stock-exists?
                             (partial cash-account-has-sufficient-funds? conn debit-value))]

    (if (= clojure.lang.ExceptionInfo (type result))

      (throw result)

      (let [{{game-id :game/id :as game-pulled} :game-pulled
             user-pulled :user-pulled
             stock-pulled :stock-pulled} result
            stock-account                      (conditionally-create-stock-account! conn game-pulled user-pulled stock-pulled)
            credit-value                       debit-value

            ;; ACCOUNT BALANCE UPDATES
            updated-debit-account  (update-in (bookkeeping.persistence/cash-account-by-game-user conn user-db-id game-id)
                                              [:bookkeeping.account/balance] - debit-value)
            updated-credit-account (-> stock-account
                                       (update-in [:bookkeeping.account/balance] + credit-value)
                                       (update-in [:bookkeeping.account/amount] + stock-amount))

            ;; T-ENTRY + JOURNAL ENTRIES
            debits+credits                    [(->debit updated-debit-account debit-value nil nil)
                                               (->credit updated-credit-account credit-value stock-price stock-amount)]
            tentry                            (apply ->tentry debits+credits)
            {gameId :game/id :as game-entity} (persistence.core/pull-entity conn game-db-id)
            updated-journal-entries           (-> game-entity
                                                  :game/users first
                                                  :game.user/portfolio
                                                  :bookkeeping.portfolio/journals first
                                                  (assoc :bookkeeping.journal/entries tentry))

            entities [tentry updated-journal-entries]]

        (as-> entities ent
          (persistence.datomic/transact-entities! conn ent)
          (:db-after ent)
          (d/q '[:find (pull ?e [*])
                 :in $ ?entry-id
                 :where [?e :bookkeeping.tentry/id ?entry-id]]
               ent
               (-> tentry :bookkeeping.tentry/id))
          (ffirst ent)
          ;; (game.persistence/track-profit-loss! ent)
          (track-profit-loss+stream-portfolio-update! conn gameId game-db-id user-db-id ent))))))

(defn sell-stock! [conn game-db-id user-db-id stock-db-id stock-amount stock-price]

  (let [validation-inputs {:conn         conn
                           :game-id      game-db-id
                           :user-id      user-db-id
                           :stock-id     stock-db-id
                           :stock-amount stock-amount
                           :stock-price  stock-price}

        result (rop/>>= validation-inputs
                        (partial stock-account-exists? conn)
                        game-exists?
                        user-exists?
                        stock-exists?
                        stock-account-has-sufficient-shares?)]

    (if (= clojure.lang.ExceptionInfo (type result))

      (throw result)

      (let [{{game-id :game/id :as game-pulled} :game-pulled
             user-pulled :user-pulled
             stock-pulled :stock-pulled
             stock-account :stock-account} result
            {{stock-account-amount :bookkeeping.account/amount}
             :bookkeeping.account/_counter-party} stock-account
            debit-value                           (Float. (format "%.2f" (* stock-amount stock-price)))
            credit-value                          debit-value
            stock-account-balance-updated         (Float. (format "%.2f" (* (- stock-account-amount stock-amount) stock-price)))

            ;; ACCOUNT BALANCE UPDATES
            updated-debit-account  (-> stock-account
                                       :bookkeeping.account/_counter-party
                                       (update-in [:bookkeeping.account/balance] (constantly stock-account-balance-updated))
                                       (update-in [:bookkeeping.account/amount] - stock-amount))
            updated-credit-account (update-in (bookkeeping.persistence/cash-account-by-game-user conn user-db-id game-id)
                                              [:bookkeeping.account/balance] + debit-value)

            ;; T-ENTRY + JOURNAL ENTRIES
            debits+credits                  [(->debit updated-debit-account debit-value stock-price stock-amount)
                                             (->credit updated-credit-account credit-value nil nil)]
            tentry                          (apply ->tentry debits+credits)
            {gameId :game/id :as game-entity} (persistence.core/pull-entity conn game-db-id)
            updated-journal-entries         (-> game-entity
                                                :game/users first
                                                :game.user/portfolio
                                                :bookkeeping.portfolio/journals first
                                                (assoc :bookkeeping.journal/entries tentry))

            entities [tentry updated-journal-entries]]

        (as-> entities ent
          (persistence.datomic/transact-entities! conn ent)
          (:db-after ent)
          (d/q '[:find (pull ?e [*])
                 :in $ ?entry-id
                 :where [?e :bookkeeping.tentry/id ?entry-id]]
               ent
               (-> tentry :bookkeeping.tentry/id))
          (ffirst ent)
          ;; (game.persistence/track-profit-loss! ent)
          (track-profit-loss+stream-portfolio-update! conn gameId game-db-id user-db-id ent)
          (identity ent))))))

(comment

  (def tentry

    {:db/id                 17592186045444
     :bookkeeping.tentry/id #uuid "c0d5052c-84f6-4d2c-921c-d0c41140f2b2"

     :bookkeeping.tentry/debits
     [{:db/id                   17592186045445
       :bookkeeping.debit/id    #uuid "ccd8e77d-7f61-4477-a653-91f19460f404"
       :bookkeeping.debit/account
       {:db/id                    17592186045437
        :bookkeeping.account/id   #uuid "69ffdf42-5220-409b-8f3e-1aa1f5d02c6e"
        :bookkeeping.account/name "Cash"
        :bookkeeping.account/type
        {:db/id    17592186045428
         :db/ident :bookkeeping.account.type/asset}
        :bookkeeping.account/orientation
        {:db/id    17592186045433
         :db/ident :bookkeeping.account.orientation/debit}}
       :bookkeeping.debit/value 5047.0}]

     :bookkeeping.tentry/credits
     [{:db/id                     17592186045446
       :bookkeeping.credit/id     #uuid "12aa40e7-2b88-4468-bca3-90755057d366"
       :bookkeeping.credit/account
       {:db/id                    17592186045442
        :bookkeeping.account/id   #uuid "1f9ade32-fd02-4322-9a7f-05bed58a4c84"
        :bookkeeping.account/name "STOCK.Dangerous Quota"
        :bookkeeping.account/type
        {:db/id    17592186045428
         :db/ident :bookkeeping.account.type/asset}
        :bookkeeping.account/orientation
        {:db/id    17592186045433
         :db/ident :bookkeeping.account.orientation/debit}
        :bookkeeping.account/counter-party
        {:db/id             17592186045440
         :game.stock/id     #uuid "f8c4c6ca-7d12-4d57-af63-5c3049b42fe0"
         :game.stock/name   "Dangerous Quota"
         :game.stock/symbol "DANG"}}
       :bookkeeping.credit/value  5047.0
       :bookkeeping.credit/price  50.47
       :bookkeeping.credit/amount 100}]})

  (pprint tentry)
  (pprint (tentry-balanced? tentry)))
