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

    (let [conn               (-> repl.state/system :persistence/datomic :conn)
          result-user-id     (:db/id (test-util/generate-user! conn))
          sink-fn            identity
          {:keys [game tick-sleep-ms control-channel]
           :as   game-control} (game.games/create-game! conn result-user-id sink-fn)

          expected-game-control-keys
          (sort '(:game :stocks-with-tick-data :tick-sleep-ms :stock-stream-channel :control-channel :close-sink-fn :sink-fn))]

      (->> game-control
           keys
           sort
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

                      (let [conn (-> repl.state/system :persistence/datomic :conn)

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

  (let [conn                                (-> repl.state/system :persistence/datomic :conn)
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

  (let [conn                                      (-> repl.state/system :persistence/datomic :conn)
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

(deftest collect-pershare-price-statistics-A-test

  (testing "Testing buy / sells with this pattern

            + 75
            + 25
            - 100"

    (let [;; A
          conn                                      (-> repl.state/system :persistence/datomic :conn)
          {result-user-id :db/id
           userId         :user/external-uid}       (test-util/generate-user! conn)

          ;; B
          tick-length 4
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

          local-transact-stock! (fn [{tickId      :game.stock.tick/id
                                     tickPrice   :game.stock.tick/close
                                     op          :op
                                     stockAmount :stockAmount}]

                                  (case op
                                    :buy (game.games/buy-stock! conn userId gameId stockId stockAmount tickId (Float. tickPrice) false)
                                    :sell (game.games/sell-stock! conn userId gameId stockId stockAmount tickId (Float. tickPrice) false)
                                    :noop))]

      ;; F buy & sell
      (->> (map #(merge %1 %2)
                subscription-ticks
                [{}
                 {:op :buy :stockAmount 75}
                 {:op :buy :stockAmount 25}
                 {}])
           (run! local-transact-stock!))


      (game.games/control-streams! control-channel channel-controls :exit)

      (testing "Initial stock purchases give us our expected profit level"

        (let [expected-profit-A (+ (-> 100000.0
                                       (#(- % (* 75 110)))
                                       (#(- % (* 25 120))))

                                   (-> 0
                                       (#(+ % (* 75 110)))
                                       (#(+ % (* 25 120)))))

              expected-profit-B (-> 100000.0
                                    (#(- % (* 75 110)))
                                    (#(- % (* 25 120)))
                                    (#(+ % (* 100 130))))]

          (is (= (.floatValue expected-profit-A)
                 (.floatValue (game.games/collect-pershare-price-statistics conn result-user-id gameId))))

          (testing "Selling stocks correctly adjusts profit level B"

            (-> (last subscription-ticks)
                (merge {:op :sell :stockAmount 100})
                local-transact-stock!)

            (is (= (.floatValue expected-profit-B)
                   (.floatValue (game.games/collect-pershare-price-statistics conn result-user-id gameId))))))))))

(deftest collect-pershare-price-statistics-B-test

  (testing "Testing buy / sells with this patterns

            + 35
            + 35
            + 30
            - 100"

    (let [;; A
          conn                                      (-> repl.state/system :persistence/datomic :conn)
          {result-user-id :db/id
           userId         :user/external-uid}       (test-util/generate-user! conn)

          ;; B
          tick-length 5
          data-sequence-B [100.0 110.0 105.0 120.0 110.0 125.0 130.0]

          ;; C create-game!
          sink-fn                                   identity
          {{gameId :game/id :as game} :game
           control-channel            :control-channel
           stock-stream-channel       :stock-stream-channel
           stocks-with-tick-data      :stocks-with-tick-data
           :as                        game-control} (game.games/create-game! conn result-user-id sink-fn data-sequence-B)
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

          local-transact-stock! (fn [{tickId      :game.stock.tick/id
                                     tickPrice   :game.stock.tick/close
                                     op          :op
                                     stockAmount :stockAmount}]

                                  (case op
                                    :buy (game.games/buy-stock! conn userId gameId stockId stockAmount tickId (Float. tickPrice) false)
                                    :sell (game.games/sell-stock! conn userId gameId stockId stockAmount tickId (Float. tickPrice) false)
                                    :noop))]


      ;; F buy & sell
      (->> (map #(merge %1 %2)
                subscription-ticks
                [{:op :buy :stockAmount 35}
                 {:op :buy :stockAmount 35}
                 {:op :buy :stockAmount 30}
                 {}])
           (run! local-transact-stock!))


      (game.games/control-streams! control-channel channel-controls :exit)

      (testing "Initial stock purchases give us our expected profit level"

        (let [expected-profit-A (+ (-> 100000.0
                                       (#(- % (* 35 100)))
                                       (#(- % (* 35 110)))
                                       (#(- % (* 30 105))))

                                   (-> 0
                                       (#(+ % (* 35 100)))
                                       (#(+ % (* 35 110)))
                                       (#(+ % (* 30 105)))))

              expected-profit-B (-> 100000.0
                                    (#(- % (* 35 100)))
                                    (#(- % (* 35 110)))
                                    (#(- % (* 30 105)))
                                    (#(+ % (* 100 110))))]

          (is (= (.floatValue expected-profit-A)
                 (.floatValue (game.games/collect-pershare-price-statistics conn result-user-id gameId))))

          (testing "Selling stocks correctly adjusts profit level B"

            (-> (last subscription-ticks)
                (merge {:op :sell :stockAmount 100})
                local-transact-stock!)

            (is (= (.floatValue expected-profit-B)
                   (.floatValue (game.games/collect-pershare-price-statistics conn result-user-id gameId))))))))))

(deftest collect-pershare-price-statistics-C-test

  (testing "Testing buy / sells with this patterns

            + 25
            + 25
            - 35

            + 25
            - 35

            + 25
            - 30"

    (let [;; A
          conn                                (-> repl.state/system :persistence/datomic :conn)
          {result-user-id :db/id
           userId         :user/external-uid} (test-util/generate-user! conn)

          ;; B
          data-sequence-B [100.0 110.0 105.0 120.0 110.0 125.0 130.0]
          tick-length     (count data-sequence-B)

          ;; C create-game!
          sink-fn                                   identity
          {{gameId     :game/id
            game-db-id :db/id :as game} :game
           control-channel              :control-channel
           stock-stream-channel         :stock-stream-channel
           stocks-with-tick-data        :stocks-with-tick-data
           :as                          game-control} (game.games/create-game! conn result-user-id sink-fn data-sequence-B)
          test-chan                                 (core.async/chan)
          game-loop-fn                              (fn [a]
                                                      (when a (core.async/>!! test-chan a)))

          ;; D start-game!
          {{:keys [mixer
                   pause-chan
                   input-chan
                   output-chan] :as channel-controls}
           :channel-controls}    (game.games/start-game! conn result-user-id game-control game-loop-fn)
          game-user-subscription (-> game
                                     :game/users first
                                     :game.user/subscriptions first)
          stockId                (:game.stock/id game-user-subscription)


          ;; E subscription price history
          subscription-ticks (->> (repeatedly #(<!! test-chan))
                                  (take tick-length)
                                  doall
                                  (map #(second (first (game.games/narrow-stock-tick-pairs-by-subscription % game-user-subscription)))))

          local-transact-stock! (fn [{tickId      :game.stock.tick/id
                                     tickPrice   :game.stock.tick/close
                                     op          :op
                                     stockAmount :stockAmount}]

                                  (case op
                                    :buy  (game.games/buy-stock! conn userId gameId stockId stockAmount tickId (Float. tickPrice) false)
                                    :sell (game.games/sell-stock! conn userId gameId stockId stockAmount tickId (Float. tickPrice) false)
                                    :noop))]


      ;; F buy & sell
      (->> (map #(merge %1 %2)
                subscription-ticks
                [{:op :buy :stockAmount 25}
                 {:op :buy :stockAmount 25}
                 {:op :sell :stockAmount 35}])
           (run! local-transact-stock!))

      (game.games/control-streams! control-channel channel-controls :exit)

      (testing "Initial stock purchases give us our expected profit level"

        (let [expected-profit-A (+ (-> 0.0 #_100000.0
                                       (#(- % (* 25 100.0))) trace
                                       (#(- % (* 25 110.0))) trace
                                       (#(+ % (* 35 105.0))) trace)

                                   (-> 0
                                       (#(+ % (* 25 100.0))) trace
                                       (#(+ % (* 25 110.0))) trace
                                       (#(- % (* 35 105.0))) trace))

              expected-profit-B (+ (-> 100000.0
                                       (#(- % (* 25 100.0)))
                                       (#(- % (* 25 110.0)))
                                       (#(+ % (* 35 105.0)))

                                       (#(- % (* 25 120.0)))
                                       (#(+ % (* 35 110.0)))
                                       #_trace)

                                   (-> 0
                                       (#(+ % (* 25 100.0)))
                                       (#(+ % (* 25 110.0)))
                                       (#(- % (* 35 105.0)))

                                       (#(+ % (* 25 120.0)))
                                       (#(- % (* 35 110.0)))
                                       #_trace))

              expected-profit-C (-> 100000.0
                                    (#(- % (* 25 100.0)))
                                    (#(- % (* 25 110.0)))
                                    (#(+ % (* 35 105.0)))

                                    (#(- % (* 25 120.0)))
                                    (#(+ % (* 35 110.0)))

                                    (#(- % (* 25 125.0)))
                                    (#(+ % (* 35 130.0)))
                                    #_trace)]

          ;; [100.0 110.0 105.0 , 120.0 110.0 , 125.0 130.0]
          ;; [100.0 110.0 105.0 , 120.0 110.0 , 125.0 130.0]

          ;; (game.games/collect-pershare-price-statistics conn result-user-id gameId)

          ;; (println "D /")
          ;; (util/pprint+identity (persistence.core/pull-entity conn game-db-id))


          ;; TODO pershare profit @ t and each tick
          ;;   running proft/loss

          ;; TODO match corresponding sells



          (is false)
          #_(is (= (.floatValue expected-profit-A)
                 (.floatValue (game.games/collect-pershare-price-statistics conn result-user-id gameId))))

          #_(testing "Selling stocks correctly adjusts profit level B"

              (->> (map #(merge %1 %2)
                        (drop 3 subscription-ticks)
                        [{:op :buy :stockAmount 25}
                         {:op :sell :stockAmount 35}])
                   util/pprint+identity
                   (run! local-transact-stock!))

              (is (= (.floatValue expected-profit-B)
                     (.floatValue (game.games/collect-pershare-price-statistics conn result-user-id gameId)))))

          #_(testing "Selling stocks correctly adjusts profit level C"

              (->> (map #(merge %1 %2)
                        (drop 5 subscription-ticks)
                        [{:op :buy :stockAmount 25}
                         {:op :sell :stockAmount 30}])
                   (run! local-transact-stock!))

              (is (= (.floatValue expected-profit-C)
                     (.floatValue (game.games/collect-pershare-price-statistics conn result-user-id gameId))))))))))


;; ;; C.
;; ;; Calculate Profit / Loss
;;
;;
;;
;; >> Verification
;; should equal sum of all profits + losses from trades
;;
;;
;; ====
;;
;; >> transaction-history <<
;; >> profit-loss-by-transaction-history <<
;;
;; :game.user/portfolio
;; :bookkeeping.portfolio
;; :bookkeeping.portfolio/journals
;; :bookkeeping.journal/entries
;; :bookkeeping.tentry
