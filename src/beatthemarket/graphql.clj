(ns beatthemarket.graphql
  (:require [clojure.core.async :as core.async
             :refer [>!!]]
            [datomic.client.api :as d]
            [com.rpl.specter :refer [transform ALL MAP-VALS]]
            [integrant.repl.state :as repl.state]
            [beatthemarket.util :as util]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.game.game :as game]
            [beatthemarket.game.games :as games]
            [clojure.data.json :as json]))


(defn resolve-hello
  [_context _args _value]
  "Hello, Clojurians!")

(defn resolve-login
  [context _ _]

  (let [{{checked-authentication :checked-authentication}
         :request}                                   context
        conn                                         (-> repl.state/system :persistence/datomic :conn)
        {:keys [db-before db-after tx-data tempids]} (iam.user/conditionally-add-new-user! conn checked-authentication)]

    (if (util/truthy? (and db-before db-after tx-data tempids))
      "user-added"
      "user-exists")))


;; TODO
;; Buy Stock

;;   ? pause subscription stream
;;     custom onto-chan (pause, inject vals)

;;   db/q game stock
;;   verify tick id
;;   verify tick price
;;   verify buy price is most recent
;;   tentry verify balanced

(defn resolve-buy-stock [context args _]

  (println "resolve-buy-stock CALLED /" args)

  ;; resolve-buy-stock CALLED / {:input {:gameId zxcv, :stockId qwerty, :tickId asdf, :tickTime 3456, :tickPrice 1234.45}} nil

  (let [{{{userId :uid} :checked-authentication} :request}    context
        conn                                               (-> repl.state/system :persistence/datomic :conn)
        {{:keys [gameId stockId tickId tickPrice]} :input} args]

    (games/buy-stock! conn userId gameId stockId tickId tickPrice)

    {:message "Ack"}))

(defn stream-new-game
  [context _ source-stream]

  (let [conn                                                (-> repl.state/system :persistence/datomic :conn)
        {{{email :email} :checked-authentication} :request} context
        result-user-id                                      (ffirst
                                                              (d/q '[:find ?e
                                                                     :in $ ?email
                                                                     :where [?e :user/email ?email]]
                                                                   (d/db conn)
                                                                   email))
        sink-fn                                             source-stream

        ;; A
        {:keys                         [game stocks-with-tick-data tick-sleep-ms
                                        data-subscription-channel control-channel
                                        close-sink-fn sink-fn] :as game-control} (games/create-game! conn result-user-id sink-fn)

        ;; B
        message (games/game->new-game-message game result-user-id)]

    ;; C
    (games/stream-subscription! tick-sleep-ms
                                data-subscription-channel control-channel
                                close-sink-fn sink-fn)

    (>!! data-subscription-channel message)

    ;; D  NOTE have a mechanism to stream multiple subscriptions
    (games/onto-open-chan ;;core.async/onto-chan
      data-subscription-channel
      (games/data-subscription-stock-sequence conn game result-user-id stocks-with-tick-data))

    ;; Return a cleanup fn
    (constantly nil)))


;; NOTE subscription resolver
(def *ping-subscribes (atom 0))
(def *ping-cleanups (atom 0))
(def *ping-context (atom nil))

(defn stream-ping
  [context args source-stream]
  (swap! *ping-subscribes inc)
  (reset! *ping-context context)
  (let [{:keys [message count]} args
        runnable                ^Runnable (fn []
                                            (dotimes [i count]

                                              ;; (println "Sanity check / stream-ping / " [i count])
                                              (source-stream {:message   (str message " #" (inc i))
                                                              :timestamp (System/currentTimeMillis)})
                                              (Thread/sleep 50))

                                            (source-stream nil))]
    (.start (Thread. runnable "stream-ping-thread")))
  ;; Return a cleanup fn:
  #(swap! *ping-cleanups inc))
