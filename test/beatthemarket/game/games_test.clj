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
            [beatthemarket.game.games.processing :as games.processing]
            [beatthemarket.game.games.pipeline :as games.pipeline]
            [beatthemarket.game.games.control :as games.control]

            [beatthemarket.state.subscriptions :as state.subscriptions]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.handler.graphql.encoder :as graphql.encoder]

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

          expected-game-control-keys
          #{:game
            :profit-loss
            :input-sequence
            :stocks-with-tick-data
            :control-channel
            :current-level

            :level-timer
            :tick-sleep-atom

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

(deftest transact-stock-tick-and-stream-pipeline-test


  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        result-user-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity
        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :accounts        (game.core/->game-user-accounts)}
        game-level :game-level/one


        ;; D Launch Game
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as        game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)
        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)]


    (doall (take 2 iterations))
    (is true)))

(defn- local-transact-stock! [{conn :conn
                               userId :userId
                               gameId :gameId
                               stockId :stockId
                               game-control :game-control}
                              {{tickId      :game.stock.tick/id
                                tickPrice   :game.stock.tick/close
                                op          :op

                                stockAmount :stockAmount} :local-transact-input :as v}]

  (case op
    :buy (games.pipeline/buy-stock-pipeline game-control conn userId gameId stockId stockAmount tickId (Float. tickPrice) false)
    :sell (games.pipeline/sell-stock-pipeline game-control conn userId gameId stockId stockAmount tickId (Float. tickPrice) false)
    :noop)
  v)

;; TODO
(deftest single-buy-and-track-running-proft-loss-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        result-user-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity
        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :accounts        (game.core/->game-user-accounts)}
        game-level :game-level/one


        ;; D Launch Game
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as        game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)
        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)


        ;; E Buy Stock
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  gameId
              :stockId stockId
              :game-control game-control}

        ops  [{:op :buy :stockAmount 100}
              ;; {:op :sell :stockAmount 100}
              ;; {:op :buy :stockAmount 200}
              ;; {:op :sell :stockAmount 200}
              ]

        ops-count (count ops)]


    ;; (doall (take 2 iterations))
    (is true)
    ;; (-> iterations first pprint)

    (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                  (assoc v :local-transact-input (merge stock-tick op))))
              (take ops-count iterations)
              (take ops-count ops))
         (map #(local-transact-stock! opts %))
         doall
         ;; (take 2)
         ;; util/pprint+identity
         )

    ))

;; TODO
(deftest multiple-buy-track-running-proft-loss-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        result-user-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 , 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity
        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :accounts        (game.core/->game-user-accounts)}
        game-level :game-level/one


        ;; D Launch Game
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as        game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)
        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)


        ;; E Buy Stock
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  gameId
              :stockId stockId
              :game-control game-control}

        ops  [{:op :buy :stockAmount 100}
              {:op :buy :stockAmount 200}]
        ops-count (count ops)]

    (is true)

    (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                  (assoc v :local-transact-input (merge stock-tick op))))
              (take ops-count iterations)
              (take ops-count ops))
         (map #(local-transact-stock! opts %))
         doall)))

;; TODO
(deftest multiple-buy-sell-track-realized-proft-loss-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        result-user-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 , 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :accounts        (game.core/->game-user-accounts)}
        game-level :game-level/one


        ;; D Launch Game
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as        game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)
        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)


        ;; E Buy Stock
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  gameId
              :stockId stockId
              :game-control game-control}

        ops  [{:op :buy :stockAmount 100}
              ;; {:op :sell :stockAmount 100}
              {:op :buy :stockAmount 200}
              {:op :sell :stockAmount 200}]
        ops-count (count ops)]

    (is true)

    (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                  (assoc v :local-transact-input (merge stock-tick op))))
              (take ops-count iterations)
              (take ops-count ops))
         (map #(local-transact-stock! opts %))
         doall)))

;; TODO
(deftest multiple-buy-sell-track-realized-closeout-running-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        result-user-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :accounts        (game.core/->game-user-accounts)}
        game-level :game-level/one


        ;; D Launch Game
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as        game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)
        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)


        ;; E Buy Stock
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  gameId
              :stockId stockId
              :game-control game-control}

        ;; ops  [{:op :buy :stockAmount 100}
        ;;       ;; {:op :sell :stockAmount 100}
        ;;       {:op :buy :stockAmount 200}
        ;;       {:op :sell :stockAmount 200}]

        ops  [{:op :buy :stockAmount 100}
              {:op :buy :stockAmount 200}
              {:op :sell :stockAmount 200}
              {:op :sell :stockAmount 100}
              ]
        ops-count (count ops)]

    (is true)

    (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                  (assoc v :local-transact-input (merge stock-tick op))))
              (take ops-count iterations)
              (take ops-count ops))
         (map #(local-transact-stock! opts %))
         doall)))

;; TODO
(deftest track-running-profit-loss-on-margin-trade-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        result-user-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :accounts        (game.core/->game-user-accounts)}
        game-level :game-level/one


        ;; D Launch Game
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as        game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)
        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)


        ;; E Buy Stock
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  gameId
              :stockId stockId
              :game-control game-control}

        ops  [{:op :buy :stockAmount 100}
              {:op :buy :stockAmount 200}
              {:op :sell :stockAmount 200}
              {:op :sell :stockAmount 200}]
        ops-count (count ops)]

    (is true)


    (with-redefs [state.subscriptions/margin-trading? (constantly true)]

      (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                  (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                    (assoc v :local-transact-input (merge stock-tick op))))
                (take ops-count iterations)
                (take ops-count ops))
           (map #(local-transact-stock! opts %))
           doall))))

