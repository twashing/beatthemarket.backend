(ns beatthemarket.datasource.polynomial)


#_(defn polynomial [a b c x]
    (-> (- (* a (Math/pow x 2))
           (* b (Math/pow x 1)))
        (- (* c x))))

#_(defn polynomial-xintercept [x]
    (polynomial 2 2 3 x))

#_(defn generate-polynomial-sequence []
    (let [one           (datasource.core/randomize-vertical-dilation polynomial 0.5 2)
          two           (datasource.core/randomize-horizontal-dilation one 0.5 2)
          polyn-partial (partial two 3)

          xinterc-polyn-left  (datasource.core/find-xintercept - polynomial-xintercept)
          xinterc-polyn-right (datasource.core/find-xintercept + polynomial-xintercept)

          granularityP (datasource.core/random-double-in-range 0.1 1)
          xsequenceP   (iterate (partial + granularityP) xinterc-polyn-left)]

      (map polyn-partial xsequenceP)))

(defn polynomial [x]

  ;; +1 (x^5)
  ;; -8 (x^3)
  ;; +10 (x^1)
  ;; +6

  (+ (* 1 (Math/pow x 5))
     (* -8 (Math/pow x 3))
     (* 10 (Math/pow x 1))
     (* 6  (Math/pow x 0))))

(defn generate-polynomial-sequence []
  (map polynomial (range -10 10)))
