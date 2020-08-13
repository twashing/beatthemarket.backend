(ns beatthemarket.datasource.oscillating-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [beatthemarket.datasource.oscillating :as datasource.oscillating]
            [beatthemarket.datasource.core :as datasource.core]))


(defspec repeatable-generate-oscillating-sequence-test
  100
  (prop/for-all [oscillating-sequences (gen/let [seed gen/nat
                                                 take-amount (gen/return 100)
                                                 compare-amount (gen/return 20)]

                                         (->> (repeatedly (fn []
                                                            (->> datasource.core/beta-configurations
                                                                 (datasource.oscillating/generate-oscillating-sequence seed)
                                                                 (take take-amount))))
                                              (take compare-amount)))]

                  (apply = oscillating-sequences)))