;; TODO
(deftest calculate-game-scores-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        result-user-id (:db/id user)
        userId         (:user/external-uid user)
        email          (:user/email user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :accounts        (game.core/->game-user-accounts)}
        game-level :game-level/one


        ;; D Launch Game
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as        game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)
        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)


        ;; E Buy Stock
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  gameId
              :stockId stockId
              :game-control game-control}

        ops  [{:op :buy :stockAmount 100}
              {:op :buy :stockAmount 200}
              {:op :sell :stockAmount 200}
              {:op :sell :stockAmount 100}]
        ops-count (count ops)]


    ;; Run Stock & Trade pipeline
    (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                  (assoc v :local-transact-input (merge stock-tick op))))
              (take ops-count iterations)
              (take ops-count ops))
         (map #(local-transact-stock! opts %))
         doall)

    (testing "Collect realized profit-losses (all)"

      (let [expected-realized-count 2
            expected-realized-keys #{:user-id :tick-id :game-id :stock-id :profit-loss-type :profit-loss}
            expected-realized-amount #{(.floatValue 666.6667) (.floatValue -500.0)}
            realized-profit-losses (game.calculation/collect-realized-profit-loss-pergame conn result-user-id gameId)]

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
                                             :profit-loss (.floatValue 166.67)}
              realized-profit-losses-perstock (game.calculation/collect-realized-profit-loss-pergame conn result-user-id gameId true)]


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
              realized-profit-losses-allgames
              (game.calculation/collect-realized-profit-loss-allgames conn result-user-id group-by-stock?)

              realized-profit-losses-allgames-forgame (get realized-profit-losses-allgames gameId)]


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
                                             :profit-loss (.floatValue 166.67)}
              realized-profit-losses-allgames
              (game.calculation/collect-realized-profit-loss-allgames conn result-user-id group-by-stock?) ]


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

#_(deftest A-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        result-user-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :accounts        (game.core/->game-user-accounts)}
        game-level :game-level/one


        ;; D Launch Game
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as        game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)
        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)


        ;; E Buy Stock
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  gameId
              :stockId stockId
              :game-control game-control}

        ops  [{:op :buy :stockAmount 100}
              {:op :buy :stockAmount 200}
              {:op :sell :stockAmount 200}
              {:op :sell :stockAmount 200}]
        ops-count (count ops)]

    (is true)

    (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                  (assoc v :local-transact-input (merge stock-tick op))))
              (take ops-count iterations)
              (take ops-count ops))
         (map #(local-transact-stock! opts %))
         doall)))

(deftest start-game!-test

  (let [;; A
        conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
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
        {{gameId     :game/id
          game-db-id :db/id :as game} :game
         control-channel              :control-channel
         game-event-stream            :game-event-stream
         :as                          game-control}
        (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)

        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)]


    (testing "Chaining the control pipeline, produces the correct value.
              Correct number of stock-tick AND portfolio-update values were streamed."

      (doall (take tick-length iterations))

      (are [x y] (= x y)
        tick-length (count @test-stock-ticks)
        tick-length (count @test-portfolio-updates))

      (->> @test-stock-ticks
           (map #(map keys %))
           (map #(map (fn [a] (into #{} a)) %))
           (map #(every? (fn [a]
                           (= #{:game.stock.tick/id :game.stock.tick/trade-time :game.stock.tick/close
                                :game.stock/id :game.stock/name}
                              a)) %))
           (every? true?)
           is))))

(deftest end-game!-test

  (let [;; A
        conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
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
        {{gameId     :game/id :as game} :game
         :as                          game-control}
        (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)

        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)]

    (testing "Running game has correct settings"

      (let [{start-time :game/start-time
             {status :db/ident} :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id gameId))]

        (is (util/exists? start-time))
        (is (= :game-status/created status))))

    (games.control/exit-game! conn gameId)

    (testing "Exiting a game, correctly sets :game/end-time abd :game/status"

      (let [{start-time :game/start-time
             end-time :game/end-time
             {status :db/ident} :game/status} (ffirst (persistence.core/entity-by-domain-id conn :game/id gameId))]

        (is (t/after? (c/to-date-time end-time) (c/to-date-time start-time)))
        (is (= :game-status/exited status))))))

(deftest start-game!-with-start-position-test

  (let [;; A
        conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
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
        {{gameId     :game/id
          game-db-id :db/id :as game} :game
         control-channel              :control-channel
         game-event-stream            :game-event-stream
         :as                          game-control}
        (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)

        start-position               3
        [historical-data iterations] (game.games/start-workbench! conn result-user-id game-control start-position)]


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
                             (= #{:stockTickId :stockTickTime :stockTickClose :stockId :stockName}
                                a)) %))
             (every? true?)
             is)))))

