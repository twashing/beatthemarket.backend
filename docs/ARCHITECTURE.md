
## Game Architecture

This project uses a GraphQL interface to communicate the frontend with the backend.


### GraphQL libs

These libraries should be websocket-enabled. A websocket connection will be used to stream real-time stock and position updates to the client. 

* [Apollo](https://www.apollographql.com/)
* [Lacinia](https://github.com/walmartlabs/lacinia) + [Lacinia-pedestal](https://github.com/walmartlabs/lacinia-pedestal)


### Use Cases

* User plays a game
```
Game is bound to user
Game gets stored, retrieved
```

* User buys a stock (test w/ sine wave)
```
ensure sufficient cash
Portfolio value goes up + down
Profit (and Loss) goes up + down
```

* User sells a stock 
```
Ensure he has enough shares
Locks in proft / loss
```


### System Entities
```
User
Portfolio
Stock
Shares

Bookkeeping system to book trades
  Book (Portfolio)
  Journal
  TEntry
  Account(Cash, Stock, Revenue (profit from trade), Expense (paying for stock))
```


### GraphQL API

**WS Stream**

```
<- [Stock] Stock subscription updates (max 4 concurrently)
<- [Stock] Available stocks, with their volatilities

<- [Market] Market subscription updates (max 4 concurrently)
<- [Market] Available stocks, with their volatilities
<- [!] Market is always runnings

<- Portfolio value change
<- RT profit / loss
<- [~] Trade confirmation

-> buy stock (name, shares, price)
-> sell stock (name, shares, price)
-> pause game 
-> resume game
-> close out all positions
-> leave market play (closes out all positions**
```


**HTTP call**

```
GET Portfolio positions + value
```


### Constraints

* Players can have positions in a maximum of 4 stocks
