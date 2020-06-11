(ns beatthemarket.util
  (:require [clojure.data.json :as json]
            [clj-http.client :as http]
            [integrant.repl.state :as state])
  (:import [com.google.firebase.auth FirebaseAuth]))


(defn exists? [a]
  (cond
    (nil? a) false
    (seqable? a) ((comp not empty?) a)
    :else true))

(defn truthy? [a]
  "Based on Clojure truthiness
   http://blog.jayfields.com/2011/02/clojure-truthy-and-falsey.html"
  (if (or (nil? a) (false? a))
    false true))

(defn pprint+identity [e]
  (clojure.pprint/pprint e)
  e)

(defn split-namespaced-keyword [kw]
  ((juxt namespace name) kw))

;; Firebase Token Helper
;;
;; Idea taken from this SO answer
;; https://stackoverflow.com/a/51346783/375616
(defn generate-custom-token [uid]
  (.. (FirebaseAuth/getInstance)
      (createCustomToken uid))  )

(defn token->body-payload [customToken]
  (json/write-str {:token customToken
                   :returnSecureToken true}))

(defn verify-custom-token [api-key customToken]
  (http/post (format "https://www.googleapis.com/identitytoolkit/v3/relyingparty/verifyCustomToken?key=%s" api-key)
             {:content-type :json
              :body (token->body-payload customToken)}))

(defn ->id-token []

  (let [{uid :admin-user-id
         api-key :api-key} (-> state/config :firebase/firebase)

        customToken (generate-custom-token uid)]

    (-> (verify-custom-token api-key customToken)
        :body
        (json/read-str :key-fn keyword)
        :idToken)))

(defn authorization-header-value
  "Possible Authorization headers values can be below.
   Taken from: https://developer.mozilla.org/en-US/docs/Web/HTTP/Authentication#Authentication_schemes

   Basic
   Bearer
   Digest
   HOBA
   Mutual
   AWS4-HMAC-SHA256"
  [auth-header]
  )


(comment


  (def uid (-> state/config :firebase/firebase :admin-user-id))
  (def api-key (-> state/config :firebase/firebase :api-key))


  (def ^String customToken (generate-custom-token uid))
  (-> (iam.auth/decode-token customToken)
      pprint)


  (def verified-token (verify-custom-token api-key customToken))
  (def verified-token-body
    (-> verified-token
        :body
        (json/read-str :key-fn keyword)))
  (def idToken (:idToken verified-token-body))


  (-> idToken
      iam.auth/check-authentication
      pprint)


  (-> verified-token
      :body
      (json/read-str :key-fn keyword)
      :idToken
      check-authentication ;; verify-id-token
      pprint))