#_(deftest buy-stock!-test

  ;; A
  (let [;; A
        conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
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
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks :as game} :game
         control-channel                    :control-channel
         game-event-stream                  :game-event-stream
         :as                                game-control}
        (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)

        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)
        stockAmount                  100]

    (doall (take tick-length iterations))


    ;; D
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

      #_(testing "Error is thrown when submitted tick is no the latest"
          (is (thrown? ExceptionInfo (game.games/buy-stock! conn userId gameId stockId stockAmount tickId0 (Float. tickPrice0)))))

      (testing "Returned Tentry matches what was submitted"
        (let [{tickPriceL :game.stock.tick/close
               tickIdL    :game.stock.tick/id}
              (->> @test-stock-ticks last
                   (filter #(= stockId (:game.stock/id %))) first)

              expected-credit-value        (Float. (format "%.2f" (* stockAmount tickPriceL)))
              expected-credit-account-name (format "STOCK.%s" stockName)

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

#_(deftest sell-stock!-test

  (let [;; A
        conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
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
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks :as game} :game
         control-channel                :control-channel
         game-event-stream              :game-event-stream
         :as                            game-control}
        (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)

        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)

        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)
        stockAmount                  100]

    (doall (take tick-length iterations))


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
                  expected-debit-account-name (format "STOCK.%s" stockName)

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

#_(deftest buy-stock!-multiple-test

  ;; A
  (let [;; A
        conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
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
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks :as game} :game
         control-channel                    :control-channel
         game-event-stream                  :game-event-stream
         :as                                game-control}
        (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)

        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)
        {stockIdA   :game.stock/id
         stockNameA :game.stock/name} (first stocks)
        {stockIdB   :game.stock/id
         stockNameB :game.stock/name} (second stocks)
        stockAmount                  100]

    (doall (take tick-length iterations))

    ;; D
    (testing "Returned Tentry matches what was submitted"
      (let [{tickPriceA :game.stock.tick/close
             tickIdA    :game.stock.tick/id}
            (->> @test-stock-ticks
                 last
                 first)

            {tickPriceB :game.stock.tick/close
             tickIdB    :game.stock.tick/id}
            (->> @test-stock-ticks
                 last
                 second)

            expected-credit-value        (Float. (format "%.2f" (* stockAmount tickPriceA)))
            expected-credit-account-name (format "STOCK.%s" stockNameA)

            expected-debit-value        (- (-> repl.state/config :game/game :starting-balance) expected-credit-value)
            expected-debit-account-name "Cash"

            result-A (game.games/buy-stock! conn userId gameId stockIdA stockAmount tickIdA (Float. tickPriceA) false)
            result-B (game.games/buy-stock! conn userId gameId stockIdB stockAmount tickIdB (Float. tickPriceB) false)

            expected-pl-entry-count 2
            expected-pl-calculation-keys
            #{:amount :latest-price->trade-price :A :stock-account-amount :credit-account-id :op
              :credit-account-name :trade-price :pershare-gain-or-loss :pershare-purchase-ratio}

            game-profit-loss (-> repl.state/system :game/games
                                 deref
                                 (get gameId)
                                 :profit-loss)
            pl-entries ((juxt
                          #(get % stockIdA)
                          #(get % stockIdB)) game-profit-loss)]


        (is (= expected-pl-entry-count (count pl-entries)))

        (->> (map first pl-entries)
             (map keys)
             (map #(into #{} %))
             (every? #(= expected-pl-calculation-keys %))
             is)))))

#_(deftest calculate-profit-loss-single-buy-sell-test

  (testing "Testing buy / sells with this pattern

            + 100
            - 100

            +200
            -200"

    (let [;; A
          conn                                (-> repl.state/system :persistence/datomic :opts :conn)
          {result-user-id :db/id
           userId         :user/external-uid} (test-util/generate-user! conn)

          ;; B
          data-sequence-fn     (constantly [100.0 110.0 , 120.0 130.0])
          data-sequence-length (count (data-sequence-fn))


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
          {{gameId     :game/id
            game-db-id :db/id
            stocks     :game/stocks :as game} :game
           control-channel                :control-channel
           game-event-stream              :game-event-stream
           :as                            game-control}
          (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)

          [_ iterations] (game.games/start-workbench! conn result-user-id game-control)
          {stockId   :game.stock/id
           stockName :game.stock/name} (first stocks)

          opts {:conn    conn
                :userId  userId
                :gameId  gameId
                :stockId stockId}
          ops  [{:op :buy :stockAmount 100}
                {:op :sell :stockAmount 100}
                {:op :buy :stockAmount 200}
                {:op :sell :stockAmount 200}]]


      (testing "Check profit/loss (running & realized), per purchase chunk - A"

        (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                    (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                      (assoc v :local-transact-input (merge stock-tick op))))
                  (take 2 iterations)
                  (take 2 ops))
             (map #(local-transact-stock! opts %))
             (take data-sequence-length)
             doall)

        (let [profit-loss (-> repl.state/system
                              :game/games deref (get gameId)
                              :profit-loss
                              (get stockId))

              [{gl1 :pershare-gain-or-loss}] (filter #(= :BUY (:op %)) profit-loss)
              [{pl1 :realized-profit-loss}]  (filter #(= :SELL (:op %)) profit-loss)]

          (are [x y] (= x y)
            10.0   gl1
            1000.0 pl1)))

      (testing "Check profit/loss (running & realized), per purchase chunk - B"

        (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                    (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                      (assoc v :local-transact-input (merge stock-tick op))))
                  (drop 2 iterations)
                  (drop 2 ops))
             (map #(local-transact-stock! opts %))
             (take data-sequence-length)
             doall)

        (let [profit-loss (-> repl.state/system
                              :game/games deref (get gameId)
                              :profit-loss
                              (get stockId))

              [{gl1 :pershare-gain-or-loss} {gl2 :pershare-gain-or-loss}] (filter #(= :BUY (:op %)) profit-loss)
              [{pl1 :realized-profit-loss} {pl2 :realized-profit-loss}]   (filter #(= :SELL (:op %)) profit-loss)]

          (are [x y] (= x y)
            ;; 10.0   gl1 ;; :pershare-gain-or-loss SHOULD NOT be updated with the latest price
            10.0   gl2
            1000.0 pl1
            2000.0 pl2)))

      (testing "We correct game.games/collect-realized-profit-loss-pergame"

        (-> (game.calculation/collect-realized-profit-loss-pergame gameId)
            first
            :profit-loss
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
          conn                                (-> repl.state/system :persistence/datomic :opts :conn)
          {result-user-id :db/id
           userId         :user/external-uid} (test-util/generate-user! conn)

          ;; B
          data-sequence-length 7
          data-sequence-fn      (constantly (take data-sequence-length (iterate (partial + 10) 100.00)))


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
          {{gameId     :game/id
            game-db-id :db/id
            stocks     :game/stocks :as game} :game
           control-channel                :control-channel
           game-event-stream              :game-event-stream
           :as                            game-control}
          (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)

          [_ iterations] (game.games/start-workbench! conn result-user-id game-control)
          {stockId   :game.stock/id
           stockName :game.stock/name} (first stocks)

          opts {:conn    conn
                :userId  userId
                :gameId  gameId
                :stockId stockId}
          ops  [{:op :buy :stockAmount 75}
                {:op :buy :stockAmount 25}
                {:op :sell :stockAmount 100}
                {:op :buy :stockAmount 120}
                {:op :buy :stockAmount 43}
                {:op :buy :stockAmount 37}
                {:op :sell :stockAmount 200}]]


      (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                  (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                    (assoc v :local-transact-input (merge stock-tick op))))
                iterations
                ops)
           (map #(local-transact-stock! opts %))
           (take data-sequence-length)
           doall)


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
            20.0                  gl1
            10.0                  gl2
            30.0                  gl3
            20.0                  gl4
            10.0                  gl5
            2250.0                pl1
            (.floatValue 7425.21) pl2)))

      (testing "game.calculation game.games/collect-realized-profit-loss-pergame"

        (-> (game.calculation/collect-realized-profit-loss-pergame gameId)
            first
            :profit-loss
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
          conn                                (-> repl.state/system :persistence/datomic :opts :conn)
          {result-user-id :db/id
           userId         :user/external-uid} (test-util/generate-user! conn)

          ;; B
          data-sequence-length 12
          data-sequence-fn      (constantly (take data-sequence-length (iterate (partial + 10) 100.00)))


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
          {{gameId     :game/id
            game-db-id :db/id
            stocks     :game/stocks :as game} :game
           control-channel                :control-channel
           game-event-stream              :game-event-stream
           :as                            game-control}
          (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)

          [_ iterations] (game.games/start-workbench! conn result-user-id game-control)
          {stockId   :game.stock/id
           stockName :game.stock/name} (first stocks)

          opts {:conn    conn
                :userId  userId
                :gameId  gameId
                :stockId stockId}
          ops  [{:op :buy :stockAmount 75}
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
                {:op :sell :stockAmount 128}]]


      (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                  (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                    (assoc v :local-transact-input (merge stock-tick op))))
                iterations
                ops)
           (map #(local-transact-stock! opts %))
           (take data-sequence-length)
           doall)


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
            0.0  gl5

            60.0 gl6
            50.0 gl7
            40.0 gl8
            30.0 gl9
            20.0 gl10

            10.0 gl11
            0.0  gl12

            227.5                  pl1
            (.floatValue 1721.38)  pl2
            (.floatValue 1822.41)  pl3
            (.floatValue 173.62)   pl4
            (.floatValue 596.08)   pl5
            (.floatValue 2049.47)  pl6
            (.floatValue 11139.32) pl7)))

      (testing "game.calculation game.games/collect-realized-profit-loss-pergame"

        (->> (game.calculation/collect-realized-profit-loss-pergame gameId)
             first
             :profit-loss
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

#_(deftest calculate-profit-loss-on-tick-test

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
          data-sequence-length 3
          data-sequence-fn (constantly (take data-sequence-length (iterate (partial + 10) 100.00)))


          ;; C create-game!
          sink-fn    identity

          stock-tick-result       (atom [])
          portfolio-update-result (atom [])
          profit-loss-history     (atom [])

          game-id (UUID/randomUUID)
          opts          {:level-timer-sec               10
                         :game-id                       game-id
                         :accounts                      (game.core/->game-user-accounts)
                         :collect-profit-loss (fn [{:keys [profit-loss] :as result}]
                                                          (swap! profit-loss-history #(conj % profit-loss))
                                                          (->> (game.calculation/collect-running-profit-loss game-id profit-loss)
                                                               (assoc result :profit-loss)))}

          game-level :game-level/one
          {{gameId     :game/id
            game-db-id :db/id
            stocks     :game/stocks :as game} :game
           control-channel                :control-channel
           stock-tick-stream              :stock-tick-stream
           portfolio-update-stream        :portfolio-update-stream
           game-event-stream              :game-event-stream
           :as                            game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)

          [_ iterations] (game.games/start-workbench! conn result-user-id game-control)
          {stockId   :game.stock/id
           stockName :game.stock/name} (first stocks)

          opts                   {:conn    conn
                                  :userId  userId
                                  :gameId  gameId
                                  :stockId stockId}
          ops [{:op :buy :stockAmount 75}
               {:op :buy :stockAmount 25}
               {:op :noop}]]

      (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                  (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                    (assoc v :local-transact-input (merge stock-tick op))))
                iterations
                ops)
           (map #(local-transact-stock! opts %))
           (take data-sequence-length)
           doall)

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

      (Thread/sleep 1000)

      (testing "Correctly streaming stock-tick and profit-loss events"

        (->> @stock-tick-result
             (map #(map keys %))
             (map #(map (fn [a] (into #{} a)) %))
             (map #(every? (fn [a]
                             (= #{:game.stock.tick/id :game.stock.tick/trade-time :game.stock.tick/close
                                  :game.stock/id :game.stock/name}
                                a)) %))
             (every? true?)
             is)

        (let [expected-tick-count         3
              expected-profit-loss-values '(0.0 750.0 750.0 1750.0)
              actual-profit-loss-values   (->> @portfolio-update-result
                                               (select [ALL ALL])
                                               (filter :profit-loss-type)
                                               (map :profit-loss))

              ;; TODO stockName is losing the "STOCK.xyz" prefix
              expected-account-update-values #{#{{:bookkeeping.account/name    "Cash"
                                                  :bookkeeping.account/balance (float 92500.0)
                                                  :bookkeeping.account/amount  0}
                                                 {:bookkeeping.account/name    (str "STOCK." stockName)
                                                  :bookkeeping.account/balance (float 7500.0)
                                                  :bookkeeping.account/amount  75}
                                                 {:bookkeeping.account/name    "Equity"
                                                  :bookkeeping.account/balance (float 100000.0)
                                                  :bookkeeping.account/amount  0}}
                                               #{{:bookkeeping.account/name    "Cash"
                                                  :bookkeeping.account/balance (float 89750.0)
                                                  :bookkeeping.account/amount  0}
                                                 {:bookkeeping.account/name    (str "STOCK." stockName)
                                                  :bookkeeping.account/balance (float 10250.0)
                                                  :bookkeeping.account/amount  100}
                                                 {:bookkeeping.account/name    "Equity"
                                                  :bookkeeping.account/balance (float 100000.0)
                                                  :bookkeeping.account/amount  0}}}
              actual-account-update-values   (->> @portfolio-update-result
                                                  (select [ALL ALL])
                                                  (filter :bookkeeping.account/balance)
                                                  (map #(select-keys % [:bookkeeping.account/name
                                                                        :bookkeeping.account/balance
                                                                        :bookkeeping.account/amount]))
                                                  (partition 3)
                                                  (into #{})
                                                  (transform [ALL] #(into #{} %)))]

          (are [x y] (= x y)
            expected-tick-count            (count @stock-tick-result)
            expected-profit-loss-values    actual-profit-loss-values
            expected-account-update-values actual-account-update-values)))

      (testing "Correctly recalculating profit-loss on a tick update (:running-profit-loss increases)"

        (let [extract-pl #(->> (get % stockId)
                               (filter :running-profit-loss)
                               (map :running-profit-loss))
              [pl0 pl1]  (->> @profit-loss-history
                              (remove empty?)
                              ((juxt first second)))]

          (->> (map (fn [l r]
                      (< l r))
                    (extract-pl pl0)
                    (extract-pl pl1))
               (every? true?)
               is))))))

