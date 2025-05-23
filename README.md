# BeatTheMarket.Backend


This sample illustrates how to use WebSockets with Pedestal and Jetty.


## Getting started

1. Start a REPL with `lein repl`
2. For development, use Integrant's Component system (below). Or the old way: Start the server within the REPL with `(def serv (run-dev))`
3. In your browser's JavaScript console

```javascript
w = new WebSocket("ws://localhost:8080/ws")
w.onmessage = function(e) { console.log(e.data); }
w.onclose = function(e) {  console.log("The connection to the server has closed."); }
w.send("Hello from the Client!");
```

You'll notice a corresponding log message in the Clojure REPL.

4. Send a message to the client and close with `(service/send-and-close!)`


## Running

```
GOOGLE_APPLICATION_CREDENTIALS="./beatthemarket-c13f8-firebase-adminsdk-k3cwr-5129bb442c.json" \
AWS_ACCESS_KEY_ID=<your-access-key-id> \
AWS_SECRET_ACCESS_KEY=<your-secret-access-key> \
lein repl

AWS_ACCESS_KEY_ID=<your-access-key-id> \
AWS_SECRET_ACCESS_KEY=<your-secret-access-key> \
lein run -p development

```

## Component

Beatthemarket uses the [Integrant](https://github.com/weavejester/integrant) Component system.

Reloaded Workflow uses [weavejester/integrant-repl](https://github.com/weavejester/integrant-repl)

```
;; Start with
(prep)
(init)

;; Or just
(go)

;; These functions are also available for control
(halt)
(reset)
```

## Configuration

To configure logging see config/logback.xml. By default, the app logs to stdout and logs/.
To learn more about configuring Logback, read its [documentation](http://logback.qos.ch/documentation.html).


## Links
* [Other Pedestal examples](http://pedestal.io/samples)


## Deployment

First make a bundle for ElasticBeanstalk.
```
lein uberjar &&

export BEATTHEMARKET_VERSION=$(date +%Y%m%d_%H%M%S)_$(git rev-parse --short HEAD) &&
mkdir -p /tmp/workspace/{build,artifact}/beatthemarket &&
cp target/beatthemarket-*-standalone.jar /tmp/workspace/build/beatthemarket/beatthemarket-standalone.jar &&
cp Procfile /tmp/workspace/build/beatthemarket/ &&

zip -j target/$BEATTHEMARKET_VERSION /tmp/workspace/build/beatthemarket/beatthemarket-standalone.jar &&
zip -g target/$BEATTHEMARKET_VERSION \
.ebextensions/options.config \
Procfile
```

## Datomic

[Connect to Datomic Cloud's access gateway](https://docs.datomic.com/cloud/getting-started/get-connected.html#access-gateway)

```
export AWS_ACCESS_KEY_ID=<key>
export AWS_SECRET_ACCESS_KEY=<secret-key>

datomic-cli/datomic client access <system-name>
```

Locally, you can create or delete a database like so (see `dev/user.clj`).

```
(state.core/set-prep :dev-local)
(state.core/init-components)

(persistence.datomic/create-database
  (-> integrant.repl.state/system :persistence/datomic :opts :client)
  (-> integrant.repl.state/system :persistence/datomic :opts :db-name))

(persistence.datomic/delete-database
  (-> integrant.repl.state/system :persistence/datomic :opts :client)
  (-> integrant.repl.state/system :persistence/datomic :opts :db-name))
```

## Code Hygiene


> Automated Tools

* [Clj-kondo](https://github.com/borkdude/clj-kondo) linter
```
clj-kondo --lint src
```

* [yagni](https://github.com/venantius/yagni) dead code checker
```
lein yagni
```

* [lein-kibit](https://github.com/jonase/kibit) idiom checker
```
lein kibit
```

* [lein-nvm](https://github.com/rm-hull/lein-nvd) National Vulnerability Database dependency-checker plugin for Leiningen
```
lein nvd check
```


> REPL

* [Orchestra](https://github.com/jeaye/orchestra). Every call to a spec'd function will have its arguments, return value, and :fn spec validated
* __[Slamhound](https://github.com/technomancy/slamhound)__ rips your namespace form apart and reconstructs it (demo [here](https://vimeo.com/80650659)).



License
-------
Copyright 2014-2019 Cognitect, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file epl-v10.html at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.
