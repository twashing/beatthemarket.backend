(ns beatthemarket.bookkeeping
  (:require [datomic.client.api :as d]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.persistence.user :as persistence.user]
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

  ([name type orientation] (->account name type orientation nil))

  ([name type orientation counter-party]

   (cond-> (hash-map
             :bookkeeping.account/id (UUID/randomUUID)
             :bookkeeping.account/name name
             :bookkeeping.account/type type
             :bookkeeping.account/orientation orientation)
     (exists? counter-party) (assoc :bookkeeping.account/counter-party counter-party))))

(defn ->tentry

  ([] (->tentry nil nil))
  ([debits credits]
   (cond-> (hash-map :bookkeeping.tentry/id (UUID/randomUUID))
     (exists? debits)  (assoc :bookkeeping.tentry/debits debits)
     (exists? credits) (assoc :bookkeeping.tentry/credits credits))))

(defn ->debit

  ([account value] (->debit account value nil))

  ([account value amount]
   (cond-> (hash-map
             :bookkeeping.debit/id (UUID/randomUUID)
             :bookkeeping.debit/account account
             :bookkeeping.debit/value value)
     (exists? amount) (assoc :bookkeeping.debit/amount amount))))

(defn ->credit

  ([account value] (->credit account value nil))

  ([account value amount]
   (cond-> (hash-map
             :bookkeeping.credit/id (UUID/randomUUID)
             :bookkeeping.credit/account account
             :bookkeeping.credit/value value)
     (exists? amount) (assoc :bookkeeping.credit/amount amount))))


(defn cash-account-by-user

  ([conn user-id]
   (cash-account-by-user (persistence.user/pull-user conn user-id)))

  ([user-pulled]
   (->> user-pulled
        :user/accounts
        (filter #(= "Cash" (:bookkeeping.account/name %)))
        first)))

(defn equity-account-by-user

  ([conn user-id]
   (equity-account-by-user (persistence.user/pull-user conn user-id)))

  ([user-pulled]
   (->> user-pulled
        :user/accounts
        (filter #(= "Equity" (:bookkeeping.account/name %)))
        first)))

(defn create-stock-account! [conn stock-entity]

  (let [counter-party (select-keys stock-entity [:db/id])
        account       (apply ->account
                             [(->> stock-entity :game.stock/name (format "STOCK.%s"))
                              :bookkeeping.account.type/asset
                              :bookkeeping.account.orientation/debit
                              counter-party])]

    (persistence.datomic/transact-entities! conn account)
    (ffirst
      (d/q '[:find ?e
             :in $ ?account-id
             :where [?e :bookkeeping.account/id ?account-id]]
           (d/db conn)
           (-> account :bookkeeping.account/id)))))

(defn conditionally-create-stock-account! [conn stock-entity]

  (let [stock-account-result-set
        (d/q '[:find ?e
               :in $ ?counter-party
               :where [?e :bookkeeping.account/counter-party ?counter-party]]
             (d/db conn)
             (:db/id stock-entity))]

    (if (exists? stock-account-result-set)
      (ffirst stock-account-result-set)
      (create-stock-account! conn stock-entity))))


(defn buy-stock! [conn user-id stock-id stock-value]

  (let [user-pulled      (d/pull (d/db conn) '[*] user-id)
        stock-pulled     (d/pull (d/db conn) '[*] stock-id)
        stock-account-id (conditionally-create-stock-account! conn stock-pulled)]


    (let [cash-account (:db/id (cash-account-by-user user-pulled))
          debit-value  stock-value

          credit-account {:db/id stock-account-id}
          credit-value   stock-value

          debits+credits [(->debit cash-account debit-value)
                          (->credit credit-account credit-value)]

          tentry (apply ->tentry debits+credits)]

      (persistence.datomic/transact-entities! conn tentry)
      tentry)))