#_(deftest stream-portfolio-update!-on-transact-test

  ;; A
  (let [;; A
        conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
         userId         :user/external-uid} (test-util/generate-user! conn)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        data-sequence-length (count (data-sequence-fn))


        ;; C create-game!
        sink-fn    identity

        stock-tick-result       (atom [])
        portfolio-update-result (atom [])
        profit-loss-history     (atom [])

        game-id (UUID/randomUUID)
        opts          {:level-timer-sec 5
                       :accounts        (game.core/->game-user-accounts)}

        game-level :game-level/one
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks :as game} :game
         control-channel                :control-channel
         portfolio-update-stream        :portfolio-update-stream
         :as                            game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)

        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts                   {:conn    conn
                                :userId  userId
                                :gameId  gameId
                                :stockId stockId}
        ops [{:op :noop}
             {:op :buy :stockAmount 75}
             {:op :buy :stockAmount 25}
             {:op :noop}
             {:op :noop}]]

    (core.async/go-loop []
      (let [a (core.async/<! portfolio-update-stream)]
        (when a
          (swap! portfolio-update-result #(conj % a))
          (recur))))

    (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                  (assoc v :local-transact-input (merge stock-tick op))))
              iterations
              ops)
         (map #(local-transact-stock! opts %))
         (take data-sequence-length)
         doall)

    ;; B
    (testing "We are correctly streaming running-profit-loss and account-balances updates"

      (let [expected-profit-loss-count     5
            expected-account-balance-count 2

            expected-profit-loss-keys   #{:game-id :stock-id :profit-loss-type :profit-loss }
            expected-profit-loss-values (->> [125.0 0.0 1125.0 -375.0]
                                             (map float)
                                             (into #{}))

            expected-account-balance-updates #{#{{:bookkeeping.account/name    (str "STOCK." stockName)
                                                  :bookkeeping.account/balance (float 10875.0)
                                                  :bookkeeping.account/amount  100}
                                                 {:bookkeeping.account/name    "Cash"
                                                  :bookkeeping.account/balance (float 89125.0)
                                                  :bookkeeping.account/amount  0}
                                                 {:bookkeeping.account/name    "Equity"
                                                  :bookkeeping.account/balance (float 100000.0)
                                                  :bookkeeping.account/amount  0}}
                                               #{{:bookkeeping.account/name    (str "STOCK." stockName)
                                                  :bookkeeping.account/balance (float 8250.0)
                                                  :bookkeeping.account/amount  75}
                                                 {:bookkeeping.account/name    "Cash"
                                                  :bookkeeping.account/balance (float 91750.0)
                                                  :bookkeeping.account/amount  0}
                                                 {:bookkeeping.account/name    "Equity"
                                                  :bookkeeping.account/balance (float 100000.0)
                                                  :bookkeeping.account/amount  0}}}

            profit-loss (->> @portfolio-update-result
                             (select [ALL ALL])
                             (filter :profit-loss-type))

            account-balances (->> @portfolio-update-result
                                  (select [ALL ALL])
                                  (filter :bookkeeping.account/balance)
                                  (map #(select-keys % [:bookkeeping.account/name
                                                        :bookkeeping.account/balance
                                                        :bookkeeping.account/amount]))
                                  (partition 3)
                                  (into #{})
                                  (transform [ALL] #(into #{} %)))]

        (are [x y] (= x y)
          expected-account-balance-count   (count account-balances)
          expected-profit-loss-count       (count profit-loss)
          expected-account-balance-updates account-balances)

        (->> (map keys profit-loss)
             (map #(into #{} %))
             (every? #(= expected-profit-loss-keys %))
             is)

        (->> (map #(select-keys % [:profit-loss]) profit-loss)
             (map vals)
             flatten
             (into #{})
             (= expected-profit-loss-values)
             is)))))

#_(deftest check-game-status-test

  (let [;; A
        conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
         userId         :user/external-uid} (test-util/generate-user! conn)

        ;; B
        data-sequence-length     12
        data-sequence-fn (constantly (take data-sequence-length (iterate (partial + 10) 100.00)))

        opts {:tick-sleep-ms 500
              :level-timer-sec 10
              :accounts        (game.core/->game-user-accounts)}

        ;; C create-game!
        sink-fn    identity
        game-level :game-level/one
        {{gameId     :game/id
          stocks     :game/stocks :as game} :game
         game-event-stream              :game-event-stream
         :as                            game-control}
        (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)]


    (testing "Pause, resume and exit signals"

      (game.games/start-game! conn result-user-id game-control)
      (is (not (game.games/game-paused? gameId)))


      (game.games/send-control-event! gameId {:event :pause :game-id gameId})
      (Thread/sleep 1000)
      (is (game.games/game-paused? gameId))


      (game.games/send-control-event! gameId {:event :resume :game-id gameId})
      (Thread/sleep 1000)
      (is (not (game.games/game-paused? gameId)))


      (testing "Checking game-message after a pause, then resume"

        (let [expected-events #{{:event :pause
                                 :game-id gameId}
                                {:event :resume
                                 :game-id gameId}}

              expected-level-timer-keys #{:remaining-in-minutes :remaining-in-seconds :game-id :type :level :event}

              game-events (test-util/to-coll game-event-stream)]

          (->> game-events
               (filter #(some #{:pause :resume} #{(:event %)}))
               (map #(select-keys % [:event :game-id]))
               (into #{})
               (= expected-events)
               is)

          (->> game-events
               (filter #(= :continue (:event %)))
               (map keys)
               (map #(into #{} %))
               (every? #(= expected-level-timer-keys %))
               is)))

      (game.games/send-control-event! gameId {:event :exit :game-id gameId})
      (Thread/sleep 1000)
      (is (not (game.games/game-paused? gameId))))


    (testing "Checking game-message after an exit"

      (let [expected-game-events [{:event :exit :game-id gameId}]
            game-events (test-util/to-coll game-event-stream)]

        (is expected-game-events game-events)))))

#_(deftest check-level-win-test

  (let [;; A
        conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
         userId         :user/external-uid} (test-util/generate-user! conn)

        ;; B
        data-sequence-length     12
        data-sequence-fn (constantly (take data-sequence-length (iterate (partial + 10) 100.00)))

        opts {:level-timer-sec 2
              :accounts        (game.core/->game-user-accounts)}

        ;; C create-game!
        sink-fn    identity
        game-level :game-level/one
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks :as game} :game
         control-channel                :control-channel
         game-event-stream              :game-event-stream
         :as                            game-control}
        (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)

        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)
        {stockId   :game.stock/id
         ;; stockName :game.stock/name
         } (first stocks)

        opts                   {:conn    conn
                                :userId  userId
                                :gameId  gameId
                                :stockId stockId}
        ops                    [{:op :buy :stockAmount 75}
                                {:op :buy :stockAmount 25}
                                {:op :sell :stockAmount 13}]]

    (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                  (assoc v :local-transact-input (merge stock-tick op))))
              iterations
              ops)
         (map #(local-transact-stock! opts %))
         (take 3)
         doall)

    (testing "Correct game message is received"

      (let [expected-count   1
            expected-message {:game-id     gameId
                              :level       :game-level/one
                              :profit-loss 1750.0
                              :event :win}
            result-messages  (test-util/to-coll game-event-stream)]

        (are [x y] (= x y)
          expected-count   (count result-messages)
          expected-message (first result-messages))

        (testing "Correct game levels are set"

          (let [expected-game-level {:profit-threshold 10000.0
                                     :lose-threshold   2000.0
                                     :level            :game-level/two}
                current-game-level  (-> repl.state/system
                                        :game/games deref (get gameId)
                                        :current-level)

                expected-db-game-level :game-level/two
                current-db-game-level  (-> (d/q '[:find (pull ?l [*])
                                                  :in $ ?game-id
                                                  :where
                                                  [?g :game/id ?game-id]
                                                  [?g :game/level ?l]]
                                                (d/db conn)
                                                gameId)
                                           ffirst
                                           :db/ident)]

            (are [x y] (= x y)
              expected-game-level    current-game-level
              expected-db-game-level current-db-game-level)))))))

