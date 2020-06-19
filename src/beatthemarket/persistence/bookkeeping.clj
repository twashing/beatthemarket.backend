(ns beatthemarket.persistence.bookkeeping
  (:require
   ;; [datomic.client.api :as d]
   ;; [beatthemarket.util :as util]
   [beatthemarket.persistence.datomic :as persistence.datomic]))



#_(defn add-portfolio! [conn portfolio]
  (->> (persistence.datomic/conditionially-wrap-in-sequence portfolio)
       (persistence.datomic/transact! conn)))

#_(defn add-journal! [conn journal]
  (->> (persistence.datomic/conditionially-wrap-in-sequence journal)
       (persistence.datomic/transact! conn)))

#_(defn add-account! [conn account]
  (->> (persistence.datomic/conditionially-wrap-in-sequence account)
       (persistence.datomic/transact! conn)))

#_(defn add-tentry! [conn tentry]
  (->> (persistence.datomic/conditionially-wrap-in-sequence tentry)
       (persistence.datomic/transact! conn)))

#_(defn add-debit! [conn debit]
  (->> (persistence.datomic/conditionially-wrap-in-sequence debit)
       (persistence.datomic/transact! conn)))

#_(defn add-credit! [conn credit]
  (->> (persistence.datomic/conditionially-wrap-in-sequence credit)
       (persistence.datomic/transact! conn)))
