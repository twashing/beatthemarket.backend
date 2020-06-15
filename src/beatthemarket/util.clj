(ns beatthemarket.util
  (:require [clojure.data.json :as json]
            [clojure.pprint :as pprint]
            [integrant.repl.state :as state]))


(defn exists? [a]
  (cond
    (nil? a) false
    (seqable? a) ((comp not empty?) a)
    :else true))

(defn truthy?
  "Based on Clojure truthiness
   http://blog.jayfields.com/2011/02/clojure-truthy-and-falsey.html"
  [a]
  (if (or (nil? a) (false? a))
    false true))

(defn pprint+identity [e]
  (pprint/pprint e)
  e)

#_(defn split-namespaced-keyword [kw]
  ((juxt namespace name) kw))

#_(defn authorization-header-value
  "Possible Authorization headers values can be below.
   Taken from: https://developer.mozilla.org/en-US/docs/Web/HTTP/Authentication#Authentication_schemes

   Basic
   Bearer
   Digest
   HOBA
   Mutual
   AWS4-HMAC-SHA256"
  [auth-header])

(comment


  (require '[beatthemarket.iam.authentication :as iam.auth])


  (def uid (-> state/config :firebase/firebase :admin-user-id))
  (def api-key (-> state/config :firebase/firebase :api-key))


  (def ^String customToken (generate-custom-token uid))
  (-> (iam.auth/decode-token customToken)
      pprint/pprint)


  (def verified-token (verify-custom-token api-key customToken))
  (def verified-token-body
    (-> verified-token
        :body
        (json/read-str :key-fn keyword)))
  (def idToken (:idToken verified-token-body))


  (-> idToken
      iam.auth/check-authentication
      pprint/pprint)


  (-> verified-token
      :body
      (json/read-str :key-fn keyword)
      :idToken
      iam.auth/check-authentication ;; verify-id-token
      pprint/pprint))
