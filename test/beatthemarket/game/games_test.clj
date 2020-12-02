(ns beatthemarket.game.games-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as core.async
             :refer [go <! >!! <!!]]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]
            [com.rpl.specter :refer [select transform ALL]]
            [datomic.client.api :as d]
            [integrant.repl.state :as repl.state]

            [beatthemarket.game.calculation :as game.calculation]
            [beatthemarket.game.core :as game.core]
            [beatthemarket.game.games :as game.games]

            [beatthemarket.bookkeeping.persistence :as bookkeeping.persistence]
            [beatthemarket.bookkeeping.core :as bookkeeping.core]
            [beatthemarket.game.games.processing :as games.processing]
            [beatthemarket.game.games.pipeline :as games.pipeline]
            [beatthemarket.game.games.control :as games.control]
            [beatthemarket.game.games.state :as games.state]
            [beatthemarket.integration.payments.core :as integration.payments.core]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.handler.graphql.encoder :as graphql.encoder]

            [beatthemarket.util :refer [ppi] :as util]
            [beatthemarket.test-util :as test-util])
  (:import [java.util UUID]
           [clojure.lang ExceptionInfo]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture)


(defn- pull-from-stock-tick-pipeline! [stock-tick-pipeline ops-before-count]

  (->> (drop ops-before-count stock-tick-pipeline)
       (take 1)
       doall))

(deftest create-game!-test

  (testing "Creating a game returns the expected game control keys"

    (let [conn         (-> repl.state/system :persistence/datomic :opts :conn)
          user-db-id   (:db/id (test-util/generate-user! conn))
          sink-fn      identity
          game-control (game.games/create-game! conn sink-fn)

          expected-game-control-keys
          #{:game
            :profit-loss
            :input-sequence
            :stocks-with-tick-data
            :control-channel
            :current-level
            :game-level
            :short-circuit-game?
            :accounts

            :level-timer
            :tick-sleep-atom
            :cash-position-at-game-start

            :group-stock-tick-pairs
            :process-transact!
            :process-transact-profit-loss!
            :process-transact-level-update!

            :calculate-profit-loss
            :stream-portfolio-update!

            :sink-fn
            :close-sink-fn

            :stock-tick-stream
            :portfolio-update-stream
            :game-event-stream

            :stream-stock-tick
            :check-level-complete
            :stream-level-update!}]

      (->> (keys game-control)
           (into #{})
           (= expected-game-control-keys )
           is))))


;; StockTick Streaming
(deftest transact-stock-tick-and-stream-pipeline-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity
        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])


        stock-tick-stream (core.async/chan)
        opts {:level-timer-sec     5
              :stagger-wavelength? false
              :user                {:db/id user-db-id}
              :accounts            (game.core/->game-user-accounts)
              :game-level          :game-level/one
              :stock-tick-stream   stock-tick-stream}


        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         level-timer :level-timer
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [historical-data iterations] (game.games/start-game! conn game-control 0)

        container (atom [])
        tick-amount 2
        iterate-tick-amount (inc tick-amount)

        expected-tick-prices '((100.0 100.0 100.0 100.0) (110.0 110.0 110.0 110.0))]

    (let [now (t/now)
          end (t/plus now (t/seconds @level-timer))]

      (->> iterations
           (iterate (fn [iters]
                      (ppi :A)
                      (games.control/step-game conn game-control now end iters)
                      (rest iters)))
           (take iterate-tick-amount)
           count))

    (test-util/consume-messages stock-tick-stream container)

    (->> (take-last tick-amount @container)
         (map (fn [a] (map (comp double :game.stock.tick/close) a)))
         (= expected-tick-prices)
         is)))

