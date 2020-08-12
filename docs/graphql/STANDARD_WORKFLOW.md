
## USE CASES


### Start a Game
lein test :only beatthemarket.handler.http.integration.standard-workflow-test/start-game-resolver-test

First create the game.
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
   {:id "bdf7ad83-322b-4401-9173-c8caebe21695",
    :stocks
    [{:id "7ff55468-67ec-444e-b20e-85ef4a112fc4",
      :name "Bureaucratic Request",
      :symbol "BURE"}
     {:id "c3c10bba-97e7-4896-bc52-67aea909be7f",
      :name "Environmental Suicide",
      :symbol "ENVI"}
     {:id "e9b6b107-fe4f-4fb1-bc03-d13206bf66e8",
      :name "Incredible Wreck",
      :symbol "INCR"}
     {:id "d9419312-4dea-4d83-ab61-81d5cf49a2b1",
      :name "Occupational Capitalism",
      :symbol "OCCU"}]}}}}
      

```

Then start that game.
```
# client >

{:id 987,
 :type :start,
 :payload
 {:query
  "mutation StartGame($id: String!) {
                                         startGame(id: $id) {
                                           stockTickId
                                           stockTickTime
                                           stockTickClose
                                           stockId
                                           stockName
                                         }
                                       }",
  :variables {:id "bdf7ad83-322b-4401-9173-c8caebe21695"}}}

# < server

{:type "complete", :id 987}
{:type "data", :id 987, :payload {:data {:startGame []}}}
```

### Start a game at a specified position
lein test :only beatthemarket.handler.http.integration.standard-workflow-test/start-game-resolver-with-start-position-test

Create game send and response.
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
   {:id "e17801bb-fc6f-40e9-85b0-932a57738ef6",
    :stocks
    [{:id "77492048-fd55-432e-bd0d-55aaaaa9a32b",
      :name "Pregnant Essay",
      :symbol "PREG"}
     {:id "1656be05-889d-4a50-8634-aa37b81c68fb",
      :name "Strong Hostility",
      :symbol "STRO"}
     {:id "8b62ea90-cbd6-417e-b55a-dc3b1bfea0aa",
      :name "Addicted Misery",
      :symbol "ADDI"}
     {:id "3ce72e93-037a-4500-a0be-9f0c517a5ead",
      :name "Cruel Prey",
      :symbol "CRUE"}]}}}}
```

