(ns beatthemarket.integration.payments.core
  (:require [clojure.core.async :as core.async]
            [integrant.repl.state :as repl.state]
            [integrant.core :as ig]
            [datomic.client.api :as d]
            [clj-time.core :as t]
            [clj-time.coerce :as c]
            [com.rpl.specter :refer [select transform ALL MAP-VALS]]
            [beatthemarket.game.persistence :as game.persistence]
            [beatthemarket.game.games.state :as game.games.state]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.util :refer [ppi] :as util]))


(defmethod ig/init-key :payments/feature-registry [_ _]

  (let [extract-products-and-subscriptions (juxt :products :subscriptions)]

    (->> (select-keys repl.state/config
                      [:payment.provider/apple
                       :payment.provider/google
                       :payment.provider/stripe])
         vals
         (map extract-products-and-subscriptions)
         (map #(apply merge %))
         (transform [ALL MAP-VALS] #(list %))
         (apply merge-with into)
         (transform [MAP-VALS] set))))

(def subscriptions #{"margin_trading_1month"})
(def products #{"additional_100k"
                "additional_200k"
                "additional_300k"
                "additional_400k"
                "additional_5_minutes"})

(defn subscription-lookup [subscriptions]
  (clojure.set/map-invert subscriptions))

(defn product-lookup [products]
  (clojure.set/map-invert products))

(defn valid-apple-product-id? [product-id]

  (let [products (-> repl.state/config :payment.provider/apple :products)]

    (as-> (vals products) v
      (into #{} v)
      (some v #{product-id})
      (util/exists? v))))

(defn valid-apple-subscription-id? [subscription-id]

  (let [subscriptions (-> repl.state/config :payment.provider/apple :subscriptions)]

    (as-> (vals subscriptions) v
      (into #{} v)
      (some v #{subscription-id})
      (util/exists? v))))

(defn valid-google-product-id? [product-id]

  (let [products (-> repl.state/config :payment.provider/google :products)]

    (as-> (vals products) v
      (into #{} v)
      (some v #{product-id})
      (util/exists? v))))

(defn valid-google-subscription-id? [subscription-id]

  (let [subscriptions (-> repl.state/config :payment.provider/google :subscriptions)]

    (as-> (vals subscriptions) v
      (into #{} v)
      (some v #{subscription-id})
      (util/exists? v))))

(defn valid-stripe-product-id? [product-id]

  (let [products (-> repl.state/config :payment.provider/stripe :products)]

    (as-> (vals products) v
      (into #{} v)
      (some v #{product-id})
      (util/exists? v))))

(defn valid-stripe-subscription-id? [subscription-id]

  (let [subscriptions (-> repl.state/system :payment.provider/stripe :subscriptions)]

    (as-> (vals subscriptions) v
      (into #{} v)
      (some v #{subscription-id})
      (util/exists? v))))

(defn credit-cash-account [amount account]
  (update account :bookkeeping.account/balance #(+ % amount)))

(def feature->amount
  {:additional_balance_100k 100000.0
   :additional_100k         100000.0
   :additional_200k         200000.0
   :additional_300k         300000.0
   :additional_400k         400000.0})

(defmulti apply-feature (fn [feature conn email payment-entity game-entity] feature))

(defmethod apply-feature :margin_trading_1month [_ conn email
                                                 payment-entity
                                                 {game-id :game/id :as game-entity}]

  ;; NOTE subscriptions (margin trading) stored in DB
  :noop)

(defmethod apply-feature :additional_balance_100k [feature conn email
                                                   payment-entity
                                                   {game-id :game/id :as game-entity}]

  (->> (game.persistence/cash-account-for-user-game conn email game-id)
       ffirst
       (credit-cash-account (feature->amount feature))
       (persistence.datomic/transact-entities! conn)))

(defmethod apply-feature :additional_100k [feature conn email
                                           payment-entity
                                           {game-id :game/id :as game-entity}]

  (->> (game.persistence/cash-account-for-user-game conn email game-id)
       ffirst
       (credit-cash-account (feature->amount feature))
       (persistence.datomic/transact-entities! conn)))

(defmethod apply-feature :additional_200k [feature conn email
                                           payment-entity
                                           {game-id :game/id :as game-entity}]

  (->> (game.persistence/cash-account-for-user-game conn email game-id)
       ffirst
       (credit-cash-account (feature->amount feature))
       (persistence.datomic/transact-entities! conn)))

(defmethod apply-feature :additional_300k [feature conn email
                                           payment-entity
                                           {game-id :game/id :as game-entity}]

  (->> (game.persistence/cash-account-for-user-game conn email game-id)
       ffirst
       (credit-cash-account (feature->amount feature))
       (persistence.datomic/transact-entities! conn)))

(defmethod apply-feature :additional_400k [feature conn email
                                           payment-entity
                                           {game-id :game/id :as game-entity}]

  (->> (game.persistence/cash-account-for-user-game conn email game-id)
       ffirst
       (credit-cash-account (feature->amount feature))
       (persistence.datomic/transact-entities! conn)))

(defmethod apply-feature :additional_5_minutes [feature _ _ _ {game-id :game/id :as game-entity}]

  (let [additional-time 5
        level-timer (:level-timer (game.games.state/inmemory-game-by-id game-id))
        now (t/now)

        end (t/plus now (t/seconds @level-timer))
        end' (t/plus end (t/minutes additional-time))
        remaining-time (game.games.state/calculate-remaining-time now end')]

    ;; TODO Redo these ugly kludges
    ;; Done to make updating time work, either in a running game B, or an exited game A

    ;; A. Kludge
    (game.games.state/update-inmemory-game-timer! game-id (-> remaining-time :interval t/in-seconds)))

  ;; B. Kludge
  (let [control-channel (:control-channel (game.games.state/inmemory-game-by-id game-id))
        game-event-message {:event feature
                            :game-id game-id}]

    (core.async/go (core.async/>! control-channel game-event-message))))


(defn mark-payment-applied-conditionally-on-running-game [conn email client-id payment]

  (let [game-entity (game.persistence/running-game-for-user-device conn email client-id)]

    (if (util/exists? game-entity)

      (hash-map :game game-entity
                :payment (assoc payment
                                :payment.applied/game (select-keys game-entity [:db/id])
                                :payment.applied/applied (c/to-date (t/now))))

      (hash-map :payment payment))))

(defn apply-payment-conditionally-on-running-game [conn email payment-entity game-entity]

  ;; TODO
  ;; [ok] subscription - turn on margin trading
  ;; products
  ;;   [ok] increase Cash balance
  ;;   - notify Client through portfolioUpdate

  (when game-entity

    (let [{{subscriptions-apple :subscriptions
            products-apple :products} :payment.provider/apple

           {subscriptions-google :subscriptions
            products-google :products} :payment.provider/google

           {subscriptions-stripe :subscriptions
            products-stripe :products} :payment.provider/stripe} repl.state/config

          feature (get (merge (subscription-lookup subscriptions-apple)
                              (product-lookup products-apple)

                              (subscription-lookup subscriptions-google)
                              (product-lookup products-google)

                              (subscription-lookup subscriptions-stripe)
                              (product-lookup products-stripe))
                       (:payment/product-id payment-entity))]

      (apply-feature feature conn email payment-entity game-entity))))

(defn margin-trading? [conn user-db-id]

  (let [subscription-set (->> (select-keys repl.state/config
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

(defn payments-for-user

  ([conn user-db-id]
   (payments-for-user conn user-db-id '[*]))

  ([conn user-db-id expr]

   (map first
        (d/q '[:find (pull ?p pexpr)
               :in $ ?u pexpr
               :where
               [?u :user/payments ?p]]
             (d/db conn) user-db-id expr))))

(defn applied-payments-for-user [conn user-db-id]

  (map first
       (d/q '[:find (pull ?p [*])
              :in $ ?u
              :where
              [?u :user/payments ?p]
              [?p :payment.applied/applied]]
            (d/db conn) user-db-id)))

(defn unapplied-payments-for-user [conn user-db-id]

  (map first
       (d/q '[:find (pull ?p [*])
              :in $ ?u
              :where
              [?u :user/payments ?p]
              [(missing? $ ?p :payment.applied/applied)]]
            (d/db conn) user-db-id)))

(defn apply-unapplied-payments-for-user

  ([conn {user-db-id :db/id :as user-entity} game-entity]

   (apply-unapplied-payments-for-user conn user-entity game-entity (unapplied-payments-for-user conn user-db-id)))

  ([conn {email :user/email :as user-entity} game-entity payment-entities]

   ;; A
   (let [transact-entities-when-exists
         #(when (util/exists? %)
            (persistence.datomic/transact-entities! conn %))]

     (->> payment-entities
          (map #(assoc %
                       :payment.applied/game (select-keys game-entity [:db/id])
                       :payment.applied/applied (c/to-date (t/now))))
          transact-entities-when-exists
          doall))

   (let [lookup-feature (fn [{product-id :payment/product-id :as payment-entity}]
                          (let [feature (->> repl.state/system
                                             :payments/feature-registry
                                             (filter (fn [[k v]] (some v #{product-id})))
                                             ffirst)]

                            (assoc payment-entity :feature feature)))]

     (->> (map lookup-feature payment-entities)
          (map #(apply-feature (:feature %) conn email % game-entity))
          ;; ppi
          doall))))

(defn apply-previous-games-unused-payments-for-user
  [conn
   {user-db-id :db/id
    email :user/email :as user-entity}
   {game-id :game/id}]

  (let [filter-exited-games (fn [{{status :db/ident} :game/status}]
                              (= status :game-status/exited))

        extract-profit-loss (comp :game.user/profit-loss first :game/users)

        exited-games (->> '[:game/id
                            :game/start-time
                            :game/end-time
                            :game/status

                            {:game/users
                             [{:game.user/profit-loss
                               [:game.user.profit-loss/amount]}]}
                            {:payment.applied/_game [*]}]
                          (beatthemarket.game.persistence/user-games conn user-db-id)
                          (map first)
                          (filter filter-exited-games))

        applied-payments (->> exited-games
                              (select [ALL :payment.applied/_game ALL])
                              (filter :payment.applied/applied)
                              (map :payment/product-id)
                              (filter #(some #{true}
                                             (into #{}
                                                   ((juxt valid-apple-product-id?
                                                          valid-google-product-id?
                                                          valid-stripe-product-id?)
                                                    %))))
                              (map keyword)
                              (map feature->amount)
                              (remove nil?)
                              (reduce +))

        cumulative-losses (->> (map extract-profit-loss exited-games)
                               flatten
                               (remove nil?)
                               (map :game.user.profit-loss/amount)
                               (filter neg?)
                               (reduce +))

        applied-payments-less-cumulative-losses (+ applied-payments cumulative-losses)]

    (when (pos? applied-payments-less-cumulative-losses)

      ;; Apply remaining positive cash, to new game
      (->> (game.persistence/cash-account-for-user-game conn email game-id)
           ffirst
           (credit-cash-account applied-payments-less-cumulative-losses)
           (persistence.datomic/transact-entities! conn)))))


(comment

  (->> (select-keys repl.state/config
                    [:payment.provider/apple
                     :payment.provider/google
                     :payment.provider/stripe])
       vals
       (map :subscriptions)
       ;; (group-by first)
       ;; (reduce-kv (fn [m k v]) {})
       ppi)

  (def feature-registry
    (let [extract-products-and-subscriptions (juxt :products :subscriptions)]

      (->> (select-keys repl.state/config
                        [:payment.provider/apple
                         :payment.provider/google
                         :payment.provider/stripe])
           vals
           (map extract-products-and-subscriptions)
           (map #(apply merge %))
           (transform [ALL MAP-VALS] #(list %))
           (apply merge-with into)
           (transform [MAP-VALS] set)
           ppi)))


  (filter (fn [[k v]]
            (some v #{"prod_I1RCtpy369Bu4g"}))
          feature-registry)

  (->> feature-registry
       (filter (fn [[k v]]
                 (some v #{"prod_I1RDqXXxLnMXdb"})))
       ppi
       (map first)
       #_(map #(apply hash-map %)))

  (->> repl.state/system
      :payments/feature-registry
      ;; ppi
      (filter (fn [[k v]]
                (some v #{"prod_I1RDqXXxLnMXdb"})))
      ppi
      ffirst))