#_(deftest check-level-lose-test

  (let [;; A
        conn                                (-> repl.state/system :persistence/datomic :opts :conn)
        {result-user-id :db/id
         userId         :user/external-uid} (test-util/generate-user! conn)

        ;; B
        data-sequence-length     12
        data-sequence-fn (constantly (take data-sequence-length (iterate (partial - 10) 100.00)))

        opts {:level-timer-sec 5
              :accounts        (game.core/->game-user-accounts)}

        ;; C create-game!
        sink-fn    identity
        game-level :game-level/one
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks :as game} :game
         control-channel                :control-channel
         game-event-stream              :game-event-stream
         :as                            game-control}
        (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)

        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts                   {:conn    conn
                                :userId  userId
                                :gameId  gameId
                                :stockId stockId}
        ops                    [{:op :buy :stockAmount 50}
                                {:op :noop}
                                {:op :sell :stockAmount 50}]]

    (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]
                (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                  (assoc v :local-transact-input (merge stock-tick op))))
              iterations
              ops)
         (map #(local-transact-stock! opts %))
         (take 3)
         doall)

    (testing "Correct game message is received"

      (let [expected-count    2
            expected-messages [{:game-id     gameId
                                :level       :game-level/one
                                :profit-loss -9500.0
                                :event     :lose}
                               {:event :exit
                                :game-id gameId}]
            result-messages   (test-util/to-coll game-event-stream)]

        (are [x y] (= x y)
          expected-count    (count result-messages)
          expected-messages result-messages)

        (testing "Correct game levels are set"

          (let [expected-game-level {:level            :game-level/one
                                     :profit-threshold 1000.0
                                     :lose-threshold   1000.0}

                current-game-level (-> repl.state/system
                                       :game/games deref (get gameId)
                                       :current-level
                                       deref)

                expected-db-game-level :game-level/one
                current-db-game-level  (-> (d/q '[:find (pull ?l [*])
                                                  :in $ ?game-id
                                                  :where
                                                  [?g :game/id ?game-id]
                                                  [?g :game/level ?l]]
                                                (d/db conn)
                                                gameId)
                                           ffirst
                                           :db/ident)]

            (are [x y] (= x y)
              expected-game-level    current-game-level
              expected-db-game-level current-db-game-level)))))))

