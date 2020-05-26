# Using WebSockets in Jetty

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

You'll notice the log message in Clojure REPL

4. Send a message to the client and close with `(service/send-and-close!)`

## Component

[Integrant](https://github.com/weavejester/integrant) Component system is now installed

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


License
-------
Copyright 2014-2019 Cognitect, Inc.

The use and distribution terms for this software are covered by the
Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
which can be found in the file epl-v10.html at the root of this distribution.

By using this software in any fashion, you are agreeing to be bound by
the terms of this license.

You must not remove this notice, or any other, from this software.
