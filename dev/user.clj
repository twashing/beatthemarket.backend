(ns user
  (:require [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.core :as ig]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :refer [resource]]
            [clojure.core.async :as core.async
              :refer [go-loop chan close! timeout alts! >! <! >!!]]
            [clj-time.core :as t]
            [clojure.core.match :refer [match]]
            [beatthemarket.state.core :as state.core]
            [beatthemarket.migration.core :as migration.core]
            [beatthemarket.util :as util]))


(comment ;; Convenience fns

  ;; Start with
  (prep)
  (init)

  ;; Or just
  (go)

  ;; These functions are also available for control
  (halt)
  (reset-all)


  ;; Catach all
  (do
    (state.core/set-prep :development)
    (state.core/init-components)
    (migration.core/run-migrations))


  (pprint integrant.repl.state/config)
  (pprint integrant.repl.state/system)


  ;; Individual
  (prep)
  (ig/init integrant.repl.state/config [:service/service])
  (def datomic-client (ig/init integrant.repl.state/config [:persistence/datomic]))


  (require '[beatthemarket.dir]
           '[clojure.tools.namespace.dir]
           '[clojure.tools.namespace.track :as track]
           '[clojure.tools.namespace.file :as file])

  (#'clojure.tools.namespace.dir/dirs-on-classpath)

  (-> (#'clojure.tools.namespace.dir/dirs-on-classpath)
      (#'clojure.tools.namespace.dir/find-files))

  (->> (#'clojure.tools.namespace.dir/dirs-on-classpath)
       (#'clojure.tools.namespace.dir/find-files)
       (#'clojure.tools.namespace.dir/deleted-files (track/tracker)))

  (pprint (#'beatthemarket.dir/scan-all (track/tracker))))

(comment

  (halt)

  (do
    (state.core/set-prep :development)
    (state.core/init-components))

  (->> "schema.lacinia.edn"
       resource slurp edn/read-string
       json/write-str
       (spit "schema.lacinia.json")))


;; RUN
(def control-channel (chan))
(def input-channel (core.async/to-chan (range)))
(def paused? (atom false))
(def tick-sleep-atom (atom 1000))
(def level-timer-atom (atom 30 #_(* 5 60)))

(defn format-remaining-time [{:keys [remaining-in-minutes remaining-in-seconds]}]
  (format "%s:%s" remaining-in-minutes remaining-in-seconds))

(defn time-expired? [{:keys [remaining-in-minutes remaining-in-seconds]}]
  (and (= 0 remaining-in-minutes) (< remaining-in-seconds 1)))

(defn calculate-remaining-time [now end]
  (let [interval (t/interval now end)]
    {:interval interval
     :remaining-in-minutes (t/in-minutes interval)
     :remaining-in-seconds (rem (t/in-seconds interval) 60)}))

(defn pause-game? [pause?]

  ;; TODO stream a :game-event
  (reset! paused? pause?))

(defn handle-control-event [control remaining]

  ;; TODO stream
  ;; :game-event
  ;; :timer-event

  ;; TODO handle events
  ;; :exit :win :lose :timeout
  )

;; TODO Subscription structures
#_{:stockTicks {:stockTickId :stockTickTime :stockTickClose :stockId}
   :portfolioUpdates [:ProfitLoss :AccountBalance]
   :gameEvents [:ControlMessage [:pause :resume :exit]
                :LevelTimer {:level :timeRemaining}
                :LevelStatus [:win :lose]]}

(defn run-game! [control-channel input-channel pause-atom tick-sleep-atom level-timer-atom]

  (let [start (t/now)]

    (go-loop [now start
              end (t/plus start (t/seconds @level-timer-atom))]

      (let [remaining (calculate-remaining-time now end)
            [controlv ch] (core.async/alts! [(core.async/timeout @tick-sleep-atom)
                                             control-channel])

            ;; pause
            ;; control
            ;; timer
            [nowA endA] (match [@pause-atom controlv (time-expired? remaining)]

                               [true :exit _] (do
                                                (handle-control-event controlv remaining)
                                                (println (format "< Paused > %s / Exiting..." (format-remaining-time remaining))))
                               [true _ _] (let [new-end (t/plus end (t/seconds 1))
                                                remaining (calculate-remaining-time now new-end)]
                                            (println (format "< Paused > %s" (format-remaining-time remaining)))
                                            [(t/now) new-end])

                               [_ :exit _] (do (handle-control-event controlv remaining)
                                               (println (format "Running %s / Exiting" (format-remaining-time remaining))))
                               [_ :win _] (do (handle-control-event controlv remaining)
                                              (println (format "Win %s" (format-remaining-time remaining))))
                               [_ :lose _] (do (handle-control-event controlv remaining)
                                               (println (format "Lose %s" (format-remaining-time remaining))))
                               [_ _ false] (let [v (<! input-channel)]

                                             ;; TODO stream
                                             ;; :stock-tick
                                             ;; :timer-event

                                             (println (format "Running %s / %s" (format-remaining-time remaining) v))
                                             [(t/now) end])
                               [_ _ true] (do (handle-control-event :timeout remaining)
                                              (println (format "Running %s / TIME'S UP!!" (format-remaining-time remaining)))))]

        (when (and nowA endA)
          (recur nowA endA))))))

(comment

  (require '[clojure.core.async :as core.async
             :refer [go-loop chan close! timeout alts! >! <! >!!]]
           '[clojure.core.match :refer [match]]
           '[clj-time.core :as t])


  ;; // A
  (def level-message
    {:level 1

     ;; :win :lose :timeout
     :status :win
     :profitLoss 10000.0})

  (def control-message

    ;; :pause :resume :exit
    :exit)


  #_(let [a (timeout 999)
        b (timeout 998)
        [v ch] (core.async/alts!! [a b])]

    (println [v ch])
    (cond
      (= ch b) "B"
      (= ch a) "A"
      :default "Unknown"))


  ;; // B
  (do
    (def control-channel (chan))
    (def input-channel (core.async/to-chan (range)))

    (def paused? (atom false))
    (def tick-sleep-atom (atom 1000))
    (def level-timer-atom (atom 30 #_(* 5 60))))


  ;; // C
  (run-game! control-channel input-channel paused? tick-sleep-atom level-timer-atom)


  ;; :pause :resume
  (pause-game? true)
  (pause-game? false)

  ;; bump tick sleep (up or down)
  (reset! tick-sleep-atom 500)
  (reset! tick-sleep-atom 1000)
  (reset! tick-sleep-atom 1500)

  ;; :win :lose :exit
  (core.async/>!! control-channel :exit)
  (core.async/>!! control-channel :win)
  (core.async/>!! control-channel :lose)



  ;; NOTE
  :timeout :exit
  :win :lose :next-tick

  ;; Stream messages
  :timer :timeout [:pause :resume] :exit :win :lose
  )


;; CALCULATE

(comment


  (do
    (def stock-tick-mappingfn (map (fn [tick] (println (format ">> tick / %s" tick)) tick)))
    (def portfolio-update-mappingfn (map (fn [profit-loss] (println (format ">> portfolio-update / %s" profit-loss)) profit-loss)))
    ;; (def game-event-mappingfn (map (fn [game-event] (println "game-event / %s" game-event) game-event)))

    (def transact-tick-mappingfn (map (fn [a] (println (format "transact-tick-mappingfn %s" a))
                                 a) #_(partial beatthemarket.persistence.datomic/transact-entities! conn)))
    (def transact-profit-loss-mappingfn (map (fn [a] (println (format "transact-profit-loss-mappingfn %s" a))
                            a) #_(partial beatthemarket.persistence.datomic/transact-entities! conn)))

    (def profit-loss-mappingfn (map (fn [a] (println (format ">> profit-loss-mappingfn %s" a))
                               a) #_beatthemarket.game.persistence/track-profit-loss!))

    (def control-channel (chan)))

  (let [stream-buffer 40
        stock-tick-stream (core.async/chan stream-buffer)
        portfolio-update-stream (core.async/chan stream-buffer)
        game-event-stream (core.async/chan stream-buffer)

        profit-loss-buffer 1
        ticks-from (core.async/chan profit-loss-buffer)

        ;; NOTE stream tick
        ticks-to (core.async/chan
                   profit-loss-buffer #_(core.async/sliding-buffer profit-loss-buffer)
                   stock-tick-mappingfn)
        profit-loss-to (core.async/chan profit-loss-buffer)

        ;; NOTE stream PL
        profit-loss-transact-to (core.async/chan
                                  profit-loss-buffer #_(core.async/sliding-buffer profit-loss-buffer)
                                  portfolio-update-mappingfn)
        game-event-from (core.async/chan profit-loss-buffer)
        game-event-to (core.async/chan profit-loss-buffer)

        concurrent 1]


    ;; stock-tick-stream
    ;; portfolio-update-stream
    ;; game-event-stream


    ;; A
    ;; (< ticks)       transact-mappingfn    (< ticks)
    ;;                 profit-loss-mappingfn (< profit-loss)
    ;; (< profit-loss) transact-mappingfn    (< profit-loss)


    (core.async/onto-chan ticks-from (range 20))

    ;; P/L
    (core.async/pipeline-blocking concurrent ticks-to                transact-tick-mappingfn    ticks-from)
    (core.async/pipeline-blocking concurrent profit-loss-to          profit-loss-mappingfn ticks-to)
    (core.async/pipeline-blocking concurrent profit-loss-transact-to transact-profit-loss-mappingfn    profit-loss-to)

    (go-loop []
      (let [[v ch] (core.async/alts! [(core.async/timeout 1000)
                                      control-channel])]

        (when-not (= v :exit)
          (when-let [vv (core.async/<! profit-loss-to #_ticks-to)]
            (println "")
            ;; (println (format "LOOP / %s" (core.async/<! profit-loss-transact-to)))
            (println (format "LOOP / %s" vv))
            (recur))))))

  (core.async/>!! control-channel :exit)


  ;; B
  ;; (> gameEvent)   transact-mappingfn    (> gameEvent)


  (core.async/onto-chan ticks-to (range))
  (core.async/close! ticks-to)
  )
