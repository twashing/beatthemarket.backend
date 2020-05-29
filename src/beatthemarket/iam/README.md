# Identity and Access Management

## Verify JWT

There's several approaches to decoding and verifying a JSON Web Token (JWT)

### A. Manually

As per this [SO post](https://stackoverflow.com/questions/38732656/decode-jwt-in-clojure-java), we can manually i decode the JWT, and validate all claims therein.


```
(import '[org.apache.commons.codec.binary Base64])

(as-> invalid-jwt jwt ;; returned-jwt is your full jwt string
  (clojure.string/split jwt #"\.") ;; split into the 3 parts of a jwt, header, body, signature
  (take 2 jwt)  ;; get the header and body
  (map #(Base64/decodeBase64 %) jwt) ;; read it into a byte array
  (map #(String. %) jwt) ;; byte array to string
  (map json/read-str jwt) ;; make it into a sensible clojure map
  (pprint jwt))
```

The resulting JSON should include these CLAIMS

* exp - expiry
* iat - Issued At
* auth_time (custom claim from Firebase - synonym for iat)
* aud - audience: Make sure beatthemarket app is the correct audience (projectId)
* sub - subject: ? How to determine if subject is who they say they are
* iss - issuer: Make sure the correct app ID URL is the issuer


Next steps would be to check these things

* Still authorized ? `(- auth_time expiry)`
* validate claims !
* verify token !


### B. Libraries

There's 2 Clojure libraries that can help in this process.

* liquidz/clj-jwt
  * https://github.com/liquidz/clj-jwt
* funcool/buddy-sign
  * https://github.com/funcool/buddy-sign
  * https://www.bradcypert.com/using-json-web-tokens-with-clojure/



There's a default Firebase Project (or app) public key, so we can verify a token.
* https://stackoverflow.com/questions/37634305/firebase-token-verification
* https://stackoverflow.com/questions/37762359/firebase-authenticate-with-backend-server

There's also a way to generate a custom private key file for your service account. In the Firebase console (Settings > Service Accounts).


### C. Firebase Admin SDK

We can also handle this by using the Firebase Admin SDK on the server. That's the approach used in this project (in `beatthemarket.iam.authentication`). The main functions of interest are here.

* `initialize-firebase` Used by the component system to initialize firebase
* `verify-token`

References

* https://firebase.google.com/docs/admin/setup#java
* https://stackoverflow.com/a/37492640/375616
* https://firebase.google.com/docs/auth/admin/verify-id-tokens#web
* https://firebase.google.com/docs/auth/admin/verify-id-tokens#java
* https://firebase.google.com/docs/reference/admin (Admin SDK Reference)
