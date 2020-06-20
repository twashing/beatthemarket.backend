(ns workbench.games
  (:require [clojure.core.async :as core.async
             :refer [>!! close!]]
            [datomic.client.api :as d]
            [integrant.repl.state :as repl.state]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.iam.authentication :as iam.auth]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.game.games :as games]
            [beatthemarket.util :as util]))


(comment ;; Create Game + Stream Stock Subscription


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
    (def game-control           (games/create-game! conn result-user-id sink-fn)))


  ;; B
  (let [{:keys [game stocks-with-tick-data tick-sleep-ms
                data-subscription-channel control-channel
                close-sink-fn sink-fn]}
        game-control]

    (games/stream-subscription! tick-sleep-ms
                                data-subscription-channel control-channel
                                close-sink-fn sink-fn)

    ;; (def message                (game->new-game-message game result-user-id))
    ;; (>!! data-subscription-channel message)

    (core.async/onto-chan
      data-subscription-channel
      (games/data-subscription-stock-sequence conn game result-user-id stocks-with-tick-data)))


  ;; C
  (-> game-control :control-channel (>!! :exit))


  ;; D
  (run! (fn [{:keys [data-subscription-channel control-channel]}]

          (println [data-subscription-channel control-channel])
          (>!! control-channel :exit)
          (close! data-subscription-channel)
          (close! control-channel))
        (-> repl.state/system :game/games deref vals)))
