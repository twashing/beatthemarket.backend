(ns beatthemarket.game.games-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as core.async
             :refer [>!! <!!] #_[go go-loop chan close! timeout alts! >! <! >!!]]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]
            [datomic.client.api :as d]
            [integrant.repl.state :as repl.state]
            [beatthemarket.game.games :as games]
            [beatthemarket.test-util :as test-util])
  (:import [java.util UUID]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture)


(deftest create-game!-test

  (testing "We get an expected game-control struct"

    (let [conn           (-> repl.state/system :persistence/datomic :conn)
          result-user-id (test-util/generate-user conn)
          sink-fn        identity
          game-control   (games/create-game! conn result-user-id sink-fn)

          expected-game-control-keys
          (sort '(:game :stocks-with-tick-data :tick-sleep-ms :data-subscription-channel :control-channel :close-sink-fn :sink-fn))]

      (->> game-control
           keys
           sort
           (= expected-game-control-keys)
           is)


      (testing "Streaming subscription flows through the correct value"

        (let [{:keys [game tick-sleep-ms stocks-with-tick-data
                      data-subscription-channel control-channel]} game-control
              close-sink-fn sink-fn]

          (games/stream-subscription! tick-sleep-ms
                                      data-subscription-channel control-channel
                                      close-sink-fn sink-fn)

          (games/onto-open-chan
            ;; core.async/onto-chan
            data-subscription-channel
            (games/data-subscription-stock-sequence conn game result-user-id stocks-with-tick-data))

          (let [[t0-time _ id0] (<!! data-subscription-channel)
                [t1-time _ id1] (<!! data-subscription-channel)]

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
