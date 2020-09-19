(ns beatthemarket.state.subscriptions
  (:require [integrant.repl.state :as repl.state]
            [integrant.core :as ig]
            [datomic.client.api :as d]
            [beatthemarket.util :as util]))


(defn margin-trading? [conn user-db-id]

  :user/payments
  :payment/product-id
  :payment.applied/applied
  :payment.applied/expired

  (let [subscription-set (->> (select-keys integrant.repl.state/config
                                           [:payment.provider/apple
                                            :payment.provider/google
                                            :payment.provider/stripe])
                              vals
                              (map :subscriptions)
                              (map :margin_trading_1month))]

    (-> (d/q '[:find (pull ?p [*])
               :in $ ?u [?product-ids ...]
               :where
               [?u :user/payments ?p]
               [?p :payment/product-id ?product-ids]
               [?p :payment.applied/applied]
               [(missing? $ ?p :payment.applied/expired)]]
             (d/db conn) user-db-id subscription-set)
        util/exists?)))



(comment

  (def one (util/ppi
             (select-keys integrant.repl.state/config
                          [:payment.provider/apple
                           :payment.provider/google
                           :payment.provider/stripe])))

  (keys one)

  (->> (vals one)
       (map :products)
       ;; (group-by :margin_trading_1month)
       util/ppi)

  (->> (vals one)
       (map :subscriptions)
       ;; (group-by :margin_trading_1month)
       (map :margin_trading_1month)
       util/ppi)


  (->> (select-keys integrant.repl.state/config
                    [:payment.provider/apple
                     :payment.provider/google
                     :payment.provider/stripe])
       vals
       (map :subscriptions)
       (map :margin_trading_1month)
       util/ppi)

  )
