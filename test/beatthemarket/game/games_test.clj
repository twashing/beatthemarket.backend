(ns beatthemarket.game.games-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as core.async
             :refer [go <! >!! <!!]]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]
            [datomic.client.api :as d]
            [integrant.repl.state :as repl.state]
            [beatthemarket.game.calculation :as game.calculation]
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

  (testing "Creating a game returns the expected game control keys"

    (let [conn           (-> repl.state/system :persistence/datomic :opts :conn)
          result-user-id (:db/id (test-util/generate-user! conn))
          sink-fn        identity
          game-control   (game.games/create-game! conn result-user-id sink-fn)

          expected-game-control-keys #{:game
                                       :profit-loss
                                       :stocks-with-tick-data
                                       :control-channel

                                       :paused?
                                       :level-timer-atom
                                       :tick-sleep-atom

                                       :transact-tick-xf
                                       :stream-stock-tick-xf
                                       :calculate-profit-loss-xf
                                       :transact-profit-loss-xf
                                       :stream-portfolio-update-xf
                                       :collect-profit-loss-xf

                                       :portfolio-update-stream
                                       :stock-tick-stream
                                       :game-event-stream

                                       :close-sink-fn
                                       :sink-fn}]

      (->> (keys game-control)
           (into #{})
           (= expected-game-control-keys )
           is))))

