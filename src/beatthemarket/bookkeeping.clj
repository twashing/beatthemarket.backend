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


(defn ->account [name type orientation]
  (hash-map
    :bookkeeping.account/id (UUID/randomUUID)
    :bookkeeping.account/name name
    :bookkeeping.account/type type
    :bookkeeping.account/orientation orientation))

#_(defn ->tentry

  ([] (->tentry nil nil))
  ([debits credits]
   (cond-> (hash-map :bookkeeping.tentry/id (UUID/randomUUID))
     (exists? debits) (assoc :bookkeeping.tentry/debits debits)
     (exists? credits) (assoc :bookkeeping.tentry/credits credits))))

#_(defn ->debit [account amount]
  (hash-map
    :bookkeeping.debit/id (UUID/randomUUID)
    :bookkeeping.debit/account account
    :bookkeeping.debit/amount amount))

#_(defn ->credit [account amount]
  (hash-map
    :bookkeeping.credit/id (UUID/randomUUID)
    :bookkeeping.credit/account account
    :bookkeeping.credit/amount amount))
