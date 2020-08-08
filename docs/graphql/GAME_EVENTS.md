
## USE CASES


### Subscribe to GameEvents

* ControlEvent `[:pause :resume :exit]`
* LevelStatus `[:win :lose]`
* LevelTimer 

lein test :only beatthemarket.handler.http.integration.game-events-test/game-events-control-events-test
```

# client >

{:id 987,
 :type :start,
 :payload
 {:query
  "mutation CreateGame($gameLevel: String!) {
                                     createGame(gameLevel: $gameLevel) {
                                       id
                                       stocks { id name symbol }
                                     }
                                   }",
  :variables {:gameLevel "one"}}}


# < server

{:type "data",
 :id 987,
 :payload
 {:data
  {:createGame
   {:id "92c2e991-612b-4458-9692-cd41be84444d",
    :stocks
    [{:id "256a82e7-56a7-4b27-8cbb-dc25d51b4286",
      :name "Fat Anxiety",
      :symbol "FAT "}
     {:id "5256c661-2807-43b4-83ab-2bc7d356e09d",
      :name "Last Class",
      :symbol "LAST"}
     {:id "1bab65b6-a5a7-4760-a496-8622230b500b",
      :name "Permanent Drink",
      :symbol "PERM"}
     {:id "f9bd3d20-4bdd-43af-addc-e7a13558ed25",
      :name "Solid Gun",
      :symbol "SOLI"}]}}}}


# client >

{:id 992,
 :type :start,
 :payload
 {:query
  "subscription GameEvents($gameId: String!) {
                                       gameEvents(gameId: $gameId) {
                                         ... on ControlEvent {
                                           event
                                           gameId
                                         }
                                         ... on LevelStatus {
                                           event
                                           gameId
                                           profitLoss
                                           level
                                         }
                                         ... on LevelTimer {
                                           gameId
                                           level
                                           minutesRemaining
                                           secondsRemaining
                                         }
                                       }
                                     }",
  :variables {:gameId "92c2e991-612b-4458-9692-cd41be84444d"}}}


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
  :variables {:id "92c2e991-612b-4458-9692-cd41be84444d"}}}


# < server

{:type "complete", :id 987}
{:type "data", :id 988, :payload {:data {:startGame []}}}


# client >

{:id 989,
 :type :start,
 :payload
 {:query
  "mutation PauseGame($gameId: String!) {
                                       pauseGame(gameId: $gameId) {
                                         event
                                         gameId
                                       }
                                     }",
  :variables {:gameId "92c2e991-612b-4458-9692-cd41be84444d"}}}


# < server

{:type "complete", :id 988}
{:type "data",
 :id 989,
 :payload
 {:data
  {:pauseGame
   {:event "pause", :gameId "92c2e991-612b-4458-9692-cd41be84444d"}}}}

{:type "complete", :id 989}

{:type "data",
 :id 992,
 :payload
 {:data
  {:gameEvents
   {:event "pause", :gameId "92c2e991-612b-4458-9692-cd41be84444d"}}}}


# client >

{:id 990,
 :type :start,
 :payload
 {:query
  "mutation ResumeGame($gameId: String!) {
                                       resumeGame(gameId: $gameId) {
                                         event
                                         gameId
                                       }
                                     }",
  :variables {:gameId "92c2e991-612b-4458-9692-cd41be84444d"}}}


# < server

{:type "data",
 :id 990,
 :payload
 {:data
  {:resumeGame
   {:event "resume", :gameId "92c2e991-612b-4458-9692-cd41be84444d"}}}}
{:type "complete", :id 990}


# < server

{:type "data",
 :id 992,
 :payload
 {:data
  {:gameEvents
   {:event "resume", :gameId "92c2e991-612b-4458-9692-cd41be84444d"}}}}


# client >

{:id 993,
 :type :start,
 :payload
 {:query
  "mutation exitGame($gameId: String!) {
                                       exitGame(gameId: $gameId) {
                                         event
                                         gameId
                                       }
                                     }",
  :variables {:gameId "92c2e991-612b-4458-9692-cd41be84444d"}}}



# < server

{:type "data",
 :id 993,
 :payload
 {:data
  {:exitGame
   {:event "exit", :gameId "92c2e991-612b-4458-9692-cd41be84444d"}}}}
```

### An example of LevelTimer events

lein test :only beatthemarket.handler.http.integration.game-events-test/game-events-level-timer-test
```

# client >

{:id 987,
 :type :start,
 :payload
 {:query
  "mutation CreateGame($gameLevel: String!) {
                                     createGame(gameLevel: $gameLevel) {
                                       id
                                       stocks { id name symbol }
                                     }
                                   }",
  :variables {:gameLevel "one"}}}


# < server

{:type "data",
 :id 987,
 :payload
 {:data
  {:createGame
   {:id "0b3ba8e8-5045-40d8-99fb-666aeecc3c32",
    :stocks
    [{:id "422a40c7-c63b-452a-8a24-bfd78891d86a",
      :name "Qualified Academy",
      :symbol "QUAL"}
     {:id "7ef513b1-dd80-4ca5-a60f-1820c5f820a6",
      :name "Talkative Castle",
      :symbol "TALK"}
     {:id "4a8635ee-b265-4273-b707-fe4166346770",
      :name "Appropriate Detector",
      :symbol "APPR"}
     {:id "3102d400-badd-4430-95bd-bde07e4a0ad5",
      :name "Desirable Gallery",
      :symbol "DESI"}]}}}}


# client >

{:id 992,
 :type :start,
 :payload
 {:query
  "subscription GameEvents($gameId: String!) {
                                       gameEvents(gameId: $gameId) {
                                         ... on ControlEvent {
                                           event
                                           gameId
                                         }
                                         ... on LevelStatus {
                                           event
                                           gameId
                                           profitLoss
                                           level
                                         }
                                         ... on LevelTimer {
                                           gameId
                                           level
                                           minutesRemaining
                                           secondsRemaining
                                         }
                                       }
                                     }",
  :variables {:gameId "0b3ba8e8-5045-40d8-99fb-666aeecc3c32"}}}


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
  :variables {:id "0b3ba8e8-5045-40d8-99fb-666aeecc3c32"}}}


# < server

{:type "complete", :id 987}

{:type "data", :id 988, :payload {:data {:startGame []}}}

{:type "complete", :id 988}

{:type "data",
 :id 992,
 :payload
 {:data
  {:gameEvents
   {:gameId "0b3ba8e8-5045-40d8-99fb-666aeecc3c32",
    :level "one",
    :minutesRemaining 5,
    :secondsRemaining 0}}}}


# client >

{:id 993,
 :type :start,
 :payload
 {:query
  "mutation exitGame($gameId: String!) {
                                       exitGame(gameId: $gameId) {
                                         event
                                         gameId
                                       }
                                     }",
  :variables {:gameId "0b3ba8e8-5045-40d8-99fb-666aeecc3c32"}}}
```
