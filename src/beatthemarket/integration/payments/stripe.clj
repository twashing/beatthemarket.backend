(ns beatthemarket.integration.payments.stripe
  (:require [clojure.data.json :as json]
            [integrant.core :as ig]
            [integrant.repl.state :as repl.state]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [magnet.payments.core :as payments.core]
            [magnet.payments.stripe.core :as core]
            [magnet.payments.stripe :as stripe]
            [magnet.payments.stripe.customer :as customer]

            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.integration.payments.stripe.persistence :as stripe.persistence]
            [beatthemarket.integration.payments.persistence :as payments.persistence]
            [beatthemarket.integration.payments.core :as integration.payments.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.util :refer [ppi] :as util]
            [datomic.client.api :as d])
  (:import [java.util UUID]))


;; [ok] Binding for Stripe productId => local productId

;; [ok] Conditionally create a Stripe customer

;; [~] Set the payment method as the default payment method for the subscription invoices
;; Adding payment method if not there

;; [ok] Create subscription

;; TODO
;; Enable features, based on purchases ..products

;; mark payment as consumed
;; check if payment has been applied / consumed... at beginning of game

;; TODO
;; pro-rate the monthly charge for the first month

;; TODO GQL Subscription "Payments"
;; payment.success payment.fail

(defmethod ig/init-key :payment.provider/stripe [_ {opts :service :as config}]
  (assoc config :client (ig/init-key :magnet.payments/stripe opts)))


;; CUSTOMER
(defn stripe-customer-by-id [client id]
  (->> (payments.core/get-all-customers client {})
       :customers
       (filter #(= id (:id %)))
       first))

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


;; PAYMENT METHOD
(defn conditionally-attach-payment-method [client payment-method-id customer-id]

  (let [payment-methods (->> (payments.core/get-customer-payment-methods client customer-id "card" {})
                             :payment-methods
                             (filter #(= payment-method-id (:id %))))]

    (if (empty? payment-methods)

      (payments.core/attach-payment-method client payment-method-id customer-id)

      (first payment-methods))))


;; PRODUCT
(defn ->payment [conn product-id payment-type {payment-id :id}]

  (let [payment-stripe (persistence.core/bind-temporary-id
                         {:payment.stripe/id payment-id
                          :payment.stripe/type payment-type})]

    (persistence.core/bind-temporary-id
      {:payment/id (UUID/randomUUID)
       :payment/product-id product-id
       :payment/provider-type :payment.provider/stripe
       :payment/provider payment-stripe})))

(defn verify-product-workflow [conn
                               client-id
                               email
                               {client :client :as component}
                               {product-id :productId
                                provider :provider
                                {customer-id :customer
                                 source :source
                                 :as payload} :token :as args}]

  ;; (ppi [conn client-id email client args])

  ;; TODO
  ;; check if customer already has source
  ;; check for errors
  (payments.core/update-customer client customer-id {:source source})

  (let [payment-type :payment.provider.stripe/charge

        {game-entity :game
         payment-entity :payment}
        (->> (payments.core/create-charge client (dissoc payload :source)) ;; TODO check for errors
             :charge
             (->payment conn product-id payment-type)
             (integration.payments.core/mark-payment-applied-conditionally-on-running-game conn email client-id))

        user-entity (-> (iam.persistence/user-by-email conn email '[:db/id])
                        ffirst
                        (assoc :user/payments payment-entity))]

    (persistence.datomic/transact-entities! conn user-entity)
    (integration.payments.core/apply-payment-conditionally-on-running-game conn email payment-entity game-entity)
    (payments.persistence/user-payments conn email)))


;; SUBSCRIPTION
(defn conditionally-create-subscription [stripe-client payload]

  ;; TODO Check if subscription exists (db, stripe)
  (let [{success? :success? :as subscription} (payments.core/create-subscription stripe-client payload)]

    (if-not success?

      (throw (Exception. (-> subscription :error-details :error :message)))

      subscription)))

(defn verify-subscription-workflow [conn
                                    client-id
                                    email
                                    {client :client :as component}
                                    {product-id :productId
                                     provider :provider
                                     {customer-id :customerId
                                      payment-method-id :paymentMethodId
                                      price-id :priceId} :token :as args}]

  (let [{success? :success? :as payment-method}
        (conditionally-attach-payment-method client payment-method-id customer-id)]

    (if-not success?

      (throw (Exception. (-> payment-method :error-details :error :message)))

      (let [{{payment-method-id :id} :payment-method} payment-method
            payload {:customer customer-id
                     :default_payment_method payment-method-id
                     :items {"0" {:price price-id}}}

            payment-type :payment.provider.stripe/subscription

            {game-entity :game
             payment-entity :payment}
            (->> (conditionally-create-subscription client payload)
                 :subscription
                 (->payment conn product-id payment-type)
                 (integration.payments.core/mark-payment-applied-conditionally-on-running-game conn email client-id))

            user-entity (-> (iam.persistence/user-by-email conn email '[:db/id])
                            ffirst
                            (assoc :user/payments payment-entity))]

        (persistence.datomic/transact-entities! conn user-entity)
        (integration.payments.core/apply-payment-conditionally-on-running-game conn email payment-entity game-entity)
        (payments.persistence/user-payments conn email)))))

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


  (def stripe-client (-> integrant.repl.state/system
                         :payment.provider/stripe
                         :client))


  ;; X. Create a customer
  (def customer
    (let [create-customer-body {:email "foo@bar.com"}]
      (ppi (payments.core/create-customer stripe-client create-customer-body))))

  (def customer-id (-> customer :customer :id))
  ;; (def payment-method-id "card_0HRqOOu4V08wojXsHiMnmpRv")
  (def payment-method-id "pm_card_visa")

  (-> (payments.core/get-customer stripe-client customer-id)
      :customer
      ppi)

  (->> (payments.core/get-customer-payment-methods stripe-client customer-id "card" {})
       :payment-methods
       (filter #(= "pm_0HSR8iu4V08wojXsqTE17D9g" #_payment-method-id (:id %)))
       ppi)

  (def result (payments.core/attach-payment-method stripe-client payment-method-id customer-id))




  (defprotocol Customers
    (create-customer [this customer])
    (get-customer [this customer-id])
    (get-all-customers [this opt-args])
    (update-customer [this customer-id customer])
    (delete-customer [this customer-id]))

  (defprotocol PaymentMethod
    (get-payment-method [this payment-method-id])
    (get-customer-payment-methods [this customer-id payment-method-type opt-args])
    (attach-payment-method [this payment-method-id customer-id])
    (detach-payment-method [this payment-method-id]))


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
