(ns beatthemarket.bookkeeping.persistence
  (:require [beatthemarket.persistence.core :as persistence.core]))


(defn cash-account-by-user

  ([conn user-id]
   (cash-account-by-user (persistence.core/pull-entity conn user-id)))

  ([user-pulled]
   (->> user-pulled
        :user/accounts
        (filter #(= "Cash" (:bookkeeping.account/name %)))
        first)))

(defn equity-account-by-user

  ([conn user-id]
   (equity-account-by-user (persistence.core/pull-entity conn user-id)))

  ([user-pulled]
   (->> user-pulled
        :user/accounts
        (filter #(= "Equity" (:bookkeeping.account/name %)))
        first)))
