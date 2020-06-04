(ns beatthemarket.test-util
  (:require [integrant.repl :refer [clear go halt prep init reset reset-all]]))


(defn component-fixture [f]
  (halt)
  (go)

  (f))
