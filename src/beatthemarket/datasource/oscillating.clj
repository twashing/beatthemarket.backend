(ns beatthemarket.datasource.oscillating
  (:require [beatthemarket.datasource.core :as datasource.core]))


(defn price-swing-occurence-sequence [chunk-multiple beta-distribution]
  (->> (repeatedly #(.sample beta-distribution))
       (map #(* % chunk-multiple))
       (map #(Math/round %))))

(defn price-change-sequence [beta-distribution]
  (->> (repeatedly #(.sample beta-distribution))
       (map #(if (> % 0.5) + -))))

(defn generate-price-directions [length-of-price-direction price-change-inputs]
  (->> (price-change-sequence (apply datasource.core/->beta-distribution price-change-inputs))
       (take length-of-price-direction)))

(defn generate-price-changes
  "0 - 0.3     / high
   0.31 - 0.45 / low
   0.46 - 0.6  / midpoint
   0.61 - 1    / bigswings"
  [beta-left-leaning
   beta-highend beta-lowend beta-midpoint beta-bigswings]

  (->> (repeatedly #(.sample beta-left-leaning))
       (map #(cond
               (< % 0.31) (.sample beta-highend)
               (< % 0.46) (.sample beta-lowend)
               (< % 0.61) (.sample beta-midpoint)
               :else      (.sample beta-bigswings)))))

(defn generate-oscillating-sequence [beta-configurations]

  (let [;; BETA Distributions

        ;; > BIG SWINGS + MIDPOINT
        beta-bigswings
        (->> beta-configurations
             :bigswings vals
             (apply datasource.core/->beta-distribution))

        beta-midpoint
        (->> beta-configurations
             :midpoint vals
             (apply datasource.core/->beta-distribution))


        ;; > LEFT LEANING
        beta-left-leaning
        (->> beta-configurations
             :left-leaning vals
             (apply datasource.core/->beta-distribution))


        ;; > HIGH END + LOW END
        beta-highend
        (->> beta-configurations
             :highend vals
             (apply datasource.core/->beta-distribution))

        beta-lowend
        (->> beta-configurations
             :lowend vals
             (apply datasource.core/->beta-distribution))


        alternating-price-change-inputs
        (->> beta-configurations
             :alternating-price-changes vals
             (iterate reverse))

        chunk-multiple 50
        price-swings   (price-swing-occurence-sequence chunk-multiple beta-midpoint)

        price-directions (->> (map generate-price-directions
                                   price-swings
                                   alternating-price-change-inputs)
                              (apply concat))

        price-changes (generate-price-changes beta-left-leaning
                                              beta-highend beta-lowend
                                              beta-midpoint beta-bigswings)

        price-change-partials (map (fn [price-direction price-change]
                                     #(price-direction % price-change))
                                   price-directions
                                   price-changes)

        initial-price (datasource.core/random-double-in-range 15 35)]



    #_(let [xaxis (range)
            yaxis (reductions (fn [acc price-change-partial]
                                (price-change-partial acc))
                              initial-price
                              price-change-partials)]

        (->> (interleave xaxis yaxis)
             (partition 2)))
    (reductions (fn [acc price-change-partial]
                  (price-change-partial acc))
                initial-price
                price-change-partials)))
