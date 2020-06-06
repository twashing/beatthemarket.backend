(ns beatthemarket.util)


(defn exists? [a]
  (cond
    (nil? a) false
    (seqable? a) ((comp not empty?) a)
    :else true))

(defn truthy? [a]
  "Based on Clojure truthiness
   http://blog.jayfields.com/2011/02/clojure-truthy-and-falsey.html"
  (if (or (nil? a) (false? a))
    false true))

(defn pprint+identity [e]
  (clojure.pprint/pprint e)
  e)
