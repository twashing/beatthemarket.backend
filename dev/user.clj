 (ns user
   (:require [integrant.repl :refer [clear go halt prep init reset reset-all]]
             [integrant.core :as ig]
             [clojure.java.io :refer [resource]]))

(integrant.repl/set-prep!
  (constantly (-> "integrant-config.edn"
                  resource
                  slurp
                  ig/read-string)))
