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

;; [ok] Conditionally create a Stripe customer

;; Set the payment method as the default payment method for the subscription invoices

;; Create subscription

;; Enable features, based on purchases ..products

;; TODO GQL Subscription "Payments"
;; payment.success payment.fail


(defmethod ig/init-key :payments/stripe [_ {opts :service :as config}]
  (assoc config :client (ig/init-key :magnet.payments/stripe opts)))

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

;; List<Object> items = new ArrayList<>();
;; Map<String, Object> item1 = new HashMap<>();
;; item1.put(
;;           "price",
;;           "price_0HROINu4V08wojXst9HsC6Yw"
;;           );
;; items.add(item1);
;; Map<String, Object> params = new HashMap<>();
;; params.put("customer", "cus_Hz9Ms08zUybaTM");
;; params.put("items", items);
;;
;; Subscription subscription =
;; Subscription.create(params);

#_(defn get-test-subscription-data []
  (let [payments-adapter (ig/init-key :magnet.payments/stripe test-config)
        plan-id (-> (core/create-plan payments-adapter test-plan-data) :plan :id)]
    {:customer (->> {:description "customer for someone@example.com"}
                    (core/create-customer payments-adapter)
                    :customer
                    :id)
     :items {"0" {:plan plan-id}}
     :trial_period_days 30}))

(comment

  (def stripe-client (-> integrant.repl.state/system
                         :payments/stripe
                         :client))

  (def input (-> "example-payload-stripe-subscription-expired-card-error.json"
                 resource
                 slurp
                 (json/read-str :key-fn keyword)))

  (let [{customer-id :customerId
         payment-method-id :paymentMethodId
         price-id :priceId} input

        payload {:customer customer-id
                 :default_payment_method payment-method-id
                 :items {"0" {:price price-id}}}]

    ;; (util/ppi [stripe-client payload])
    (util/ppi (payments.core/create-subscription stripe-client payload))))

;; A
{:db/ident       :payment/id
 :db/valueType   :db.type/uuid
 :db/cardinality :db.cardinality/one
 :db/unique      :db.unique/value}

{:db/ident       :payment/product-id
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique      :db.unique/value}

{:db/ident       :payment/provider-type
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one}

{:db/ident       :payment/provider
 :db/valueType   :db.type/ref
 :db/cardinality :db.cardinality/one}

;; B
{:db/ident       :payment.stripe/id
 :db/valueType   :db.type/uuid
 :db/cardinality :db.cardinality/one
 :db/unique      :db.unique/value}

{:db/ident       :payment.stripe/customer-id
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique      :db.unique/value}

{:db/ident       :payment.stripe/payment-method-id
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique      :db.unique/value}

{:db/ident       :payment.stripe/price-id
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :db/unique      :db.unique/value}

(defn verify-product-workflow [conn
                               {client :client :as component}
                               {{customer-id :customer
                                 source :source
                                 :as payload} :token}]

  ;; {"customer": "cus_9JzjeBVXZWH0e5",
  ;;  "amount": 100,
  ;;  "currency": "usd"}

  (util/ppi payload)

  ;; TODO check if customer already has source
  (util/ppi (payments.core/update-customer client customer-id {:source source}))

  ;; TODO transform result to client
  (let [{id :id} (util/ppi (payments.core/create-charge client (dissoc payload :source)))]

    []))

(defn conditionally-create-subscription [stripe-client payload]

  ;; TODO Check if subscription exists (db, stripe)
  (let [{success? :success? :as subscription} (util/ppi (payments.core/create-subscription stripe-client payload))]

    (if-not success?
      (throw (Exception. (-> subscription :error-details :error :message util/ppi)))

      ;; TODO transact to DB
      subscription)))


