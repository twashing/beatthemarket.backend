# Overview 

BeatTheMarket uses an AWS Datomic Cloud instance of [Datomic](https://www.datomic.com) as its database.


## Development

Development uses the [datomic-client-memdb](https://clojars.org/datomic-client-memdb) (`[datomic-client-memdb "1.1.1"]`) library to develop and run an in-memory database.

Here's an example of Setting up a local proxy to the PROD database.

```
./datomic client access -p beatthemarket-datomic beatthemarket-datomic4
```

## Production

Cloud setup is outlined in these steps

* [Setup Datomic Cloud](https://docs.datomic.com/cloud/getting-started/getting-started.html)
* [Configure the Datomic system for user access](https://docs.datomic.com/cloud/getting-started/configure-access.html)
* [Connecting to the Datomic Cloud instance from outside the Datomic VPC](https://docs.datomic.com/cloud/getting-started/get-connected.html)

Further, the Datomic Cloud instance is a CloudFormation Stack, itself composed of Compute and Storage stacks. Connection parameters can be found in the **Compute** stack `Outputs` section.


## TODO

* Migrate from [datomic-client-memdb](https://clojars.org/datomic-client-memdb) to [Local Dev and CI with dev-local](https://docs.datomic.com/cloud/dev-local.html)