;; TODO
(deftest pausing-game-stores-expected-data-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        result-user-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :accounts        (game.core/->game-user-accounts)}
        game-level :game-level/one


        ;; D Launch Game
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as        game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)
        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)


        ;; E Buy Stock
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  gameId
              :stockId stockId
              :game-control game-control}

        ops-before-pause  [{:op :buy :stockAmount 100}
                           {:op :buy :stockAmount 200}
                           {:op :sell :stockAmount 200}]
        ops-before-pause-count (count ops-before-pause)]

    (is true)

    ;; BEFORE :pause
    (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                  (assoc v :local-transact-input (merge stock-tick op))))
              (take ops-before-pause-count iterations)
              (take ops-before-pause-count ops-before-pause))
         (map #(local-transact-stock! opts %))
         doall)

    ;; :pause
    (games.control/pause-game! conn gameId)

    ;; :game/start-position
    ;; :game.user/profit-loss
    ;; :game/status #:db{:id 17592186045430 :ident :game-status/paused}
    ;; :game/level-timer "[]"
    ;; :game/level #:db{:id 17592186045417 :ident :game-level/one}
    ;; :game.stock/data-seed


    ;; B AFTER :pause
    (util/pprint+identity
      (persistence.core/entity-by-domain-id conn :game/id gameId))
    ))

