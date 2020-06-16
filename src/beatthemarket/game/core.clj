(ns beatthemarket.game.core
  (:require [integrant.core :as ig]))


(defmethod ig/init-key :game/games [_ _]
  (atom {}))


(comment


  (require '[integrant.repl.state])
  (:game/games integrant.repl.state/system)


  (let [runnable ^Runnable (fn []

                             ;; game (or market)
                             ;; user
                             ;; stocks -> data-sequences

                             (loop [
                                    ;; new tick (on timer)
                                    ;; calculate profit | loss
                                    ;;   level completed
                                    ;;   game won (ended)
                                    ;;   game lost (ended)
                                    ;; signals
                                    ;;   game suspended
                                    ;;   game resumed

                                    a 1]

                               (recur (inc a)))
                             )]


    (.start (Thread. runnable "stream-new-game-thread"))))
