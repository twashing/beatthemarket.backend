;; Copyright 2013 Relevance, Inc.
;; Copyright 2014-2019 Cognitect, Inc.
;;
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
;; which can be found in the file epl-v10.html at the root of this distribution.
;;
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;;
;; You must not remove this notice, or any other, from this software.

(defproject beatthemarket "0.5.5"
  :description "Sample of web sockets with Jetty"
  :url "http://pedestal.io/samples/index"
  :license {:name "Eclipse Public License"
            :url  "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [io.pedestal/pedestal.service "0.5.5"]
                 [org.clojure/core.async "0.4.474"]
                 [org.clojure/data.csv "1.0.0"]
                 [org.clojure/data.json "1.0.0"]
                 [org.clojure/tools.cli "1.0.194"]

                 ;; Remove this line and uncomment one of the next lines to
                 ;; use Immutant or Tomcat instead of Jetty:
                 [io.pedestal/pedestal.jetty "0.5.5"]
                 ;; [io.pedestal/pedestal.immutant "0.5.5"]
                 ;; [io.pedestal/pedestal.tomcat "0.5.5"]

                 [ch.qos.logback/logback-classic "1.2.3" :exclusions [org.slf4j/slf4j-api]]
                 [org.slf4j/jul-to-slf4j "1.7.25"]
                 [org.slf4j/jcl-over-slf4j "1.7.25"]
                 [org.slf4j/log4j-over-slf4j "1.7.25"]
                 [org.apache.commons/commons-math3 "3.6.1"]
                 [integrant "0.8.0"]
                 [aero "1.1.6"]
                 [integrant/repl "0.3.1"]
                 [clj-time "0.15.2"]
                 [nrepl "0.7.0"]
                 ;; [com.google.firebase/firebase-admin "6.13.0"]
                 [com.google.firebase/firebase-admin "6.13.0"
                  #_:exclusions
                  #_[com.google.api-client/google-api-client
                   com.google.api.grpc/proto-google-iam-v1
                   com.google.auth/google-auth-library-credentials
                   com.google.auth/google-auth-library-oauth2-http
                   com.google.cloud/google-cloud-core
                   com.google.errorprone/error_prone_annotations
                   com.google.guava/guava
                   com.google.http-client/google-http-client
                   com.google.http-client/google-http-client-jackson2
                   com.google.protobuf/protobuf-java-util
                   io.grpc/grpc-api
                   io.grpc/grpc-context
                   io.grpc/grpc-core
                   io.grpc/grpc-netty-shaded
                   io.opencensus/opencensus-api
                   io.opencensus/opencensus-contrib-http-util
                   org.apache.httpcomponents/httpclient
                   org.apache.httpcomponents/httpcore
                   org.checkerframework/checker-compat-qual
                   org.codehaus.mojo/animal-sniffer-annotations]]

                 [spootnik/unilog "0.7.25"]
                 [org.clojure/tools.logging "1.1.0"]
                 [com.walmartlabs/lacinia-pedestal "0.14.0-alpha-1"
                  :exclusions
                  [cheshire
                   commons-codec
                   commons-io
                   com.cognitect/transit-clj
                   com.cognitect/transit-java
                   com.fasterxml.jackson.dataformat/jackson-dataformat-cbor
                   com.fasterxml.jackson.dataformat/jackson-dataformat-smile
                   io.dropwizard.metrics/metrics-core
                   io.dropwizard.metrics/metrics-jmx
                   io.opentracing/opentracing-api
                   io.opentracing/opentracing-noop
                   io.opentracing/opentracing-util
                   io.pedestal/pedestal.interceptor
                   io.pedestal/pedestal.jetty
                   io.pedestal/pedestal.log
                   io.pedestal/pedestal.route
                   io.pedestal/pedestal.service
                   org.clojure/clojure
                   org.clojure/core.async
                   org.clojure/core.match
                   org.clojure/tools.reader
                   org.eclipse.jetty/jetty-alpn-server
                   org.eclipse.jetty/jetty-client
                   org.eclipse.jetty/jetty-http
                   org.eclipse.jetty/jetty-io
                   org.eclipse.jetty/jetty-security
                   org.eclipse.jetty/jetty-server
                   org.eclipse.jetty/jetty-servlet
                   org.eclipse.jetty/jetty-util
                   org.eclipse.jetty/jetty-xml
                   org.eclipse.jetty.http2/http2-common
                   org.eclipse.jetty.http2/http2-hpack
                   org.eclipse.jetty.http2/http2-server
                   org.eclipse.jetty.websocket/websocket-api
                   org.eclipse.jetty.websocket/websocket-client
                   org.eclipse.jetty.websocket/websocket-common
                   org.eclipse.jetty.websocket/websocket-server
                   org.eclipse.jetty.websocket/websocket-servlet
                   org.slf4j/slf4j-api
                   ring/ring-codec
                   ring/ring-core]]
                 [com.datomic/client-pro "0.9.57"
                  :exclusions
                  [com.cognitect/transit-clj
                   com.cognitect/transit-java
                   org.clojure/core.async
                   org.eclipse.jetty/jetty-http
                   org.eclipse.jetty/jetty-io
                   org.eclipse.jetty/jetty-client
                   org.eclipse.jetty/jetty-util]]
                 [com.rpl/specter "1.1.3"]
                 [rop "0.4.1"]]

  :min-lein-version "2.0.0"
  :resource-paths ["config", "resources"]

  :profiles {:dev     {:aliases      {"run-dev" ["trampoline" "run" "-m" "beatthemarket.handler.http.server/run-dev"]}
                       :dependencies [[clj-http "3.10.1"]
                                      [datomic-client-memdb "1.1.1"
                                       :exclusions
                                       [org.slf4j/slf4j-nop
                                        com.cognitect/transit-clj
                                        com.cognitect/transit-java
                                        org.clojure/clojure
                                        org.clojure/core.async
                                        org.clojure/core.cache
                                        org.clojure/core.memoize
                                        org.clojure/tools.analyzer
                                        org.clojure/tools.analyzer.jvm
                                        org.clojure/tools.reader
                                        org.eclipse.jetty/jetty-client
                                        org.eclipse.jetty/jetty-http
                                        org.eclipse.jetty/jetty-io
                                        org.eclipse.jetty/jetty-util]]
                                      [stylefruits/gniazdo "1.1.3"
                                       :exclusions [org.eclipse.jetty.websocket/websocket-client]]
                                      [expound "0.8.4"]]
                       :plugins      [[com.jakemccrary/lein-test-refresh "0.24.1"]
                                      [venantius/yagni "0.1.7"]
                                      [lein-kibit "0.1.8"]
                                      [lein-nvd "1.4.0"]]}
             :uberjar {:aot [beatthemarket.handler.http.server]}}
  :main ^{:skip-aot true} beatthemarket.handler.http.server)
