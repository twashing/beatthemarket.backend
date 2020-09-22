(ns beatthemarket.persistence.generators
  (:require [clojure.java.io :refer [resource]]
            [clojure.edn :refer [read-string]]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [integrant.repl.state :as repl.state]
            [datomic.client.api :as d]

            [beatthemarket.persistence.core :as persistence.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.util :as util :refer [ppi]]))


(def name->email (fn [full-name]
                   (as-> full-name v
                     (clojure.string/split v #" ")
                     (map clojure.string/lower-case v)
                     (interpose "." v)
                     (concat v ["@foo.com"])
                     (apply str v))))


(def jazz-names ["Charles Mingus" "John Coltrane" "Herbie Hancock" "Miles Davis" "Sun Ra" "Thelonious Monk"])
(def gen-user-full-name (gen/elements jazz-names))
(def gen-account-name (gen/elements ["Cash"]))
(def gen-rand-long (gen/elements (map long (range 100))))


(def full-name-spec (s/with-gen string? (constantly gen-user-full-name)))
(def account-name-spec (s/with-gen string? (constantly gen-account-name)))
(def long-spec (s/with-gen nat-int? (constantly gen-rand-long)))
(def game-status? #{:game-status/created :game-status/running :game-status/paused :game-status/won :game-status/lost :game-status/exited})
(def game-level? #{:game-level/one :game-level/two :game-level/three :game-level/four :game-level/five :game-level/six})
(def profit-loss-type? #{:profit-loss/realized})
(def account-type? #{:bookkeeping.account.type/asset :bookkeeping.account.type/liability})


(s/def :user/name full-name-spec)
(s/def :user/email string?)
(s/def :user/external-uid string?)
(def user-spec (s/with-gen (s/and map? distinct?)
                 #(gen/fmap
                    (fn [{:keys [user/name user/email] :as user}]
                      (->> (name->email name)
                           (assoc user :user/email)
                           persistence.core/bind-temporary-id))
                    (s/gen (s/keys :req [:user/email :user/name :user/external-uid])))))
(s/def ::user user-spec)


(s/def :bookkeeping.account/id uuid?)
(s/def :bookkeeping.account/name account-name-spec)
(s/def :bookkeeping.account/type account-type?)
(s/def :bookkeeping.account/balance float?)
(def account-spec (s/with-gen map?
                    #(gen/fmap
                       persistence.core/bind-temporary-id
                       (s/gen (s/keys :req [:bookkeeping.account/id :bookkeeping.account/name :bookkeeping.account/type :bookkeeping.account/balance])))))
(s/def ::account account-spec)


(s/def :game.user.profit-loss/amount float?)
(s/def :game.user.profit-loss/type profit-loss-type?)
(def profit-loss-spec (s/with-gen map?
                        #(gen/fmap
                           persistence.core/bind-temporary-id
                           (s/gen (s/keys :req [:game.user.profit-loss/amount :game.user.profit-loss/type])))))
(s/def ::profit-loss profit-loss-spec)


(s/def :game.user/user ::user)
(s/def :game.user/accounts (s/coll-of ::account :count 1))
(s/def :game.user/user-client uuid?)
(s/def :game.user/profit-loss (s/coll-of ::profit-loss))
(def game-user-spec (s/with-gen map?
                      #(gen/fmap
                         persistence.core/bind-temporary-id
                         (s/gen (s/keys :req [:game.user/user :game.user/accounts :game.user/user-client :game.user/profit-loss])))))
(s/def ::game-user game-user-spec)


(s/def :game/id uuid?)
(s/def :game/start-time inst?)
(s/def :game/users (s/with-gen distinct?
                     #(gen/fmap
                        identity
                        (s/gen (s/coll-of ::game-user :count 1)))))
(s/def :game/start-position long-spec)
(s/def :game/status game-status?)
(s/def :game/level game-level?)
(def game-spec (s/with-gen map?
                 #(gen/fmap
                    persistence.core/bind-temporary-id
                    (s/gen (s/keys :req [:game/id :game/start-time :game/users :game/start-position :game/status :game/level])))))
(s/def ::game game-spec)

(def generate-game #(gen/generate (s/gen ::game)))
(defn generate-games []

  ;; TODO Fix this
  (let [temporary-kludge-toget-distinct-names
        (fn [jazz-name game]
          (let [uname jazz-name
                email (name->email uname)]
            (update-in game [:game/users 0 :game.user/user] #(assoc % :user/name uname :user/email email))))]
    (->> (s/exercise ::game)
         (map first)
         (map temporary-kludge-toget-distinct-names jazz-names))))

(comment


  (def conn (-> repl.state/system :persistence/datomic :opts :conn))

  (generate-game)
  (generate-games)



  (persistence.datomic/transact-entities! conn game)


  (ppi
    (d/q '[:find (pull ?e [*])
           :where [?e :game/id]]
         (d/db conn)))



  ;; beatthemarket.game.calculation/collect-account-balances
  ;; beatthemarket.game.calculation/collect-realized-profit-loss-pergame

  #_(d/q '[:find ?gameId (pull ?pls [*])
           :in $ ?user-id
           :where
           [?g :game/id ?gameId]
           [?g :game/users ?gus]
           [?gus :game.user/user ?user-id]
           [?gus :game.user/profit-loss ?pls]]
         (d/db conn)
         ;; user-db-id
         17592186045454)

  #_(d/q '[:find (pull ?ua [:bookkeeping.account/id
                            :bookkeeping.account/name
                            :bookkeeping.account/balance
                            :bookkeeping.account/amount
                            {:bookkeeping.account/counter-party
                             [:game.stock/name]}])
           :in $ ?game-id ?user-id
           :where
           [?game-id]
           [?game-id :game/users ?gus]
           [?gus :game.user/user ?user-id]
           [?gus :game.user/accounts ?ua]]
         (d/db conn)
         ;; game-db-id user-db-id
         17592186045452 17592186045454))
