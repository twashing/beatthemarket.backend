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
            [beatthemarket.bookkeeping.persistence :as bookkeeping.persistence]
            [beatthemarket.persistence.core :as persistence.core]
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

    (let [conn               (-> repl.state/system :persistence/datomic :opts :conn)
          result-user-id     (:db/id (test-util/generate-user! conn))
          sink-fn            identity
          {:keys [game tick-sleep-ms control-channel]
           :as   game-control} (game.games/create-game! conn result-user-id sink-fn)

          expected-game-control-keys
          #{:game :stocks-with-tick-data :tick-sleep-ms
            :stock-stream-channel :control-channel
            :close-sink-fn :sink-fn :profit-loss}]

      (->> game-control
           keys
           (into #{})
           (= expected-game-control-keys)
           is)

      (testing "Streaming subscription flows through the correct value"

        (let [test-chan    (core.async/chan)
              game-loop-fn (fn [a]
                             (core.async/>!! test-chan a))

              {{:keys [mixer
                       pause-chan
                       input-chan
                       output-chan] :as channel-controls}
               :channel-controls} (game.games/start-game! conn result-user-id game-control game-loop-fn)

              output                     (<!! test-chan)
              expected-transaction-count 8
              expected-stock-count       4
              expected-stock-tick-count  expected-stock-count]

          (are [x y] (= x y)
            expected-transaction-count (count output)
            expected-stock-tick-count  (count (filter :game.stock.tick/id output))
            expected-stock-count       (count (filter :game.stock/id output)))


          (testing "We are getting subscription as part of our stocks"

            (let [game-user-subscription (-> game
                                             :game/users first
                                             :game.user/subscriptions first)
                  [{{stock-tick :game.stock.tick/id} :game.stock/price-history}
                   {tick :game.stock.tick/id}]
                  (game.games/narrow-stock-tick-pairs-by-subscription output game-user-subscription)]

              (is (= stock-tick tick))


              (testing "Subscription ticks are in correct time order"

                (let [{t0-time :game.stock.tick/trade-time
                       id0 :game.stock.tick/id}
                      (-> (<!! test-chan)
                          (game.games/narrow-stock-tick-pairs-by-subscription game-user-subscription)
                          first
                          second)

                      {t1-time :game.stock.tick/trade-time
                       id1 :game.stock.tick/id}
                      (-> (<!! test-chan)
                          (game.games/narrow-stock-tick-pairs-by-subscription game-user-subscription)
                          first
                          second)]

                  (is (t/after?
                        (c/from-long t1-time)
                        (c/from-long t0-time)))

                  (testing "Two ticks streamed to client, got saved to the DB"

                      (let [conn (-> repl.state/system :persistence/datomic :opts :conn)

                            tick-id0 id0
                            tick-id1 id1]

                        (->> (d/q '[:find ?e
                                    :in $ [?tick-id ...]
                                    :where
                                    [?e :game.stock.tick/id ?tick-id]]
                                  (d/db conn)
                                  [tick-id0 tick-id1])
                             count
                             (= 2)
                             is)))))))

          (game.games/control-streams! control-channel channel-controls :exit))))))

