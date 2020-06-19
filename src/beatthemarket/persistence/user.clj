(ns beatthemarket.persistence.user
  (:require [datomic.client.api :as d]
            [beatthemarket.bookkeeping :as bookkeeping]
            [beatthemarket.persistence.datomic :as persistence.datomic]))


(defn user-by-email [conn email]

  (let [db (d/db conn)
        user-q '[:find ?e
                 :in $ ?email
                 :where
                 [?e :user/email ?email]]]

    (d/q user-q db email)))

(defn pull-user [conn user-id]
  (d/pull (d/db conn) '[*] user-id))

(defn add-user! [conn {:keys [email name uid]}]

  ;; Default set of accounts (:book)
  (let [accounts (->> [["Cash" :bookkeeping.account.type/asset :bookkeeping.account.orientation/debit]
                       ["Equity" :bookkeeping.account.type/equity :bookkeeping.account.orientation/credit]]
                      (map #(apply bookkeeping/->account %)))]

    (->> {:user/email        email
          :user/name         name
          :user/external-uid uid
          :user/accounts     accounts}
         (persistence.datomic/transact-entities! conn))))