Start game send and response. This will have the initial history, from 0 to your `startPosition`.
```
# client >

{:id 988,
 :type :start,
 :payload
 {:query
  "mutation StartGame($id: String!, $startPosition: Int) {
                                         startGame(id: $id, startPosition: $startPosition) {
                                           stockTickId
                                           stockTickTime
                                           stockTickClose
                                           stockId
                                           stockName
                                         }
                                       }",
  :variables
  {:id "e17801bb-fc6f-40e9-85b0-932a57738ef6", :startPosition 10}}}
  

# < server

{:type "complete", :id 987}
{:type "data",
 :id 988,
 :payload
 {:data
  {:startGame
   [[{:stockTickId "d7cbe484-3bf1-4292-8a23-42337db07a76",
      :stockTickTime "1596822713606",
      :stockTickClose 88.79000091552734,
      :stockId "77492048-fd55-432e-bd0d-55aaaaa9a32b",
      :stockName "Pregnant Essay"}
     {:stockTickId "1b0ca381-1187-48df-b4bf-6b103a6cd88f",
      :stockTickTime "1596822713610",
      :stockTickClose 103.36000061035156,
      :stockId "1656be05-889d-4a50-8634-aa37b81c68fb",
      :stockName "Strong Hostility"}
     {:stockTickId "9a60af43-6472-43da-ac7e-4186da7b1dde",
      :stockTickTime "1596822713613",
      :stockTickClose 108.41000366210938,
      :stockId "8b62ea90-cbd6-417e-b55a-dc3b1bfea0aa",
      :stockName "Addicted Misery"}
     {:stockTickId "83ba706d-ec85-40ad-94f9-21e4a06b1d6b",
      :stockTickTime "1596822713618",
      :stockTickClose 138.75999450683594,
      :stockId "3ce72e93-037a-4500-a0be-9f0c517a5ead",
      :stockName "Cruel Prey"}]
    [{:stockTickId "32061781-4e05-46f9-bcf5-ebf057de28a8",
      :stockTickTime "1596822714606",
      :stockTickClose 88.37000274658203,
      :stockId "77492048-fd55-432e-bd0d-55aaaaa9a32b",
      :stockName "Pregnant Essay"}
     {:stockTickId "178c83b6-0da6-40ac-9252-c052a3a4591e",
      :stockTickTime "1596822714610",
      :stockTickClose 103.22000122070312,
      :stockId "1656be05-889d-4a50-8634-aa37b81c68fb",
      :stockName "Strong Hostility"}
     {:stockTickId "5e2caa08-2a5c-4054-bf4a-be5ecbe4cb4c",
      :stockTickTime "1596822714613",
      :stockTickClose 108.62000274658203,
      :stockId "8b62ea90-cbd6-417e-b55a-dc3b1bfea0aa",
      :stockName "Addicted Misery"}
     {:stockTickId "4101b377-52b0-4667-bba9-d43386ba812c",
      :stockTickTime "1596822714618",
      :stockTickClose 139.63999938964844,
      :stockId "3ce72e93-037a-4500-a0be-9f0c517a5ead",
      :stockName "Cruel Prey"}]
    [{:stockTickId "028d6d91-9cbf-4e25-93a3-0dffd2995189",
      :stockTickTime "1596822715606",
      :stockTickClose 88.04000091552734,
      :stockId "77492048-fd55-432e-bd0d-55aaaaa9a32b",
      :stockName "Pregnant Essay"}
     {:stockTickId "21601499-75f3-4617-9783-d149c894db94",
      :stockTickTime "1596822715610",
      :stockTickClose 102.44000244140625,
      :stockId "1656be05-889d-4a50-8634-aa37b81c68fb",
      :stockName "Strong Hostility"}
     {:stockTickId "ea7bf6a2-01b5-414b-8d83-ad60742fe0f5",
      :stockTickTime "1596822715613",
      :stockTickClose 107.81999969482422,
      :stockId "8b62ea90-cbd6-417e-b55a-dc3b1bfea0aa",
      :stockName "Addicted Misery"}
     {:stockTickId "f5cb8f2c-8c8a-47b7-b1ac-b6e325d5f7ad",
      :stockTickTime "1596822715618",
      :stockTickClose 139.42999267578125,
      :stockId "3ce72e93-037a-4500-a0be-9f0c517a5ead",
      :stockName "Cruel Prey"}]
    [{:stockTickId "f7db44b1-e194-4e4c-ba43-28553177562f",
      :stockTickTime "1596822716606",
      :stockTickClose 88.7699966430664,
      :stockId "77492048-fd55-432e-bd0d-55aaaaa9a32b",
      :stockName "Pregnant Essay"}
     {:stockTickId "da9905f8-0ea6-4a54-a2e7-641b5923c731",
      :stockTickTime "1596822716610",
      :stockTickClose 102.52999877929688,
      :stockId "1656be05-889d-4a50-8634-aa37b81c68fb",
      :stockName "Strong Hostility"}
     {:stockTickId "c8f604cd-b08a-4692-a9f6-d4e3dc3a52a8",
      :stockTickTime "1596822716613",
      :stockTickClose 107.69000244140625,
      :stockId "8b62ea90-cbd6-417e-b55a-dc3b1bfea0aa",
      :stockName "Addicted Misery"}
     {:stockTickId "70d474e8-ed10-4033-9aaa-9f48298ab795",
      :stockTickTime "1596822716618",
      :stockTickClose 139.97999572753906,
      :stockId "3ce72e93-037a-4500-a0be-9f0c517a5ead",
      :stockName "Cruel Prey"}]
    [{:stockTickId "1bb248cc-2313-490a-8380-ce4b8b601064",
      :stockTickTime "1596822717606",
      :stockTickClose 88.22000122070312,
      :stockId "77492048-fd55-432e-bd0d-55aaaaa9a32b",
      :stockName "Pregnant Essay"}
     {:stockTickId "1eb743fb-6af8-4ad1-89bf-161885c524b7",
      :stockTickTime "1596822717610",
      :stockTickClose 103.79000091552734,
      :stockId "1656be05-889d-4a50-8634-aa37b81c68fb",
      :stockName "Strong Hostility"}
     {:stockTickId "335fa954-58db-4d03-b5af-f468d5188883",
      :stockTickTime "1596822717613",
      :stockTickClose 107.45999908447266,
      :stockId "8b62ea90-cbd6-417e-b55a-dc3b1bfea0aa",
      :stockName "Addicted Misery"}
     {:stockTickId "c15f5e34-3282-4fe8-9f28-102d5ecf9d1a",
      :stockTickTime "1596822717618",
      :stockTickClose 140.4600067138672,
      :stockId "3ce72e93-037a-4500-a0be-9f0c517a5ead",
      :stockName "Cruel Prey"}]
    [{:stockTickId "ab8abb12-0211-4d07-911f-359ace5ee9f8",
      :stockTickTime "1596822718606",
      :stockTickClose 89.13999938964844,
      :stockId "77492048-fd55-432e-bd0d-55aaaaa9a32b",
      :stockName "Pregnant Essay"}
     {:stockTickId "1ee0861e-5510-4a24-b8fb-5c7d6daa6fd9",
      :stockTickTime "1596822718610",
      :stockTickClose 104.77999877929688,
      :stockId "1656be05-889d-4a50-8634-aa37b81c68fb",
      :stockName "Strong Hostility"}
     {:stockTickId "ca66065b-ceb8-4713-90b7-70197b789b91",
      :stockTickTime "1596822718613",
      :stockTickClose 108.76000213623047,
      :stockId "8b62ea90-cbd6-417e-b55a-dc3b1bfea0aa",
      :stockName "Addicted Misery"}
     {:stockTickId "89f045b5-46d9-4b96-aba3-d19e3f1f96e1",
      :stockTickTime "1596822718618",
      :stockTickClose 141.8000030517578,
      :stockId "3ce72e93-037a-4500-a0be-9f0c517a5ead",
      :stockName "Cruel Prey"}]
    [{:stockTickId "e1f6f0b4-a032-403d-949a-456c4d9ff9e7",
      :stockTickTime "1596822719606",
      :stockTickClose 89.0,
      :stockId "77492048-fd55-432e-bd0d-55aaaaa9a32b",
      :stockName "Pregnant Essay"}
     {:stockTickId "5be9923b-78b6-4dac-b675-0fae9bbbb0c3",
      :stockTickTime "1596822719610",
      :stockTickClose 104.19000244140625,
      :stockId "1656be05-889d-4a50-8634-aa37b81c68fb",
      :stockName "Strong Hostility"}
     {:stockTickId "72565cf9-8721-463b-a7af-06ce051f6253",
      :stockTickTime "1596822719613",
      :stockTickClose 110.05000305175781,
      :stockId "8b62ea90-cbd6-417e-b55a-dc3b1bfea0aa",
      :stockName "Addicted Misery"}
     {:stockTickId "b5cff5fc-16f7-4e46-9c31-b4159ab468d8",
      :stockTickTime "1596822719618",
      :stockTickClose 143.00999450683594,
      :stockId "3ce72e93-037a-4500-a0be-9f0c517a5ead",
      :stockName "Cruel Prey"}]
    [{:stockTickId "fb8b03fe-41aa-4e6a-9940-934c99a0df27",
      :stockTickTime "1596822720606",
      :stockTickClose 90.44000244140625,
      :stockId "77492048-fd55-432e-bd0d-55aaaaa9a32b",
      :stockName "Pregnant Essay"}
     {:stockTickId "985c33b2-1a74-4528-bf1c-ad8ce854f937",
      :stockTickTime "1596822720610",
      :stockTickClose 104.6500015258789,
      :stockId "1656be05-889d-4a50-8634-aa37b81c68fb",
      :stockName "Strong Hostility"}
     {:stockTickId "21063727-86b8-4b05-b2a8-45ed5e86da9d",
      :stockTickTime "1596822720613",
      :stockTickClose 110.29000091552734,
      :stockId "8b62ea90-cbd6-417e-b55a-dc3b1bfea0aa",
      :stockName "Addicted Misery"}
     {:stockTickId "e13a4f83-7e81-4967-9bbf-726f3b9ed373",
      :stockTickTime "1596822720618",
      :stockTickClose 142.55999755859375,
      :stockId "3ce72e93-037a-4500-a0be-9f0c517a5ead",
      :stockName "Cruel Prey"}]
    [{:stockTickId "c682cd54-7deb-4bf0-a8ff-df7131d624d6",
      :stockTickTime "1596822721606",
      :stockTickClose 90.45999908447266,
      :stockId "77492048-fd55-432e-bd0d-55aaaaa9a32b",
      :stockName "Pregnant Essay"}
     {:stockTickId "0e682a5f-e5b4-4004-8617-98d5add55586",
      :stockTickTime "1596822721610",
      :stockTickClose 105.88999938964844,
      :stockId "1656be05-889d-4a50-8634-aa37b81c68fb",
      :stockName "Strong Hostility"}
     {:stockTickId "e52dcff0-6deb-4c65-b7e9-59331cb9856c",
      :stockTickTime "1596822721613",
      :stockTickClose 111.11000061035156,
      :stockId "8b62ea90-cbd6-417e-b55a-dc3b1bfea0aa",
      :stockName "Addicted Misery"}
     {:stockTickId "610424de-f62d-47ad-8f0f-99f29fb20d96",
      :stockTickTime "1596822721618",
      :stockTickClose 143.94000244140625,
      :stockId "3ce72e93-037a-4500-a0be-9f0c517a5ead",
      :stockName "Cruel Prey"}]
    [{:stockTickId "211e28d2-8da3-4251-96f9-abbd4ef166e0",
      :stockTickTime "1596822722606",
      :stockTickClose 91.83999633789062,
      :stockId "77492048-fd55-432e-bd0d-55aaaaa9a32b",
      :stockName "Pregnant Essay"}
     {:stockTickId "c6c6c197-a401-4f70-ba6d-6806f9854f55",
      :stockTickTime "1596822722610",
      :stockTickClose 106.5999984741211,
      :stockId "1656be05-889d-4a50-8634-aa37b81c68fb",
      :stockName "Strong Hostility"}
     {:stockTickId "b558d9ea-5b77-4f0d-8eb2-cfd616649034",
      :stockTickTime "1596822722613",
      :stockTickClose 111.68000030517578,
      :stockId "8b62ea90-cbd6-417e-b55a-dc3b1bfea0aa",
      :stockName "Addicted Misery"}
     {:stockTickId "af70a6e6-26ec-45f4-afdc-c112ae67e161",
      :stockTickTime "1596822722618",
      :stockTickClose 143.5,
      :stockId "3ce72e93-037a-4500-a0be-9f0c517a5ead",
      :stockName "Cruel Prey"}]]}}}
```


