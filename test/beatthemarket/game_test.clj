(ns beatthemarket.game-test
  (:require [clojure.test :refer :all]
            [datomic.client.api :as d]
            [integrant.repl.state :as state]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.persistence.user :as persistence.user]
            [beatthemarket.game :as game]
            [beatthemarket.util :as util])
  (:import [java.util UUID]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture)


(deftest initialize-game-test

  (let [conn (-> integrant.repl.state/system :persistence/datomic :conn)

        email "foo@bar.com"
        checked-authentication
        (hash-map :email email
                  :name "Foo Bar"
                  :uid (str (UUID/randomUUID)))

        ;; Add User
        _              (persistence.user/add-user! conn checked-authentication)
        result-user-id (-> (d/q '[:find ?e
                                  :in $ ?email
                                  :where [?e :user/email ?email]]
                                (d/db conn)
                                email)
                           ffirst)

        user-entity (hash-map :db/id result-user-id)

        ;; Initialize Game
        game (game/initialize-game conn user-entity)
        result-game-id (-> (d/q '[:find ?e
                                  :in $ ?game-idre
                                  :where [?e :game/id ?game-id]]
                                (d/db conn)
                                (:game/id game))
                           ffirst)

        pulled-game (d/pull (d/db conn) '[*] result-game-id)
        pulled-user (d/pull (d/db conn) '[*] result-user-id)]

    (let [game-users (:game/users pulled-game)]

      (testing "User has bound game"

        (-> game-users first :game.user/user :user/email (= email) is)

        (is (= pulled-user (-> game-users first :game.user/user))))

      (testing "User's stock subscriptions are a part of the games stocks"
        (let [game-stocks (:game/stocks pulled-game)
              game-user-subscriptions (-> game-users first :game.user/subscriptions)]

          (is (some (into #{} game-stocks) game-user-subscriptions)))))))
