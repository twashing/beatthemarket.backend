(ns beatthemarket.handler.http.service-test
  (:require [clojure.test :refer :all]
            [io.pedestal.test :refer :all]
            [io.pedestal.http :as http]
            [integrant.repl.state :as state]
            [beatthemarket.handler.authentication :as auth]
            [beatthemarket.handler.http.service :as service]
            [beatthemarket.test-util :as test-util]))


(use-fixtures :once (partial test-util/component-prep-fixture :test))
(use-fixtures :each
  test-util/component-fixture
  test-util/migration-fixture)
