(ns beatthemarket.game.game-test
  (:require [clojure.test :refer :all]
            [datomic.client.api :as d]
            [integrant.repl.state :as state]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.bookkeeping :as bookkeeping]
            [beatthemarket.persistence.user :as persistence.user]
            [beatthemarket.game.game :as game]
            [beatthemarket.util :as util])
  (:import [java.util UUID]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture)


(deftest initialize-game-test

  (let [conn (-> integrant.repl.state/system :persistence/datomic :conn)

        email "foo@bar.com"
        checked-authentication
        (hash-map :email email
                  :name "Foo Bar"
                  :uid (str (UUID/randomUUID)))

        ;; Add User
        _              (persistence.user/add-user! conn checked-authentication)
        result-user-id (-> (d/q '[:find ?e
                                  :in $ ?email
                                  :where [?e :user/email ?email]]
                                (d/db conn)
                                email)
                           ffirst)

        user-entity (hash-map :db/id result-user-id)

        ;; Initialize Game
        game (game/initialize-game conn user-entity)
        result-game-id (-> (d/q '[:find ?e
                                  :in $ ?game-id
                                  :where [?e :game/id ?game-id]]
                                (d/db conn)
                                (:game/id game))
                           ffirst)

        pulled-game (d/pull (d/db conn) '[*] result-game-id)
        pulled-user (d/pull (d/db conn) '[*] result-user-id)]

    (let [game-users (:game/users pulled-game)]

      (testing "User has bound game"

        (-> game-users first :game.user/user :user/email (= email) is)

        (is (= pulled-user (-> game-users first :game.user/user))))

      (testing "User's stock subscriptions are a part of the games stocks"
        (let [game-stocks (:game/stocks pulled-game)
              game-user-subscriptions (-> game-users first :game.user/subscriptions)]

          (is (some (into #{} game-stocks) game-user-subscriptions)))))))

(deftest create-entry-test

  (let [conn (-> integrant.repl.state/system :persistence/datomic :conn)

        result-user-id (test-util/generate-user conn)
        result-stock-id (ffirst (test-util/generate-stocks conn 1))

        stock-amount 100
        stock-price 50.47

        tentry (bookkeeping/buy-stock! conn result-user-id result-stock-id stock-amount stock-price)

        result-tentry-id (ffirst
                           (d/q '[:find ?e
                                  :in $ ?tentry-id
                                  :where [?e :bookkeeping.tentry/id ?tentry-id]]
                                (d/db conn)
                                (:bookkeeping.tentry/id tentry)))]

    (is (util/exists? result-tentry-id))

    (let [pulled-tentry (util/pprint+identity (d/pull (d/db conn) '[*] result-tentry-id))
          pulled-stock (util/pprint+identity (d/pull (d/db conn) '[*] result-stock-id))
          expected-stock-account-name (format "STOCK.%s" (:game.stock/name pulled-stock))]

      (testing "debit is cash account"
        (->> pulled-tentry
            :bookkeeping.tentry/debits
            first
            :bookkeeping.debit/account
            :bookkeeping.account/name
            (= "Cash")
            is))

      (testing "credit is stock account"
        (->> pulled-tentry
            :bookkeeping.tentry/credits
            first
            :bookkeeping.credit/account
            :bookkeeping.account/name
            (= expected-stock-account-name)
            is))

      (testing "We have new stock account"
        (-> (d/q '[:find ?e
                   :in $ ?stock-aacount-name
                   :where [?e :bookkeeping.account/name ?stock-aacount-name]]
                 (d/db conn)
                 expected-stock-account-name)
            first
            util/exists?
            is))

      ;; debits + credits balance

      ;; Portfoliio now has value of
      ;;   +stock account
      ;;   -cash account
      )))

(comment ;; TEntry


  (require '[integrant.repl.state :as repl.state]
           '[beatthemarket.test-util :as test-util]
           '[beatthemarket.bookkeeping :as bookkeeping]
           '[beatthemarket.iam.authentication :as iam.auth]
           '[beatthemarket.iam.user :as iam.user])


  ;; USER
  (do

    (def conn                   (-> repl.state/system :persistence/datomic :conn))
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

  ;; (cash-account-by-user user-pulled)
  ;; (equity-account-by-user user-pulled)

  ;; TODO Input
  #_{:stockId 1234
     :tickId "asdf"
     :tickTime 3456
     :tickPrice 1234.45}


  ;; STOCK
  (def stocks (generate-stocks 1))
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


  ;; Create account for stock
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
  (let [cash-account (:db/id (cash-account-by-user user-pulled))
        debit-value 1234.45

        credit-account {:db/id stock-account-id}
        credit-value 1234.45

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
  )
