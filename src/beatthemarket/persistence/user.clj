(ns beatthemarket.persistence.user
  (:require [beatthemarket.bookkeeping :as bookkeeping]
            [beatthemarket.persistence.datomic :as persistence.datomic]))


(defn add-user! [conn {:keys [email name uid] :as checked-authentication}]

  ;; Default set of accounts (:book)
  (let [accounts (->> [["Cash" :bookkeeping.account.type/asset :bookkeeping.account.orientation/debit]
                       ["Equity" :bookkeeping.account.type/equity :bookkeeping.account.orientation/credit]]
                      (map #(apply bookkeeping/->account %)))])

  (->> [{:user/email        email
         :user/name         name
         :user/external-uid uid
         :user/accounts     accounts}]
       (persistence.datomic/transact! conn)))