### Subscribe to stock ticks
lein test :only beatthemarket.handler.http.integration.standard-workflow-test/stream-stock-ticks-test

Create a game.
```
# client >

{:id 987,
 :type :start,
 :payload
 {:query
  "mutation CreateGame($gameLevel: Int!) {
                                       createGame(gameLevel: $gameLevel) {
                                         id
                                         stocks  { id name symbol }
                                       }
                                     }",
  :variables {:gameLevel 1}}}


# < server

{:type "data",
 :id 987,
 :payload
 {:data
  {:createGame
   {:id "094559fc-98c5-4efa-b64e-6e3f1a5540c2",
    :stocks
    [{:id "8fc24c6c-ea43-4085-9ff2-5a3756e388e4",
      :name "Great Doll",
      :symbol "GREA"}
     {:id "f84338c2-9db6-45f1-bc1b-76bd10311245",
      :name "Mental Graph",
      :symbol "MENT"}
     {:id "9a207140-26bc-4ae4-9a1a-4f5c588500cb",
      :name "Quantitative Loyalty",
      :symbol "QUAN"}
     {:id "81a53b29-a0de-40a2-a891-4d1e900d41cb",
      :name "Tall Photography",
      :symbol "TALL"}]}}}}
```

Start a game.
```
# client >

{:id 987,
 :type :start,
 :payload
 {:query
  "mutation StartGame($id: String!) {
                                         startGame(id: $id) {
                                           stockTickId
                                           stockTickTime
                                           stockTickClose
                                           stockId
                                           stockName
                                         }
                                       }",
  :variables {:id "094559fc-98c5-4efa-b64e-6e3f1a5540c2"}}}


# < server

{:type "complete", :id 987}
{:type "data", :id 987, :payload {:data {:startGame []}}}
```

