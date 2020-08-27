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

## Subscriptions

The kinds of subscriptions are itemized below

* :stockTicks; a list of [:StockTick] (A)
* :portfolioUpdates; a list of [:PortfolioUpdate] (B)
* :gameEvents; a :GameEvent (C)


A.
```
:StockTick
{:stockTickId
 :stockTickTime
 :stockTickClose
 :stockId
 :stockName}
```

B.
```
:PortfolioUpdate has one of these types #{:ProfitLoss :AccountBalance}

:AccountBalance
{:id
 :name
 :balance
 :counterParty
 :amount}

:ProfitLoss
{:profitLoss
 :stockId
 :gameId
 :profitLossType}
```

C.
```
:GameEvent has one of these types #{:ControlEvent :LevelStatus :LevelTimer}

:ControlEvent
{:event #{:pause :resume :exit}
 :gameId}

:LevelStatus
{:event #{:win :lose}
 :gameId
 :profitLoss
 :level}

:LevelTimer
{:gameId
 :level
 :minutesRemaining
 :secondsRemaining}
```

### Examples

For example GraphQL use case call and responses, see the `docs/graphql` [directory](docs/graphql).
