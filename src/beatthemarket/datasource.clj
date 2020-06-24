(ns beatthemarket.datasource
  (:require [clj-time.core :as t]
            [clj-time.coerce :as c]
            [beatthemarket.datasource.core :as datasource.core]
            [beatthemarket.datasource.sine :as datasource.sine]
            [beatthemarket.datasource.oscillating :as datasource.oscillating]))


;; granularity
;; => randomize granularity, or zoom of sample
;;   polynomial:  between 0.1 - 1
;;   sine:  between 0.1 - 1

;; combination
;;   (phase begin / end)
;;   (beta curve) Sine + Polynomial + Stochastic Oscillating, distributed under a Beta Curve
;; => beta distribution of a=2 b=4.1 x=0 (see: http://keisan.casio.com/exec/system/1180573226)

(defn ->data-sequences [beta-configurations]
  (let [yintercept (datasource.core/random-double-in-range 50 120)]
    {:datasource.sine/generate-sine-sequence (datasource.sine/generate-sine-sequence 1.5 2.7 yintercept)
     :datasource.sine/generate-cosine-sequence (datasource.sine/generate-cosine-sequence)
     :datasource.oscillating/generate-oscillating-sequence
     (datasource.oscillating/generate-oscillating-sequence beta-configurations)}))

(defn combine-data-sequences [& data-sequences]
  (apply (partial map +) data-sequences))

(defn ->combined-data-sequence

  ([beta-configurations]
   (let [named-sequences [:datasource.sine/generate-sine-sequence
                          :datasource.sine/generate-cosine-sequence
                          :datasource.oscillating/generate-oscillating-sequence]]

     (apply (partial ->combined-data-sequence beta-configurations) named-sequences)))

  ([beta-configurations & named-sequences]
   (->> (->data-sequences beta-configurations)
        ((apply juxt named-sequences))
        (apply combine-data-sequences))))

(defn combined-data-sequence-with-datetime
  ([start-time combined-data-sequence]
   (combined-data-sequence-with-datetime start-time 0 combined-data-sequence))
  ([start-time y-offset combined-data-sequence]
   (let [time-seq (iterate #(t/plus % (t/seconds 1)) start-time)]
     (map (fn [t y]
            [(c/to-long t)
             (Float. (format "%.2f" (+ y-offset y)))])
          time-seq
          combined-data-sequence))))


(comment

  (def data-sequences (->data-sequences datasource.core/beta-configurations))

  ;; ALL alias
  (->combined-data-sequence datasource.core/beta-configurations)
  (->combined-data-sequence datasource.core/beta-configurations :datasource.sine/generate-sine-sequence))

(comment ;; LATEST

  (->> (->combined-data-sequence datasource.core/beta-configurations)
       (combined-data-sequence-with-datetime (t/now))
       (take 50)
       clojure.pprint/pprint)



  ;; TODO Remove Polynomial if I can't make it cycle cleanly
  ;; (def c (datasource.polynomial/generate-polynomial-sequence))
  ;; (def cc (datasource.polynomial/generate-polynomial-sequence))

  ;; TODO Mechanism to control granularity of x-axis data point

  ;; Generate
  ;;   individually
  ;;   combined (pass in specfication)

  (do
    (require '[clojure.data.csv :as csv]
             '[clojure.java.io :as io])

    ;; a - amplitude of the wave (0.5 - 2.7)
    ;; b - horizontal dilation (0.3 - 2.7)
    ;; d - quantifies vertical translation


    ;; 1, 2.7, 0
    ;; 1.5, 2.7, 0
    (def a (datasource.sine/generate-sine-sequence 1.5 2.7 0))
    (def b (datasource.sine/generate-cosine-sequence))

    ;; (def c (datasource.sine/generate-sine+cosine-sequence))
    (def c (combine-data-sequences a b))

    (def d (datasource.oscillating/generate-oscillating-sequence datasource.core/beta-configurations))
    (def combined (combine-data-sequences a b d)))

  (let [length 128]

    (with-open [sine-writer (io/writer "out.sine.csv")
                cosine-writer (io/writer "out.cosine.csv")
                sine+cosine-writer (io/writer "out.sine+cosine.csv")
                oscillating-writer (io/writer "out.oscillating.csv")
                combined-writer (io/writer "out.combined.csv")]

      ;; Sine
      (let [xaxis (range)
            yaxis a]

        (->> (interleave xaxis yaxis)
             (partition 2)
             (take length)
             (csv/write-csv sine-writer)))

      ;; Cosine
      (let [xaxis (range)
            yaxis b]

        (->> (interleave xaxis yaxis)
             (partition 2)
             (take length)
             (csv/write-csv cosine-writer)))

      ;; Sine t + Cos (sqrt(3)t)
      (let [xaxis (range)
            yaxis c]

        (->> (interleave xaxis yaxis)
             (partition 2)
             (take length)
             (csv/write-csv sine+cosine-writer)))

      ;; Oscillating
      (let [xaxis (range)
            yaxis d]

        (->> (interleave xaxis yaxis)
             (partition 2)
             (take length)
             (csv/write-csv oscillating-writer)))

      ;; Combined
      (let [xaxis (range)
            yaxis combined]

        (->> (interleave xaxis yaxis)
             (partition 2)
             (take 500)
             (csv/write-csv combined-writer))))))

;; TODO Cleanup
;; put into fn
;; make configurable, which algorithms to combine
;; stop oscillating from going from i. vanishing or ii. exploding
(comment ;; Combined Price SAMPLES

  (require '[clojure.data.csv :as csv]
           '[clojure.java.io :as io])

  (with-open [output-writer (io/writer "out.combined.csv")]
    (let [length 128
          xaxis (range)

          ysines (generate-sine-sequence)
          yconsines (generate-cosine-sequence)
          yoscillatings (generate-oscillating-sequence)
          yaxis (map (fn [& args]
                       (apply + args))
                     ysines
                     yconsines
                     yoscillatings)]

      (->> (interleave xaxis yaxis)
           (partition 2)
           (take length)
           (csv/write-csv output-writer)))))

;; NOTE

;; Beta Distribution for these things
;; A. Direction of price changes
;;   Level 1: More increasing price changes than decreasing
;;   Level 5: Equal swings between increasing price changes -> decreasing
;;   Level 10: More decreasing price changes than increasing

;; B. Occurence of high vs low price changes
;;   Level 1: Big price changes
;;   Level 10: Small price changes

;; C. Number of ticks before changing beta distribution for direction of price change
;;   Level 1: Midpoint distribution for when to change price direction

;; D Start Price
(comment ;; BetaDistribution SAMPLES

  ;; Greater price swings at opposite ends
  ;; alpha = beta = 0.5

  ;; Prices within 0.25 / 0.75
  ;; alpha = beta = 2.0

  ;; Prices closer to the high end
  ;; alpha = 5 / beta = 1

  ;; Prices closer to the low end
  ;; alpha = 1 / beta = 3
  ;; alpha = 1 / beta = 5

  ;; https://en.wikipedia.org/wiki/Beta_distribution

  (def beta-distribution (BetaDistribution. 2.0 2.0))
  (def betas (repeatedly #(.sample beta-distribution)))

  (require '[clojure.data.csv :as csv]
           '[clojure.java.io :as io])

  (with-open [writer-midpoint (io/writer "out.beta-midpoint.csv")
              writer-bigswings (io/writer "out.beta-bigswings.csv")
              writer-highend (io/writer "out.beta-highend.csv")
              writer-lowend-a (io/writer "out.beta-lowend-a.csv")
              writer-lowend-b (io/writer "out.beta-lowend-b.csv")]
    (let [length 128
          xaxis (range)

          beta-midpoint (BetaDistribution. 2.0 2.0)
          yaxis (repeatedly #(.sample beta-midpoint))

          beta-bigswings (BetaDistribution. 0.5 0.5)
          ybigswings (repeatedly #(.sample beta-bigswings))

          beta-highend (BetaDistribution. 3 1)
          yhighend (repeatedly #(.sample beta-highend))

          beta-loend-a (BetaDistribution. 1 3)
          ylowend-a (repeatedly #(.sample beta-loend-a))

          beta-lowend-b (BetaDistribution. 1 5)
          ylowend-b (repeatedly #(.sample beta-lowend-b))]

      (->> (interleave xaxis yaxis)
           (partition 2)
           (take length)
           (csv/write-csv writer-midpoint))

      (->> (interleave xaxis ybigswings)
           (partition 2)
           (take length)
           (csv/write-csv writer-bigswings))

      (->> (interleave xaxis yhighend)
           (partition 2)
           (take length)
           (csv/write-csv writer-highend))

      (->> (interleave xaxis ylowend-a)
           (partition 2)
           (take length)
           (csv/write-csv writer-lowend-a))

      (->> (interleave xaxis ylowend-b)
           (partition 2)
           (take length)
           (csv/write-csv writer-lowend-b)))))

;; NOTE

;; BetaDistribution for these parameters (both Sine + Polynomial)

;; A. Vertical

;; B. Wave Length
;;   Level 1: i. Larger wave lengths (WL);
;;            ii. WL dilation is smaller;
;;            iii. Less occurrence of WL dilation
;;   Level 10: i. medium WLs
;;             ii. WL dilation is medium
;;             iii. Larger occurence of wave length dilation
(comment ;; Sine, Sine+Cosine, Polynomial, Oscillating SAMPLES

  ;; (def bdist1 (BetaDistribution. 2.0 5.0))
  ;; (def bdist2 (BetaDistribution. 2.0 4.0))
  ;; (def bdist3 (BetaDistribution. 2.0 3.0))
  ;; (def bdist-even (BetaDistribution. 2.0 2.0))

  (take 5 (generate-prices-orig bdist))
  (take 20 (generate-prices-iterate bdist))
  (take 2 (generate-prices-for bdist))
  (take 2 (generate-prices-partition bdist))

  (last (take 20 (generate-prices-reductions bdist)))
  (take 100 (generate-prices))


  (def sine-sequence (generate-sine-sequence))
  (def polynomial-sequence (generate-polynomial-sequence))
  (def oscillating-sequence (generate-oscillating-sequence))


  (require '[clojure.data.csv :as csv]
           '[clojure.java.io :as io])


  (let [length 128]

    (with-open [sine-writer (io/writer "out.sine.csv")
                polynomial-writer (io/writer "out.polynomial.csv")
                polynomial-prime-writer (io/writer "out.polynomial.prime.csv")
                sine+cosine-writer (io/writer "out.sine+cosine.csv")
                oscillating-writer (io/writer "out.oscillating.csv")]

      ;; Sine
      (let [xaxis (range)
            yaxis (generate-sine-sequence)]

        (->> (interleave xaxis yaxis)
             (partition 2)
             (take length)
             (csv/write-csv sine-writer)))

      ;; Polynomial
      (let [xaxis (range)

            ;; polynomial-xintercept ;; generate-polynomial-sequence
            yaxis (map polynomial-redux (range))]

        (->> (interleave xaxis yaxis)
             (partition 2)
             (take length)
             (csv/write-csv polynomial-writer)))

      #_(let [xaxis (->> (iterate #(+ % 1/10) 0)
                         (map float))
              yaxis (->> (iterate #(+ % 1/10) 0)
                         (map #(/ % 10))
                         (map polynomial-redux) ;; polynomial-xintercept ;; generate-polynomial-sequence
                         (take length))]

          (->> (interleave xaxis yaxis)
               (partition 2)
               (take length)
               (csv/write-csv polynomial-writer)))

      ;; sin t + cos (sqrt(3)t)
      (let [xaxis (range -10 10)
            yaxis (map sine+cosine (range -10 10))]
        (->> (interleave xaxis yaxis)
             (partition 2)
             (take length)
             (csv/write-csv sine+cosine-writer)))

      ;; Oscillating
      (let [xaxis (range)
            yaxis (generate-oscillating-sequence)]

        (->> (interleave xaxis yaxis)
             (partition 2)
             (take length)
             (csv/write-csv oscillating-writer))))))

(comment

  (->> beta-configurations
       :bigswings
       vals
       (apply ->beta-distribution))


  ;; > C. PRICE SWINGS
  (def beta-midpoint
    (->> beta-configurations
         :midpoint
         vals
         (apply ->beta-distribution)))

  (def price-swings (price-swing-occurence-sequence 50 beta-midpoint))


  ;; A. PRICE CHANGE DIRECTION
  ;; (def beta-highend
  ;;   (->beta-distribution 3.0 2.5)
  ;;   #_(->> beta-configurations
  ;;        :highend
  ;;        vals
  ;;        (apply ->beta-distribution)))
  ;; (def price-changes (price-change-sequence (->beta-distribution 3.0 2.75) #_beta-highend))

  (->> (price-change-sequence (->beta-distribution 3.0 2.65))
       (take 100)
       (filter #(= - %))
       count)

  )

(comment ;; Highend, Lowend BetaDistribution SAMPLES

  ;; Greater price swings at opposite ends
  ;; alpha = beta = 0.5

  ;; Prices within 0.25 / 0.75
  ;; alpha = beta = 2.0

  ;; Prices closer to the high end
  ;; alpha = 5 / beta = 1

  ;; Prices closer to the low end
  ;; alpha = 1 / beta = 3
  ;; alpha = 1 / beta = 5

  ;; https://en.wikipedia.org/wiki/Beta_distribution

  (def beta-distribution (BetaDistribution. 2.0 2.0))
  (def betas (repeatedly #(.sample beta-distribution)))

  (require '[clojure.data.csv :as csv]
           '[clojure.java.io :as io])

  (with-open [writer-highend (io/writer "out.beta-highend.csv")
              writer-lowend (io/writer "out.beta-lowend.csv")]
    (let [length 128
          xaxis (range)

          beta-midpoint (BetaDistribution. 2.0 2.0)
          yaxis (repeatedly #(.sample beta-midpoint))

          beta-bigswings (BetaDistribution. 0.5 0.5)
          ybigswings (repeatedly #(.sample beta-bigswings))

          beta-highend (BetaDistribution. 3 1)
          yhighend (repeatedly #(.sample beta-highend))

          beta-lowend (BetaDistribution. 1 3)
          ylowend (repeatedly #(.sample beta-lowend))]

      (->> (interleave xaxis yaxis)
           (partition 2)
           (take length)
           (csv/write-csv writer-midpoint))

      (->> (interleave xaxis ybigswings)
           (partition 2)
           (take length)
           (csv/write-csv writer-bigswings))

      (->> (interleave xaxis yhighend)
           (partition 2)
           (take length)
           (csv/write-csv writer-highend))

      (->> (interleave xaxis ylowend)
           (partition 2)
           (take length)
           (csv/write-csv writer-lowend))
      )))

;; >> ================================= >>

(comment ;; BetaDistribution Price SAMPLES

  (require '[clojure.data.csv :as csv]
           '[clojure.java.io :as io])

  (with-open [writer (io/writer "out.final.csv")]

    (let [length 500]

      (->> (generate-prices beta-configurations)
           (take length)
           (csv/write-csv writer)))))
