(ns beatthemarket.integration.payments.stripe
  (:require [integrant.core :as ig]
            [magnet.payments.stripe.core :as core]
            [magnet.payments.stripe :as stripe]
            [beatthemarket.util :as util]))


;; Customer
;; Subscription


(comment

  (require [integrant.repl])

  (pprint integrant.repl.state/config)
  (pprint integrant.repl.state/system)


  (-> integrant.repl.state/system
      :magnet.payments/stripe
      ;; util/pprint+identity
      )


  ;; Product (Stripe)
  ;; Prices (Stripe) How much and how often
  ;;   ! $5 buys margin trading (subscription)
  ;;   ! $1 subscription buys more balance (subscription * 4)
  ;;   ! $1 Refill Balance (purchase)
  ;; Customer (Stripe) => User
  ;; Subscriptions (Stripe) - When to provision User access to product

  )
