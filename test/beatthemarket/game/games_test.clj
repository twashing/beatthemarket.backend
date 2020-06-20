(ns beatthemarket.game.games-test
  (:require [clojure.test :refer :all]
            [integrant.repl.state :as repl.state]
            [beatthemarket.game.games :as games]
            [beatthemarket.test-util :as test-util]))


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
           is))))

(comment


  (require '[beatthemarket.test-util :as test-util]
           '[beatthemarket.iam.authentication :as iam.auth]
           '[beatthemarket.iam.user :as iam.user])

  ;; A
  (do

    (def conn                   (-> repl.state/system :persistence/datomic :conn))
    (def id-token               (test-util/->id-token))
    (def checked-authentication (iam.auth/check-authentication id-token))
    (def add-user-db-result     (iam.user/conditionally-add-new-user! conn checked-authentication))
    (def result-user-id         (ffirst
                                  (d/q '[:find ?e
                                         :in $ ?email
                                         :where [?e :user/email ?email]]
                                       (d/db conn)
                                       (-> checked-authentication
                                           :claims (get "email")))))
    (def sink-fn                util/pprint+identity)
    (def game-control           (create-game! conn result-user-id sink-fn)))


  ;; B
  (let [{:keys [game stocks-with-tick-data tick-sleep-ms
                data-subscription-channel control-channel
                close-sink-fn sink-fn]}
        game-control]

    (stream-subscription! tick-sleep-ms
                          data-subscription-channel control-channel
                          close-sink-fn sink-fn)

    ;; (def message                (game->new-game-message game result-user-id))
    ;; (>!! data-subscription-channel message)

    (core.async/onto-chan
      data-subscription-channel
      (data-subscription-stock-sequence conn game result-user-id stocks-with-tick-data)))

  ;; C
  (-> game-control :control-channel (>!! :exit)))
