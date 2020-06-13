(ns beatthemarket.iam.user
  (:require [beatthemarket.util :as util]
            [beatthemarket.persistence.user :as persistence.user]))


(defn user-exists? [result-entities]
  (let [set-and-subsets-note-empty?
        (every-pred util/exists? (partial every? util/exists?))]
    (set-and-subsets-note-empty? result-entities)))

(defn conditionally-add-new-user! [conn {email :email :as checked-authentication}]
  (when-not (user-exists? (persistence.user/user-by-email conn email))
    (persistence.user/add-user! conn checked-authentication)))
