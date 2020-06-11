(ns beatthemarket.game-test
  (:require [clojure.test :refer :all]
            [datomic.client.api :as d]
            [integrant.repl.state :as state]
            [beatthemarket.test-util
             :refer [component-prep-fixture component-fixture subscriptions-fixture]]

            [beatthemarket.persistence.user :as persistence.user]
            [beatthemarket.game :as game])
  (:import [java.util UUID]))


(use-fixtures :once (partial component-prep-fixture :test))
(use-fixtures :each component-fixture)


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
        _           (game/initialize-game conn user-entity)
        pulled-user (d/pull (d/db conn) '[*] result-user-id)]

    (testing "User has bound game"

      (let [{bound-games :user/games} pulled-user
            [{subscriptions :game/subscriptions
              stocks        :game/stocks}]     bound-games]

        (is (= (sort '(:db/id :user/email :user/name :user/external-uid :user/games :user/accounts))
               (-> pulled-user keys sort)))

        (is (some (into #{} stocks) subscriptions))))))