Subscribe to StockTicks.
```
# client >

{:id 987,
 :type :start,
 :payload
 {:query
  "subscription StockTicks($gameId: String!) {
                                           stockTicks(gameId: $gameId) {
                                             stockTickId
                                             stockTickTime
                                             stockTickClose
                                             stockId
                                             stockName
                                         }
                                       }",
  :variables {:gameId "094559fc-98c5-4efa-b64e-6e3f1a5540c2"}}}
{:type "complete", :id 987}


# < server

{:type "data",
 :id 987,
 :payload
 {:data
  {:stockTicks
   [{:stockTickId "b1f79944-4b7b-4127-a7ec-a15e03c4e3c7",
     :stockTickTime "1596822767686",
     :stockTickClose 138.07000732421875,
     :stockId "8fc24c6c-ea43-4085-9ff2-5a3756e388e4",
     :stockName "Great Doll"}
    {:stockTickId "5306119e-2b67-4797-967e-928950f1dd59",
     :stockTickTime "1596822767690",
     :stockTickClose 130.8699951171875,
     :stockId "f84338c2-9db6-45f1-bc1b-76bd10311245",
     :stockName "Mental Graph"}
    {:stockTickId "ca78c44e-e104-4864-b4ff-95a0f1bc602c",
     :stockTickTime "1596822767693",
     :stockTickClose 109.31999969482422,
     :stockId "9a207140-26bc-4ae4-9a1a-4f5c588500cb",
     :stockName "Quantitative Loyalty"}
    {:stockTickId "27853a94-b0aa-47bf-83bc-e4b592db899f",
     :stockTickTime "1596822767697",
     :stockTickClose 119.63999938964844,
     :stockId "81a53b29-a0de-40a2-a891-4d1e900d41cb",
     :stockName "Tall Photography"}]}}}
```

