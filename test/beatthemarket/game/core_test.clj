(ns beatthemarket.game.core-test
  (:require [clojure.test :refer :all]
            [datomic.client.api :as d]
            [com.rpl.specter :refer [transform ALL]]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.repl.state :as repl.state]
            [beatthemarket.test-util :as test-util]

            [beatthemarket.state.core :as state.core]
            [beatthemarket.migration.core :as migration.core]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.game.core :as game.core]
            [beatthemarket.game.games :as game.games]
            [beatthemarket.game.games.control :as games.control]

            [beatthemarket.util :as util]
            [beatthemarket.bookkeeping.core :as bookkeeping]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.migration.core :as migration.core]
            [beatthemarket.bookkeeping.core :as bookkeeping.core])
  (:import [java.util UUID]
           [clojure.lang ExceptionInfo]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture)


(deftest initialize-game!-test

  (let [conn (-> repl.state/system :persistence/datomic :opts :conn)

        email "foo@bar.com"
        checked-authentication
        (hash-map :email email
                  :name "Foo Bar"
                  :uid (str (UUID/randomUUID)))

        ;; Add User
        _              (iam.user/add-user! conn checked-authentication)
        result-user-id (-> (d/q '[:find ?e
                                  :in $ ?email
                                  :where [?e :user/email ?email]]
                                (d/db conn)
                                email)
                           ffirst)

        user-entity (hash-map :db/id result-user-id)

        ;; Initialize Game
        game (game.core/initialize-game! conn user-entity)


        result-game-id (-> (d/q '[:find ?e
                                  :in $ ?game-id
                                  :where [?e :game/id ?game-id]]
                                (d/db conn)
                                (:game/id game))
                           ffirst)

        pulled-game (d/pull (d/db conn) '[*] result-game-id)
        pulled-user (d/pull (d/db conn) '[*] result-user-id)]

    ;; (is true)

    (let [game-users (:game/users pulled-game)]

      (testing "User has bound game"

        (-> game-users first :game.user/user :user/email (= email) is)
        (is (= pulled-user (-> game-users first :game.user/user))))

      (testing "User's stock subscriptions are a part of the games stocks"
        (let [expected-accounts #{{:bookkeeping.account/amount 0
                                   :bookkeeping.account/name "Cash"
                                   :bookkeeping.account/orientation :bookkeeping.account.orientation/debit
                                   :bookkeeping.account/type :bookkeeping.account.type/asset
                                   :bookkeeping.account/balance (float 100000.0)}
                                  {:bookkeeping.account/amount 0
                                   :bookkeeping.account/name "Equity"
                                   :bookkeeping.account/orientation :bookkeeping.account.orientation/credit
                                   :bookkeeping.account/type :bookkeeping.account.type/equity
                                   :bookkeeping.account/balance (float 100000.0)}}

              game-user-accounts (->> game-users first
                                      :game.user/accounts
                                      (into #{})
                                      (transform [ALL] #(dissoc % :db/id :bookkeeping.account/id))
                                      (transform [ALL :bookkeeping.account/type] :db/ident)
                                      (transform [ALL :bookkeeping.account/orientation] :db/ident))]

          (is (= expected-accounts game-user-accounts)))))))

(deftest buy-stock!-test

  (let [conn         (-> repl.state/system :persistence/datomic :opts :conn)
        stock-amount 100
        stock-price  50.47

        game-id  nil
        user-id  nil
        stock-id nil
        tick-db-id nil]

    (testing "Cannot buy stock without having created a game"

      ;; [conn game-db-id user-db-id stock-db-id tick-db-id stock-amount stock-price]
      (is (thrown? ExceptionInfo (bookkeeping/buy-stock! conn game-id user-id stock-id tick-db-id stock-amount stock-price)))

      (testing "Cannot buy stock without having a user"

        (let [user-id                  (:db/id (test-util/generate-user! conn))
              sink-fn                  identity
              {{game-id :db/id} :game} (game.games/create-game! conn user-id sink-fn)
              {stock-id :db/id}        (ffirst (test-util/generate-stocks! conn 1))
              tick-db-id               nil]

          (testing "Buying a stock creates a tentry"

            (let [{tentry-id :db/id} (bookkeeping/buy-stock! conn game-id user-id stock-id tick-db-id stock-amount stock-price)]

              (is (util/exists? tentry-id))

              (let [pulled-tentry               (persistence.core/pull-entity conn tentry-id)
                    pulled-stock                (persistence.core/pull-entity conn stock-id)
                    expected-stock-account-name (bookkeeping.core/->stock-account-name (:game.stock/name pulled-stock))
                    new-stock-account           (-> pulled-tentry
                                                    :bookkeeping.tentry/credits first
                                                    :bookkeeping.credit/account)]

                (testing "debit is cash account"
                  (->> pulled-tentry
                       :bookkeeping.tentry/debits first
                       :bookkeeping.debit/account
                       :bookkeeping.account/name
                       (= "Cash")
                       is))

                (testing "credit is stock account"
                  (->> new-stock-account
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

                (testing "Debits + Credits balance"

                  (is (bookkeeping/tentry-balanced? pulled-tentry))
                  (is (bookkeeping/value-equals-price-times-amount? pulled-tentry)))

                (testing "Stock account is bound to the game.user's set of accounts"

                  (let [game-pulled (persistence.core/pull-entity conn game-id)]

                    (->> game-pulled
                         :game/users first
                         :game.user/accounts
                         (map :bookkeeping.account/id)
                         (some #{(:bookkeeping.account/id new-stock-account)})
                         is)

                    (testing "Portfolio now has value of
                                +stock account
                                -cash account"

                      (let [cash-starting-balance  (-> repl.state/config :game/game :starting-balance)
                            stock-starting-balance 0.0
                            value-change           (Float. (format "%.2f" (* stock-amount stock-price)))

                            game-user-accounts (->> game-pulled
                                                    :game/users first
                                                    :game.user/accounts)]

                        (->> game-user-accounts
                             (filter #(= "Cash" (:bookkeeping.account/name %)))
                             first
                             :bookkeeping.account/balance
                             (= (- cash-starting-balance value-change))
                             is)

                        (->> game-user-accounts
                             (filter #(clojure.string/starts-with? (:bookkeeping.account/name %) "STOCK."))
                             first
                             :bookkeeping.account/balance
                             (= (+ stock-starting-balance value-change))
                             is)))))))))))))

(deftest buy-stock!-insufficient-funds-test

  (testing "Cannot buy stock with insufficient funds"

    (let [conn                     (-> repl.state/system :persistence/datomic :opts :conn)
          stock-amount             100
          stock-price              50.47
          starting-cash-balance    0.0
          user-id                  (:db/id (test-util/generate-user! conn))
          sink-fn                  identity
          {{game-id :db/id} :game} (game.games/create-game! conn user-id sink-fn
                                                            :game-level/one
                                                            games.control/->data-sequence
                                                            {:accounts (game.core/->game-user-accounts starting-cash-balance)})

          {stock-id :db/id} (ffirst (test-util/generate-stocks! conn 1))
          tick-db-id        nil]

      (is (thrown? ExceptionInfo (bookkeeping/buy-stock! conn game-id user-id stock-id tick-db-id stock-amount stock-price))))))

(deftest sell-stock!-test

  (let [conn         (-> repl.state/system :persistence/datomic :opts :conn)
        stock-amount 100
        stock-price  50.47

        game-id  nil
        user-id  nil
        stock-id nil
        tick-db-id nil]

    (testing "Cannot sell stock without having created a game"

      (is (thrown? ExceptionInfo (bookkeeping/sell-stock! conn game-id user-id stock-id tick-db-id stock-amount stock-price)))

      (testing "Cannot sell stock without having a user"

        (let [user-id                  (:db/id (test-util/generate-user! conn))
              sink-fn                  identity
              {{game-id :db/id} :game :as game} (game.games/create-game! conn user-id sink-fn)
              {stock-id :db/id}        (ffirst (test-util/generate-stocks! conn 1))]

          (testing "Cannot sell stock if you have insufficient shares"

            (is (thrown? ExceptionInfo (bookkeeping/sell-stock! conn game-id user-id stock-id tick-db-id stock-amount stock-price))))

          (testing "Initial Stock BUY"

            (let [buy-stock-amount 100
                  sell-stock-amount 50]

              (bookkeeping/buy-stock! conn game-id user-id stock-id tick-db-id buy-stock-amount stock-price)


              (testing "Selling a stock creates a tentry"

                (let [{tentry-id :db/id} (bookkeeping/sell-stock! conn game-id user-id stock-id tick-db-id sell-stock-amount stock-price)]

                  (is (util/exists? tentry-id))

                  (let [pulled-tentry               (persistence.core/pull-entity conn tentry-id)
                        pulled-stock                (persistence.core/pull-entity conn stock-id)
                        expected-stock-account-name (bookkeeping.core/->stock-account-name (:game.stock/name pulled-stock))
                        new-stock-account           (-> pulled-tentry
                                                        :bookkeeping.tentry/credits first
                                                        :bookkeeping.credit/account)]

                    (testing "debit is cash account"
                      (->> pulled-tentry
                           :bookkeeping.tentry/debits first
                           :bookkeeping.debit/account
                           :bookkeeping.account/name
                           (= expected-stock-account-name)
                           is))

                    (testing "credit is stock account"
                      (->> new-stock-account
                           :bookkeeping.account/name
                           (= "Cash")
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

                    (testing "Debits + Credits balance"

                      (is (bookkeeping/tentry-balanced? pulled-tentry))
                      (is (bookkeeping/value-equals-price-times-amount? pulled-tentry)))

                    (testing "Stock account is bound to the game.user's set of accounts"

                      (let [game-pulled (persistence.core/pull-entity conn game-id)]

                        (->> game-pulled
                             :game/users first
                             :game.user/accounts
                             (map :bookkeeping.account/id)
                             (some #{(:bookkeeping.account/id new-stock-account)})
                             is)

                        (testing "Portfolio now has value of
                                +stock account
                                -cash account"

                          (let [cash-starting-balance  (-> repl.state/config :game/game :starting-balance)
                                stock-starting-balance 0.0
                                buy-value-change       (Float. (format "%.2f" (* buy-stock-amount stock-price)))
                                sell-value-change      (Float. (format "%.2f" (* sell-stock-amount stock-price)))

                                game-user-accounts (->> game-pulled
                                                        :game/users first
                                                        :game.user/accounts)]

                            (->> game-user-accounts
                                 (filter #(= "Cash" (:bookkeeping.account/name %)))
                                 first
                                 :bookkeeping.account/balance
                                 (= (+ (- cash-starting-balance buy-value-change) sell-value-change))
                                 is)

                            (->> game-user-accounts
                                 (filter #(clojure.string/starts-with? (:bookkeeping.account/name %) "STOCK."))
                                 first
                                 :bookkeeping.account/balance
                                 (= (- (+ stock-starting-balance buy-value-change) sell-value-change))
                                 is)))))))))))))))

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
    (def conn (-> repl.state/system :persistence/datomic :opts :conn))
    (->> (d/pull (d/db conn) '[*] result-user-id)
         util/pprint+identity
         (def user-pulled)))

  ;; (cash-account-by-game-user user-pulled)
  ;; (equity-account-by-game-user user-pulled)

  ;; TODO Input
  #_{:stockId 1234
     :tickId "asdf"
     :tickTime 3456
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


  ;; Create account for stock
  (let [satrting-balance 0.0
        counter-party (select-keys stock-pulled [:db/id])]

    (def account (apply bookkeeping/->account
                        [(->> stock-pulled :game.stock/name bookkeeping.core/->stock-account-name)
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
       (def tentry-pulled)))
