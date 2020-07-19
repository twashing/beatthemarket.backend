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

         paused? :paused?
         tick-sleep-atom :tick-sleep-atom
         level-timer-atom :level-timer-atom
         :as game-control}
        (game.games/create-game! conn result-user-id sink-fn data-sequence-B stream-stock-tick-xf stream-portfolio-update-xf)]

    (testing "Chaining the control pipeline, produces the correct value"

      ;; "Starting a game correctly chains the control pipeline"
      ;; (def start-game-result (game.games/start-game! conn result-user-id game-control))
      ;; (core.async/>!! control-channel :exit)

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
                    ;; tickPrice0 :game.stock.tick/close
                    tickId0    :game.stock.tick/id}
                   (->> @test-stock-ticks
                        first
                        (filter #(= stockId (:game.stock/id %)))
                        first)

                   {tickTime1 :game.stock.tick/trade-time
                    ;; tickPrice1 :game.stock.tick/close
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

         paused?          :paused?
         tick-sleep-atom  :tick-sleep-atom
         level-timer-atom :level-timer-atom
         :as              game-control}
        (game.games/create-game! conn result-user-id sink-fn data-sequence-B stream-stock-tick-xf stream-portfolio-update-xf)

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
          (->> @test-stock-ticks
               first
               (filter #(= stockId (:game.stock/id %)))
               first)

          {tickPrice1 :game.stock.tick/close
           tickId1    :game.stock.tick/id}
          (->> @test-stock-ticks
               second
               (filter #(= stockId (:game.stock/id %)))
               first)]

      (testing "We are checking game is current and belongs to the user"
        (is (thrown? ExceptionInfo (game.games/buy-stock! conn userId "non-existant-game-id" stockId stockAmount tickId1 tickPrice1))))

      (testing "Error is thrown when submitted price does not match price from tickId"
        (is (thrown? ExceptionInfo (game.games/buy-stock! conn userId gameId stockId stockAmount tickId1 (Float. (- tickPrice1 1))))))

      (testing "Error is thrown when submitted tick is no the latest"
        (is (thrown? ExceptionInfo (game.games/buy-stock! conn userId gameId stockId stockAmount tickId0 (Float. tickPrice0)))))

      (testing "Returned Tentry matches what was submitted"
        (let [{tickPriceL :game.stock.tick/close
               tickIdL    :game.stock.tick/id}
              (->> @test-stock-ticks
                   last
                   (filter #(= stockId (:game.stock/id %)))
                   first)

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

         paused?          :paused?
         tick-sleep-atom  :tick-sleep-atom
         level-timer-atom :level-timer-atom
         :as              game-control}
        (game.games/create-game! conn result-user-id sink-fn data-sequence-B stream-stock-tick-xf stream-portfolio-update-xf)

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
          (->> @test-stock-ticks
               first
               (filter #(= stockId (:game.stock/id %)))
               first)

          {tickPrice1 :game.stock.tick/close
           tickId1    :game.stock.tick/id}
          (->> @test-stock-ticks
               second
               (filter #(= stockId (:game.stock/id %)))
               first)]

      (testing "We are checking game is current and belongs to the user"
        (is (thrown? ExceptionInfo (game.games/sell-stock! conn userId "non-existant-game-id" stockId stockAmount tickId1 tickPrice1))))

      (testing "Error is thrown when submitted price does not match price from tickId"
        (is (thrown? ExceptionInfo (game.games/sell-stock! conn userId gameId stockId stockAmount tickId1 (Float. (- tickPrice1 1))))))

      (testing "Error is thrown when submitted tick is no the latest"
        (is (thrown? ExceptionInfo (game.games/sell-stock! conn userId gameId stockId stockAmount tickId0 (Float. tickPrice0)))))

      (testing "Ensure we have the stock on hand before selling"

        (let [{tickPriceL :game.stock.tick/close
               tickIdL    :game.stock.tick/id}
              (->> @test-stock-ticks
                   last
                   (filter #(= stockId (:game.stock/id %)))
                   first)]

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

;; TODO
;; calculate P/L on buy
;; save P/L to DB (not component)
;; calculate P/L on tick

;; calculate P/L on sell


;; {:game.stock.tick/id #uuid "cb5ff770-435a-4b4f-bf0c-b16f34a088e5"
;;  :game.stock.tick/trade-time 1595175003845
;;  :game.stock.tick/close 100.0
;;  :game.stock/id #uuid "b5986d10-c88a-44db-bca0-fec65e4d479b"
;;  :game.stock/name "Dominant Pencil"}

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

#_(deftest calculate-profit-loss-single-buy-sell-test

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

      #_(game.games/control-streams! control-channel channel-controls :exit)

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

#_(deftest calculate-profit-loss-multiple-buy-single-sell-test

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

      #_(game.games/control-streams! control-channel channel-controls :exit)

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

#_(deftest calculate-profit-loss-multiple-buy-multiple-sell-test

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

      ;; #_(game.games/control-streams! control-channel channel-controls :exit)

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