# BUY and SELL Stocks
lein test :only beatthemarket.handler.http.integration.standard-workflow-test/sell-stock-test

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
   {:id "db7a7f78-bd72-4cbe-a302-5872f5ea90fc",
    :stocks
    [{:id "45aaca9b-107a-4c7d-85a0-45415cc1e382",
      :name "Conservative Decoration",
      :symbol "CONS"}
     {:id "2d5752eb-1e79-4ebe-9cdf-39402f7a5a5a",
      :name "Finished Format",
      :symbol "FINI"}
     {:id "367749db-18ac-4fe0-864f-28af4dd91cce",
      :name "Legislative Koran",
      :symbol "LEGI"}
     {:id "a2cc8dfc-c9ab-4c2d-a51a-3cc5a13991e1",
      :name "Plain Outside",
      :symbol "PLAI"}]}}}}
      

# client >

{:id 988,
 :type :start,
 :payload
 {:query
  "mutation StartGame($id: String!) {
                                         startGame(id: $id) {
                                           stockTickId
                                           stockTickTime
                                           stockTickClose
                                           stockId
                                           stockName
                                         }
                                       }",
  :variables {:id "db7a7f78-bd72-4cbe-a302-5872f5ea90fc"}}}


# < server

{:type "complete", :id 987}
{:type "data", :id 988, :payload {:data {:startGame []}}}


# client >

{:id 989,
 :type :start,
 :payload
 {:query
  "subscription StockTicks($gameId: String!) {
                                           stockTicks(gameId: $gameId) {
                                             stockTickId
                                             stockTickTime
                                             stockTickClose
                                             stockId
                                             stockName
                                         }
                                       }",
  :variables {:gameId "db7a7f78-bd72-4cbe-a302-5872f5ea90fc"}}}
{:type "complete", :id 988}


# < server