{:success? true
 :charge
 {:calculated_statement_descriptor "INTERRUPTSOFTWARE.COM"
  :description nil
  :disputed false
  :amount 100
  :payment_method "card_0HS5PNu4V08wojXsBCmtXWsi"
  :application_fee nil
  :source_transfer nil
  :receipt_url
  "https://pay.stripe.com/receipts/acct_12Z6u4V08wojXsSOSBOg/ch_0HSDjBu4V08wojXsmkrqfq68/rcpt_I2IJgqarEIIrSuTvGEgaXkRhyjgdrq2"
  :application_fee_amount nil
  :application nil
  :failure_message nil
  :payment_intent nil
  :captured true
  :statement_descriptor_suffix nil
  :dispute nil
  :payment_method_details
  {:card
   {:exp_year 2021
    :installments nil
    :wallet nil
    :checks
    {:address_line1_check nil
     :address_postal_code_check nil
     :cvc_check nil}
    :last4 "4242"
    :brand "visa"
    :funding "credit"
    :three_d_secure nil
    :network "visa"
    :exp_month 9
    :country "US"
    :fingerprint "L7v8KceRseAFi1Ve"}
   :type "card"}
  :receipt_email nil
  :on_behalf_of nil
  :created 1600313049
  :outcome
  {:network_status "approved_by_network"
   :reason nil
   :risk_level "normal"
   :risk_score 57
   :seller_message "Payment complete."
   :type "authorized"}
  :receipt_number nil
  :source
  {:address_line1_check nil
   :address_state nil
   :dynamic_last4 nil
   :address_zip_check nil
   :tokenization_method nil
   :exp_year 2021
   :name nil
   :cvc_check nil
   :last4 "4242"
   :brand "Visa"
   :customer "cus_9JzjeBVXZWH0e5"
   :address_country nil
   :funding "credit"
   :address_line2 nil
   :id "card_0HS5PNu4V08wojXsBCmtXWsi"
   :address_zip nil
   :address_line1 nil
   :exp_month 9
   :country "US"
   :metadata {}
   :object "card"
   :fingerprint "L7v8KceRseAFi1Ve"
   :address_city nil}
  :customer "cus_9JzjeBVXZWH0e5"
  :balance_transaction "txn_0HSDjBu4V08wojXsui8n5Sdy"
  :transfer_group nil
  :invoice nil
  :currency "usd"
  :refunded false
  :review nil
  :status "succeeded"
  :id "ch_0HSDjBu4V08wojXsmkrqfq68"
  :paid true
  :failure_code nil
  :order nil
  :transfer_data nil
  :livemode false
  :shipping nil
  :fraud_details {}
  :billing_details
  {:address
   {:city nil
    :country nil
    :line1 nil
    :line2 nil
    :postal_code nil
    :state nil}
   :email nil
   :name nil
   :phone nil}
  :metadata {}
  :destination nil
  :object "charge"
  :statement_descriptor nil
  :refunds
  {:object "list"
   :data []
   :has_more false
   :total_count 0
   :url "/v1/charges/ch_0HSDjBu4V08wojXsmkrqfq68/refunds"}
  :amount_refunded 0}}

(defn verify-subscription-workflow [conn
                                    {client :client :as component}
                                    {product-id :productId
                                     {customer-id :customerId
                                      payment-method-id :paymentMethodId
                                      price-id :priceId} :token :as args}]

  ;; Attach payment method to client
  (println "A /")

  ;; TODO check if payment method attached
  (util/ppi (payments.core/attach-payment-method client payment-method-id customer-id))

  ;; Purchase Product || Create a subscription
  (let [payload {:customer customer-id
                 :default_payment_method payment-method-id
                 :items {"0" {:price price-id}}}]

    (println "B /")
    ;; (println (format "Valid product %s" (valid-stripe-product-id? product-id)))
    ;; (println (format "Valid subscription %s" (valid-stripe-subscription-id? product-id)))
    (conditionally-create-subscription client payload)))

;; {"customerId": "cus_9JzjeBVXZWH0e5",
;;  "paymentMethodId": "card_9Jzj8NB5gDrYce",
;;  "priceId": "price_0HROJnu4V08wojXsIaqX0FEP"}

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
