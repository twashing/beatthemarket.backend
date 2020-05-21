(ns beatthemarket.datasource.sine
  (:require [beatthemarket.datasource.core :as datasource.core]))


(defn sine
  "f(x) = a sin (b(x − c)) + d
   y = a sin (b(x − c)) + d
   y = a sin (b(x − pi/2)) + d

   d - quantifies vertical translation
   a - amplitude of the wave
   b - horizontal dilation
   c - horizontal translation"
  [a b d x]
  (- (* a
        (Math/sin (* b
                     (- x
                        (/ Math/PI 2)))))
     d))

(defn sine-xintercept [x]
  (sine 2 2 0 x))

(defn generate-sine-sequence []

  (let [ein (datasource.core/randomize-vertical-dilation sine 0.5 2.7)
        zwei (datasource.core/randomize-horizontal-dilation ein 0.3 2.7)
        sine-partial (partial zwei 0)

        xinterc-sine-left (datasource.core/find-xintercept - sine-xintercept)
        xinterc-sine-right (datasource.core/find-xintercept + sine-xintercept)

        granularityS (datasource.core/rand-double-in-range 0.1 1)
        xsequenceS (iterate (partial + granularityS) xinterc-sine-left)]

    (map sine-partial xsequenceS)))

(defn generate-cosine-sequence []
  (map #(Math/cos
          (* (Math/sqrt 3)
             %))
       (range)))