{:type "data",
 :id 989,
 :payload
 {:data
  {:stockTicks
   [{:stockTickId "ce0cefdd-39fd-495d-bdb9-5c0a38cd7ed3",
     :stockTickTime "1596822821672",
     :stockTickClose 98.56999969482422,
     :stockId "45aaca9b-107a-4c7d-85a0-45415cc1e382",
     :stockName "Conservative Decoration"}
    {:stockTickId "fab1b2cb-3884-4a19-988e-4b90550531ba",
     :stockTickTime "1596822821676",
     :stockTickClose 86.11000061035156,
     :stockId "2d5752eb-1e79-4ebe-9cdf-39402f7a5a5a",
     :stockName "Finished Format"}
    {:stockTickId "f336207e-885e-4ec5-88f5-abbf0ef76283",
     :stockTickTime "1596822821679",
     :stockTickClose 86.41000366210938,
     :stockId "367749db-18ac-4fe0-864f-28af4dd91cce",
     :stockName "Legislative Koran"}
    {:stockTickId "769818b6-2803-413d-a2f0-39f65850da69",
     :stockTickTime "1596822821682",
     :stockTickClose 108.27999877929688,
     :stockId "a2cc8dfc-c9ab-4c2d-a51a-3cc5a13991e1",
     :stockName "Plain Outside"}]}}}


# client >

{:id 990,
 :type :start,
 :payload
 {:query
  "mutation BuyStock($input: BuyStock!) {
                                           buyStock(input: $input) {
                                             message
                                           }
                                         }",
  :variables
  {:input
   {:gameId "db7a7f78-bd72-4cbe-a302-5872f5ea90fc",
    :stockId "45aaca9b-107a-4c7d-85a0-45415cc1e382",
    :stockAmount 100,
    :tickId "ce0cefdd-39fd-495d-bdb9-5c0a38cd7ed3",
    :tickTime -905012440,
    :tickPrice 98.56999969482422}}}}


# < server

{:type "data", :id 990, :payload {:data {:buyStock {:message "Ack"}}}}
{:type "complete", :id 990}


# client >

{:id 991,
 :type :start,
 :payload
 {:query
  "mutation SellStock($input: SellStock!) {
                                           sellStock(input: $input) {
                                             message
                                           }
                                         }",
  :variables
  {:input
   {:gameId "db7a7f78-bd72-4cbe-a302-5872f5ea90fc",
    :stockId "45aaca9b-107a-4c7d-85a0-45415cc1e382",
    :stockAmount 100,
    :tickId "ce0cefdd-39fd-495d-bdb9-5c0a38cd7ed3",
    :tickTime -905012440,
    :tickPrice 98.56999969482422}}}}


# < server

