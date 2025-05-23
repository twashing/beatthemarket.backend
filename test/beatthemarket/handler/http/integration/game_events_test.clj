(ns beatthemarket.handler.http.integration.game-events-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :as core.async]
            [integrant.repl.state :as state]
            ;; [com.rpl.specter :refer [transform ALL]]

            [beatthemarket.test-util :as test-util]
            [beatthemarket.util :refer [ppi] :as util])
  (:import [java.util UUID]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture
  (test-util/subscriptions-fixture "ws://localhost:8080/ws"))

(deftest game-events-control-events-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        email "twashing@gmail.com"
        gameLevel 1]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "mutation CreateGame($gameLevel: Int!) {
                                     createGame(gameLevel: $gameLevel) {
                                       id
                                       stocks { id name symbol }
                                     }
                                   }"
                           :variables {:gameLevel gameLevel}}})

    (test-util/<message!! 1000)

    (let [{gameId :id} (-> (test-util/<message!! 1000) :payload :data :createGame)]

      (test-util/send-data {:id   992
                            :type :start
                            :payload
                            {:query "subscription GameEvents($gameId: String!) {
                                       gameEvents(gameId: $gameId) {
                                         ... on ControlEvent {
                                           event
                                           gameId
                                         }
                                         ... on LevelStatus {
                                           event
                                           gameId
                                           profitLoss
                                           level
                                         }
                                         ... on LevelTimer {
                                           gameId
                                           level
                                           minutesRemaining
                                           secondsRemaining
                                         }
                                       }
                                     }"
                             :variables {:gameId gameId}}})

      (test-util/send-data {:id   988
                            :type :start
                            :payload
                            {:query "mutation StartGame($id: String!) {
                                         startGame(id: $id) {
                                           stockTickId
                                           stockTickTime
                                           stockTickClose
                                           stockId
                                           stockName
                                         }
                                       }"
                             :variables {:id gameId}}})

      (test-util/<message!! 1000)
      (test-util/<message!! 1000)
      (test-util/<message!! 1000)

      ;; >> ================ >>

      ;; A.i
      (testing "We receive the correct pause event Ackknowledgement"

        (test-util/send-data {:id   989
                              :type :start
                              :payload
                              {:query "mutation PauseGame($gameId: String!) {
                                       pauseGame(gameId: $gameId) {
                                         event
                                         gameId
                                       }
                                     }"
                               :variables {:gameId gameId}}})

        (let [expected-pause-response {:type "data"
                                       :id 989
                                       :payload
                                       {:data
                                        {:pauseGame
                                         {:event "pause" :gameId gameId}}}}

              pause-response (test-util/<message!! 1000)]

          (is (= expected-pause-response pause-response))))


      ;; A.ii
      (testing "We receive the correct pause subscription notification"

        (test-util/<message!! 1000)
        (let [expected-pause-event {:type "data"
                                    :id 992
                                    :payload
                                    {:data
                                     {:gameEvents
                                      {:event "pause" :gameId gameId}}}}

              pause-event (test-util/<message!! 1000)]

          (is (= expected-pause-event pause-event))))


      ;; >> ================ >>

            ;; B.i
      (testing "We receive the correct resume event Acknowledgement"

        (test-util/send-data {:id   990
                              :type :start
                              :payload
                              {:query "mutation ResumeGame($gameId: String!) {
                                       resumeGame(gameId: $gameId) {
                                         event
                                         gameId
                                       }
                                     }"
                               :variables {:gameId gameId}}})

        (let [expected-resume-response {:type "data"
                                       :id 990
                                       :payload
                                       {:data
                                        {:resumeGame
                                         {:event "resume" :gameId gameId}}}}

              resume-response (test-util/<message!! 1000)]

          (is (= expected-resume-response resume-response))))


      ;; >> ================ >>


      ;; C
      (testing "We receive the correct exit event Ackknowledgement"

        (test-util/send-data {:id   993
                              :type :start
                              :payload
                              {:query "mutation exitGame($gameId: String!) {
                                       exitGame(gameId: $gameId) {
                                         event
                                         gameId
                                       }
                                     }"
                               :variables {:gameId gameId}}})

        (test-util/<message!! 1000)
        (test-util/<message!! 1000)
        (let [expected-exit-message {:type "data"
                                     :id 993
                                     :payload
                                     {:data
                                      {:exitGame
                                       {:event "exit", :gameId gameId}}}}

              exit-message (test-util/<message!! 1000)]

          (is (= expected-exit-message exit-message)))))))

(deftest game-events-level-timer-test

  (let [service (-> state/system :server/server :io.pedestal.http/service-fn)
        id-token (test-util/->id-token)
        client-id (UUID/randomUUID)

        email "twashing@gmail.com"
        gameLevel 1]

    (test-util/send-init {:client-id (str client-id)})
    (test-util/login-assertion service id-token)

    (test-util/send-data {:id   987
                          :type :start
                          :payload
                          {:query "mutation CreateGame($gameLevel: Int!) {
                                     createGame(gameLevel: $gameLevel) {
                                       id
                                       stocks { id name symbol }
                                     }
                                   }"
                           :variables {:gameLevel gameLevel}}})

    (test-util/<message!! 1000)

    (let [{gameId :id} (-> (test-util/<message!! 1000) :payload :data :createGame)]

      (test-util/send-data {:id   992
                            :type :start
                            :payload
                            {:query "subscription GameEvents($gameId: String!) {
                                       gameEvents(gameId: $gameId) {
                                         ... on ControlEvent {
                                           event
                                           gameId
                                         }
                                         ... on LevelStatus {
                                           event
                                           gameId
                                           profitLoss
                                           level
                                         }
                                         ... on LevelTimer {
                                           gameId
                                           level
                                           minutesRemaining
                                           secondsRemaining
                                         }
                                       }
                                     }"
                             :variables {:gameId gameId}}})

      (test-util/send-data {:id   988
                            :type :start
                            :payload
                            {:query "mutation StartGame($id: String!) {
                                         startGame(id: $id) {
                                           stockTickId
                                           stockTickTime
                                           stockTickClose
                                           stockId
                                           stockName
                                         }
                                       }"
                             :variables {:id gameId}}})

      (test-util/<message!! 1000)
      (test-util/<message!! 1000)

      (Thread/sleep 100)

      (let [expected-timer-event {:type "data"
                                  :id 992
                                  :payload
                                  {:data
                                   {:gameEvents
                                    {:gameId gameId
                                     :level 1
                                     :minutesRemaining 5
                                     :secondsRemaining 0}}}}

            timer-event (->> (test-util/consume-subscriptions)
                             (filter #(= 992 (:id %)))
                             first)]

        (is (= expected-timer-event timer-event)))


      ;; >> ================ >>

      (test-util/send-data {:id   993
                            :type :start
                            :payload
                            {:query "mutation exitGame($gameId: String!) {
                                       exitGame(gameId: $gameId) {
                                         event
                                         gameId
                                       }
                                     }"
                             :variables {:gameId gameId}}}))))
