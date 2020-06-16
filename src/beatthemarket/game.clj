(ns beatthemarket.game
  (:require [clj-time.core :as t]
            [clj-time.coerce :as c]
            [beatthemarket.bookkeeping :as bookkeeping]
            [beatthemarket.datasource.name-generator :as name-generator]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.persistence.bookkeeping :as persistence.bookkeeping]
            [beatthemarket.util :as util]
            [datomic.client.api :as d])
  (:import [java.util UUID]))


(defn bind-temporary-id [entity]
  (assoc entity :db/id (str (UUID/randomUUID))))

(defn ->game [game-level stocks user]

  (let [subscriptions (take 1 stocks)
        portfolio-with-journal (beatthemarket.bookkeeping/->portfolio
                                 (beatthemarket.bookkeeping/->journal))

        game-users (-> (hash-map
                         :game.user/user user
                         :game.user/subscriptions subscriptions
                         :game.user/portfolio portfolio-with-journal)
                       bind-temporary-id
                       list)]

    (hash-map
      :game/id (UUID/randomUUID)
      :game/start-time (c/to-date (t/now))
      :game/level game-level
      :game/stocks stocks
      :game/users game-users)))

(defn ->stock

  ([name symbol] (->stock name symbol nil))
  ([name symbol price-history]
   (cond-> (hash-map
             :game.stock/id (UUID/randomUUID)
             :game.stock/name name
             :game.stock/symbol symbol)
     (util/exists? price-history) (assoc :game.stock/price-history price-history))))

(defn initialize-game [conn user-entity]

  (let [game-level :game-level/one
        stocks     (->> (name-generator/generate-names 4)
                        (map (juxt :stock-name :stock-symbol))
                        (map #(apply ->stock %))
                        (map bind-temporary-id))
        game (->game game-level stocks user-entity)]

    (persistence.datomic/transact-entities! conn game)
    game))

(comment ;; Portfolio


  (require '[integrant.repl.state])


  (def conn (-> integrant.repl.state/system :persistence/datomic :conn))


  (def journal
    (->> (beatthemarket.bookkeeping/->journal)
         (beatthemarket.persistence.bookkeeping/add-journal! conn)))


  (def portfolio
    (->> (beatthemarket.bookkeeping/->portfolio)
         (beatthemarket.persistence.bookkeeping/add-portfolio! conn)))


  (def composite-portfolio
    (->> (beatthemarket.bookkeeping/->journal)
         beatthemarket.bookkeeping/->portfolio
         (beatthemarket.persistence.bookkeeping/add-portfolio! conn)))


  (def result-portfolio (d/q '[:find ?e ?id
                               :in $ ?id
                               :where [?e :bookkeeping.journal/id ?id]]
                             (d/db conn)
                             (UUID/fromString "3cf2f83f-1954-4693-ab61-781024979519")))

  (d/pull (d/db conn) '[*] (ffirst result-portfolio))


  (def db (d/db conn))
  (d/q '[:find ?e
         :where [?e :bookkeeping.journal/id]] db))

(comment ;; Accounts


  (require '[integrant.repl.state])


  ;; Insert
  (let [conn (-> integrant.repl.state/system :persistence/datomic :conn)]

    (->> [["Cash" :bookkeeping.account.type/asset :bookkeeping.account.orientation/debit]
          ["Equity" :bookkeeping.account.type/equity :bookkeeping.account.orientation/credit]]
         (map #(apply bookkeeping/->account %))
         (persistence.bookkeeping/add-account! conn)))

  ;; Query
  (def conn (-> integrant.repl.state/system :persistence/datomic :conn))
  (def result-accounts (d/q '[:find ?e
                               :where [?e :bookkeeping.account/id]]
                             (d/db conn)))


  (d/pull (d/db conn) '[*] (ffirst result-accounts))

  (->> result-accounts
       (map #(d/pull (d/db conn) '[*] (first %)))))

(comment ;; Game

  (let [;; Create a bookkeeping book
        portfolio+journal (->> (beatthemarket.bookkeeping/->journal)
                               beatthemarket.bookkeeping/->portfolio)

        ;; Generate stocks + first subscription
        stocks (->> [["Sun Ra Inc" "SUN"]
                     ["Miles Davis Inc" "MILD"]
                     ["John Coltrane Inc" "JONC"]]
                    (map #(apply ->stock %))
                    (map bind-temporary-id))
        subscriptions (take 1 stocks)

        game-level :game-level/one]

    ;; Save stocks
    (def result-game (->game game-level portfolio+journal subscriptions stocks))
    result-game))

(comment ;; Initialize

  ;; Insert
  (let [conn (-> integrant.repl.state/system :persistence/datomic :conn)
        user nil]

    (initialize-game conn user))

  (def result *1)

  ;; Query
  (def conn (-> integrant.repl.state/system :persistence/datomic :conn))
  (def result-game (d/q '[:find ?e
                          :where [?e :game/id]]
                        (d/db conn)))

  (d/pull (d/db conn) '[*] (ffirst result-game)))
