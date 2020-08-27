(ns beatthemarket.game.core
  (:require [clj-time.core :as t]
            [clj-time.coerce :as c]
            [com.rpl.specter :refer [select-one  pred ALL]]
            [integrant.core :as ig]
            [integrant.repl.state :as repl.state]
            [beatthemarket.bookkeeping.core :as bookkeeping]
            [beatthemarket.datasource.core :as datasource.core]
            [beatthemarket.datasource.name-generator :as name-generator]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.util :as util]
            [datomic.client.api :as d])
  (:import [java.util UUID]))


(defmethod ig/init-key :game/game [_ config]
  config)

(defn game-user-by-user-id [game result-user-id]
  (select-one [:game/users ALL (pred #(= result-user-id
                                         (-> % :game.user/user :db/id)))]
              game))

(defn ->game

  ([game-level stocks user accounts]
   (->game game-level stocks user accounts {:game-id (UUID/randomUUID)
                                            :game-status :game-status/created}))

  ([game-level stocks user accounts {game-id :game-id
                                     game-status :game-status
                                     client-id :client-id}]

   (let [portfolio-with-journal (bookkeeping/->portfolio
                                  (bookkeeping/->journal))

         game-users (-> (hash-map
                          :game.user/user user
                          :game.user/accounts accounts
                          :game.user/portfolio portfolio-with-journal)
                        persistence.core/bind-temporary-id
                        list)]

     (cond-> (hash-map
               :game/id (or game-id (UUID/randomUUID))
               :game/start-time (c/to-date (t/now))
               :game/level game-level
               :game/stocks stocks
               :game/users game-users
               :game/status game-status)
       client-id (assoc :game.user/user-client client-id)))))

(defn ->stock

  ([name symbol] (->stock name symbol nil))
  ([name symbol price-history]
   (cond-> (hash-map
             :game.stock/id (UUID/randomUUID)
             :game.stock/name name
             :game.stock/data-seed (datasource.core/random-seed)
             :game.stock/symbol symbol)
     (util/exists? price-history) (assoc :game.stock/price-history price-history))))

