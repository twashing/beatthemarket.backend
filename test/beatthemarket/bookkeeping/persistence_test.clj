(ns beatthemarket.bookkeeping.persistence-test
  (:require [clojure.test :refer :all]
            [beatthemarket.test-util :as test-util]
            [integrant.repl.state :as repl.state]
            [com.rpl.specter :refer [transform ALL]]

            [beatthemarket.game.games :as game.games]
            [beatthemarket.bookkeeping.core :as bookkeeping.core]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.bookkeeping.persistence :as bookkeeping.persistence]
            [beatthemarket.util :as util]

            [datomic.client.api :as d]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture)

(deftest cash-account-by-game-user-test

  (let [conn (-> repl.state/system :persistence/datomic :opts :conn)]

    (testing "Retrieving Cash account for a game.user"

      (let [user-db-id                  (:db/id (test-util/generate-user! conn))
            sink-fn                     identity
            {{game-db-id :db/id
              game-id :game/id} :game} (game.games/create-game! conn user-db-id sink-fn)

            expected-cash-account {:bookkeeping.account/name "Cash"
                                   :bookkeeping.account/type :bookkeeping.account.type/asset
                                   :bookkeeping.account/balance (float 100000.0)
                                   :bookkeeping.account/amount 0
                                   :bookkeeping.account/orientation :bookkeeping.account.orientation/debit}

            result-cash-account
            (as-> (bookkeeping.persistence/cash-account-by-game-user conn user-db-id game-id) ca
              (select-keys ca [:bookkeeping.account/name
                               :bookkeeping.account/type
                               :bookkeeping.account/balance
                               :bookkeeping.account/amount
                               :bookkeeping.account/orientation])
              (transform [:bookkeeping.account/type] :db/ident ca)
              (transform [:bookkeeping.account/orientation] :db/ident ca))]

        (is (= expected-cash-account result-cash-account))))))

(deftest equity-account-by-game-user-test

  (let [conn (-> repl.state/system :persistence/datomic :opts :conn)]

    (testing "Retrieving Equity account for a game.user"

      (let [user-db-id                  (:db/id (test-util/generate-user! conn))
            sink-fn                     identity
            {{game-db-id :db/id
              game-id :game/id} :game} (game.games/create-game! conn user-db-id sink-fn)

            expected-equity-account {:bookkeeping.account/name "Equity"
                                     :bookkeeping.account/type :bookkeeping.account.type/equity
                                   :bookkeeping.account/balance (float 100000.0)
                                   :bookkeeping.account/amount 0
                                     :bookkeeping.account/orientation :bookkeeping.account.orientation/credit}

            result-equity-account
            (as-> (bookkeeping.persistence/equity-account-by-game-user conn user-db-id game-id) ca
              (select-keys ca [:bookkeeping.account/name
                               :bookkeeping.account/type
                               :bookkeeping.account/balance
                               :bookkeeping.account/amount
                               :bookkeeping.account/orientation])
              (transform [:bookkeeping.account/type] :db/ident ca)
              (transform [:bookkeeping.account/orientation] :db/ident ca))]

        (is (= expected-equity-account result-equity-account))))))

(deftest stock-accounts-by-game-user-test

  (let [conn                                        (-> repl.state/system :persistence/datomic :opts :conn)
        {user-db-id :db/id :as user}                (test-util/generate-user! conn)
        sink-fn                                     identity
        {{game-id     :game/id
          game-stocks :game/stocks} :game :as game} (game.games/create-game! conn user-db-id sink-fn)

        {stock-name  :game.stock/name :as stock} (first game-stocks)]

    (bookkeeping.core/conditionally-create-stock-account! conn (:game game) user stock)

    (testing "Buying a new stock creates the corresponding stock account"

      (let [result (bookkeeping.persistence/stock-accounts-by-game-user conn user-db-id game-id)

            expected-stock-account-count 1
            expected-stock-account-name  (bookkeeping.core/->stock-account-name stock-name)
            expected-stock-name          stock-name]

        (are [x y] (= x y)
          expected-stock-account-count (count result)
          expected-stock-account-name  (-> result ffirst :bookkeeping.account/name)
          expected-stock-name          (-> result ffirst :bookkeeping.account/counter-party :game.stock/name))))))
