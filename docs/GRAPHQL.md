## Overview

Against the Backend, the client will use GraphQL with these parameters. Always besure to pass along the "Authorization" header. 
```
:post "/api"
:body "<body>"
:headers {"Content-Type" "application/json"
          "Authorization" "Bearer <your-token>"}
```

### Login

Depending on whether the user already exists, you'll get one of two responses.
```
body: {"query": "mutation Login { login { message }} "}
response: {"data":{"login":{"message":"useradded"}}}

body: {"query": "mutation Login { login { message }} "}
response: {"data":{"login":{"message":"userexists"}}}
```


### New game

The client will make i. a call to create a game in the system. 
```
body: {"query": "mutation CreateGame { createGame { id stocks } }"}
response:
{:data
  {:createGame
   {:id "a4ea2eed-c1e6-45ff-a8e9-8fef843fab8f",
    :stocks
    ["{id \"8f5cd436-a654-42ae-b1cb-f1db04da3402\" name \"Sour Dragon\" symbol \"SOUR\"}"
    "{id \"c80194bb-872e-4e56-92f5-0fbf1999d10d\" name \"Wise Growth\" symbol \"WISE\"}"
    "{id \"2bfb8eea-888f-4d91-bbcb-2620fcf1648d\" name \"Considerable Man\" symbol \"CONS\"}"
    "{id \"f868af60-e04d-4f27-8024-c5f6371ed2e0\" name \"First Pioneer\" symbol \"FIRS\"}"]}}}
```

The game then has to be started.
```
body: {"query": "mutation StartGame($id: String!) {
                   startGame(id: $id) {
                     message
                   }
                 }
       "variables" \"{id id}\""}

response:
{:data
  {:createGame
   {:message "gamestarted"}}}
```


### Stream Stock Ticks

```
body: {:query "subscription StreamTicks($id: String!) {
               streamTicks(id: $id) {
                 message
               }
             }"
       :variables {:id id}}
                                 
response:
({stockTickId "d9340e77-8190-4900-b0e8-ac6f19d6b7e3"
  stockTickTime 1595286847399
  stockTickClose 250.0
  stockId "b0db03de-a7e7-4a5d-80ac-dbcee519433c"
  stockName "Angry Jewel"}
 {stockTickId "5dbcf574-e87c-4dec-ae9d-74c6ad4754ed"
  stockTickTime 1595286847399
  stockTickClose 250.0
  stockId "b8f95491-8d44-4962-9f55-cd99caa54374"
  stockName "Definite Occupation"}
 {stockTickId "2578ea76-6660-495d-abfc-7edd2aee7e17"
  stockTickTime 1595286847399
  stockTickClose 250.0
  stockId "b2399122-25db-48ca-a9d9-a609a7987f62"
  stockName "Grateful Receipt"}
 {stockTickId "0a0e1873-627d-496d-8916-c559edd573ab"
  stockTickTime 1595286847399
  stockTickClose 250.0
  stockId "980f117f-d763-479b-81c5-10b0991f25c6"
  stockName "Memorable Standard"})

({stockTickId "19fdb28b-d115-4ac3-aef3-ffb1ca6f066e"
  stockTickTime 1595286848399
  stockTickClose 260.0
  stockId "b0db03de-a7e7-4a5d-80ac-dbcee519433c"
  stockName "Angry Jewel"}
 {stockTickId "3fbce3da-56cc-473e-87bb-5b71d9457d28"
  stockTickTime 1595286848399
  stockTickClose 260.0
  stockId "b8f95491-8d44-4962-9f55-cd99caa54374"
  stockName "Definite Occupation"}
 {stockTickId "cf78c973-d903-4c04-8366-dc4f392859fe"
  stockTickTime 1595286848399
  stockTickClose 260.0
  stockId "b2399122-25db-48ca-a9d9-a609a7987f62"
  stockName "Grateful Receipt"}
 {stockTickId "2085ef2c-bfc3-4a7c-aef8-d7b046cc4b97"
  stockTickTime 1595286848399
  stockTickClose 260.0
  stockId "980f117f-d763-479b-81c5-10b0991f25c6"
  stockName "Memorable Standard"})
```

### Buy a stock (Stream P/L, Account Balances)


### Sell a stock


### Complete level 1 (Stream Game Events)
* Lose Level


### Complete Game
* Lose Game
