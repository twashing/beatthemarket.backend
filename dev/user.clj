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

(defn calculate-remaining-time [now end]
  (let [interval (t/interval now end)
        remaining-in-minutes (t/in-minutes interval)
        remaining-in-seconds (rem (t/in-seconds interval) 60)]
    (format "%s:%s" remaining-in-minutes remaining-in-seconds)))

(defn stream-game! [control-channel pause-atom tick-sleep-atom level-timer-atom]

  (let [start (t/now)]

    (go-loop [now start
              end (t/plus start (t/minutes @level-timer-atom))]

      (let [remaining (calculate-remaining-time now end)
            [v ch] (core.async/alts! [(core.async/timeout @tick-sleep-atom)
                                      control-channel])]
        (match [@pause-atom v]

               [true :exit] (println (format "< Paused > %s / Exiting..." remaining))
               [true _] (let [new-end (t/plus end (t/seconds 1))
                              remaining (calculate-remaining-time now new-end)]
                          (println (format "< Paused > %s" remaining))
                          (recur (t/now) new-end))

               [_ :exit] (println (format "Running %s / Exiting" remaining))
               [_ _] (do
                       (println (format "Running %s" remaining))
                       (recur  (t/now) end))
               )
        ))))


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
    (def paused? (atom false))
    (def tick-sleep-atom (atom 1000))
    (def level-timer-atom (atom 5)))

  ;; //
  (stream-game! control-channel paused? tick-sleep-atom level-timer-atom)


  (reset! paused? true)
  (reset! paused? false)
  (core.async/>!! control-channel :exit)

  :win :lose :timeout :exit
  :next-tick

  (def start (t/now))
  (def end (t/plus start (t/minutes 5)))
  ;; now
  ;; remaining (t/in-seconds (t/interval 'now end))

  )