(deftest start-game!-test

  (let [conn                 (-> repl.state/system :persistence/datomic :opts :conn)
        result-user-id       (:db/id (test-util/generate-user! conn))
        data-sequence-B      [100.0 110.0 105.0 120.0 110.0 125.0 130.0]
        sink-fn              identity

        test-stock-ticks (atom [])
        test-portfolio-updates (atom [])

        stream-stock-tick-xf (map (fn [a]
                                    (swap! test-stock-ticks
                                           (fn [b]
                                             (let [stock-ticks (game.games/group-stock-tick-pairs a)]
                                               (conj b stock-ticks))))))
        stream-portfolio-update-xf (map (fn [a]
                                          (swap! test-portfolio-updates (fn [b] (conj b a)))))

        {{gameId :game/id
          game-db-id :db/id :as game} :game
         control-channel :control-channel
         :as game-control}
        (game.games/create-game! conn result-user-id sink-fn data-sequence-B
                                 stream-stock-tick-xf stream-portfolio-update-xf nil)]

    (testing "Chaining the control pipeline, produces the correct value"

      (let [data-sequence-count (count data-sequence-B)
            profit-loss-transact-to (game.games/chain-control-pipeline game-control {:conn conn :user-db-id result-user-id})]

        (run! (fn [_]
                (core.async/go
                  (core.async/<! profit-loss-transact-to)))
              data-sequence-B)

        (Thread/sleep 1000)

        (testing "Correct number of stock-tick AND portfolio-update values were streamed"

          (are [x y] (= x y)
            data-sequence-count (count @test-stock-ticks)
            data-sequence-count (count @test-portfolio-updates))

          (->> @test-stock-ticks
               (map #(map keys %))
               (map #(map (fn [a] (into #{} a)) %))
               (map #(every? (fn [a]
                               (= #{:game.stock.tick/id :game.stock.tick/trade-time :game.stock.tick/close
                                    :game.stock/id :game.stock/name}
                                  a)) %))
               (every? true?)
               is))

        (testing "The correct ticks were saved"

          (let [stockId (-> game :game/stocks first :game.stock/id)
                price-history (d/q '[:find (pull ?ph [*])
                                     :in $ ?game-id ?stock-id
                                     :where
                                     [?e :game/id ?game-id]
                                     [?e :game/stocks ?gs]
                                     [?gs :game.stock/id ?stock-id]
                                     [?gs :game.stock/price-history ?ph]]
                                   (d/db conn)
                                   gameId stockId)]

            (are [x y] (= x y)

              data-sequence-count (count price-history)

              (->> data-sequence-B
                   (map #(Float. %))
                   (into #{}))

              (->> price-history
                   (map (comp :game.stock.tick/close first))
                   (into #{})))

            (testing "Subscription ticks are in correct time order"

              (let

                  [{tickTime0 :game.stock.tick/trade-time
                    tickId0    :game.stock.tick/id}
                   (->> @test-stock-ticks
                        first
                        (filter #(= stockId (:game.stock/id %)))
                        first)

                   {tickTime1 :game.stock.tick/trade-time
                    tickId1    :game.stock.tick/id}
                   (->> @test-stock-ticks
                        second
                        (filter #(= stockId (:game.stock/id %)))
                        first)]

                (is (t/after?
                      (c/from-long tickTime1)
                      (c/from-long tickTime0)))

                (testing "Two ticks streamed to client, got saved to the DB"

                  (->> (d/q '[:find ?e
                              :in $ [?tick-id ...]
                              :where
                              [?e :game.stock.tick/id ?tick-id]]
                            (-> repl.state/system :persistence/datomic :opts :conn d/db)
                            [tickId0 tickId1])
                       count
                       (= 2)
                       is))))))))))

(deftest buy-stock!-test

  ;; A
  (let [conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
         userId         :user/external-uid} (test-util/generate-user! conn)
        data-sequence-B                     [100.0 110.0 105.0 120.0 110.0 125.0 130.0]
        sink-fn                             identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        stream-stock-tick-xf       (map (fn [a]
                                          (swap! test-stock-ticks
                                                 (fn [b]
                                                   (let [stock-ticks (game.games/group-stock-tick-pairs a)]
                                                     (conj b stock-ticks))))))
        stream-portfolio-update-xf (map (fn [a]
                                          (swap! test-portfolio-updates (fn [b] (conj b a)))))

        {{gameId     :game/id
          game-db-id :db/id :as game} :game
         control-channel              :control-channel
         :as              game-control}
        (game.games/create-game! conn result-user-id sink-fn data-sequence-B
                                 stream-stock-tick-xf stream-portfolio-update-xf nil)

        profit-loss-transact-to (game.games/start-game! conn result-user-id game-control)
        game-user-subscription  (-> game
                                    :game/users first
                                    :game.user/subscriptions first)
        stockId                 (:game.stock/id game-user-subscription)
        stockAmount             100]

    ;; B
    (run! (fn [_]
            (core.async/go
              (core.async/<! profit-loss-transact-to)))
          data-sequence-B)
    (Thread/sleep 1000)

    ;; C
    (let [{tickPrice0 :game.stock.tick/close
           tickId0    :game.stock.tick/id}
          (->> @test-stock-ticks first
               (filter #(= stockId (:game.stock/id %))) first)

          {tickPrice1 :game.stock.tick/close
           tickId1    :game.stock.tick/id}
          (->> @test-stock-ticks second
               (filter #(= stockId (:game.stock/id %))) first)]

      (testing "We are checking game is current and belongs to the user"
        (is (thrown? ExceptionInfo (game.games/buy-stock! conn userId "non-existant-game-id" stockId stockAmount tickId1 tickPrice1))))

      (testing "Error is thrown when submitted price does not match price from tickId"
        (is (thrown? ExceptionInfo (game.games/buy-stock! conn userId gameId stockId stockAmount tickId1 (Float. (- tickPrice1 1))))))

      (testing "Error is thrown when submitted tick is no the latest"
        (is (thrown? ExceptionInfo (game.games/buy-stock! conn userId gameId stockId stockAmount tickId0 (Float. tickPrice0)))))

      (testing "Returned Tentry matches what was submitted"
        (let [{tickPriceL :game.stock.tick/close
               tickIdL    :game.stock.tick/id}
              (->> @test-stock-ticks last
                   (filter #(= stockId (:game.stock/id %))) first)

              expected-credit-value        (Float. (format "%.2f" (* stockAmount tickPriceL)))
              expected-credit-account-name (->> game
                                                :game/users first
                                                :game.user/subscriptions first
                                                :game.stock/name
                                                (format "STOCK.%s"))

              expected-debit-value        (- (-> repl.state/config :game/game :starting-balance) expected-credit-value)
              expected-debit-account-name "Cash"

              result-tentry (game.games/buy-stock! conn userId gameId stockId stockAmount tickIdL (Float. tickPriceL))

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

          ;; TODO
          ;; Check portfolio-update-stream for
          ;; running-profit-loss
          ;; account-balances

          (are [x y] (= x y)
            expected-credit-value        credit-value
            expected-credit-account-name credit-account-name
            expected-debit-value         debit-value
            expected-debit-account-name  debit-account-name))))))

(deftest sell-stock!-test

  ;; A
  (let [conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
         userId         :user/external-uid} (test-util/generate-user! conn)
        data-sequence-B                     [100.0 110.0 105.0 120.0 110.0 125.0 130.0]
        sink-fn                             identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        stream-stock-tick-xf       (map (fn [a]
                                          (swap! test-stock-ticks
                                                 (fn [b]
                                                   (let [stock-ticks (game.games/group-stock-tick-pairs a)]
                                                     (conj b stock-ticks))))))
        stream-portfolio-update-xf (map (fn [a]
                                          (swap! test-portfolio-updates (fn [b] (conj b a)))))

        {{gameId     :game/id
          game-db-id :db/id :as game} :game
         control-channel              :control-channel
         :as              game-control}
        (game.games/create-game! conn result-user-id sink-fn data-sequence-B
                                 stream-stock-tick-xf stream-portfolio-update-xf nil)

        profit-loss-transact-to (game.games/start-game! conn result-user-id game-control)
        game-user-subscription  (-> game
                                    :game/users first
                                    :game.user/subscriptions first)
        stockId                 (:game.stock/id game-user-subscription)
        stockAmount             100]

    ;; B
    (run! (fn [_]
            (core.async/go
              (core.async/<! profit-loss-transact-to)))
          data-sequence-B)
    (Thread/sleep 1000)

    ;; C
    (let [{tickPrice0 :game.stock.tick/close
           tickId0    :game.stock.tick/id}
          (->> @test-stock-ticks first
               (filter #(= stockId (:game.stock/id %))) first)

          {tickPrice1 :game.stock.tick/close
           tickId1    :game.stock.tick/id}
          (->> @test-stock-ticks second
               (filter #(= stockId (:game.stock/id %))) first)]

      (testing "We are checking game is current and belongs to the user"
        (is (thrown? ExceptionInfo (game.games/sell-stock! conn userId "non-existant-game-id" stockId stockAmount tickId1 tickPrice1))))

      (testing "Error is thrown when submitted price does not match price from tickId"
        (is (thrown? ExceptionInfo (game.games/sell-stock! conn userId gameId stockId stockAmount tickId1 (Float. (- tickPrice1 1))))))

      (testing "Error is thrown when submitted tick is no the latest"
        (is (thrown? ExceptionInfo (game.games/sell-stock! conn userId gameId stockId stockAmount tickId0 (Float. tickPrice0)))))

      (testing "Ensure we have the stock on hand before selling"

        (let [{tickPriceL :game.stock.tick/close
               tickIdL    :game.stock.tick/id}
              (->> @test-stock-ticks last
                   (filter #(= stockId (:game.stock/id %))) first)]

          (game.games/buy-stock! conn userId gameId stockId stockAmount tickIdL (Float. tickPriceL))

          (testing "Returned Tentry matches what was submitted"

            (let [initial-debit-value         0.0
                  debit-value-change          (Float. (format "%.2f" (* stockAmount tickPriceL)))
                  expected-debit-value        (- (+ initial-debit-value debit-value-change) debit-value-change)
                  expected-debit-account-name (->> game
                                                   :game/users first
                                                   :game.user/subscriptions first
                                                   :game.stock/name
                                                   (format "STOCK.%s"))

                  expected-credit-value        (- (+ (-> repl.state/config :game/game :starting-balance) debit-value-change)
                                                  debit-value-change)
                  expected-credit-account-name "Cash"

                  result-tentry (game.games/sell-stock! conn userId gameId stockId stockAmount tickIdL (Float. tickPriceL))

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
                expected-credit-account-name credit-account-name))))))))

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
          conn                 (-> repl.state/system :persistence/datomic :opts :conn)
          {result-user-id :db/id
           userId         :user/external-uid} (test-util/generate-user! conn)

          ;; B
          data-sequence-A [100.0 110.0 , 120.0 130.0]
          tick-length (count data-sequence-A)

          ;; C create-game!
          sink-fn              identity

          test-stock-ticks (atom [])
          test-portfolio-updates (atom [])

          stream-stock-tick-xf (map (fn [a]
                                      (swap! test-stock-ticks
                                             (fn [b]
                                               (let [stock-ticks (game.games/group-stock-tick-pairs a)]
                                                 (conj b stock-ticks))))))
          stream-portfolio-update-xf (map (fn [a]
                                            (swap! test-portfolio-updates (fn [b] (conj b a)))))

          {{gameId :game/id
            game-db-id :db/id :as game} :game
           control-channel :control-channel
           :as game-control}
          (game.games/create-game! conn result-user-id sink-fn data-sequence-A
                                   stream-stock-tick-xf stream-portfolio-update-xf nil)

          profit-loss-transact-to (game.games/start-game! conn result-user-id game-control)
          game-user-subscription  (-> game
                                      :game/users first
                                      :game.user/subscriptions first)
          stockId (:game.stock/id game-user-subscription)
          opts {:conn conn
                :userId userId
                :gameId gameId
                :stockId stockId}]

      (run! (fn [_]
              (core.async/go
                (core.async/<! profit-loss-transact-to)))
            data-sequence-A)
      (Thread/sleep 1000)

      (let [stock-ticks (->> @test-stock-ticks
                             (map (fn [a] (filter #(= stockId (:game.stock/id %)) a)))
                             flatten)]

        ;; F buy & sell
        (->> (map #(merge %1 %2)
                  stock-ticks
                  [{:op :buy :stockAmount 100}
                   {:op :sell :stockAmount 100}
                   {:op :buy :stockAmount 200}
                   {:op :sell :stockAmount 200}])
             (run! (partial local-transact-stock! opts))))

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

        (-> (game.calculation/collect-realized-profit-loss gameId)
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
            conn                 (-> repl.state/system :persistence/datomic :opts :conn)
            {result-user-id :db/id
             userId         :user/external-uid} (test-util/generate-user! conn)

            ;; B
            tick-length 7
            data-sequence-A (take tick-length (iterate (partial + 10) 100.00))

            ;; C create-game!
            sink-fn              identity

            test-stock-ticks (atom [])
            test-portfolio-updates (atom [])

            stream-stock-tick-xf (map (fn [a]
                                        (swap! test-stock-ticks
                                               (fn [b]
                                                 (let [stock-ticks (game.games/group-stock-tick-pairs a)]
                                                   (conj b stock-ticks))))))
            stream-portfolio-update-xf (map (fn [a]
                                              (swap! test-portfolio-updates (fn [b] (conj b a)))))

            {{gameId :game/id
              game-db-id :db/id :as game} :game
             control-channel :control-channel
             :as game-control}
            (game.games/create-game! conn result-user-id sink-fn data-sequence-A
                                     stream-stock-tick-xf stream-portfolio-update-xf nil)

            profit-loss-transact-to (game.games/start-game! conn result-user-id game-control)
            game-user-subscription  (-> game
                                        :game/users first
                                        :game.user/subscriptions first)
            stockId (:game.stock/id game-user-subscription)
            opts {:conn conn
                  :userId userId
                  :gameId gameId
                  :stockId stockId}]

        (run! (fn [_]
                (core.async/go
                  (core.async/<! profit-loss-transact-to)))
              data-sequence-A)
        (Thread/sleep 1000)

        ;; F buy & sell
        (let [stock-ticks (->> @test-stock-ticks
                               (map (fn [a] (filter #(= stockId (:game.stock/id %)) a)))
                               flatten)]

          (->> (map #(merge %1 %2)
                    stock-ticks
                    [{:op :buy :stockAmount 75}
                     {:op :buy :stockAmount 25}
                     {:op :sell :stockAmount 100}
                     {:op :buy :stockAmount 120}
                     {:op :buy :stockAmount 43}
                     {:op :buy :stockAmount 37}
                     {:op :sell :stockAmount 200}])
               (run! (partial local-transact-stock! opts)))

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
                (.floatValue 7425.21) pl2))))

        (testing "game.calculation game.games/collect-realized-profit-loss"

          (-> (game.calculation/collect-realized-profit-loss gameId)
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
            conn                 (-> repl.state/system :persistence/datomic :opts :conn)
            {result-user-id :db/id
             userId         :user/external-uid} (test-util/generate-user! conn)

            ;; B
            tick-length 12
            data-sequence-A (take tick-length (iterate (partial + 10) 100.00))


            ;; C create-game!
            sink-fn              identity

            test-stock-ticks (atom [])
            test-portfolio-updates (atom [])

            stream-stock-tick-xf (map (fn [a]
                                        (swap! test-stock-ticks
                                               (fn [b]
                                                 (let [stock-ticks (game.games/group-stock-tick-pairs a)]
                                                   (conj b stock-ticks))))))
            stream-portfolio-update-xf (map (fn [a]
                                              (swap! test-portfolio-updates (fn [b] (conj b a)))))

            {{gameId :game/id
              game-db-id :db/id :as game} :game
             control-channel :control-channel
             :as game-control}
            (game.games/create-game! conn result-user-id sink-fn data-sequence-A
                                     stream-stock-tick-xf stream-portfolio-update-xf nil)

            profit-loss-transact-to (game.games/start-game! conn result-user-id game-control)
            game-user-subscription  (-> game
                                        :game/users first
                                        :game.user/subscriptions first)
            stockId (:game.stock/id game-user-subscription)
            opts {:conn conn
                  :userId userId
                  :gameId gameId
                  :stockId stockId}]

        (run! (fn [_]
                (core.async/go
                  (core.async/<! profit-loss-transact-to)))
              data-sequence-A)
        (Thread/sleep 1000)

        (let [stock-ticks (->> @test-stock-ticks
                               (map (fn [a] (filter #(= stockId (:game.stock/id %)) a)))
                               flatten)]

          ;; F buy & sell
          (->> (map #(merge %1 %2)
                    stock-ticks
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
               (run! (partial local-transact-stock! opts))))

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

        (testing "game.calculation game.games/collect-realized-profit-loss"

          (-> (game.calculation/collect-realized-profit-loss gameId)
              (get stockId)
              (= (.floatValue 17729.78))
              is)))))

(def updated-profit-loss
  '({:amount 75
     :latest-price->trade-price [140.0 100.0]
     :A 0.0
     :stock-account-amount 75
     :credit-account-id #uuid "f43d1a7c-3a15-412f-93e5-6de18f065528"
     :op :BUY
     :credit-account-name "STOCK.Happy Forehead"
     :trade-price 100.0
     :pershare-gain-or-loss 40.0
     :pershare-purchase-ratio 25/29}
    {:amount 25
     :latest-price->trade-price [140.0 110.0]
     :A 0.0
     :stock-account-amount 100
     :credit-account-id #uuid "f43d1a7c-3a15-412f-93e5-6de18f065528"
     :op :BUY
     :credit-account-name "STOCK.Happy Forehead"
     :trade-price 110.0
     :pershare-gain-or-loss 30.0
     :pershare-purchase-ratio 25/87}
    {:amount 13
     :realized-profit-loss 227.5
     :latest-price->trade-price [140.0 120.0]
     :debit-account-id #uuid "f43d1a7c-3a15-412f-93e5-6de18f065528"
     :stock-account-amount 87
     :op :SELL
     :debit-account-name "STOCK.Happy Forehead"
     :trade-price 120.0
     :pershare-gain-or-loss 20.0
     :pershare-purchase-ratio 13/87}
    {:amount 52
     :realized-profit-loss 1721.38
     :latest-price->trade-price [140.0 130.0]
     :debit-account-id #uuid "f43d1a7c-3a15-412f-93e5-6de18f065528"
     :stock-account-amount 35
     :op :SELL
     :debit-account-name "STOCK.Happy Forehead"
     :trade-price 130.0
     :pershare-gain-or-loss 10.0
     :pershare-purchase-ratio 52/87}
    {:amount 35
     :realized-profit-loss 1822.41
     :latest-price->trade-price [140.0 140.0]
     :debit-account-id #uuid "f43d1a7c-3a15-412f-93e5-6de18f065528"
     :stock-account-amount 0
     :op :SELL
     :debit-account-name "STOCK.Happy Forehead"
     :trade-price 140.0
     :pershare-gain-or-loss 0.0}
    {:amount 120
     :latest-price->trade-price [210.0 150.0]
     :A 0.0
     :stock-account-amount 120
     :credit-account-id #uuid "f43d1a7c-3a15-412f-93e5-6de18f065528"
     :op :BUY
     :credit-account-name "STOCK.Happy Forehead"
     :trade-price 150.0
     :pershare-gain-or-loss 60.0
     :pershare-purchase-ratio 120/133}
    {:amount 43
     :latest-price->trade-price [210.0 160.0]
     :A 0.0
     :stock-account-amount 163
     :credit-account-id #uuid "f43d1a7c-3a15-412f-93e5-6de18f065528"
     :op :BUY
     :credit-account-name "STOCK.Happy Forehead"
     :trade-price 160.0
     :pershare-gain-or-loss 50.0
     :pershare-purchase-ratio 43/133}
    {:amount 10
     :realized-profit-loss 173.62
     :latest-price->trade-price [210.0 170.0]
     :debit-account-id #uuid "f43d1a7c-3a15-412f-93e5-6de18f065528"
     :stock-account-amount 153
     :op :SELL
     :debit-account-name "STOCK.Happy Forehead"
     :trade-price 170.0
     :pershare-gain-or-loss 40.0
     :pershare-purchase-ratio 10/133}
    {:amount 20
     :realized-profit-loss 596.08
     :latest-price->trade-price [210.0 180.0]
     :debit-account-id #uuid "f43d1a7c-3a15-412f-93e5-6de18f065528"
     :stock-account-amount 133
     :op :SELL
     :debit-account-name "STOCK.Happy Forehead"
     :trade-price 180.0
     :pershare-gain-or-loss 30.0
     :pershare-purchase-ratio 20/133}
    {:amount 42
     :realized-profit-loss 2049.47
     :latest-price->trade-price [210.0 190.0]
     :debit-account-id #uuid "f43d1a7c-3a15-412f-93e5-6de18f065528"
     :stock-account-amount 91
     :op :SELL
     :debit-account-name "STOCK.Happy Forehead"
     :trade-price 190.0
     :pershare-gain-or-loss 20.0
     :pershare-purchase-ratio 6/19}
    {:amount 37
     :latest-price->trade-price [210.0 200.0]
     :A 0.0
     :stock-account-amount 128
     :credit-account-id #uuid "f43d1a7c-3a15-412f-93e5-6de18f065528"
     :op :BUY
     :credit-account-name "STOCK.Happy Forehead"
     :trade-price 200.0
     :pershare-gain-or-loss 10.0
     :pershare-purchase-ratio 37/128}
    {:amount 128
     :realized-profit-loss 11139.32
     :latest-price->trade-price [210.0 210.0]
     :debit-account-id #uuid "f43d1a7c-3a15-412f-93e5-6de18f065528"
     :stock-account-amount 0
     :op :SELL
     :debit-account-name "STOCK.Happy Forehead"
     :trade-price 210.0
     :pershare-gain-or-loss 0.0}))

(deftest calculate-profit-loss-on-tick-test

  (testing "Calculate and update P/L on streaming ticks. These are previous purchase patterns.

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
            conn                                (-> repl.state/system :persistence/datomic :opts :conn)
            {result-user-id :db/id
             userId         :user/external-uid} (test-util/generate-user! conn)

            ;; B
            tick-length     2
            data-sequence-A (take tick-length (iterate (partial + 10) 250.00))

            ;; C create-game!
            sink-fn identity

            stock-tick-result       (atom [])
            portfolio-update-result (atom [])
            profit-loss-history      (atom [])

            kludgeGameId* (atom nil)
            collect-profit-loss-xf (map (fn [profit-loss]
                                          (swap! profit-loss-history #(conj % profit-loss))
                                          (game.calculation/collect-running-profit-loss @kludgeGameId* profit-loss)))

            {{gameId     :game/id
              game-db-id :db/id :as game} :game
             control-channel              :control-channel
             stock-tick-stream            :stock-tick-stream
             portfolio-update-stream      :portfolio-update-stream
             :as                          game-control}
            (game.games/create-game! conn result-user-id sink-fn data-sequence-A nil nil collect-profit-loss-xf)
            _ (reset! kludgeGameId* gameId)


            ;; D start-game!
            profit-loss-transact-to (game.games/start-game! conn result-user-id game-control)
            game-user-subscription  (-> game
                                        :game/users first
                                        :game.user/subscriptions first)
            stockId                 (:game.stock/id game-user-subscription)]


        ;; E reset a P/L structure
        (swap! (:game/games repl.state/system)
               (fn [gs]
                 (update-in gs [gameId :profit-loss stockId] (constantly updated-profit-loss))))


        ;; F Collect streaming values
         (core.async/go-loop []
           (let [a (core.async/<! stock-tick-stream)]
             (when a
               (swap! stock-tick-result #(conj % a))
               (recur))))
         (core.async/go-loop []
           (let [a (core.async/<! portfolio-update-stream)]
             (when a
               (swap! portfolio-update-result #(conj % a))
               (recur))))


        ;; G Run game
        (run! (fn [_]
                (core.async/go
                  (core.async/<! profit-loss-transact-to)))
              data-sequence-A)
        (Thread/sleep 2000)


        (testing "Correctly streaming stock-tick and profit-loss events"

          (let [[m0 m1] ((juxt first second) @portfolio-update-result)]

            (->> @stock-tick-result
                 (map #(map keys %))
                 (map #(map (fn [a] (into #{} a)) %))
                 (map #(every? (fn [a]
                                 (= #{:game.stock.tick/id :game.stock.tick/trade-time :game.stock.tick/close
                                      :game.stock/id :game.stock/name}
                                    a)) %))
                 (every? true?)
                 is)

            (are [x y] (= x y)
              (.floatValue 21464.51) (-> m0 seq first second)
              (.floatValue 24046.62) (-> m1 seq first second))))

        (testing "Correctly recalculating profit-loss on a tick update (:running-profit-loss increases)"

          (let [extract-pl #(->> (get % stockId)
                                 (filter :running-profit-loss)
                                 (map :running-profit-loss))
                [pl0 pl1] ((juxt first second) @profit-loss-history)]

            (->> (map (fn [l r]
                        (< l r))
                      (extract-pl pl0)
                      (extract-pl pl1))
                 (every? true?)
                 is))))))

(deftest stream-portfolio-update-on-transact-test

  ;; A
  (let [conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
         userId         :user/external-uid} (test-util/generate-user! conn)
        data-sequence-B                     [100.0 110.0 105.0 120.0 110.0 125.0 130.0]
        sink-fn                             identity

        ;; test-stock-ticks       (atom [])
        ;; test-portfolio-updates (atom [])
        ;; stream-stock-tick-xf       (map (fn [a]
        ;;                                   (swap! test-stock-ticks
        ;;                                          (fn [b]
        ;;                                            (let [stock-ticks (game.games/group-stock-tick-pairs a)]
        ;;                                              (conj b stock-ticks))))))
        ;; stream-portfolio-update-xf (map (fn [a]
        ;;                                   (swap! test-portfolio-updates (fn [b] (conj b a)))))

        {{gameId :game/id :as game} :game
         control-channel            :control-channel
         stock-tick-stream          :stock-tick-stream
         portfolio-update-stream    :portfolio-update-stream
         :as                        game-control}
        (game.games/create-game! conn result-user-id sink-fn data-sequence-B
                                 nil nil nil)

        portfolio-update-result (atom [])
        profit-loss-transact-to (game.games/start-game! conn result-user-id game-control)
        stockId (-> game
                    :game/users first
                    :game.user/subscriptions first
                    :game.stock/id)
        stockAmount             100]

    (core.async/go-loop []
      (let [a (core.async/<! portfolio-update-stream)]
        (when a
          (swap! portfolio-update-result #(conj % a))
          (recur))))

    ;; B
    (run! (fn [_]
            (core.async/go
              (core.async/<! profit-loss-transact-to)))
          data-sequence-B)

    ;; C
    (let [test-stock-ticks       (atom [])
          f (fn [a]
              (swap! test-stock-ticks
                     #(conj % a)))]

      ;; D Collect stock-tick-stream values
      (run! (fn [_]
              (core.async/go
                (f (core.async/<! stock-tick-stream))))
            data-sequence-B)
      (Thread/sleep 1000)


      (testing "We are correctly streaming running-profit-loss and account-balances updates"

        (let [{tickPriceL :game.stock.tick/close
               tickIdL    :game.stock.tick/id}
              (->> @test-stock-ticks last
                   (filter #(= stockId (:game.stock/id %))) first)]

          (game.games/buy-stock! conn userId gameId stockId stockAmount tickIdL (Float. tickPriceL) false)
          (Thread/sleep 1000)

          ;; profit-loss and account-balances shapes should look like this
          '(({:game-id #uuid "afffbd97-4a26-4e6c-aa68-e63945f77e8e"
              :stock-id #uuid "8c8518e9-0601-443d-a26a-af3c45e5ac21"
              :profit-loss-type :running-profit-loss
              :profit-loss 0.0})

            (#{:bookkeeping.account/id #uuid "113c18ef-ccf3-4b2d-bf4e-a8bcb9869f05"
               :bookkeeping.account/name "STOCK.Relative Waste"
               :bookkeeping.account/balance 10500.0
               :bookkeeping.account/amount 100
               :bookkeeping.account/counter-party #:game.stock{:name "Relative Waste"}}
              #{:bookkeeping.account/id #uuid "a99ee1be-da48-401f-af63-e84fb1058a7c"
                :bookkeeping.account/name "Cash"
                :bookkeeping.account/balance 89500.0
                :bookkeeping.account/amount 0}
              #{:bookkeeping.account/id #uuid "e47c40e0-18c8-41f9-afd1-5eeae5e92472"
                :bookkeeping.account/name "Equity"
                :bookkeeping.account/balance 100000.0
                :bookkeeping.account/amount 0}))

          (let [expected-profit-loss-count 1
                expected-account-balance-count 3
                [profit-loss account-balances] (util/pprint+identity (filter (comp not empty?) @portfolio-update-result))]

            (are [x y] (= x y)
              expected-account-balance-count (count account-balances)
              expected-profit-loss-count (count profit-loss))

            (->> (map keys account-balances)
                 (map #(into #{} %))
                 (every? #(or (= #{:bookkeeping.account/id :bookkeeping.account/name :bookkeeping.account/balance
                                   :bookkeeping.account/amount} %)
                              (= #{:bookkeeping.account/id :bookkeeping.account/name :bookkeeping.account/balance
                                   :bookkeeping.account/amount :bookkeeping.account/counter-party} %)))
                 is)

            (->> (map keys profit-loss)
                 (map #(into #{} %))
                 (every? #(= #{:game-id :stock-id :profit-loss-type :profit-loss} %))
                 is)))))))
