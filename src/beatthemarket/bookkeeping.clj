(ns beatthemarket.bookkeeping
  (:require [datomic.client.api :as d]
            [com.rpl.specter :refer [select pred ALL MAP-VALS]]
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


(defn tentry-balanced? [tentry]

  (let [{:keys [:bookkeeping.tentry/debits :bookkeeping.tentry/credits]} tentry]

    ;; LHS
    ;; :bookkeeping.debit/account
    ;; :bookkeeping.account/orientation
    ;; :db/ident :bookkeeping.account.orientation/debit
    ;;
    ;; :bookkeeping.credit/account
    ;; :bookkeeping.account/orientation
    ;; :db/ident :bookkeeping.account.orientation/credit

    ;; RHS
    ;; :bookkeeping.credit/account
    ;; :bookkeeping.account/orientation
    ;; :db/ident :bookkeeping.account.orientation/debit
    ;;
    ;; :bookkeeping.debit/account
    ;; :bookkeeping.account/orientation
    ;; :db/ident :bookkeeping.account.orientation/credit

    (let [debits+credits (concat debits credits)
          lhs (filter (fn [debit-or-credit]
                        (or
                          (-> debit-or-credit
                              :bookkeeping.debit/account
                              :bookkeeping.account/orientation
                              (#(= :bookkeeping.account.orientation/debit (:db/ident %))))
                          (-> debit-or-credit
                              :bookkeeping.credit/account
                              :bookkeeping.account/orientation
                              (#(= :bookkeeping.account.orientation/credit (:db/ident %))))))
                      debits+credits)

          rhs (filter (fn [debit-or-credit]

                        (or
                          (-> debit-or-credit
                              :bookkeeping.credit/account
                              :bookkeeping.account/orientation
                              (#(= :bookkeeping.account.orientation/debit (:db/ident %))))
                          (-> debit-or-credit
                              :bookkeeping.debit/account
                              :bookkeeping.account/orientation
                              (#(= :bookkeeping.account.orientation/credit (:db/ident %)))))))]

      :bookkeeping.debit/value
      :bookkeeping.credit/value
      )))

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
       :bookkeeping.credit/amount 100}]

     })

  (pprint tentry)
  (pprint (tentry-balanced? tentry))


  #_{:db/id 17592186045437
     :bookkeeping.account/id
     #uuid "69ffdf42-5220-409b-8f3e-1aa1f5d02c6e"
     :bookkeeping.account/name "Cash"
     :bookkeeping.account/type
     #:db{:id 17592186045428 :ident :bookkeeping.account.type/asset}
     :bookkeeping.account/orientation
     #:db{:id 17592186045433
          :ident :bookkeeping.account.orientation/debit}}

  :bookkeeping.debit/account
  :bookkeeping.debit/value
  :bookkeeping.debit/price
  :bookkeeping.debit/amount

  :bookkeeping.credit/account
  :bookkeeping.credit/value
  :bookkeeping.credit/price
  :bookkeeping.credit/amount

  )


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

    (as-> account obj
      (persistence.datomic/transact-entities! conn obj)
      (:db-after obj)
      (d/q '[:find ?e
             :in $ ?account-id
             :where [?e :bookkeeping.account/id ?account-id]]
           obj
           (-> account :bookkeeping.account/id))
      (ffirst obj))))

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

(defn buy-stock! [conn user-id stock-id stock-amount stock-price]

  (let [user-pulled      (d/pull (d/db conn) '[*] user-id)
        stock-pulled     (d/pull (d/db conn) '[*] stock-id)
        stock-account-id (conditionally-create-stock-account! conn stock-pulled)]


    (let [cash-account   (:db/id (cash-account-by-user user-pulled))

          stock-value    (* stock-amount stock-price)
          debit-value    stock-value

          credit-account {:db/id stock-account-id}
          credit-value   stock-value

          debits+credits [(->debit cash-account debit-value nil nil)
                          (->credit credit-account credit-value stock-price stock-amount)]

          tentry (apply ->tentry debits+credits)]

      (persistence.datomic/transact-entities! conn tentry)
      tentry)))