{:type "data", :id 991, :payload {:data {:sellStock {:message "Ack"}}}}
```


### Subscribe to PortfolioUpdates
lein test :only beatthemarket.handler.http.integration.standard-workflow-test/stream-portfolio-updates-test

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
   {:id "d1684d51-cb54-4e9b-a09d-076cdea6f6c9",
    :stocks
    [{:id "44f11c67-8fa1-49f7-ba27-9c572001adc2",
      :name "Unaware Corner",
      :symbol "UNAW"}
     {:id "a56637ec-18ea-4fc3-ba19-ef9154214061",
      :name "Blind Fan",
      :symbol "BLIN"}
     {:id "61eee438-c07d-446f-8303-cc3d4e819994",
      :name "Eloquent Injection",
      :symbol "ELOQ"}
     {:id "90fed113-050d-457d-ab65-cd7c995ffda8",
      :name "Important Nationalist",
      :symbol "IMPO"}]}}}}


# client >

{:id 988,
 :type :start,
 :payload
 {:query
  "mutation StartGame($id: String!) {
                                         startGame(id: $id) {
                                           stockTickId
                                           stockTickTime
                                           stockTickClose
                                           stockId
                                           stockName
                                         }
                                       }",
  :variables {:id "d1684d51-cb54-4e9b-a09d-076cdea6f6c9"}}}


# < server

{:type "complete", :id 987}
{:type "data", :id 988, :payload {:data {:startGame []}}}


# client >

{:id 989,
 :type :start,
 :payload
 {:query
  "subscription StockTicks($gameId: String!) {
                                           stockTicks(gameId: $gameId) {
                                             stockTickId
                                             stockTickTime
                                             stockTickClose
                                             stockId
                                             stockName
                                         }
                                       }",
  :variables {:gameId "d1684d51-cb54-4e9b-a09d-076cdea6f6c9"}}}

{:type "complete", :id 988}


# < server

{:type "data",
 :id 989,
 :payload
 {:data
  {:stockTicks
   [{:stockTickId "317ca66f-c371-4d1f-92a0-e009622a6a1b",
     :stockTickTime "1596822871736",
     :stockTickClose 89.7300033569336,
     :stockId "44f11c67-8fa1-49f7-ba27-9c572001adc2",
     :stockName "Unaware Corner"}
    {:stockTickId "38e33ec0-240f-4d1a-b241-ce7314038bc8",
     :stockTickTime "1596822871741",
     :stockTickClose 135.99000549316406,
     :stockId "a56637ec-18ea-4fc3-ba19-ef9154214061",
     :stockName "Blind Fan"}
    {:stockTickId "7b005e8e-5a87-4c9d-b968-973f58b8472c",
     :stockTickTime "1596822871744",
     :stockTickClose 108.61000061035156,
     :stockId "61eee438-c07d-446f-8303-cc3d4e819994",
     :stockName "Eloquent Injection"}
    {:stockTickId "c09ab328-eaed-49ad-9d11-30c6b2da69ae",
     :stockTickTime "1596822871748",
     :stockTickClose 129.77000427246094,
     :stockId "90fed113-050d-457d-ab65-cd7c995ffda8",
     :stockName "Important Nationalist"}]}}}



# client >

{:id 990,
 :type :start,
 :payload
 {:query
  "mutation BuyStock($input: BuyStock!) {
                                         buyStock(input: $input) {
                                           message
                                         }
                                     }",
  :variables
  {:input
   {:gameId "d1684d51-cb54-4e9b-a09d-076cdea6f6c9",
    :stockId "44f11c67-8fa1-49f7-ba27-9c572001adc2",
    :stockAmount 100,
    :tickId "317ca66f-c371-4d1f-92a0-e009622a6a1b",
    :tickTime -904962376,
    :tickPrice 89.7300033569336}}}}


# < server

{:type "data", :id 990, :payload {:data {:buyStock {:message "Ack"}}}}
{:type "complete", :id 990}


# client >

{:id 991,
 :type :start,
 :payload
 {:query
  "subscription PortfolioUpdates($gameId: String!) {
                                         portfolioUpdates(gameId: $gameId) {
                                           message
                                         }
                                       }",
  :variables {:gameId "d1684d51-cb54-4e9b-a09d-076cdea6f6c9"}}}


# < server

{:type "data",
 :id 989,
 :payload
 {:data
  {:stockTicks
   [{:stockTickId "9fff53fc-5a15-4956-9a45-9d51abea5a16",
     :stockTickTime "1596822872736",
     :stockTickClose 89.30000305175781,
     :stockId "44f11c67-8fa1-49f7-ba27-9c572001adc2",
     :stockName "Unaware Corner"}
    {:stockTickId "a86184d9-4733-4822-b25c-da665b5ad922",
     :stockTickTime "1596822872741",
     :stockTickClose 135.5800018310547,
     :stockId "a56637ec-18ea-4fc3-ba19-ef9154214061",
     :stockName "Blind Fan"}
    {:stockTickId "d574ff57-e5a6-426d-8f99-103a5b027ef8",
     :stockTickTime "1596822872744",
     :stockTickClose 108.47000122070312,
     :stockId "61eee438-c07d-446f-8303-cc3d4e819994",
     :stockName "Eloquent Injection"}
    {:stockTickId "dd03a49c-d4e3-443c-85c1-1b73ea61908d",
     :stockTickTime "1596822872748",
     :stockTickClose 129.6699981689453,
     :stockId "90fed113-050d-457d-ab65-cd7c995ffda8",
     :stockName "Important Nationalist"}]}}}

{:type "error",
 :id 991,
 :payload
 {:message "Cannot query field `message' on type `PortfolioUpdate'."}}
