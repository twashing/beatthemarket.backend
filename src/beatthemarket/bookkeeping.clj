(ns beatthemarket.bookkeeping
  (:require [beatthemarket.util :refer [exists?]])
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
     (exists? debits) (assoc :bookkeeping.tentry/debits debits)
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
