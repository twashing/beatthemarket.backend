(ns beatthemarket.game.games-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as core.async
             :refer [go <! >!! <!!]]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]
            [datomic.client.api :as d]
            [integrant.repl.state :as repl.state]
            [beatthemarket.game.games :as game.games]
            [beatthemarket.util :as util]
            [beatthemarket.test-util :as test-util])
  (:import [java.util UUID]
           [clojure.lang ExceptionInfo]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture)


(deftest create-game!-test

  (testing "We get an expected game-control struct"

    (let [conn               (-> repl.state/system :persistence/datomic :conn)
          result-user-id     (:db/id (test-util/generate-user! conn))
          sink-fn            identity
          {:keys [tick-sleep-ms control-channel]
           :as   game-control} (game.games/create-game! conn result-user-id sink-fn)

          expected-game-control-keys
          (sort '(:game :stocks-with-tick-data :tick-sleep-ms :stock-stream-channel :control-channel :close-sink-fn :sink-fn))]

      (->> game-control
           keys
           sort
           (= expected-game-control-keys)
           is)

      (testing "Streaming subscription flows through the correct value"

        (let [output-chan  (core.async/chan)
              game-loop-fn (fn [a]
                             (core.async/>!! output-chan a))]

          ;; A
          (game.games/start-game! conn result-user-id game-control game-loop-fn)
          (pprint (-> integrant.repl.state/system :games/games))

          ;; B
          (core.async/go-loop []
            (let [[v ch] (core.async/alts! [(core.async/timeout tick-sleep-ms)
                                            output-chan])]

              (println (format "TEST go-loop / value / %s" v))
              (when-not (nil? v)
                (recur))))

          ;; C
          ;; (core.async/>!! control-channel :exit)
          )


        #_(let [{:keys [game tick-sleep-ms stocks-with-tick-data
                        stock-stream-channel control-channel]} game-control
                close-sink-fn                                  sink-fn]

            (game.games/stream-subscription! tick-sleep-ms
                                             stock-stream-channel control-channel
                                             close-sink-fn sink-fn)

            (game.games/onto-open-chan
              ;; core.async/onto-chan
              stock-stream-channel
              (game.games/stocks->stock-sequences conn game result-user-id stocks-with-tick-data))

            (let [[t0-time _ id0] (<!! stock-stream-channel)
                  [t1-time _ id1] (<!! stock-stream-channel)]

              (is (t/after?
                    (c/from-long (Long/parseLong t1-time))
                    (c/from-long (Long/parseLong t0-time))))

              (testing "Two ticks streamed to client, got saved to the DB"

                (let [conn (-> repl.state/system :persistence/datomic :conn)

                      tick-id0 (UUID/fromString id0)
                      tick-id1 (UUID/fromString id1)]

                  (->> (d/q '[:find ?e
                              :in $ [?tick-id ...]
                              :where
                              [?e :game.stock.tick/id ?tick-id]]
                            (d/db conn)
                            [tick-id0 tick-id1])
                       count
                       (= 2)
                       is)))))))))

#_(deftest buy-stock!-test

  (let [conn                                                (-> repl.state/system :persistence/datomic :conn)
        {result-user-id :db/id
         userId         :user/external-uid}                 (test-util/generate-user! conn)
        sink-fn                                             identity
        {{gameId :game/id :as game} :game
         stock-stream-channel  :stock-stream-channel
         stocks-with-tick-data      :stocks-with-tick-data} (game.games/create-game! conn result-user-id sink-fn)

        _ (game.games/onto-open-chan
            stock-stream-channel
            (take 2 (game.games/stocks->stock-sequences conn game result-user-id stocks-with-tick-data)))

        stockId     (-> game
                        :game/users first
                        :game.user/subscriptions first
                        :game.stock/id)
        stockAmount 100

        [_ tickPrice0 tickId0] (<!! stock-stream-channel)
        [_ tickPrice1 tickId1] (<!! stock-stream-channel)
        tickId0                (UUID/fromString tickId0)
        tickId1                (UUID/fromString tickId1)]

    (testing "We are checking game is current and belongs to the user"
      (is (thrown? ExceptionInfo (game.games/buy-stock! conn userId "non-existant-game-id" stockId stockAmount tickId1 tickPrice1))))

    (testing "Error is thrown when submitted price does not match price from tickId"
      (is (thrown? ExceptionInfo (game.games/buy-stock! conn userId gameId stockId stockAmount tickId1 (Float. (- tickPrice1 1))))))

    (testing "Error is thrown when submitted tick is no the latest"
      (is (thrown? ExceptionInfo (game.games/buy-stock! conn userId gameId stockId stockAmount tickId0 (Float. tickPrice0)))))

    (testing "Returned Tentry matches what was submitted"
      (let [expected-credit-value        (Float. (format "%.2f" (* stockAmount tickPrice1)))
            expected-credit-account-name (->> game
                                              :game/users first
                                              :game.user/subscriptions first
                                              :game.stock/name
                                              (format "STOCK.%s"))

            expected-debit-value        (- (-> repl.state/config :game/game :starting-balance) expected-credit-value)
            expected-debit-account-name "Cash"

            result-tentry (game.games/buy-stock! conn userId gameId stockId stockAmount tickId1 (Float. tickPrice1))

            {debit-account-name :bookkeeping.account/name
             debit-value        :bookkeeping.account/balance}
            (-> result-tentry
                :bookkeeping.tentry/debits first
                :bookkeeping.debit/account
                (select-keys [:bookkeeping.account/name :bookkeeping.account/balance]))

            {credit-account-name :bookkeeping.account/name
             credit-value        :bookkeeping.account/balance}
            (-> result-tentry
                :bookkeeping.tentry/credits first
                :bookkeeping.credit/account
                (select-keys [:bookkeeping.account/name :bookkeeping.account/balance]))]

        (are [x y] (= x y)
          expected-credit-value        credit-value
          expected-credit-account-name credit-account-name
          expected-debit-value         debit-value
          expected-debit-account-name  debit-account-name)))))