```

### Query a User's Profit / Loss 
lein test :only beatthemarket.handler.http.integration.standard-workflow-test/user-market-profit-loss-test

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
   {:id "3a6a48fe-c3a7-4172-82b8-6ad6ed6cc1a2",
    :stocks
    [{:id "dbc0a5c1-db43-4467-a7c2-260288225d12",
      :name "Surprising Column",
      :symbol "SURP"}
     {:id "16319c5e-3569-4d67-b175-14047446db9f",
      :name "Ample Element",
      :symbol "AMPL"}
     {:id "8fb591b1-241b-4d68-8971-5f33230e8e2f",
      :name "Deep Hell",
      :symbol "DEEP"}
     {:id "34b82711-a430-47ed-8d0c-db65f0ec4034",
      :name "Graphic Meeting",
      :symbol "GRAP"}]}}}}


# client >

{:id 988,
 :type :start,
 :payload
 {:query
  "mutation StartGame($id: String!) {
                                         startGame(id: $id) {
                                           stockTickId
                                           stockTickTime
                                           stockTickClose
                                           stockId
                                           stockName
                                         }
                                       }",
  :variables {:id "3a6a48fe-c3a7-4172-82b8-6ad6ed6cc1a2"}}}


# < server

{:type "complete", :id 987}
{:type "data", :id 988, :payload {:data {:startGame []}}}


# client >

{:id 989,
 :type :start,
 :payload
 {:query
  "subscription StockTicks($gameId: String!) {
                                           stockTicks(gameId: $gameId) {
                                             stockTickId
                                             stockTickTime
                                             stockTickClose
                                             stockId
                                             stockName
                                         }
                                       }",
  :variables {:gameId "3a6a48fe-c3a7-4172-82b8-6ad6ed6cc1a2"}}}


# < server

{:type "complete", :id 988}
{:type "data",
 :id 989,
 :payload
 {:data
  {:stockTicks
   [{:stockTickId "35fe5561-f366-444c-b30e-3fdfcfd16f10",
     :stockTickTime "1596822926327",
     :stockTickClose 72.91999816894531,
     :stockId "dbc0a5c1-db43-4467-a7c2-260288225d12",
     :stockName "Surprising Column"}
    {:stockTickId "24a15893-2edb-4ba4-a256-d9569165c55c",
     :stockTickTime "1596822926332",
     :stockTickClose 136.50999450683594,
     :stockId "16319c5e-3569-4d67-b175-14047446db9f",
     :stockName "Ample Element"}
    {:stockTickId "c7c2e0e4-b0b6-43eb-989d-444df92d7b58",
     :stockTickTime "1596822926335",
     :stockTickClose 139.72999572753906,
     :stockId "8fb591b1-241b-4d68-8971-5f33230e8e2f",
     :stockName "Deep Hell"}
    {:stockTickId "a280d8a2-a24a-4cf1-a3db-4296d1433a0b",
     :stockTickTime "1596822926339",
     :stockTickClose 148.55999755859375,
     :stockId "34b82711-a430-47ed-8d0c-db65f0ec4034",
     :stockName "Graphic Meeting"}]}}}



# client >

{:id 990,
 :type :start,
 :payload
 {:query
  "mutation BuyStock($input: BuyStock!) {
                                         buyStock(input: $input) {
                                           message
                                         }
                                     }",
  :variables
  {:input
   {:gameId "3a6a48fe-c3a7-4172-82b8-6ad6ed6cc1a2",
    :stockId "dbc0a5c1-db43-4467-a7c2-260288225d12",
    :stockAmount 100,
    :tickId "35fe5561-f366-444c-b30e-3fdfcfd16f10",
    :tickTime -904907785,
    :tickPrice 72.91999816894531}}}}


# < server

{:type "data", :id 990, :payload {:data {:buyStock {:message "Ack"}}}}
{:type "complete", :id 990}


# client >

{:id 991,
 :type :start,
 :payload
 {:query
  "query UserPersonalProfitLoss($email: String!, $gameId: String!) {
                                       userPersonalProfitLoss(email: $email, gameId: $gameId) {
                                         profitLoss
                                         stockId
                                         gameId
                                         profitLossType
                                       }
                                     }",
  :variables
  {:email "twashing@gmail.com",
   :gameId "3a6a48fe-c3a7-4172-82b8-6ad6ed6cc1a2"}}}


# < server

{:type "data",
 :id 991,
 :payload
 {:data
  {:userPersonalProfitLoss
   [{:profitLoss 0.0,
     :stockId "dbc0a5c1-db43-4467-a7c2-260288225d12",
     :gameId "3a6a48fe-c3a7-4172-82b8-6ad6ed6cc1a2",
     :profitLossType "realized"}
    {:profitLoss 0.0,
     :stockId "dbc0a5c1-db43-4467-a7c2-260288225d12",
     :gameId "3a6a48fe-c3a7-4172-82b8-6ad6ed6cc1a2",
     :profitLossType "running"}]}}}

```
