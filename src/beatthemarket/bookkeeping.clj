(ns beatthemarket.bookkeeping
  (:require [datomic.client.api :as d]
            [com.rpl.specter :refer [select pred ALL MAP-VALS]]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.util :as util :refer [exists?]])
  (:import [java.util UUID]))


(defn ->portfolio

  ([] (->portfolio nil))

  ([journals]
   (cond-> (hash-map :bookkeeping.portfolio/id (UUID/randomUUID))
     (exists? journals) (assoc :bookkeeping.portfolio/journals journals))))

(defn ->journal

  ([] (->journal nil))

  ([entries]
   (cond-> (hash-map :bookkeeping.journal/id (UUID/randomUUID))
     (exists? entries) (assoc :bookkeeping.journal/entries entries))))

(defn ->account

  ([name type orientation] (->account name type orientation 0.0 nil))

  ([name type orientation balance counter-party]

   (cond-> (hash-map
             :bookkeeping.account/id (UUID/randomUUID)
             :bookkeeping.account/name name
             :bookkeeping.account/type type
             :bookkeeping.account/balance balance
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

    (exists? price) (assoc :bookkeeping.debit/price price)
    (exists? amount) (assoc :bookkeeping.debit/amount amount)))

(defn ->credit [account value price amount]

  (cond-> (hash-map
            :bookkeeping.credit/id (UUID/randomUUID)
            :bookkeeping.credit/account account
            :bookkeeping.credit/value value
            ;; :db/ensure :bookkeeping.credit/validate
            )

    (exists? price) (assoc :bookkeeping.credit/price price)
    (exists? amount) (assoc :bookkeeping.credit/amount amount)))

;; TODO Make a user-accounts-balanced? function
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
                   (* (:bookkeeping.debit/price %)
                      (:bookkeeping.debit/amount %))))
           true)

        value-equals-price-times-amount-credit?
        #(if (or (:bookkeeping.credit/price %)
                 (:bookkeeping.credit/amount %))

           (and (and (:bookkeeping.credit/price %)
                     (:bookkeeping.credit/amount %))

                (= (:bookkeeping.credit/value %)
                   (Double. (format "%.2f" (* (:bookkeeping.credit/price %)
                                              (:bookkeeping.credit/amount %))))))
           true)]

    (and (every? value-equals-price-times-amount-debit? debits)
         (every? value-equals-price-times-amount-credit? credits))))