(defn ->game-user-accounts

  ([] (->game-user-accounts (-> repl.state/config :game/game :starting-balance)))

  ([starting-balance]
   (let [starting-amount 0
         counter-party   nil]
     (->> [["Cash" :bookkeeping.account.type/asset :bookkeeping.account.orientation/debit starting-balance starting-amount counter-party]
           ["Equity" :bookkeeping.account.type/equity :bookkeeping.account.orientation/credit starting-balance starting-amount counter-party]]
          (map #(apply bookkeeping/->account %))))))

(defn generate-stocks! [amount]
  (->> (name-generator/generate-names amount)
       (map (juxt :stock-name :stock-symbol))
       (map #(apply ->stock %))
       (map persistence.core/bind-temporary-id)))

(defn initialize-game!

  ([conn user-entity]
   (initialize-game! conn user-entity (->game-user-accounts)))

  ([conn user-entity accounts]
   (initialize-game! conn user-entity accounts :game-level/one))

  ([conn user-entity accounts game-level]
   (initialize-game! conn user-entity accounts game-level (->> (name-generator/generate-names 4)
                                                               (map (juxt :stock-name :stock-symbol))
                                                               (map #(apply ->stock %))
                                                               (map persistence.core/bind-temporary-id))))

  ([conn user-entity accounts game-level stocks]
   (initialize-game! conn user-entity accounts game-level stocks {:game-status :game-status/created}))

  ([conn user-entity accounts game-level stocks opts]
   (let [game (->game game-level stocks user-entity accounts opts)]

     (as-> game gm
       (persistence.datomic/transact-entities! conn gm)
       (:db-after gm)
       (d/q '[:find (pull ?e [*])
              :in $ ?id
              :where [?e :game/id ?id]]
            gm
            (:game/id game))
       (ffirst gm)))))


(comment ;; Portfolio


  (require '[integrant.repl.state])


  (def conn (-> integrant.repl.state/system :persistence/datomic :conn))


  (def journal
    (->> (bookkeeping/->journal)
         (persistence.datomic/transact-entities! conn)))


  (def portfolio
    (->> (bookkeeping/->portfolio)
         (persistence.datomic/transact-entities! conn)))


  (def composite-portfolio
    (->> (bookkeeping/->journal)
         bookkeeping/->portfolio
         (persistence.datomic/transact-entities! conn)))


  (def result-portfolio (d/q '[:find ?e ?id
                               :in $ ?id
                               :where [?e :bookkeeping.journal/id ?id]]
                             (d/db conn)
                             (UUID/fromString "3cf2f83f-1954-4693-ab61-781024979519")))

  (d/pull (d/db conn) '[*] (ffirst result-portfolio))


  (def db (d/db conn))
  (d/q '[:find ?e
         :where [?e :bookkeeping.journal/id]] db))

(comment ;; Accounts


  (require '[integrant.repl.state])


  ;; Insert
  (let [conn (-> integrant.repl.state/system :persistence/datomic :conn)]

    (->> [["Cash" :bookkeeping.account.type/asset :bookkeeping.account.orientation/debit]
          ["Equity" :bookkeeping.account.type/equity :bookkeeping.account.orientation/credit]]
         (map #(apply bookkeeping/->account %))
         (persistence.bookkeeping/add-account! conn)))

  ;; Query
  (def conn (-> integrant.repl.state/system :persistence/datomic :conn))
  (def result-accounts (d/q '[:find ?e
                              :where [?e :bookkeeping.account/id]]
                            (d/db conn)))


  (d/pull (d/db conn) '[*] (ffirst result-accounts))

  (->> result-accounts
       (map #(d/pull (d/db conn) '[*] (first %)))))

(comment ;; TEntry


  (require '[integrant.repl.state :as repl.state]
           '[beatthemarket.test-util :as test-util]
           '[beatthemarket.bookkeeping.core :as bookkeeping]
           '[beatthemarket.iam.authentication :as iam.auth]
           '[beatthemarket.iam.user :as iam.user])


  ;; USER
  (do

    (def conn                   (-> repl.state/system :persistence/datomic :opts :conn))
    (def id-token               (test-util/->id-token))
    (def checked-authentication (iam.auth/check-authentication id-token))
    (def add-user-db-result     (iam.user/conditionally-add-new-user! conn checked-authentication))
    (def result-user-id         (ffirst
                                  (d/q '[:find ?e
                                         :in $ ?email
                                         :where [?e :user/email ?email]]
                                       (d/db conn)
                                       (-> checked-authentication
                                           :claims (get "email"))))))

  ;; ACCOUNT
  (do
    (def conn (-> integrant.repl.state/system :persistence/datomic :conn))
    (->> (d/pull (d/db conn) '[*] result-user-id)
         util/pprint+identity
         (def user-pulled)))

  ;; (cash-account-by-game-user user-pulled)
  ;; (equity-account-by-game-user user-pulled)

  ;; TODO Input
  #_{:stockId   1234
     :tickId    "asdf"
     :tickTime  3456
     :tickPrice 1234.45}


  ;; STOCK
  (def stocks (generate-stocks! 1))
  (persistence.datomic/transact-entities! conn stocks)
  (def result-stock-id (ffirst
                         (d/q '[:find ?e
                                :in $ ?stock-id
                                :where [?e :game.stock/id ?stock-id]]
                              (d/db conn)
                              (-> stocks first :game.stock/id))))
  (->> (d/pull (d/db conn) '[*] result-stock-id)
       util/pprint+identity
       (def stock-pulled))



  ;; >> ==================================== >>


  ;; >> Conditionally Create account for stock <<
  (let [counter-party (select-keys stock-pulled [:db/id])]

    (def account (apply bookkeeping/->account
                        [(->> stock-pulled :game.stock/name (format "STOCK.%s"))
                         :bookkeeping.account.type/asset
                         :bookkeeping.account.orientation/debit
                         counter-party]))
    (persistence.datomic/transact-entities! conn account))
  (def stock-account-id (ffirst
                          (d/q '[:find ?e
                                 :in $ ?account-id
                                 :where [?e :bookkeeping.account/id ?account-id]]
                               (d/db conn)
                               (-> account :bookkeeping.account/id))))


  ;; TENTRY
  (let [cash-account (:db/id (cash-account-by-game-user user-pulled))
        debit-value  1234.45

        credit-account {:db/id stock-account-id}
        credit-value   1234.45

        debits+credits [(bookkeeping/->debit cash-account debit-value)
                        (bookkeeping/->credit credit-account credit-value)]]

    ;; TODO Create TEntry
    (def tentry (apply bookkeeping/->tentry debits+credits))
    (persistence.datomic/transact-entities! conn tentry))

  (def result-tentry-id (ffirst
                          (d/q '[:find ?e
                                 :in $ ?tentry-id
                                 :where [?e :bookkeeping.tentry/id ?tentry-id]]
                               (d/db conn)
                               (:bookkeeping.tentry/id tentry))))
  (->> (d/pull (d/db conn) '[*] result-tentry-id)
       util/pprint+identity
       (def tentry-pulled))


  ;; >> ==================================== >>

  )

(comment ;; Game

  (let [;; Create a bookkeeping book
        portfolio+journal (->> (bookkeeping/->journal)
                               bookkeeping/->portfolio)

        ;; Generate stocks + first subscription
        stocks        (->> [["Sun Ra Inc" "SUN"]
                            ["Miles Davis Inc" "MILD"]
                            ["John Coltrane Inc" "JONC"]]
                           (map #(apply ->stock %))
                           (map persistence.core/bind-temporary-id))
        subscriptions (take 1 stocks)

        game-level :game-level/one]

    ;; Save stocks
    (def result-game (->game game-level portfolio+journal subscriptions stocks))
    result-game))

(comment ;; Initialize

  ;; Insert
  (let [conn (-> integrant.repl.state/system :persistence/datomic :conn)
        user nil]

    (initialize-game! conn user))

  (def result *1)

  ;; Query
  (def conn (-> integrant.repl.state/system :persistence/datomic :conn))
  (def result-game (d/q '[:find ?e
                          :where [?e :game/id]]
                        (d/db conn)))

  (d/pull (d/db conn) '[*] (ffirst result-game)))
