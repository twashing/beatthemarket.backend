(ns beatthemarket.handler.http.graphql)


(defn resolve-hello
  [context args value]
  "Hello, Clojurians!")

(defn resolve-login
  [context args value]

  ;; TODO conditionally saves new user
  :user

  ;; TODO
  {:status 200}

  "login CALLED")

(defn stream-new-game
  [context args source-stream]

  ;; TODO play
  ;;   creates a new game

  ;;   creates a list of market stocks
  ;;   picks a default stock

  ;;   subscribes user to default stock
  ;;   pushes Portfolio positions + value to client
  ;;   streams the default stock to client
  [:game :level :user :book :stock :subscription]

  (let [{:keys [message]} args

        runnable ^Runnable (fn []
                             (source-stream {:message message})
                             (Thread/sleep 50)
                             (source-stream nil))]

    (.start (Thread. runnable "stream-ping-thread"))
    ;; Return a cleanup fn:
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
        runnable ^Runnable (fn []
                             (dotimes [i count]

                               ;; (println "Sanity check / stream-ping / " [i count])
                               (source-stream {:message (str message " #" (inc i))
                                               :timestamp (System/currentTimeMillis)})
                               (Thread/sleep 50))

                             (source-stream nil))]
    (.start (Thread. runnable "stream-ping-thread")))
  ;; Return a cleanup fn:
  #(swap! *ping-cleanups inc))