(defn cash-account-by-user

  ([conn user-id]
   (cash-account-by-user (persistence.core/pull-entity conn user-id)))

  ([user-pulled]
   (->> user-pulled
        :user/accounts
        (filter #(= "Cash" (:bookkeeping.account/name %)))
        first)))

(defn equity-account-by-user

  ([conn user-id]
   (equity-account-by-user (persistence.core/pull-entity conn user-id)))

  ([user-pulled]
   (->> user-pulled
        :user/accounts
        (filter #(= "Equity" (:bookkeeping.account/name %)))
        first)))

(defn create-stock-account! [conn user-entity stock-entity]

  (let [starting-balance         0.0
        counter-party            (select-keys stock-entity [:db/id])
        account                  (persistence.core/bind-temporary-id
                                   (apply ->account
                                          [(->> stock-entity :game.stock/name (format "STOCK.%s"))
                                           :bookkeeping.account.type/asset
                                           :bookkeeping.account.orientation/debit
                                           starting-balance
                                           counter-party]))
        user-entity-with-account (assoc user-entity :user/accounts account)
        entities                 [account user-entity-with-account]]

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

(defn conditionally-create-stock-account! [conn user-entity stock-entity]

  (let [stock-account-result-set
        (d/q '[:find ?e
               :in $ ?counter-party
               :where [?e :bookkeeping.account/counter-party ?counter-party]]
             (d/db conn)
             (:db/id stock-entity))]

    (if (exists? stock-account-result-set)
      {:db/id (ffirst stock-account-result-set)}
      (create-stock-account! conn user-entity stock-entity))))

(defn buy-stock! [conn game-id user-id stock-id stock-amount stock-price]
  {:pre [(exists? (persistence.core/pull-entity conn game-id))
         (exists? (persistence.core/pull-entity conn user-id))
         (exists? (persistence.core/pull-entity conn stock-id))]}

  ;; TODO
  ;; game exists
  ;; user exists
  ;; stock exists
  ;;
  ;; user is bound to game
  ;; stock is bound to game
  (let [user-pulled   (persistence.core/pull-entity conn user-id)
        stock-pulled  (persistence.core/pull-entity conn stock-id)
        stock-account (conditionally-create-stock-account! conn user-pulled stock-pulled)
        debit-value   (* stock-amount stock-price)
        credit-value  debit-value

        ;; ACCOUNT BALANCE UPDATES
        updated-debit-account  (update-in (cash-account-by-user user-pulled) [:bookkeeping.account/balance] - debit-value)
        updated-credit-account (update-in stock-account [:bookkeeping.account/balance] + credit-value)

        ;; T-ENTRY + JOURNAL ENTRIES
        debits+credits          [(->debit updated-debit-account debit-value nil nil)
                                 (->credit updated-credit-account credit-value stock-price stock-amount)]
        tentry                  (apply ->tentry debits+credits)
        updated-journal-entries (-> (persistence.core/pull-entity conn game-id)
                                    :game/users first
                                    :game.user/portfolio
                                    :bookkeeping.portfolio/journals first
                                    (assoc :bookkeeping.journal/entries tentry))

        entities [tentry updated-journal-entries #_updated-debit-account #_updated-credit-account]]

    (as-> entities ent
      (persistence.datomic/transact-entities! conn ent)
      (:db-after ent)
      (d/q '[:find ?e
             :in $ ?entry-id
             :where [?e :bookkeeping.tentry/id ?entry-id]]
           ent
           (-> tentry :bookkeeping.tentry/id))
      (ffirst ent)
      (persistence.core/pull-entity conn ent))))

(comment

  (def tentry

    {:db/id 17592186045444
     :bookkeeping.tentry/id #uuid "c0d5052c-84f6-4d2c-921c-d0c41140f2b2"

     :bookkeeping.tentry/debits
     [{:db/id 17592186045445
       :bookkeeping.debit/id #uuid "ccd8e77d-7f61-4477-a653-91f19460f404"
       :bookkeeping.debit/account
       {:db/id 17592186045437
        :bookkeeping.account/id #uuid "69ffdf42-5220-409b-8f3e-1aa1f5d02c6e"
        :bookkeeping.account/name "Cash"
        :bookkeeping.account/type
        {:db/id 17592186045428
         :db/ident :bookkeeping.account.type/asset}
        :bookkeeping.account/orientation
        {:db/id 17592186045433
         :db/ident :bookkeeping.account.orientation/debit}}
       :bookkeeping.debit/value 5047.0}]

     :bookkeeping.tentry/credits
     [{:db/id 17592186045446
       :bookkeeping.credit/id #uuid "12aa40e7-2b88-4468-bca3-90755057d366"
       :bookkeeping.credit/account
       {:db/id 17592186045442
        :bookkeeping.account/id #uuid "1f9ade32-fd02-4322-9a7f-05bed58a4c84"
        :bookkeeping.account/name "STOCK.Dangerous Quota"
        :bookkeeping.account/type
        {:db/id 17592186045428
         :db/ident :bookkeeping.account.type/asset}
        :bookkeeping.account/orientation
        {:db/id 17592186045433
         :db/ident :bookkeeping.account.orientation/debit}
        :bookkeeping.account/counter-party
        {:db/id 17592186045440
         :game.stock/id #uuid "f8c4c6ca-7d12-4d57-af63-5c3049b42fe0"
         :game.stock/name "Dangerous Quota"
         :game.stock/symbol "DANG"}}
       :bookkeeping.credit/value 5047.0
       :bookkeeping.credit/price 50.47
       :bookkeeping.credit/amount 100}]})

  (pprint tentry)
  (pprint (tentry-balanced? tentry)))
