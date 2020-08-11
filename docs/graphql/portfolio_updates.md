
## USE CASES

PortfolioUpdates comprise of two types of events: ProfitLoss and AccountBalance.

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
   {:id "271ddc93-fef8-41b5-9263-d49a771cbc12",
    :stocks
    [{:id "318e063e-dce8-485b-b553-e4b5e483c632",
      :name "Agile Fact",
      :symbol "AGIL"}
     {:id "33cc1345-775f-48ef-acc9-6ae09d28fa57",
      :name "Daily Infection",
      :symbol "DAIL"}
     {:id "648ff095-0532-42a1-abaf-2dd8bcd85af9",
      :name "Genetic Muscle",
      :symbol "GENE"}
     {:id "4abaec0a-6f69-4c72-ae56-e87dea0c99c6",
      :name "Manual Protein",
      :symbol "MANU"}]}}}}


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
  :variables {:id "271ddc93-fef8-41b5-9263-d49a771cbc12"}}}


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
  :variables {:gameId "271ddc93-fef8-41b5-9263-d49a771cbc12"}}}


# client >

{:id 990,
 :type :start,
 :payload
 {:query
  "subscription PortfolioUpdates($gameId: String!) {
                           portfolioUpdates(gameId: $gameId) {
                             ... on ProfitLoss {
                               profitLoss
                               stockId
                               gameId
                               profitLossType
                             }
                             ... on AccountBalance {
                               id
                               name
                               balance
                               counterParty
                               amount
                             }
                           }
                         }",
  :variables {:gameId "271ddc93-fef8-41b5-9263-d49a771cbc12"}}}


# < server

{:type "complete", :id 988}
{:type "data",
 :id 989,
 :payload
 {:data
  {:stockTicks
   [{:stockTickId "5037ecc4-b986-4ba1-aa0e-5b99f4c56cf4",
     :stockTickTime "1596911749434",
     :stockTickClose 117.12000274658203,
     :stockId "318e063e-dce8-485b-b553-e4b5e483c632",
     :stockName "Agile Fact"}
    {:stockTickId "348f4fbc-b342-4acb-bbd5-4822d77114b3",
     :stockTickTime "1596911749439",
     :stockTickClose 115.19999694824219,
     :stockId "33cc1345-775f-48ef-acc9-6ae09d28fa57",
     :stockName "Daily Infection"}
    {:stockTickId "6367241e-a92a-4e85-a457-fa91b5d46c17",
     :stockTickTime "1596911749442",
     :stockTickClose 71.30999755859375,
     :stockId "648ff095-0532-42a1-abaf-2dd8bcd85af9",
     :stockName "Genetic Muscle"}
    {:stockTickId "685db084-bc31-40b8-87d9-207b7999c384",
     :stockTickTime "1596911749446",
     :stockTickClose 104.12000274658203,
     :stockId "4abaec0a-6f69-4c72-ae56-e87dea0c99c6",
     :stockName "Manual Protein"}]}}}


# client >

{:id 991,
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
   {:gameId "271ddc93-fef8-41b5-9263-d49a771cbc12",
    :stockId "318e063e-dce8-485b-b553-e4b5e483c632",
    :stockAmount 100,
    :tickId "5037ecc4-b986-4ba1-aa0e-5b99f4c56cf4",
    :tickTime -816084678,
    :tickPrice 117.12000274658203}}}}


# < server

{:type "data", :id 991, :payload {:data {:buyStock {:message "Ack"}}}}
{:type "complete", :id 991}
{:type "data",
 :id 990,
 :payload
 {:data
  {:portfolioUpdates
   [{:profitLoss 0.0,
     :stockId "318e063e-dce8-485b-b553-e4b5e483c632",
     :gameId "271ddc93-fef8-41b5-9263-d49a771cbc12",
     :profitLossType "running"}]}}}
{:type "data",
 :id 990,
 :payload
 {:data
  {:portfolioUpdates
   [{:id "6ede2313-2085-4fdc-a421-68a5150addb4",
     :name "STOCK.Agile Fact",
     :balance 11712.0,
     :counterParty "Agile Fact",
     :amount 100}
    {:id "5343d116-2256-4ff5-a777-e8db7fd17b8a",
     :name "Cash",
     :balance 88288.0,
     :counterParty nil,
     :amount 0}
    {:id "bc535bfa-d92b-4f49-9b7c-d408b7a73203",
     :name "Equity",
     :balance 100000.0,
     :counterParty nil,
     :amount 0}]}}}

{:type "data",
 :id 989,
 :payload
 {:data
  {:stockTicks
   [{:stockTickId "7320af00-71b3-4ca6-ad6f-c1f83fc0aabb",
     :stockTickTime "1596911750434",
     :stockTickClose 116.91000366210938,
     :stockId "318e063e-dce8-485b-b553-e4b5e483c632",
     :stockName "Agile Fact"}
    {:stockTickId "663900f3-0696-4307-8040-3c4e22733bae",
     :stockTickTime "1596911750439",
     :stockTickClose 115.88999938964844,
     :stockId "33cc1345-775f-48ef-acc9-6ae09d28fa57",
     :stockName "Daily Infection"}
    {:stockTickId "fa7e4508-8aa6-48ea-8628-95db0fc72149",
     :stockTickTime "1596911750442",
     :stockTickClose 71.27999877929688,
     :stockId "648ff095-0532-42a1-abaf-2dd8bcd85af9",
     :stockName "Genetic Muscle"}
    {:stockTickId "6926c386-b5b4-4242-ba62-51ab7af6b4e9",
     :stockTickTime "1596911750446",
     :stockTickClose 103.4000015258789,
     :stockId "4abaec0a-6f69-4c72-ae56-e87dea0c99c6",
     :stockName "Manual Protein"}]}}}
```
