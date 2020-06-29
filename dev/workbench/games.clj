(ns workbench.games
  (:require [clojure.core.async :as core.async
             :refer [>!! close! chan to-chan pipeline-blocking]]
            [datomic.client.api :as d]
            [integrant.repl.state :as repl.state]
            [beatthemarket.test-util :as test-util]
            [beatthemarket.iam.authentication :as iam.auth]
            [beatthemarket.iam.user :as iam.user]
            [beatthemarket.game.games :as games]
            [beatthemarket.persistence.datomic :as persistence.datomic]
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
                stock-stream-channel control-channel
                close-sink-fn sink-fn]}
        game-control]

    #_(games/stream-subscription! tick-sleep-ms
                                  stock-stream-channel control-channel
                                  close-sink-fn sink-fn)

    ;; (def message                (game->new-game-message game result-user-id))
    ;; (>!! stock-stream-channel message)

    #_(core.async/onto-chan
        stock-stream-channel
        (games/stocks->stock-sequences conn game result-user-id stocks-with-tick-data))

    ;; i.
    #_(->> (games/stocks->stock-sequences conn game result-user-id stocks-with-tick-data)
         (take 4)
         ;; first ;; <<
         pprint)


    ;; input-chan (stock-sequences)
    ;; mix-chan (pause, resume)


    ;; ii.
    (let [concurrent        10
          input-chan        (to-chan (take 2 (games/stocks->stock-sequences conn game result-user-id stocks-with-tick-data)))
          blocking-transact (fn [entities]
                              (println "Sanity /" (persistence.datomic/transact-entities! conn entities))
                              entities)
          mix-chan          (chan)]

      ;; A. transact-entities
      (pipeline-blocking concurrent
                         mix-chan
                         (map blocking-transact)
                         input-chan)

      ;; B. controls to pause , resume
      (let [output-chan stock-stream-channel
            mixer       (core.async/mix output-chan)]

        (core.async/admix mixer mix-chan))

      (core.async/<!! output-chan)
      (core.async/<!! output-chan)))

  ;; (toggle mixer { in-channel-one { :pause true } })


  ;; C
  (-> game-control :control-channel (>!! :exit))


  ;; D
  (run! (fn [{:keys [stock-stream-channel control-channel]}]

          (println [stock-stream-channel control-channel])
          (>!! control-channel :exit)
          (close! stock-stream-channel)
          (close! control-channel))
        (-> repl.state/system :game/games deref vals)))

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


  ;; B.i
  (let [{:keys [game stocks-with-tick-data]}          game-control
        input-seq                                     (games/stocks->stock-sequences conn game result-user-id stocks-with-tick-data)
        {:keys [mixer mix-chan stock-stream-channel]} (games/chain-stock-sequence-controls! conn game-control input-seq)]

    (def mixer mixer)
    (def mix-chan mix-chan)

    #_(core.async/go-loop []
      (Thread/sleep 1000)
      (println (core.async/<! stock-stream-channel))
      (recur)))


  (core.async/toggle mixer { mix-chan { :pause true } })
  (core.async/toggle mixer { mix-chan { :pause false} })


  ;; C
  (-> game-control :control-channel (>!! :exit)))

(comment


  ;; >> <<
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


  ;; >> <<
  (let [final-chan                               (core.async/chan)
        game-loop-fn                              (fn [a]
                                                    (println "C. game-loop-fn /" #_a)
                                                    ;; (core.async/>!! final-chan a)
                                                    )
        {{:keys [control-channel
                 mixer
                 pause-chan
                 input-chan
                 output-chan] :as channel-controls}
         :channel-controls} (games/start-game! conn result-user-id game-control game-loop-fn)]

    ;; A
    (def control-channel control-channel)
    (def mixer mixer)
    (def input-chan input-chan)

    (def final-chan final-chan)
    (def output-chan output-chan)
    (def channel-controls channel-controls)

    ;; B
    #_(core.async/go-loop []
        (let [[v ch] (core.async/alts! [(core.async/timeout tick-sleep-ms)
                                        final-chan])]

          (println (format "TEST go-loop / value / %s" v))
          (when-not (nil? v)
            (recur))))

    ;; C
    ;; (core.async/>!! control-channel :exit)
    )

  (games/control-streams! channel-controls :pause)
  (games/control-streams! channel-controls :resume)
  (games/control-streams! channel-controls :exit)


  (core.async/toggle mixer { input-chan { :pause true } })
  (core.async/toggle mixer { input-chan { :pause false } })

  ;; (core.async/>!! (-> game-control :control-channel) :exit)
  (core.async/>!! control-channel :exit)
  (core.async/<!! output-chan)
  (core.async/<!! final-chan)


  ;; >> <<
  (pprint (-> integrant.repl.state/system :games/games))

  )
