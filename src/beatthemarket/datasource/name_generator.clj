(ns beatthemarket.datasource.name-generator
  (:require [integrant.core :as ig]
            [integrant.repl.state :as state])
  (:import [org.kohsuke.randname RandomNameGenerator]))


(defn generate-name []
  (let [stock-name (as-> (:name-generator/name-generator state/system) nme
                     ((fn [^RandomNameGenerator nm] (.next nm)) nme)
                     (clojure.string/split nme #"_")
                     (map clojure.string/capitalize nme)
                     (clojure.string/join " " nme))

        stock-symbol (-> (subs stock-name 0 4)
                         clojure.string/upper-case)]

    (hash-map
      :stock-name stock-name
      :stock-symbol stock-symbol)))

(defn generate-names

  ([]
   (repeatedly generate-name))

  ([n]
   (take n (generate-names))))

(defmethod ig/init-key :name-generator/name-generator [_ {}]
  (RandomNameGenerator.))


(comment
  (generate-name))