#_(deftest sell-stock!-test

  (let [conn                                                (-> repl.state/system :persistence/datomic :conn)
        {result-user-id :db/id
         userId         :user/external-uid}                 (test-util/generate-user! conn)
        sink-fn                                             identity
        {{gameId :game/id :as game} :game
         stock-stream-channel  :stock-stream-channel
         stocks-with-tick-data      :stocks-with-tick-data} (game.games/create-game! conn result-user-id sink-fn)

        _ (game.games/onto-open-chan
            stock-stream-channel
            (take 2 (game.games/stocks->stock-sequences conn game result-user-id stocks-with-tick-data)))

        stockId     (-> game
                        :game/users first
                        :game.user/subscriptions first
                        :game.stock/id)
        stockAmount 100

        [_ tickPrice0 tickId0] (<!! stock-stream-channel)
        [_ tickPrice1 tickId1] (<!! stock-stream-channel)
        tickId0                (UUID/fromString tickId0)
        tickId1                (UUID/fromString tickId1)]

    (testing "We are checking game is current and belongs to the user"
      (is (thrown? ExceptionInfo (game.games/sell-stock! conn userId "non-existant-game-id" stockId stockAmount tickId1 tickPrice1))))

    (testing "Error is thrown when submitted price does not match price from tickId"
      (is (thrown? ExceptionInfo (game.games/sell-stock! conn userId gameId stockId stockAmount tickId1 (Float. (- tickPrice1 1))))))

    (testing "Error is thrown when submitted tick is no the latest"
      (is (thrown? ExceptionInfo (game.games/sell-stock! conn userId gameId stockId stockAmount tickId0 (Float. tickPrice0)))))

    (testing "Ensure we have the stock on hand before selling"

      (game.games/buy-stock! conn userId gameId stockId stockAmount tickId1 (Float. tickPrice1))

      (testing "Returned Tentry matches what was submitted"

        (let [initial-debit-value         0.0
              debit-value-change          (Float. (format "%.2f" (* stockAmount tickPrice1)))
              expected-debit-value        (- (+ initial-debit-value debit-value-change) debit-value-change)
              expected-debit-account-name (->> game
                                               :game/users first
                                               :game.user/subscriptions first
                                               :game.stock/name
                                               (format "STOCK.%s"))

              expected-credit-value        (- (+ (-> repl.state/config :game/game :starting-balance) debit-value-change)
                                              debit-value-change)
              expected-credit-account-name "Cash"

              result-tentry (game.games/sell-stock! conn userId gameId stockId stockAmount tickId1 (Float. tickPrice1))

              {debit-account-name :bookkeeping.account/name
               debit-value        :bookkeeping.account/balance}
              (-> result-tentry
                  :bookkeeping.tentry/debits first
                  :bookkeeping.debit/account
                  (select-keys [:bookkeeping.account/name :bookkeeping.account/balance]))

              {credit-account-name :bookkeeping.account/name
               credit-value        :bookkeeping.account/balance}
              (-> result-tentry
                  :bookkeeping.tentry/credits first
                  :bookkeeping.credit/account
                  (select-keys [:bookkeeping.account/name :bookkeeping.account/balance]))]

          (are [x y] (= x y)
            expected-debit-value         debit-value
            expected-debit-account-name  debit-account-name
            expected-credit-value        credit-value
            expected-credit-account-name credit-account-name))))))
