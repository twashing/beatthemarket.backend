(ns beatthemarket.datasource-test
  (:require [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [beatthemarket.datasource :as datasource]
            [beatthemarket.datasource.core :as datasource.core]))


(defspec repeatable-generate-combined-sequences-test
  100
  (prop/for-all [data-sequences (gen/let [seed gen/nat
                                          take-amount (gen/return 100)
                                          compare-amount (gen/return 20)]

                                  (->> (repeatedly (fn []
                                                     (->> datasource.core/beta-configurations
                                                          (datasource/->data-sequences seed)
                                                          (take take-amount))))
                                       (take compare-amount)))]

                (let [sine-sequences (map :datasource.sine/generate-sine-sequence data-sequences)
                      cosine-sequences (map :datasource.sine/generate-cosine-sequence data-sequences)
                      oscillating-sequence (map :datasource.oscillating/generate-oscillating-sequence data-sequences)]

                  (and (apply = sine-sequences)
                       (apply = cosine-sequences)
                       (apply = oscillating-sequence)))))
