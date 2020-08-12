
## USE CASES


### Query a User
lein test :only beatthemarket.handler.http.integration.inspect-game-test/query-user-test

```

# client >

{:id 987,
 :type :start,
 :payload
 {:query
  "query User($email: String!) {
                                     user(email: $email) {
                                       userEmail
                                       userName
                                       userExternalUid
                                     }
                                   }",
  :variables {:email "twashing@gmail.com"}}}


# < server

{:type "data",
 :id 987,
 :payload
 {:data
  {:user
   {:userEmail "twashing@gmail.com",
    :userName "Timothy Washington",
    :userExternalUid "VEDgLEOk1eXZ5jYUcc4NklAU3Kv2"}}}}
```

### Query Users

lein test :only beatthemarket.handler.http.integration.inspect-game-test/query-users-test
```

# client >

{:id 987,
 :type :start,
 :payload
 {:query
  "query Users {
                                     users {
                                       userEmail
                                       userName
                                       userExternalUid
                                     }
                                   }"}}


# < server

{:type "data",
 :id 987,
 :payload
 {:data
  {:users
   [{:userEmail "twashing@gmail.com",
     :userName "Timothy Washington",
     :userExternalUid "VEDgLEOk1eXZ5jYUcc4NklAU3Kv2"}]}}}
```

### Query a User's AccountBalances, for a Game

lein test :only beatthemarket.handler.http.integration.inspect-game-test/query-account-balances-test
```

# client >

{:id 987,
 :type :start,
 :payload
 {:query
  "mutation CreateGame($gameLevel: Int!) {
                                     createGame(gameLevel: $gameLevel) {
                                       id
                                       stocks { id name symbol }
                                     }
                                   }",
  :variables {:gameLevel 1}}}


# < server

{:type "data",
 :id 987,
 :payload
 {:data
  {:createGame
   {:id "5ad3a474-a340-42ac-bd71-bf85b6443c6f",
    :stocks
    [{:id "d9fc95fd-677d-4719-b05a-e32b66514281",
      :name "Medieval Ballet",
      :symbol "MEDI"}
     {:id "b5674d1b-0c5c-4c4b-a05a-7b2003eac33a",
      :name "Pure Conductor",
      :symbol "PURE"}
     {:id "9c8d3c33-c97f-409c-859d-3137c6df54b0",
      :name "Talented Europe",
      :symbol "TALE"}
     {:id "436181ab-8ee1-4d26-a25c-e1a4815bb11b",
      :name "Applied Housing",
      :symbol "APPL"}]}}}}


# client >

{:id 988,
 :type :start,
 :payload
 {:query
  "query AccountBalances($gameId: String!, $email: String!) {
                                       accountBalances(gameId: $gameId, email: $email) {
                                         id
                                         name
                                         balance
                                         counterParty
                                         amount
                                       }
                                   }",
  :variables
  {:gameId "5ad3a474-a340-42ac-bd71-bf85b6443c6f",
   :email "twashing@gmail.com"}}}


# < server

{:type "complete", :id 987}
{:type "data",
 :id 988,
 :payload
 {:data
  {:accountBalances
   [{:id "23a7d9b3-a619-44e7-a4ae-1906a36037bc",
     :name "Cash",
     :balance 100000.0,
     :counterParty nil,
     :amount 0}
    {:id "ed65779f-7218-4fc1-bf2f-2ffc76a5bdf5",
     :name "Equity",
     :balance 100000.0,
     :counterParty nil,
     :amount 0}]}}}
```
