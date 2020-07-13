(ns beatthemarket.iam.user
  (:require [datomic.client.api :as d]
            [integrant.repl.state :as repl.state]
            [beatthemarket.bookkeeping.core :as bookkeeping]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.util :as util]))


(defn user-exists? [result-entities]
  (let [set-and-subsets-not-empty?
        (every-pred util/exists? (partial every? util/exists?))]
    (set-and-subsets-not-empty? result-entities)))

(defn add-user!

  ([conn checked-authentication] (add-user! conn checked-authentication (-> repl.state/config :game/game :starting-balance)))

  ([conn {:keys [email name uid]} starting-balance]

   (let [starting-amount 0
         counter-party   nil
         accounts
         (->> [["Cash" :bookkeeping.account.type/asset :bookkeeping.account.orientation/debit starting-balance starting-amount counter-party]
               ["Equity" :bookkeeping.account.type/equity :bookkeeping.account.orientation/credit starting-balance starting-amount counter-party]]
              (map #(apply bookkeeping/->account %)))]

     (->> {:user/email        email
           :user/name         name
           :user/external-uid uid
           :user/accounts     accounts}
          (persistence.datomic/transact-entities! conn)))))

(defn conditionally-add-new-user!

  ([conn checked-authentication]
   (conditionally-add-new-user! conn checked-authentication (-> repl.state/config :game/game :starting-balance)))

  ([conn {email :email :as checked-authentication} starting-balance]
   (if-not (user-exists? (iam.persistence/user-by-email conn email))
     (add-user! conn checked-authentication starting-balance)
     {:db-after (d/db conn)})))