;; TODO
(deftest replay-reconstructs-running-profit-loss-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        result-user-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 120.0 110.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :accounts        (game.core/->game-user-accounts)}
        game-level :game-level/one


        ;; D Launch Game
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as        game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)
        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)


        ;; E Buy Stock
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  gameId
              :stockId stockId
              :game-control game-control}

        ops-before-pause  [{:op :buy :stockAmount 100}
                           {:op :buy :stockAmount 200}
                           {:op :sell :stockAmount 200}]
        ops-before-pause-count (count ops-before-pause)]

    (is true)

    ;; BEFORE :pause
    (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                  (assoc v :local-transact-input (merge stock-tick op))))
              (take ops-before-pause-count iterations)
              (take ops-before-pause-count ops-before-pause))
         (map #(local-transact-stock! opts %))
         doall)

    ;; :pause
    (games.control/pause-game! conn gameId)

    ;; B AFTER :pause
    #_(util/pprint+identity
      (persistence.core/entity-by-domain-id conn :game/id gameId))

    ;; Resume game
    (games.control/resume-game! conn gameId result-user-id game-control)

    ))

;; TODO
(deftest resume-game-correctly-replays-ticks-AND-pipelines-from-the-correct-position-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        result-user-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 , 120.0 112.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :accounts        (game.core/->game-user-accounts)}
        game-level :game-level/one


        ;; D Launch Game
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as        game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)
        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)


        ;; E Buy Stock
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  gameId
              :stockId stockId
              :game-control game-control}

        ;; ops  [{:op :buy :stockAmount 100}
        ;;       ;; {:op :sell :stockAmount 100}
        ;;       {:op :buy :stockAmount 200}
        ;;       {:op :sell :stockAmount 200}]

        ops-before-pause  [{:op :buy :stockAmount 100}
                           {:op :buy :stockAmount 200}
                           {:op :sell :stockAmount 200}]
        ops-before-pause-count (count ops-before-pause)

        ops-after-pause  [{:op :sell :stockAmount 100}]
        ops-after-pause-count (count ops-after-pause)]

    (is true)

    ;; BEFORE :pause
    (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                  (assoc v :local-transact-input (merge stock-tick op))))
              (take ops-before-pause-count iterations)
              (take ops-before-pause-count ops-before-pause))
         (map #(local-transact-stock! opts %))
         doall)

    ;; :pause
    (games.control/pause-game! conn gameId)

    ;; B AFTER :pause
    #_(util/pprint+identity
      (persistence.core/entity-by-domain-id conn :game/id gameId))

    ;; Resume game
    (let [{iterations :iterations} (games.control/resume-workbench! conn gameId result-user-id game-control data-sequence-fn)]

      (->> (map first iterations)
           (take 5)
           util/pprint+identity)

      )))

