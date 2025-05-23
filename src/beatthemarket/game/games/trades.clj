(ns beatthemarket.game.games.trades
  (:require [clojure.core.match :refer [match]]
            [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]
            [rop.core :as rop]

            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.bookkeeping.core :as bookkeeping]
            [beatthemarket.util :refer [ppi] :as util]))


;; BUY | SELL
(defn- game-iscurrent-and-belongsto-user? [{:keys [conn gameId userId] :as inputs}]
  (if (util/exists?
        (d/q '[:find (pull ?e [:db/id])
               :in $ ?game-id ?user-id
               :where
               [?e :game/id ?game-id]
               [?e :game/start-time]
               [(missing? $ ?e :game/end-time)]
               [?e :game/users ?us]
               [?us :game.user/user ?u]
               [?u :user/external-uid ?user-id]]
             (d/db conn)
             gameId userId))
    (rop/succeed inputs)
    (rop/fail (ex-info "Game isn't current or doesn't belong to user" inputs))))

(defn- submitted-price-matches-tick? [{:keys [conn tickId tickPrice] :as inputs}]

  (let [{tick-price :game.stock.tick/close :as tick}
        (ffirst
          (d/q '[:find (pull ?e [:game.stock.tick/close])
                 :in $ ?tick-id
                 :where
                 [?e :game.stock.tick/id ?tick-id]]
               (d/db conn)
               tickId))]

    (if (= tickPrice tick-price)
      (rop/succeed inputs)
      (let [message (format "Submitted price [%s] does not match price from tickId" tickPrice)]
        (rop/fail (ex-info message tick))))))

(defn stock->tick-history [conn stockId]

  (let [latest-tick-threshold (get (:game/game integrant.repl.state/config) :latest-tick-threshold 2)]

    ;; TODO Howto interpret ?limit-threshold as a Number
    ;; (ppi [latest-tick-threshold (type latest-tick-threshold)])

    (->> (d/q '[:find (pull ?e [(:game.stock/price-history :limit 5)])
                :in $ ?stock-id ?limit-threshold
                :where
                [?e :game.stock/id ?stock-id]]
              (d/db conn) stockId latest-tick-threshold)
         ffirst
         :game.stock/price-history
         (sort-by :game.stock.tick/trade-time >))))

(defn- latest-tick? [{:keys [conn tickId stockId] :as inputs}]

  (let [latest-tick-threshold (get (:game/game integrant.repl.state/config) :latest-tick-threshold 2)
        tick-history-sorted (stock->tick-history conn stockId)
        latest-tick-comparator (->> (take latest-tick-threshold tick-history-sorted)
                                    (map :game.stock.tick/id)
                                    set)]

    (if (some latest-tick-comparator [tickId])
      (rop/succeed inputs)
      (let [message (format "Submitted tick [%s] is not the latest" tickId)]
        (rop/fail (ex-info message {:tick-history-sorted
                                    (take 5 tick-history-sorted)}))))))

(defn buy-stock!

  ([conn user-db-id user-external-id gameId stockId stockAmount tickId tickPrice]
   (buy-stock! conn user-db-id user-external-id gameId stockId stockAmount tickId tickPrice true))

  ([conn user-db-id user-external-id gameId stockId stockAmount tickId tickPrice validate?]
   (let [validation-inputs {:conn        conn
                            :userId      user-external-id
                            :gameId      gameId
                            :stockId     stockId
                            :stockAmount stockAmount
                            :tickId      tickId
                            :tickPrice   tickPrice}]

     (match [validate? (rop/>>= validation-inputs
                                game-iscurrent-and-belongsto-user?
                                submitted-price-matches-tick?
                                latest-tick?)]

            [true (result :guard #(= clojure.lang.ExceptionInfo (type %)))] (throw result)
            [_ _] (let [game-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game/id gameId '[:db/id]))
                        stock-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game.stock/id stockId '[:db/id]))
                        tick-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game.stock.tick/id tickId '[:db/id]))]

                    (bookkeeping/buy-stock! conn game-db-id user-db-id stock-db-id tick-db-id stockAmount tickPrice))))))

(defn sell-stock!

  ([conn user-db-id user-external-id gameId stockId stockAmount tickId tickPrice]
   (sell-stock! conn user-db-id user-external-id gameId stockId stockAmount tickId tickPrice true))

  ([conn user-db-id user-external-id gameId stockId stockAmount tickId tickPrice validate?]

   (let [validation-inputs {:conn conn
                            :userId user-external-id
                            :gameId gameId
                            :stockId stockId
                            :stockAmount stockAmount
                            :tickId tickId
                            :tickPrice tickPrice}]

     (match [validate? (rop/>>= validation-inputs
                                game-iscurrent-and-belongsto-user?
                                submitted-price-matches-tick?
                                latest-tick?)]

            [true (result :guard #(= clojure.lang.ExceptionInfo (type %)))] (throw result)
            [_ _] (let [game-db-id  (util/extract-id (persistence.core/entity-by-domain-id conn :game/id gameId '[:db/id]))
                        stock-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game.stock/id stockId '[:db/id]))
                        tick-db-id (util/extract-id (persistence.core/entity-by-domain-id conn :game.stock.tick/id tickId '[:db/id]))]

                    (bookkeeping/sell-stock! conn game-db-id user-db-id stock-db-id tick-db-id stockAmount tickPrice))))))
