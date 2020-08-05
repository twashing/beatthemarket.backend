(ns beatthemarket.iam.user
  (:require [datomic.client.api :as d]
            [integrant.repl.state :as repl.state]
            [beatthemarket.iam.persistence :as iam.persistence]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.util :as util]))


(defn user-exists? [result-entities]
  (let [set-and-subsets-not-empty?
        (every-pred util/exists? (partial every? util/exists?))]
    (set-and-subsets-not-empty? result-entities)))

(defn add-user! [conn {:keys [email name uid]}]

  (->> {:user/email        email
        :user/name         name
        :user/external-uid uid}
       (persistence.datomic/transact-entities! conn)))

(defn conditionally-add-new-user! [conn {email :email :as checked-authentication}]

  (if-not (user-exists? (iam.persistence/user-by-email conn email))
    (add-user! conn checked-authentication)
    {:db-after (d/db conn)}))