;; TODO
(deftest B-test

  (let [;; A
        conn (-> repl.state/system :persistence/datomic :opts :conn)
        user (test-util/generate-user! conn)
        result-user-id (:db/id user)
        userId         (:user/external-uid user)

        ;; B
        data-sequence-fn (constantly [100.0 110.0 105.0 , 120.0 112.0 125.0 130.0])
        tick-length      (count (data-sequence-fn))

        ;; C
        sink-fn                identity

        test-stock-ticks       (atom [])
        test-portfolio-updates (atom [])

        opts       {:level-timer-sec 5
                    :accounts        (game.core/->game-user-accounts)}
        game-level :game-level/one


        ;; D Launch Game
        {{gameId     :game/id
          game-db-id :db/id
          stocks     :game/stocks
          :as        game} :game
         :as        game-control} (game.games/create-game! conn result-user-id sink-fn game-level data-sequence-fn opts)
        [_ iterations] (game.games/start-workbench! conn result-user-id game-control)


        ;; E Buy Stock
        {stockId   :game.stock/id
         stockName :game.stock/name} (first stocks)

        opts {:conn    conn
              :userId  userId
              :gameId  gameId
              :stockId stockId
              :game-control game-control}

        ;; ops  [{:op :buy :stockAmount 100}
        ;;       ;; {:op :sell :stockAmount 100}
        ;;       {:op :buy :stockAmount 200}
        ;;       {:op :sell :stockAmount 200}]

        ops-before-pause  [{:op :buy :stockAmount 100}
                           {:op :buy :stockAmount 200}
                           {:op :sell :stockAmount 200}]
        ops-before-pause-count (count ops-before-pause)

        ops-after-pause  [{:op :sell :stockAmount 100}]
        ops-after-pause-count (count ops-after-pause)]

    (is true)

    ;; BEFORE :pause
    (->> (map (fn [[{stock-ticks :stock-ticks :as v} vs] op]

                (let [stock-tick (util/narrow-stock-ticks stockId stock-ticks)]
                  (assoc v :local-transact-input (merge stock-tick op))))
              (take ops-before-pause-count iterations)
              (take ops-before-pause-count ops-before-pause))
         (map #(local-transact-stock! opts %))
         doall)

    ;; :pause
    (games.control/pause-game! conn gameId)

    ;; B AFTER :pause
    #_(util/pprint+identity
      (persistence.core/entity-by-domain-id conn :game/id gameId))

    ;; Resume game
    (let [{iterations :iterations} (games.control/resume-workbench! conn gameId result-user-id game-control data-sequence-fn)]

      (->> (map first iterations)
           (take 5)
           util/pprint+identity)

      )))