#_(deftest single-buy-and-track-running-proft-loss-test

  (let [;; A
        conn           (-> repl.state/system :persistence/datomic :opts :conn)
        user           (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity
        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts {:level-timer-sec 5
              :user            {:db/id user-db-id}
              :accounts        (game.core/->game-user-accounts)
              :game-level      :game-level/one}


        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations]                   (game.games/start-game!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id  :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn         conn
              :userId       userId
              :gameId       game-id
              :stockId      stock-id
              :game-control game-control}]

    (testing "Running :profit-loss after a single buy"

      (let [ops-before [{:op :buy :stockAmount 100}]
            ops-before-count (count ops-before)]

        (test-util/run-trades! iterations stock-id opts ops-before ops-before-count)

        (let [expected-before-running-profit-loss {user-db-id
                                                   {stock-id
                                                    [{:amount 100
                                                      :counter-balance-direction :buy
                                                      :stock-account-amount 100
                                                      :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                                      :op :buy
                                                      :counter-balance-amount 100
                                                      :pershare-gain-or-loss 0.0
                                                      :running-profit-loss 0.0
                                                      :price 100.0
                                                      :pershare-purchase-ratio 1}]}}

              running-profit-loss (-> (game.calculation/running-profit-loss-for-game game-id)
                                      (update-in [user-db-id stock-id] (fn [x] (map #(dissoc % :stock-account-id) x))))]

          (is (= expected-before-running-profit-loss running-profit-loss)))))


    (testing "Running :profit-loss after a single buy, then a tick"

      (let [ops-after [{:op :noop}]
            ops-after-count (count ops-after)]

        (test-util/run-trades! (drop 1 iterations) stock-id opts ops-after ops-after-count)

        (let [expected-after-running-profit-loss {user-db-id
                                                  {stock-id
                                                   [{:amount 100
                                                     :counter-balance-direction :buy
                                                     :stock-account-amount 100
                                                     :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                                     :op :buy
                                                     :latest-price->price [110.0 100.0]
                                                     :counter-balance-amount 100
                                                     :pershare-gain-or-loss 10.0
                                                     :running-profit-loss 1000.0
                                                     :price 100.0
                                                     :pershare-purchase-ratio 1}]}}

              running-profit-loss (-> (game.calculation/running-profit-loss-for-game game-id)
                                      (update-in [user-db-id stock-id] (fn [x] (map #(dissoc % :stock-account-id) x))))]

          (is (= expected-after-running-profit-loss running-profit-loss)))))))

#_(deftest multiple-buy-track-running-proft-loss-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 , 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity
        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :user            {:db/id user-db-id}
                    :accounts        (game.core/->game-user-accounts)
                    :game-level      :game-level/one}


        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations] (game.games/start-game!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id  :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn         conn
              :userId       userId
              :gameId       game-id
              :stockId      stock-id
              :game-control game-control}

        ops  [{:op :buy :stockAmount 100}
              {:op :buy :stockAmount 200}]
        ops-count (count ops)]

    (testing "Expected profit-loss data after multiple buys"

      (test-util/run-trades! iterations stock-id opts ops ops-count)

      (let [expected-profit-loss {user-db-id
                                  {stock-id
                                   #{{:amount 200
                                      :counter-balance-direction :buy
                                      :stock-account-amount 300
                                      :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                      :op :buy
                                      :latest-price->price [(.floatValue 110.0) (.floatValue 110.0)]
                                      :counter-balance-amount 300
                                      :pershare-gain-or-loss 0.0
                                      :running-profit-loss 0.0
                                      :price (.floatValue 110.0)
                                      :pershare-purchase-ratio 2/3}
                                     {:amount 100
                                      :counter-balance-direction :buy
                                      :stock-account-amount 100
                                      :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                      :op :buy
                                      :latest-price->price [(.floatValue 110.0) (.floatValue 100.0)]
                                      :counter-balance-amount 300
                                      :pershare-gain-or-loss 10.0
                                      :running-profit-loss 999.9999999999999
                                      :price (.floatValue 100.0)
                                      :pershare-purchase-ratio 1/3}}}}
            profit-loss (-> (game.calculation/running-profit-loss-for-game game-id)
                            (update-in [user-db-id stock-id] (fn [x] (map #(dissoc % :stock-account-id) x)))
                            (update-in [user-db-id stock-id] (fn [x] (into #{} x))))]

        (is (= expected-profit-loss profit-loss))))))

#_(deftest multiple-buy-sell-track-realized-proft-loss-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 , 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])


        opts       {:level-timer-sec 5
                    :user            {:db/id user-db-id}
                    :accounts        (game.core/->game-user-accounts)
                    :game-level      :game-level/one}


        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations] (game.games/start-game!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  game-id
              :stockId stock-id
              :game-control game-control}

        ops  [{:op :buy :stockAmount 100}
              {:op :buy :stockAmount 200}
              {:op :sell :stockAmount 200}]
        ops-count (count ops)]

    (testing "We are seeing the correct running profit-loss, after realizing a portion of the P/L"

      (test-util/run-trades! iterations stock-id opts ops ops-count)

      (let [expected-running-profit-loss {user-db-id
                                          {stock-id
                                           #{{:amount 200
                                              :counter-balance-direction :buy
                                              :stock-account-amount 300
                                              :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                              :op :buy
                                              :shrinkage 1/3
                                              :latest-price->price [(.floatValue 105.0) (.floatValue 110.0)]
                                              :counter-balance-amount 100
                                              :pershare-gain-or-loss -5.0
                                              :running-profit-loss -111
                                              :price (.floatValue 110.0)
                                              :pershare-purchase-ratio 2/9}
                                             {:amount 100
                                              :counter-balance-direction :buy
                                              :stock-account-amount 100
                                              :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                              :op :buy
                                              :shrinkage 1/3
                                              :latest-price->price [(.floatValue 105.0) (.floatValue 100.0)]
                                              :counter-balance-amount 100
                                              :pershare-gain-or-loss 5.0
                                              :running-profit-loss 56
                                              :price (.floatValue 100.0)
                                              :pershare-purchase-ratio 1/9}}}}

            expected-realized-profit-loss #{{:user-id user-db-id
                                             ;; :tick-id #uuid "1a428ad8-f5ca-45c4-8d5e-9d7f8c27a9fd"
                                             :game-id game-id
                                             :stock-id stock-id
                                             :profit-loss-type :realized-profit-loss
                                             :profit-loss (.floatValue -500.0)}}

            running-profit-loss (-> (game.calculation/running-profit-loss-for-game game-id)
                                    (update-in [user-db-id stock-id] (fn [x] (map #(dissoc % :stock-account-id) x)))
                                    (update-in [user-db-id stock-id] (fn [x] (map #(assoc % :running-profit-loss (Math/round (:running-profit-loss %))) x)))
                                    (update-in [user-db-id stock-id] (fn [x] (into #{} x))))

            realized-profit-loss (->> (game.calculation/realized-profit-loss-for-game conn user-db-id game-id)
                                      (map #(dissoc % :tick-id))
                                      (into #{}))]

        (are [x y] (= x y)
          expected-running-profit-loss running-profit-loss
          expected-realized-profit-loss realized-profit-loss)))))

#_(deftest multiple-buy-sell-track-realized-closeout-running-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])


        opts       {:level-timer-sec 5
                    :user            {:db/id user-db-id}
                    :accounts        (game.core/->game-user-accounts)
                    :game-level      :game-level/one}


        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations] (game.games/start-game!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  game-id
              :stockId stock-id
              :game-control game-control}

        ops  [{:op :buy :stockAmount 100}
              {:op :buy :stockAmount 200}
              {:op :sell :stockAmount 200}
              {:op :sell :stockAmount 100}]
        ops-count (count ops)]

    (testing "We are seeing the correct running profit-loss, after realizing all of the P/L"

      (test-util/run-trades! iterations stock-id opts ops ops-count)

      (let [expected-running-profit-loss {user-db-id {stock-id []}}
            expected-realized-profit-loss #{{:user-id user-db-id
                                             :game-id game-id
                                             :stock-id stock-id
                                             :profit-loss-type :realized-profit-loss
                                             :profit-loss (.floatValue 444.44446)}
                                            {:user-id user-db-id
                                             :game-id game-id
                                             :stock-id stock-id
                                             :profit-loss-type :realized-profit-loss
                                             :profit-loss (.floatValue -500.0)}}

            running-profit-loss (game.calculation/running-profit-loss-for-game game-id)
            realized-profit-loss (->> (game.calculation/realized-profit-loss-for-game conn user-db-id game-id)
                                      (map #(dissoc % :tick-id))
                                      (into #{}))]

        (are [x y] (= x y)
          expected-running-profit-loss running-profit-loss
          expected-realized-profit-loss realized-profit-loss)))))

#_(deftest calculate-game-scores-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)
        email          (:user/email user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 , 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :user            {:db/id user-db-id}
                    :accounts        (game.core/->game-user-accounts)
                    :game-level      :game-level/one}


        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations] (game.games/start-game!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  game-id
              :stockId stock-id
              :game-control game-control}

        ops  [{:op :buy :stockAmount 100}
              {:op :buy :stockAmount 200}
              {:op :sell :stockAmount 200}
              {:op :sell :stockAmount 100}]
        ops-count (count ops)]


    ;; Run Stock & Trade pipeline
    (test-util/run-trades! iterations stock-id opts ops ops-count)


    (testing "Collect realized profit-losses (all)"

      (let [expected-realized-count 2
            expected-realized-keys #{:user-id :tick-id :game-id :stock-id :profit-loss-type :profit-loss}
            expected-realized-amount #{(.floatValue 444.44446) (.floatValue -500.0)}
            realized-profit-losses (game.calculation/collect-realized-profit-loss-pergame conn user-db-id game-id)]

        (is (= expected-realized-count (count realized-profit-losses)))

        (->> realized-profit-losses
             (map keys)
             (map #(into #{} %))
             (every? #(= expected-realized-keys %))
             is)

        (->> realized-profit-losses
             (map :profit-loss)
             (into #{})
             (= expected-realized-amount)
             is))

      (testing "Collect realized profit-loss, per stock"

        (let [expected-realized-count 1
              expected-realized-keys #{:user-id :tick-id :game-id :stock-id :profit-loss-type :profit-loss}
              expected-realized-profit-loss {:profit-loss-type :realized-profit-loss
                                             :profit-loss (.floatValue -55.56)}
              realized-profit-losses-perstock (game.calculation/collect-realized-profit-loss-pergame conn user-db-id game-id true)]

          (is (= expected-realized-count (count realized-profit-losses-perstock)))

          (->> realized-profit-losses-perstock
               (map keys)
               (map #(into #{} %))
               (every? #(= expected-realized-keys %))
               is)

          (-> (first realized-profit-losses-perstock)
              (select-keys [:profit-loss-type :profit-loss])
              (= expected-realized-profit-loss)
              is)))

      (testing "Collect realized profit-loss, all games"

        (let [group-by-stock? false
              expected-realized-count 2
              expected-realized-keys #{:user-id :tick-id :game-id :stock-id :profit-loss-type :profit-loss}
              expected-realized-profit-loss {:profit-loss-type :realized-profit-loss
                                             :profit-loss (.floatValue 166.67)}
              realized-profit-losses-allgames (game.calculation/collect-realized-profit-loss-allgames conn user-db-id group-by-stock?)

              realized-profit-losses-allgames-forgame (get realized-profit-losses-allgames game-id)]

          (is (= expected-realized-count (count realized-profit-losses-allgames-forgame)))

          (->> realized-profit-losses-allgames-forgame
               (map keys)
               (map #(into #{} %))
               (every? #(= expected-realized-keys %))
               is)))

      (testing "Collect realized profit-loss, all games, per stock"

        (let [group-by-stock? true
              expected-realized-count 1
              expected-realized-keys #{:user-id :tick-id :game-id :stock-id :profit-loss-type :profit-loss}
              expected-realized-profit-loss {:profit-loss-type :realized-profit-loss
                                             :profit-loss (.floatValue -55.56)}
              realized-profit-losses-allgames (game.calculation/collect-realized-profit-loss-allgames conn user-db-id group-by-stock?) ]


          (is (= expected-realized-count (count realized-profit-losses-allgames)))

          (->> realized-profit-losses-allgames
               (map keys)
               (map #(into #{} %))
               (every? #(= expected-realized-keys %))
               is)

          (-> (first realized-profit-losses-allgames)
              (select-keys [:profit-loss-type :profit-loss])
              (= expected-realized-profit-loss)
              is))))))

#_(deftest collect-realized-profit-loss-all-users-allgames-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)
        email          (:user/email user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 , 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :user            {:db/id user-db-id}
                    :accounts        (game.core/->game-user-accounts)
                    :game-level      :game-level/one}


        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations] (game.games/start-game!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  game-id
              :stockId stock-id
              :game-control game-control}

        ops  [{:op :buy :stockAmount 100}
              {:op :buy :stockAmount 200}
              {:op :sell :stockAmount 200}
              {:op :sell :stockAmount 100}]
        ops-count (count ops)]


    ;; Run Stock & Trade pipeline
    (test-util/run-trades! iterations stock-id opts ops ops-count)


    ;; (ppi (game.calculation/collect-realized-profit-loss-all-users-allgames conn false))
    (game.calculation/collect-realized-profit-loss-all-users-allgames conn true)

    ;; TODO complete
    #_[#:user{:email "twashing@gmail.com",
              :game
              {:game/id #uuid "cd0c8980-96c7-4c8d-85fc-731dab4849c7",
               :game/status :game-status/created,
               :game.user/profit-loss
               ({:user-id nil,
                 :tick-id nil,
                 :game-id #uuid "cd0c8980-96c7-4c8d-85fc-731dab4849c7",
                 :stock-id #uuid "f86edd69-5bef-46e5-a5cb-77935be4f46e",
                 :profit-loss-type :realized-profit-loss,
                 :profit-loss -55.56})},
              :name "Timothy Washington",
              :external-uid "VEDgLEOk1eXZ5jYUcc4NklAU3Kv2"}]))

#_(deftest track-running-profit-loss-on-margin-trade-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 , 105.0 120.0 , 110.0 105.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :user            {:db/id user-db-id}
                    :accounts        (game.core/->game-user-accounts)
                    :game-level      :game-level/one}


        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations] (game.games/start-game!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  game-id
              :stockId stock-id
              :game-control game-control}

        ;; i.
        ops-before  [{:op :buy :stockAmount 100}
                     {:op :buy :stockAmount 200}
                     {:op :sell :stockAmount 200}
                     {:op :sell :stockAmount 200}]
        ops-before-count (count ops-before)

        ;; ii.
        ops-after  [{:op :noop}]
        ops-after-count (count ops-after)

        ;; iii.
        ops-short-sell  [{:op :buy :stockAmount 100}]
        ops-short-sell-count (count ops-short-sell)]

    (with-redefs [integration.payments.core/margin-trading? (constantly true)]

      (testing "The stock account amount can go negative"

        (test-util/run-trades! iterations stock-id opts ops-before ops-before-count)

        (let [running-profit-loss (game.calculation/running-profit-loss-for-game game-id)
              expected-running-profit-loss-before {user-db-id
                                                   {stock-id
                                                    [{:amount 200
                                                      :counter-balance-direction :sell
                                                      :stock-account-amount -100
                                                      :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                                      :op :sell
                                                      :counter-balance-amount -100
                                                      :pershare-gain-or-loss 0.0
                                                      :running-profit-loss 0.0
                                                      :price (.floatValue 120.0)
                                                      :pershare-purchase-ratio 1}]}}]

          (-> running-profit-loss
              (update-in [user-db-id stock-id] (fn [x] (map #(dissoc % :stock-account-id) x)))
              (= expected-running-profit-loss-before)
              is)))

      (testing "Subsequent ticks correctly recalculate a running P/L, when short selling"

        (pull-from-stock-tick-pipeline! (games.pipeline/stock-tick-pipeline game-control) ops-before-count)

        (let [expected-running-profit-loss-after {user-db-id
                                                  {stock-id
                                                   #{{:amount 200
                                                      :counter-balance-direction :sell
                                                      :stock-account-amount -100
                                                      :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                                      :op :sell
                                                      :latest-price->price [(.floatValue 110.0) (.floatValue 120.0)]
                                                      :counter-balance-amount -100
                                                      :pershare-gain-or-loss -10.0
                                                      :running-profit-loss 1000.0
                                                      :price (.floatValue 120.0)
                                                      :pershare-purchase-ratio 1}}}}

              running-profit-loss (-> (game.calculation/running-profit-loss-for-game game-id)
                                      (update-in [user-db-id stock-id] (fn [x] (map #(dissoc % :stock-account-id) x)))
                                      (update-in [user-db-id stock-id] (fn [x] (into #{} x))))]

          (is (= expected-running-profit-loss-after running-profit-loss))))

      (testing "Filling a short sell, correctly i. resolves the running P/L AND ii. stores the realized profit"

        (test-util/run-trades! (drop (+ ops-before-count ops-after-count) iterations) stock-id opts ops-short-sell ops-short-sell-count)

        (let [running-profit-loss (game.calculation/running-profit-loss-for-game game-id)
              realized-profit-loss (->> (game.calculation/realized-profit-loss-for-game conn user-db-id game-id)
                                        (map #(dissoc % :tick-id))
                                        (into #{}))

              expected-running-profit-loss {user-db-id {#uuid "05847422-0b5f-4e31-970e-29d8688bc0ba" []}}
              expected-realized-profit-loss #{{:user-id user-db-id
                                               :game-id game-id
                                               :stock-id stock-id
                                               :profit-loss-type :realized-profit-loss
                                               :profit-loss (.floatValue 1500.0)}
                                              {:user-id user-db-id
                                               :game-id game-id
                                               :stock-id stock-id
                                               :profit-loss-type :realized-profit-loss
                                               :profit-loss (.floatValue 666.6667)}
                                              {:user-id user-db-id
                                               :game-id game-id
                                               :stock-id stock-id
                                               :profit-loss-type :realized-profit-loss
                                               :profit-loss (.floatValue -500.0)}}]

          (are [x y] (= x y)
            expected-running-profit-loss expected-running-profit-loss
            realized-profit-loss expected-realized-profit-loss))))))


;; GameEvents & Streaming
#_(deftest win-level-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 112.0 , 105.0 120.0 , 110.0 105.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        game-event-stream (core.async/chan)
        opts       {:level-timer-sec 5
                    :user            {:db/id user-db-id}
                    :accounts        (game.core/->game-user-accounts)
                    :game-level      :game-level/one
                    :game-event-stream game-event-stream}


        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations] (game.games/start-game!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  game-id
              :stockId stock-id
              :game-control game-control}

        ;; i.
        ops-before  [{:op :buy :stockAmount 100}
                     {:op :sell :stockAmount 100}
                     {:op :noop}]
        ops-before-count (count ops-before)]

    (testing "Tick sleep is 1000 me before leveling up"

      (let [tick-sleep-atom (:tick-sleep-atom (games.state/inmemory-game-by-id game-id))
            expected-tick-sleep 1000]
        (is (= expected-tick-sleep @tick-sleep-atom))))

    (testing "Testing the correct level win message is shown"

      (test-util/run-trades! iterations stock-id opts ops-before ops-before-count)

      (Thread/sleep 1000) ;; NOTE kludge to get around timing transact of new level
      (let [{{level :db/ident} :game/level :as game1} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))

            expected-game-event {:game-id game-id
                                 :level :game-level/one
                                 :profit-loss (.floatValue 1200.0)
                                 :event :win
                                 :type :LevelStatus}

            expected-game-level :game-level/two]

        (are [x y] (= x y)
          expected-game-event (core.async/<!! game-event-stream)
          expected-game-level level)))

    (testing "Tick sleep is 950 me after leveling up"

      (let [tick-sleep-atom (:tick-sleep-atom (games.state/inmemory-game-by-id game-id))
            expected-tick-sleep 900]
        (is (= expected-tick-sleep @tick-sleep-atom))))))

#_(deftest lose-level-test

  (let [;; A
        conn       (-> repl.state/system :persistence/datomic :opts :conn)
        user       (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId     (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 80.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        game-event-stream (core.async/chan)
        opts              {:level-timer-sec   5
                           :user              {:db/id user-db-id}
                           :accounts          (game.core/->game-user-accounts)
                           :game-level        :game-level/one
                           :game-event-stream game-event-stream}


        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations]                   (game.games/start-game!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id  :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn         conn
              :userId       userId
              :gameId       game-id
              :stockId      stock-id
              :game-control game-control}

        ;; i.
        ops-before       [{:op :buy :stockAmount 100}]
        ops-before-count (count ops-before)]

    (testing "Testing the correct level lose message is shown"

      (test-util/run-trades! iterations stock-id opts ops-before ops-before-count)
      (pull-from-stock-tick-pipeline! (games.pipeline/stock-tick-pipeline game-control) ops-before-count)


      (Thread/sleep 1000) ;; NOTE kludge to get around timing transact of new level
      (let [{{level :db/ident} :game/level} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))

            expected-game-event {:game-id     game-id
                                 :level       :game-level/one
                                 :profit-loss (.floatValue -2000.0)
                                 :event       :lose
                                 :type        :LevelStatus}

            expected-game-level :game-level/one]

        (are [x y] (= x y)
          expected-game-event (core.async/<!! game-event-stream)
          expected-game-level level)))))

#_(deftest win-game-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 1500100.0 100.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])


        game-event-stream (core.async/chan)
        opts       {:level-timer-sec 5
                    :user            {:db/id user-db-id}
                    :accounts        (game.core/->game-user-accounts)
                    :game-level      :game-level/ten
                    :game-event-stream game-event-stream}


        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations] (game.games/start-game!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  game-id
              :stockId stock-id
              :game-control game-control}

        ;; i.
        ops-before  [{:op :buy :stockAmount 1000}
                     {:op :sell :stockAmount 1000}
                     {:op :noop}]
        ops-before-count (count ops-before)]

    (testing "Testing the correct game win message is shown"

      (with-redefs [integration.payments.core/margin-trading? (constantly true)]

        (test-util/run-trades! iterations stock-id opts ops-before ops-before-count)

        (Thread/sleep 2000)

        (let [{{level :db/ident} :game/level} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))

              expected-game-event {:game-id game-id
                                   :level :game-level/ten
                                   :profit-loss 1.5E9
                                   :event :win
                                   :type :LevelStatus}

              expected-game-level :game-level/bluesky]

          (are [x y] (= x y)
            expected-game-event (core.async/<!! game-event-stream)
            expected-game-level level))))))

#_(deftest timeout-game-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 1500100.0 100.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])


        game-event-stream (core.async/chan)
        opts       {:level-timer-sec 2
                    :user            {:db/id user-db-id}
                    :accounts        (game.core/->game-user-accounts)
                    :game-level      :game-level/ten
                    :game-event-stream game-event-stream}


        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         level-timer :level-timer
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)]

    (with-redefs [games.control/lose-game! (constantly true)
                  game.calculation/realized-profit-loss-for-game
                  (constantly
                    [{:user-id 17592186045535
                      :tick-id #uuid "61edaa6b-1e7b-43ab-af25-7523aa315ac3"
                      :game-id game-id
                      :stock-id #uuid "f3a47490-5792-42ca-8747-ef3ac9420d39"
                      :profit-loss-type :realized-profit-loss
                      :profit-loss 509.37}])]

      (let [now (t/now)
            end (t/plus now (t/seconds @level-timer))]

        (games.control/handle-control-event
          conn game-event-stream {:event :timeout
                                  :game-id game-id} now end)))

    (is (= (core.async/<!! game-event-stream)
           {:event :continue :game-id game-id :level :game-level/ten :minutesRemaining 0 :secondsRemaining 0 :type :LevelTimer}))

    (is (= (core.async/<!! game-event-stream)
           {:event :lose :game-id game-id :profit-loss 509.37 :level :game-level/ten :type :LevelStatus}))))

#_(deftest start-game!-test

  (let [;; A
        conn       (-> repl.state/system :persistence/datomic :opts :conn)
        user       (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId     (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 80.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        game-event-stream (core.async/chan)
        opts              {:level-timer-sec   5
                           :user              {:db/id user-db-id}
                           :accounts          (game.core/->game-user-accounts)
                           :game-level        :game-level/one
                           :game-event-stream game-event-stream}

        ;; D Launch Game
        {{game-id    :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)]

    (testing "We have :cash-position-at-game-start"

      (let [expected-cash-position-at-game-create 0.0
            cash-position-at-game-start (:cash-position-at-game-start game-control)]

        (is (= expected-cash-position-at-game-create @cash-position-at-game-start))


        (testing ":cash-position-at-game-start has been updated to the DB value"

          (let [[_ iterations] (game.games/start-game!-workbench conn game-control)
                expected-cash-position-at-game-start 100000.0
                inmemory-game (games.state/inmemory-game-by-id game-id)]

            (is (= expected-cash-position-at-game-start @(:cash-position-at-game-start inmemory-game)))


            (testing "Margin trading + buy/sell, uses 10x the :cash-position-at-game-start"

              (let [;; E Buy Stock
                    {stock-id  :game.stock/id
                     stockName :game.stock/name} (first stocks)

                    opts {:conn         conn
                          :userId       userId
                          :gameId       game-id
                          :stockId      stock-id
                          :game-control game-control}

                    ;; i.
                    ops-before       [{:op :buy :stockAmount 9500}
                                      {:op :noop}]
                    ops-before-count (count ops-before)]

                (with-redefs [integration.payments.core/margin-trading? (constantly true)]

                  (let [expected-successful-trade-count 2]

                    (is (= expected-successful-trade-count
                           (count (test-util/run-trades! iterations stock-id opts ops-before ops-before-count))))))

                (let [expected-before-running-profit-loss {user-db-id
                                                           {stock-id
                                                            [{:amount 9500
                                                              :counter-balance-direction :buy
                                                              :stock-account-amount 9500
                                                              :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                                              :op :buy
                                                              :latest-price->price [80.0 100.0]
                                                              :counter-balance-amount 9500
                                                              :pershare-gain-or-loss -20.0
                                                              :running-profit-loss -190000.0
                                                              :price 100.0
                                                              :pershare-purchase-ratio 1}]}}

                      running-profit-loss (-> (game.calculation/running-profit-loss-for-game game-id)
                                              (update-in [user-db-id stock-id] (fn [x] (map #(dissoc % :stock-account-id) x))))]

                  (is (= expected-before-running-profit-loss running-profit-loss)))))))))))

#_(deftest end-game!-test

  (let [;; A
        conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {user-db-id :db/id
         userId         :user/external-uid} (test-util/generate-user! conn)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length     (count (data-sequence-fn))


        ;; C create-game!
        sink-fn                identity
        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec                   5
                    :accounts                          (game.core/->game-user-accounts)
                    :stream-stock-tick       (fn [a]
                                               (let [stock-ticks (games.processing/group-stock-tick-pairs a)]
                                                 (swap! test-stock-ticks
                                                        (fn [b]
                                                          (conj b stock-ticks)))
                                                 stock-ticks))
                    :stream-portfolio-update! (fn [a]
                                                (swap! test-portfolio-updates (fn [b] (conj b a)))
                                                a)}
        game-level :game-level/one
        {{game-id     :game/id :as game} :game
         :as                          game-control}
        (game.games/create-game! conn user-db-id sink-fn game-level data-sequence-fn opts)

        [_ iterations] (game.games/start-game!-workbench conn user-db-id game-control)]

    (testing "Running game has correct settings"

      (let [{start-time :game/start-time
             {status :db/ident} :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))]

        (is (util/exists? start-time))
        (is (= :game-status/created status))))

    (games.control/exit-game! conn game-id)

    (testing "Exiting a game, correctly sets :game/end-time abd :game/status"

      (let [{start-time :game/start-time
             end-time :game/end-time
             {status :db/ident} :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))]

        (is (t/after? (c/to-date-time end-time) (c/to-date-time start-time)))
        (is (= :game-status/exited status))))))

#_(deftest start-game!-with-start-position-test

  (let [;; A
        conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {user-db-id :db/id
         userId         :user/external-uid} (test-util/generate-user! conn)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length     (count (data-sequence-fn))


        ;; C create-game!
        sink-fn                identity
        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec          5
                    :user                     {:db/id user-db-id}
                    :accounts                 (game.core/->game-user-accounts)
                    :game-level               :game-level/one
                    :stream-stock-tick        (fn [a]
                                                (let [stock-ticks (games.processing/group-stock-tick-pairs a)]
                                                  (swap! test-stock-ticks
                                                         (fn [b]
                                                           (conj b stock-ticks)))
                                                  stock-ticks))
                    :stream-portfolio-update! (fn [a]
                                                (swap! test-portfolio-updates (fn [b] (conj b a)))
                                                a)}
        {{game-id     :game/id
          game-db-id :db/id :as game} :game
         control-channel              :control-channel
         game-event-stream            :game-event-stream
         :as                          game-control}
        (game.games/create-game! conn sink-fn data-sequence-fn opts)


        start-position               3
        [historical-data iterations] (game.games/start-game!-workbench conn user-db-id game-control start-position)]


    (testing "Game's startPosition is seeking to the correct location"

      (let [expected-price 120.0
            result-price   (-> iterations ffirst :stock-ticks first :game.stock.tick/close)]

        (is (= expected-price result-price))))

    (testing "We are returning the correcet historical data"

      (let [result-historical-data (->> (map :stock-ticks historical-data)
                                        (map #(map graphql.encoder/stock-tick->graphql %)))

            expected-historical-data-length start-position]

        (is (= expected-historical-data-length (count result-historical-data)))

        (->> result-historical-data
             (map #(map keys %))
             (map #(map (fn [a] (into #{} a)) %))
             (map #(every? (fn [a]
                             (= #{:stockTickId :stockTickTime :stockTickClose :stock-id :stockName}
                                a)) %))
             (every? true?)
             is)))))

#_(deftest pausing-game-stores-expected-data-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts {:level-timer-sec 5
              :user            {:db/id user-db-id}
              :accounts        (game.core/->game-user-accounts)
              :game-level      :game-level/one}


        ;; D Launch Game
        {{game-id     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations]                   (game.games/start-game!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  game-id
              :stockId stock-id
              :game-control game-control}

        ops-before-pause  [{:op :buy :stockAmount 100}
                           {:op :buy :stockAmount 200}
                           {:op :sell :stockAmount 200}]
        ops-before-pause-count (count ops-before-pause)]

    (is true)

    ;; BEFORE :pause
    (test-util/run-trades! iterations stock-id opts ops-before-pause ops-before-pause-count)

    ;; :pause
    (games.control/pause-game! conn game-id)

    ;; :game/start-position
    ;; :game.user/profit-loss
    ;; :game/status #:db{:id 17592186045430 :ident :game-status/paused}
    ;; :game/level-timer "[]"
    ;; :game/level #:db{:id 17592186045417 :ident :game-level/one}
    ;; :game.stock/data-seed


    ;; B AFTER :pause
    (testing "We are setting :game/status to :game-status/paused"

      (let [game-status (-> (persistence.core/entity-by-domain-id conn :game/id game-id)
                            ffirst
                            :game/status
                            :db/ident)

            expected-game-status :game-status/paused]

        (is (= expected-game-status game-status))))))

#_(deftest resume-game-correctly-replays-ticks-AND-pipelines-from-the-correct-position-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 , 120.0 112.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])


        opts {:level-timer-sec 5
              :user            {:db/id user-db-id}
              :accounts        (game.core/->game-user-accounts)
              :game-level      :game-level/one}


        ;; D Launch Game
        {{game-id     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations]                   (game.games/start-game!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  game-id
              :stockId stock-id
              :game-control game-control}

        ops-before-pause  [{:op :buy :stockAmount 100}
                           {:op :buy :stockAmount 200}
                           {:op :sell :stockAmount 200}]
        ops-before-pause-count (count ops-before-pause)

        ops-after-pause  [{:op :sell :stockAmount 100}]
        ops-after-pause-count (count ops-after-pause)]


    ;; BEFORE :pause
    (test-util/run-trades! iterations stock-id opts ops-before-pause ops-before-pause-count)


    ;; :pause
    (games.control/pause-game! conn game-id)

    ;; AFTER :pause
    (testing "On Pause, we are setting :game/status to :game-status/paused"

      (let [game-status (-> (persistence.core/entity-by-domain-id conn :game/id game-id)
                            ffirst
                            :game/status
                            :db/ident)

            expected-game-status :game-status/paused]

        (is (= expected-game-status game-status))))

    ;; AFTER resume
    (println "\n")
    (println "RESUME Game!!")
    (testing "On Resume, i. we are setting :game/status to :game-status/running.
                         ii. replay reconstructs running profit loss test."

      (let [{iterations :iterations} (games.control/resume-workbench! conn game-id user-db-id game-control data-sequence-fn)
            game-status (-> (persistence.core/entity-by-domain-id conn :game/id game-id)
                            ffirst
                            :game/status
                            :db/ident)

            expected-game-status :game-status/running

            expected-profit-loss {user-db-id
                                  {stock-id
                                    #{{:amount 200
                                       :counter-balance-direction :buy
                                       :stock-account-amount 300
                                       :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                       :op :buy
                                       :shrinkage 1/3
                                       :latest-price->price [(.floatValue 120.0) (.floatValue 110.0)]
                                       :counter-balance-amount 100
                                       :pershare-gain-or-loss 10.0
                                       :running-profit-loss 666.6666666666667
                                       :price (.floatValue 110.0)
                                       :pershare-purchase-ratio 2/9}
                                      {:amount 100
                                       :counter-balance-direction :buy
                                       :stock-account-amount 100
                                       :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                       :op :buy
                                       :shrinkage 1/3
                                       :latest-price->price [(.floatValue 120.0) (.floatValue 100.0)]
                                       :counter-balance-amount 100
                                       :pershare-gain-or-loss 20.0
                                       :running-profit-loss 222.22222222222223
                                       :price (.floatValue 100.0)
                                       :pershare-purchase-ratio 1/9}}}}

            profit-loss (-> (games.control/get-inmemory-profit-loss game-id)
                            (update-in [user-db-id stock-id] (fn [x] (map #(dissoc % :stock-account-id) x)))
                            (update-in [user-db-id stock-id] (fn [x] (into #{} x))))]

        (are [x y] (= x y)
          expected-game-status game-status
          expected-profit-loss profit-loss)


        ;; TODO Run the next :op, check P/L
        ;; (ppi iterations)

        ;; TODO check values are streamed to the correct client

        ))))

#_(deftest join-game-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 , 120.0 112.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])


        opts {:level-timer-sec 5
              ;; :user            {:db/id user-db-id}
              ;; :accounts        (game.core/->game-user-accounts)
              :game-level      :game-level/one}


        ;; D Launch Game
        {{game-id     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations]                   (game.games/start-market!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  game-id
              :stockId stock-id
              :game-control game-control}

        ops-before-pause  [{:op :noop}
                           {:op :noop}
                           {:op :noop}]
        ops-before-pause-count (count ops-before-pause)

        ops-after-pause  [{:op :buy :stockAmount 100}
                          {:op :sell :stockAmount 50}]
        ops-after-pause-count (count ops-after-pause)]


    ;; BEFORE :pause
    (test-util/run-trades! iterations stock-id opts ops-before-pause ops-before-pause-count)


    ;; :Pause
    (games.control/pause-game! conn game-id)


    ;; AFTER join
    (println "\n")
    (println "JOIN Game!!")
    (testing "After joining, getting correct status and P/L"

      (let [local-stream-stock-tick (fn [stock-ticks]
                                      (ppi [:stock-ticks stock-ticks])
                                      stock-ticks)

            local-stream-portfolio-update! (fn [{:keys [profit-loss] :as data}]
                                             (ppi [:profit-loss profit-loss])
                                             data)

            {iterations-after-join :iterations} (games.control/join-game!
                                                  conn user-db-id game-id
                                                  ;; TODO test streaming later
                                                  game-control
                                                  #_(assoc game-control
                                                         :stream-stock-tick local-stream-stock-tick
                                                         :stream-portfolio-update! local-stream-portfolio-update!)
                                                  data-sequence-fn)
            {{game-status :db/ident} :game/status
             game-users :game/users} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))

            expected-game-status :game-status/running
            expected-profit-loss {user-db-id {stock-id #{}}}

            profit-loss (-> (games.control/get-inmemory-profit-loss game-id)
                            (update-in [user-db-id stock-id] (fn [x] (map #(dissoc % :stock-account-id) x)))
                            (update-in [user-db-id stock-id] (fn [x] (into #{} x))))]

        (are [x y] (= x y)
          expected-game-status game-status
          expected-profit-loss profit-loss)


        (testing "User was added to game"
          (let [[{{user-db-id-added :db/id
                   user-external-id-added :user/external-uid} :game.user/user}]
                (:game/users (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id)))]

            (are [x y] (= x y)
              user-db-id user-db-id-added
              userId user-external-id-added)))


        (testing "We are getting the correct next iterations"

          (let [expected-iteration-A '(120.0 120.0 120.0 120.0)
                iteration-A (->> iterations-after-join
                                 ffirst
                                 :stock-ticks
                                 (map :game.stock.tick/close)
                                 (map double))

                expected-iteration-B '(112.0 125.0 130.0)
                iteration-B (->> (map :stock-ticks (-> iterations-after-join first second))
                                 (map (comp :game.stock.tick/close first))
                                 (map double))]

            (are [x y] (= x y)
              expected-iteration-A iteration-A
              expected-iteration-B iteration-B)))

        (testing "Checking Running and Realized P/L, after a buy and sell"

          (test-util/run-trades! iterations-after-join stock-id opts ops-after-pause ops-after-pause-count)

          (let [expected-realized-profit-loss #{{:user-id user-db-id
                                                 ;; :tick-id #uuid "c44626cf-b751-4fe1-9753-39abf7f1f966"
                                                 :game-id game-id
                                                 :stock-id stock-id
                                                 :profit-loss-type :realized-profit-loss
                                                 :profit-loss (.floatValue -800.0)}}

                expected-running-profit-loss {user-db-id
                                              {stock-id
                                               #{{:amount 100
                                                  :counter-balance-direction :buy
                                                  :stock-account-amount 100
                                                  ;; :stock-account-id #uuid "37306d98-2c57-4bf9-a5ff-45beef8ccc42"
                                                  :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                                  :op :buy
                                                  :shrinkage 1/2
                                                  :latest-price->price [(.floatValue 112.0) (.floatValue 120.0)]
                                                  :counter-balance-amount 50
                                                  :pershare-gain-or-loss -8.0
                                                  :running-profit-loss -200.0
                                                  :price (.floatValue 120.0)
                                                  :pershare-purchase-ratio 1/2}}}}

                realized-profit-loss (->> (game.calculation/collect-realized-profit-loss-pergame conn user-db-id game-id)
                                          (map #(dissoc % :tick-id))
                                          (into #{}))
                running-profit-loss (-> (game.calculation/running-profit-loss-for-game game-id)
                                        (update-in [user-db-id stock-id] (fn [x] (map #(dissoc % :stock-account-id) x)))
                                        (update-in [user-db-id stock-id] (fn [x] (into #{} x))))]

            (def A realized-profit-loss)
            (def B running-profit-loss)

            (are [x y] (= x y)
              expected-realized-profit-loss realized-profit-loss
              expected-running-profit-loss running-profit-loss)))

        ;; TODO check values are streamed to the correct client

        ))))

#_(deftest attempt-join-game-already-member-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 , 120.0 112.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])


        opts {:level-timer-sec 5
              :user            {:db/id user-db-id}
              :accounts        (game.core/->game-user-accounts)
              :game-level      :game-level/one}


        ;; D Launch Game
        {{game-id     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations]                   (game.games/start-market!-workbench conn game-control)


        ;; E Buy Stock
        {stock-id   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  game-id
              :stockId stock-id
              :game-control game-control}

        ops-before-pause  [{:op :buy :stockAmount 100}
                           {:op :buy :stockAmount 200}
                           {:op :sell :stockAmount 200}]
        ops-before-pause-count (count ops-before-pause)]


    ;; BEFORE :pause
    (test-util/run-trades! iterations stock-id opts ops-before-pause ops-before-pause-count)


    ;; :Pause
    (games.control/pause-game! conn game-id)


    ;; AFTER join
    (println "\n")
    (println "JOIN Game!!")
    (testing "After joining, getting correct status and P/L"

      ;; (games.control/join-game! conn user-db-id game-id game-control data-sequence-fn)
      (let [{iterations-after-join :iterations} (games.control/join-game! conn user-db-id game-id game-control data-sequence-fn)
            {{game-status :db/ident} :game/status
             game-users :game/users} (ffirst (persistence.core/entity-by-domain-id conn :game/id game-id))

            expected-game-status :game-status/running
            expected-profit-loss {user-db-id
                                  {stock-id
                                   #{{:amount 200
                                      :counter-balance-direction :buy
                                      :stock-account-amount 300
                                      :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                      :op :buy
                                      :shrinkage 1/3
                                      :latest-price->price [(.floatValue 130.0) (.floatValue 110.0)]
                                      :counter-balance-amount 100
                                      :pershare-gain-or-loss 20.0
                                      :running-profit-loss 1333.3333333333335
                                      :price (.floatValue 110.0)
                                      :pershare-purchase-ratio 2/9}
                                     {:amount 100
                                      :counter-balance-direction :buy
                                      :stock-account-amount 100
                                      :stock-account-name (bookkeeping.core/->stock-account-name stockName)
                                      :op :buy
                                      :shrinkage 1/3
                                      :latest-price->price [(.floatValue 130.0) (.floatValue 100.0)]
                                      :counter-balance-amount 100
                                      :pershare-gain-or-loss 30.0
                                      :running-profit-loss 333.3333333333333
                                      :price (.floatValue 100.0)
                                      :pershare-purchase-ratio 1/9}}}}

            profit-loss (-> (games.control/get-inmemory-profit-loss game-id)
                            (update-in [user-db-id stock-id] (fn [x] (map #(dissoc % :stock-account-id) x)))
                            (update-in [user-db-id stock-id] (fn [x] (into #{} x))))]

        (are [x y] (= x y)
          expected-game-status game-status
          expected-profit-loss profit-loss)))))

#_(deftest apply-additional-5-minutes-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        user-db-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 , 120.0 112.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])


        original-timer-seconds 5
        opts {:level-timer-sec original-timer-seconds
              :user            {:db/id user-db-id}
              :accounts        (game.core/->game-user-accounts)
              :game-level      :game-level/one}


        ;; D Launch Game
        {{game-id     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         control-channel :control-channel
         game-event-stream :game-event-stream
         :as               game-control} (game.games/create-game! conn sink-fn data-sequence-fn opts)
        [_ iterations]                   (game.games/start-game!-workbench conn game-control)

        payment-entity {:payment/product-id "additional_5_minutes"}
        game-entity (:game game-control)]

    (testing "Game event returns timer with an additional 5 minutes"

      (integration.payments.core/apply-payment-conditionally-on-running-game conn nil payment-entity game-entity)

      (core.async/go
        (let [[event ch] (core.async/alts! [(core.async/timeout 2000) game-event-stream])

              expected-event {:remaining-in-minutes 5
                              :remaining-in-seconds original-timer-seconds
                              :event :continue
                              :game-id game-id}]

          (is (= expected-event event)))))))
