(ns beatthemarket.integration.payments.stripe
  (:require [clojure.data.json :as json]
            [integrant.core :as ig]
            [magnet.payments.core :as payments.core]
            [magnet.payments.stripe.core :as core]
            [magnet.payments.stripe :as stripe]
            [magnet.payments.stripe.customer :as customer]

            [beatthemarket.integration.payments.stripe.persistence :as stripe.persistence]
            [beatthemarket.util :as util]))


;; [ok] Binding for Stripe productId => local productId

;; Conditionally create a Stripe customer

;; Set the payment method as the default payment method for the subscription invoices

;; Create subscription

;; Enable features, based on purchases ..products

;; TODO GQL Subscription "Payments"
;; payment.success payment.fail


(defmethod ig/init-key :payments/stripe [_ {opts :service :as config}]
  (assoc config :client (ig/init-key :magnet.payments/stripe opts)))

#_(defn local-customer-exists? [conn customer-id]
    ((comp not empty?) (util/ppi (stripe.persistence/customer-by-id conn customer-id))))

#_(defn stripe-customer-exists? [client customer-id]
    (->> (payments.core/get-all-customers client {})
         :customers
         (map :id)
         util/ppi
         (filter #(= customer-id %))
         util/ppi
         (util/exists?)))

#_(defn local-customer [conn customer-id]
    (util/ppi (ffirst (stripe.persistence/customer-by-id conn customer-id))))

(defn stripe-customer-by-id [client id]
  (->> (payments.core/get-all-customers client {})
       :customers
       (filter #(= id (:id %)))
       first
       util/ppi))

(defn stripe-customers-by-email [client email]
  (-> (payments.core/get-all-customers client {})
      (update :customers (fn [a] (filter #(= email (:email %)) a)))))

(defn conditionally-create-customer! [client email]

  (let [{customers :customers :as result} (stripe-customers-by-email client email)]
    (if (not (empty? customers))

      result

      (let [create-customer-body {:email email}]

        (->> (payments.core/create-customer client create-customer-body)
             :customer
             list
             (hash-map :customers))))))

(defn delete-customer! [client customer-id]
  (payments.core/delete-customer client customer-id))

(defn delete-customers-by-email! [client email]
  (->> (stripe-customers-by-email client email)
       :customers
       (map #(delete-customer! client (:id %)))))

(defn verify-payment-workflow [conn
                               {client :client :as component}
                               {{customer-id :customerId} :token :as args}]

  ;; [ok] List customer
  ;; https://stripe.com/docs/api/customers/list

  ;; (util/ppi [component args])



  ;; (util/pprint+identity component)


  ;; Conditionally create a Stripe customer

  ;; X. Create a customer
  #_(def customer
      (let [create-customer-body {:email "swashing@gmail.com"}]
        (payments.core/create-customer stripe-client create-customer-body)))

  )

(comment


  (pprint integrant.repl.state/config)
  (pprint integrant.repl.state/system)


  (require '[clj-http.client :as client]
           '[magnet.payments.core :as payments.core])


  ;; Product (Stripe)
  ;; Prices (Stripe) How much and how often
  ;;   ! $5 buys margin trading (subscription)
  ;;   ! $1 subscription buys more balance (subscription * 4)
  ;;   ! $1 Refill Balance (purchase)
  ;; Customer (Stripe) => User
  ;; Subscriptions (Stripe) - When to provision User access to product


  ;; Testing
  ;; https://stripe.com/docs/testing


  ;; tok_visa

  (def stripe-client (-> integrant.repl.state/system
                         :payments/stripe
                         :client))


  ;; X. Create a customer
  (def customer
    (let [create-customer-body {:email "swashing@gmail.com"}]
      (util/ppi (payments.core/create-customer stripe-client create-customer-body))))


  ;; TODO Overview
  ;; https://www.youtube.com/playlist?list=PLz-qdc-PbYk7m063n00p-USU9dIBKAwJx

  ;; TODO
  ;; Make sure customer has a default payment method

  ;; TODO
  ;; X. Create payment method from Stripe token / card information (customer, payment method, and price IDs)
  ;; https://stripe.com/docs/billing/subscriptions/fixed-price#collect-payment

  ;; createSubscription({
  ;;   customerId: customerId,
  ;;   paymentMethodId: result.paymentMethod.id,
  ;;   priceId: priceId,
  ;; });

  ;; X. Create a subscription
  (def margin_trading_1month {:amount (* 5 100)
                              :currency "usd"
                              :interval "month"
                              :product {:name "Basic"
                                        :id "prod_BVG7ieHtkzpfZa"}})

  (def margin-trading-plan (payments.core/create-plan stripe-client margin_trading_1month))


  (payments.core/get-all-plans stripe-client {})
  (payments.core/get-all-customers stripe-client {})


  (def subscription
    (let [test-subscription-data {:customer "cus_Hz9Ms08zUybaTM"
                                  :items {"0" {:plan "basic"}}}]
      (payments.core/create-subscription stripe-client test-subscription-data)))


  ;; TODO
  ;; ! No :trial_period_days
  ;; ? Setup webhook for payment success / failure
  )
