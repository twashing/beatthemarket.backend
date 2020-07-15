(ns user
  (:require [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [integrant.core :as ig]
            [clojure.edn :as edn]
            [clojure.data.json :as json]
            [clojure.java.io :refer [resource]]
            [beatthemarket.state.core :as state.core]
            [beatthemarket.migration.core :as migration.core]
            [beatthemarket.util :as util]
            [clojure.core.async :as core.async]))


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

  :exit :win :lose :timeout

  ;; TODO stream
  ;; :game-event
  ;; :timer-event
  )

(defn stream-game! [control-channel input-channel pause-atom tick-sleep-atom level-timer-atom]

  (let [start (t/now)]

    (go-loop [now start
              end (t/plus start (t/seconds @level-timer-atom))]

      (let [remaining (calculate-remaining-time now end)
            [controlv ch] (core.async/alts! [(core.async/timeout @tick-sleep-atom)
                                      control-channel])]

        ;; pause
        ;; control
        ;; timer
        (match [@pause-atom controlv (time-expired? remaining)]

               [true :exit _] (do
                                (handle-control-event controlv remaining)
                                (println (format "< Paused > %s / Exiting..." (format-remaining-time remaining))))
               [true _ _] (let [new-end (t/plus end (t/seconds 1))
                                remaining (calculate-remaining-time now new-end)]
                            (println (format "< Paused > %s" (format-remaining-time remaining)))
                            (recur (t/now) new-end))

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
                             (recur  (t/now) end))
               [_ _ true] (do (handle-control-event :timeout remaining)
                              (println (format "Running %s / TIME'S UP!!" (format-remaining-time remaining)))))))))


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
  (stream-game! control-channel input-channel paused? tick-sleep-atom level-timer-atom)


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


  ;; TODO Maybe later
  ;; [x] Bump timer (up or down)
  )