(deftest buy-stock!-test

  (let [conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
         userId         :user/external-uid} (test-util/generate-user! conn)
        sink-fn                             identity
        {{gameId :game/id :as game} :game
         control-channel            :control-channel
         stock-stream-channel       :stock-stream-channel
         stocks-with-tick-data      :stocks-with-tick-data
         :as                        game-control}                  (game.games/create-game! conn result-user-id sink-fn)
        test-chan                           (core.async/chan)
        game-loop-fn                        (fn [a]
                                              (core.async/>!! test-chan a))
        {{:keys                             [mixer
                                             pause-chan
                                             input-chan
                                             output-chan] :as channel-controls}
         :channel-controls}                 (game.games/start-game! conn result-user-id game-control game-loop-fn)
        game-user-subscription              (-> game
                                                :game/users first
                                                :game.user/subscriptions first)
        stockId                             (:game.stock/id game-user-subscription)
        stockAmount                         100

        {tickPrice0 :game.stock.tick/close
         tickId0    :game.stock.tick/id}
        (-> (<!! test-chan)
            (game.games/narrow-stock-tick-pairs-by-subscription game-user-subscription)
            first
            second)

        {tickPrice1 :game.stock.tick/close
         tickId1    :game.stock.tick/id}
        (-> (<!! test-chan)
            (game.games/narrow-stock-tick-pairs-by-subscription game-user-subscription)
            first
            second)]

    (game.games/control-streams! control-channel channel-controls :exit)

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

(deftest sell-stock!-test

  (let [conn                                      (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
         userId         :user/external-uid}       (test-util/generate-user! conn)
        sink-fn                                   identity
        {{gameId :game/id :as game} :game
         control-channel            :control-channel
         stock-stream-channel       :stock-stream-channel
         stocks-with-tick-data      :stocks-with-tick-data
         :as                        game-control} (game.games/create-game! conn result-user-id sink-fn)
        test-chan                                 (core.async/chan)
        game-loop-fn                              (fn [a]
                                                    (core.async/>!! test-chan a))
        {{:keys                                           [mixer
                                                           pause-chan
                                                           input-chan
                                                           output-chan] :as channel-controls}
         :channel-controls}                       (game.games/start-game! conn result-user-id game-control game-loop-fn)
        game-user-subscription                    (-> game
                                                      :game/users first
                                                      :game.user/subscriptions first)
        stockId                                   (:game.stock/id game-user-subscription)
        stockAmount                               100

        {tickPrice0 :game.stock.tick/close
         tickId0    :game.stock.tick/id}
        (-> (<!! test-chan)
            (game.games/narrow-stock-tick-pairs-by-subscription game-user-subscription)
            first
            second)

        {tickPrice1 :game.stock.tick/close
         tickId1    :game.stock.tick/id}
        (-> (<!! test-chan)
            (game.games/narrow-stock-tick-pairs-by-subscription game-user-subscription)
            first
            second)]

    (game.games/control-streams! control-channel channel-controls :exit)

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

(defn- local-transact-stock! [{conn :conn
                              userId :userId
                              gameId :gameId
                              stockId :stockId}
                             {tickId      :game.stock.tick/id
                              tickPrice   :game.stock.tick/close
                              op          :op
                              stockAmount :stockAmount}]

  (case op
    :buy (game.games/buy-stock! conn userId gameId stockId stockAmount tickId (Float. tickPrice) false)
    :sell (game.games/sell-stock! conn userId gameId stockId stockAmount tickId (Float. tickPrice) false)
    :noop))

(deftest calculate-profit-loss-single-buy-sell-test

  (testing "Testing buy / sells with this pattern

            + 100
            - 100

            +200
            -200"

    (let [;; A
          conn                                      (-> repl.state/system :persistence/datomic :opts :conn)
          {result-user-id :db/id
           userId         :user/external-uid}       (test-util/generate-user! conn)

          ;; B
          data-sequence-A [100.0 110.0 , 120.0 130.0]
          tick-length (count data-sequence-A)

          ;; C create-game!
          sink-fn                                   identity
          {{gameId :game/id :as game} :game
           control-channel            :control-channel
           stock-stream-channel       :stock-stream-channel
           stocks-with-tick-data      :stocks-with-tick-data
           :as                        game-control} (game.games/create-game! conn result-user-id sink-fn data-sequence-A)
          test-chan                                 (core.async/chan)
          game-loop-fn                              (fn [a]
                                                      (when a (core.async/>!! test-chan a)))

          ;; D start-game!
          {{:keys                                           [mixer
                                                             pause-chan
                                                             input-chan
                                                             output-chan] :as channel-controls}
           :channel-controls}                       (game.games/start-game! conn result-user-id game-control game-loop-fn)
          game-user-subscription                    (-> game
                                                        :game/users first
                                                        :game.user/subscriptions first)
          stockId                                   (:game.stock/id game-user-subscription)


          ;; E subscription price history
          subscription-ticks (->> (repeatedly #(<!! test-chan))
                                  (take tick-length)
                                  (map #(second (first (game.games/narrow-stock-tick-pairs-by-subscription % game-user-subscription)))))

          opts {:conn conn
                :userId userId
                :gameId gameId
                :stockId stockId}]

      ;; F buy & sell
      (->> (map #(merge %1 %2)
                subscription-ticks
                [{:op :buy :stockAmount 100}
                 {:op :sell :stockAmount 100}
                 {:op :buy :stockAmount 200}
                 {:op :sell :stockAmount 200}])
           (run! (partial local-transact-stock! opts)))

      (game.games/control-streams! control-channel channel-controls :exit)

      (testing "Chunks Realized profit/losses are correctly calculated, for single buy/sell (multiple times)"

        (let [profit-loss (-> repl.state/system
                              :game/games deref (get gameId)
                              :profit-loss
                              (get stockId))

              [{gl1 :pershare-gain-or-loss} {gl2 :pershare-gain-or-loss}] (filter #(= :BUY (:op %)) profit-loss)
              [{pl1 :realized-profit-loss} {pl2 :realized-profit-loss}] (filter #(= :SELL (:op %)) profit-loss)]

          (are [x y] (= x y)
            10.0 gl1
            10.0 gl2
            1000.0 pl1
            2000.0 pl2)))

      (testing "We correct game.games/collect-realized-profit-loss"

        (-> (game.games/collect-realized-profit-loss gameId)
            (get stockId)
            (= 3000.0)
            is)))))

(deftest calculate-profit-loss-multiple-buy-single-sell-test

  (testing "Testing buy / sells with this pattern

            + 75
            + 25
            - 100

            + 120
            + 43
            + 37
            - 200"

    (let [;; A
          conn                                      (-> repl.state/system :persistence/datomic :opts :conn)
          {result-user-id :db/id
           userId         :user/external-uid}       (test-util/generate-user! conn)

          ;; B
          tick-length 7
          data-sequence-A (take tick-length (iterate (partial + 10) 100.00))

          ;; C create-game!
          sink-fn                                   identity
          {{gameId :game/id :as game} :game
           control-channel            :control-channel
           stock-stream-channel       :stock-stream-channel
           stocks-with-tick-data      :stocks-with-tick-data
           :as                        game-control} (game.games/create-game! conn result-user-id sink-fn data-sequence-A)
          test-chan                                 (core.async/chan)
          game-loop-fn                              (fn [a]
                                                      (when a (core.async/>!! test-chan a)))

          ;; D start-game!
          {{:keys                                           [mixer
                                                             pause-chan
                                                             input-chan
                                                             output-chan] :as channel-controls}
           :channel-controls}                       (game.games/start-game! conn result-user-id game-control game-loop-fn)
          game-user-subscription                    (-> game
                                                        :game/users first
                                                        :game.user/subscriptions first)
          stockId                                   (:game.stock/id game-user-subscription)


          ;; E subscription price history
          subscription-ticks (->> (repeatedly #(<!! test-chan))
                                  (take tick-length)
                                  (map #(second (first (game.games/narrow-stock-tick-pairs-by-subscription % game-user-subscription)))))

          opts {:conn conn
                :userId userId
                :gameId gameId
                :stockId stockId}]

      ;; F buy & sell
      (->> (map #(merge %1 %2)
                subscription-ticks
                [{:op :buy :stockAmount 75}
                 {:op :buy :stockAmount 25}
                 {:op :sell :stockAmount 100}
                 {:op :buy :stockAmount 120}
                 {:op :buy :stockAmount 43}
                 {:op :buy :stockAmount 37}
                 {:op :sell :stockAmount 200}])
           (run! (partial local-transact-stock! opts)))

      (game.games/control-streams! control-channel channel-controls :exit)

      (testing "Chunks Realized profit/losses are correctly calculated, for multiple buys, single sell (multiple times)"

        (let [profit-loss (-> repl.state/system
                              :game/games deref (get gameId)
                              :profit-loss
                              (get stockId))

              [{gl1 :pershare-gain-or-loss}
               {gl2 :pershare-gain-or-loss}

               {gl3 :pershare-gain-or-loss}
               {gl4 :pershare-gain-or-loss}
               {gl5 :pershare-gain-or-loss}] (filter #(= :BUY (:op %)) profit-loss)

              [{pl1 :realized-profit-loss}
               {pl2 :realized-profit-loss}] (filter #(= :SELL (:op %)) profit-loss)]

          (are [x y] (= x y)
            20.0 gl1
            10.0 gl2
            30.0 gl3
            20.0 gl4
            10.0 gl5
            2250.0 pl1
            (.floatValue 7425.21) pl2)))

      (testing "We correct game.games/collect-realized-profit-loss"

        (-> (game.games/collect-realized-profit-loss gameId)
            (get stockId)
            (= (.floatValue 9675.21))
            is)))))

(deftest calculate-profit-loss-multiple-buy-multiple-sell-test

  (testing "Testing buy / sells with this pattern

            + 75
            + 25
            - 13
            - 52
            - 35

            + 120
            + 43
            - 10
            - 20
            - 42

            + 37
            - 128"

    (let [;; A
          conn                                      (-> repl.state/system :persistence/datomic :opts :conn)
          {result-user-id :db/id
           userId         :user/external-uid}       (test-util/generate-user! conn)

          ;; B
          tick-length 12
          data-sequence-A (take tick-length (iterate (partial + 10) 100.00))

          ;; C create-game!
          sink-fn                                   identity
          {{gameId :game/id :as game} :game
           control-channel            :control-channel
           stock-stream-channel       :stock-stream-channel
           stocks-with-tick-data      :stocks-with-tick-data
           :as                        game-control} (game.games/create-game! conn result-user-id sink-fn data-sequence-A)
          test-chan                                 (core.async/chan)
          game-loop-fn                              (fn [a]
                                                      (when a (core.async/>!! test-chan a)))

          ;; D start-game!
          {{:keys                                           [mixer
                                                             pause-chan
                                                             input-chan
                                                             output-chan] :as channel-controls}
           :channel-controls}                       (game.games/start-game! conn result-user-id game-control game-loop-fn)
          game-user-subscription                    (-> game
                                                        :game/users first
                                                        :game.user/subscriptions first)
          stockId                                   (:game.stock/id game-user-subscription)


          ;; E subscription price history
          subscription-ticks (->> (repeatedly #(<!! test-chan))
                                  (take tick-length)
                                  (map #(second (first (game.games/narrow-stock-tick-pairs-by-subscription % game-user-subscription)))))

          opts {:conn conn
                :userId userId
                :gameId gameId
                :stockId stockId}]

      ;; F buy & sell
      (->> (map #(merge %1 %2)
                subscription-ticks
                [{:op :buy :stockAmount 75}
                 {:op :buy :stockAmount 25}
                 {:op :sell :stockAmount 13}
                 {:op :sell :stockAmount 52}
                 {:op :sell :stockAmount 35}

                 {:op :buy :stockAmount 120}
                 {:op :buy :stockAmount 43}
                 {:op :sell :stockAmount 10}
                 {:op :sell :stockAmount 20}
                 {:op :sell :stockAmount 42}

                 {:op :buy :stockAmount 37}
                 {:op :sell :stockAmount 128}])
           (run! (partial local-transact-stock! opts)))

      (game.games/control-streams! control-channel channel-controls :exit)

      (testing "Chunks Realized profit/losses are correctly calculated, for multiple buys, multiple sells (multiple times)"

        (let [profit-loss (-> repl.state/system
                              :game/games deref (get gameId)
                              :profit-loss
                              (get stockId))

              [{gl1 :pershare-gain-or-loss}
               {gl2 :pershare-gain-or-loss}
               {gl3 :pershare-gain-or-loss}
               {gl4 :pershare-gain-or-loss}
               {gl5 :pershare-gain-or-loss}
               {gl6 :pershare-gain-or-loss}
               {gl7 :pershare-gain-or-loss}
               {gl8 :pershare-gain-or-loss}
               {gl9 :pershare-gain-or-loss}
               {gl10 :pershare-gain-or-loss}
               {gl11 :pershare-gain-or-loss}
               {gl12 :pershare-gain-or-loss}] profit-loss

              [{pl1 :realized-profit-loss}
               {pl2 :realized-profit-loss}
               {pl3 :realized-profit-loss}
               {pl4 :realized-profit-loss}
               {pl5 :realized-profit-loss}
               {pl6 :realized-profit-loss}
               {pl7 :realized-profit-loss}] (filter #(= :SELL (:op %)) profit-loss)]

          (are [x y] (= x y)

            40.0 gl1
            30.0 gl2
            20.0 gl3
            10.0 gl4
            0.0 gl5

            60.0 gl6
            50.0 gl7
            40.0 gl8
            30.0 gl9
            20.0 gl10

            10.0 gl11
            0.0 gl12

            227.5 pl1
            (.floatValue 1721.38) pl2
            (.floatValue 1822.41) pl3
            (.floatValue 173.62) pl4
            (.floatValue 596.08) pl5
            (.floatValue 2049.47) pl6
            (.floatValue 11139.32) pl7)))

      (testing "We correct game.games/collect-realized-profit-loss"

        (-> (game.games/collect-realized-profit-loss gameId)
            (get stockId)
            (= (.floatValue 17729.78))
            is)))))
