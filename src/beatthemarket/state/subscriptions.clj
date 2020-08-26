(ns beatthemarket.state.subscriptions
  (:require [integrant.repl.state :as repl.state]
            [integrant.core :as ig]
            [beatthemarket.util :as util]))


(defn margin-trading? []

  (->> repl.state/system
       :subscriptions/subscriptions
       (some #{:margin-trading})
       nil?
       not))

(defmethod ig/init-key :subscriptions/subscriptions [_ {}]

  #_#{:margin-trading
    ;; :additional-balance-100k
    ;; :additional-balance-200k
    ;; :additional-balance-300k
    :additional-balance-400k
    :refill-balance}
  #{}
  )
