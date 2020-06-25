(ns beatthemarket.persistence.user
  (:require [datomic.client.api :as d]
            [integrant.repl.state :as repl.state]
            [beatthemarket.bookkeeping :as bookkeeping]
            [beatthemarket.persistence.datomic :as persistence.datomic]))


(defn user-by-email [conn email]
  (d/q '[:find (pull ?e [*])
         :in $ ?email
         :where
         [?e :user/email ?email]]
       (d/db conn)
       email))

(defn user-by-external-uid [conn external-uid]
  (d/q '[:find (pull ?e [*])
         :in $ ?external-uid
         :where
         [?e :user/external-uid ?external-uid]]
       (d/db conn)
       external-uid))

(defn add-user! [conn {:keys [email name uid]}]

  (let [starting-balance (-> repl.state/system :game/game :starting-balance)
        counter-party    nil
        accounts
        (->> [["Cash" :bookkeeping.account.type/asset :bookkeeping.account.orientation/debit starting-balance counter-party]
              ["Equity" :bookkeeping.account.type/equity :bookkeeping.account.orientation/credit starting-balance counter-party]]
             (map #(apply bookkeeping/->account %)))]

    (->> {:user/email        email
          :user/name         name
          :user/external-uid uid
          :user/accounts     accounts}
         (persistence.datomic/transact-entities! conn))))
