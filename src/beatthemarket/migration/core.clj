(ns beatthemarket.migration.core
  (:require [clojure.java.io :refer [resource]]
            [datomic.client.api :as d]
            [integrant.repl.state :as state]
            [com.rpl.specter :refer [select ALL]]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]

            [beatthemarket.state.core :as state.core]
            [beatthemarket.persistence.datomic :as persistence.datomic]
            [beatthemarket.migration.schema-init :as schema-init]
            [beatthemarket.util :as util :refer [ppi]])
  (:import [java.util UUID]))


(def migration-schema
  [{:db/ident       :migration/id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value}

   {:db/ident       :migration/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value}

   {:db/ident       :migration/tag
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value}

   {:db/ident       :migration/created
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value}

   {:db/ident       :migration/applied
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value}])

(def custom-formatter (f/formatter "yyyy.MM.dd.kk.mm.ss.SSS"))

#_(defn create-database! [client db-name]
  (d/create-database client {:db-name db-name}))

#_(defn connect-to-database [client db-name]
  (d/connect client {:db-name db-name}))

#_(defn apply-norms!

  ([]
   (let [norms (schema-init/load-norm)]
     (apply-norms! norms)))

  ([norms]

   (let [conn (-> state/system :persistence/datomic :opts :conn)]
     (apply-norms! conn norms)))

  ([conn norms]
   (persistence.datomic/transact! conn norms)))

#_(def run-migrations apply-norms!)

#_(defn initialize-production []
  (run-migrations))


(defn conditionally-apply-migration-schema! [conn migration-schema]
  (when-not (persistence.datomic/entity-exists? conn :migration/id)
    (beatthemarket.persistence.datomic/transact-entities! conn migration-schema)))

(defn migration-label->file-anme [migration-label]
  (format "resources/migration/%s.edn" migration-label))

(defn create-migration!

  ([migration-name migration-data] (create-migration! migration-name migration-data :default))

  ([migration-name migration-data migration-tag]

   (let [migration-label
         (->> (t/now)
              (f/unparse custom-formatter)
              (format "%s-%s" migration-name)
              keyword)]

     (binding [*print-length* nil]
       (with-open [w (clojure.java.io/writer (migration-label->file-anme (name migration-label)))]
         (binding [*out* w] (clojure.pprint/pprint {migration-tag {migration-label migration-data}})))))))

(comment ;; Create a migration file

  ;; (require '[io.rkn.conformity :as c]
  ;;          '[clj-time.format :as f])
  ;; (def custom-formatter (f/formatter "yyyy.MM.dd:kk:mm:ss.SSS"))

  ;; A
  (def schema-datomic
    (-> ;; "migration/schema.datomic.edn"
      "schema.datomic.edn"
      resource slurp
      read-string))

  (create-migration! "schema-init" schema-datomic)

  ;; B
  (require '[beatthemarket.persistence.generators :as persistence.generators])
  (def sample-game-norms (persistence.generators/generate-games))
  (create-migration! "sample-games" sample-game-norms :development))

(defn migration-tag->time-string [migration-tag]

  (-> migration-tag
      name
      (clojure.string/split #"\-")
      last))

(defn migration-tag->creation-date [migration-tag]

  (->> (migration-tag->time-string migration-tag)
       (f/parse custom-formatter)))

(defn migration-tag->name [migration-tag]

  (->> migration-tag
       name
       (#(clojure.string/split % #"\-"))
       butlast
       (clojure.string/join "-")))

(defn migration-ran? [conn migration-tag]
  (util/exists?
    (d/q '[:find ?m
           :in $ ?migration-tag ?applied-comparator
           :where
           [?m :migration/tag ?migration-tag]
           [?m :migration/applied ?applied]
           [(> ?applied-comparator ?applied)]]
         (d/db conn)
         migration-tag
         (c/to-date (t/now)))))

(comment
  (ppi
    (d/q '[:find (pull ?m [*])
           :where
           [?m :migration/id]]
         (d/db conn))))

(defn conditionally-apply-migration! [conn migration]

  (let [tag (ffirst migration)]
    (when-not (migration-ran? conn tag)
      (let [migration-data (-> migration first second)
            migration-entity
            {:migration/id (UUID/randomUUID)
             :migration/name (migration-tag->name tag)
             :migration/tag (name tag)
             :migration/created (c/to-date (migration-tag->creation-date tag))
             :migration/applied (c/to-date (t/now))}]

        (->> (list migration-entity)
             (concat migration-data)
             (beatthemarket.persistence.datomic/transact-entities! conn))))))

(defn apply-migrations [conn tags migrations]

  (let [sort-by-migration-creation
        #(-> % ffirst migration-tag->time-string)]

    (->> (filter #(some tags (into #{} (keys %)))
                 migrations)
         (select [ALL #(some tags (into #{} (keys %)))])
         (map vals)
         flatten
         (sort-by sort-by-migration-creation)
         (map (partial conditionally-apply-migration! conn)))))

(defn load-migrations

  ([] (load-migrations "resources/migration/"))

  ([migrations-directory]

   (let [schema-files (file-seq (clojure.java.io/file migrations-directory))]

     (->> schema-files
          (filter #(.isFile %))
          (map #(.getAbsolutePath %))
          (map slurp)
          (map read-string)))))

(defn run-migrations

  ([] (run-migrations (-> state/system :persistence/datomic :opts :conn)))

  ([conn] (run-migrations conn #{:default}))

  ([conn tags]

   (conditionally-apply-migration-schema! conn migration-schema)
   (->> (load-migrations)
        (apply-migrations conn tags)
        doall)))

(comment

  (def conn (-> state/system :persistence/datomic :opts :conn))
  (conditionally-apply-migration-schema! conn migration-schema)

  (def migrations (load-migrations))
  (->> (apply-migrations conn
                         ;; #{:development}
                         #{:default :development}
                         migrations))

  :schema-init-2020.09.29.02.07.52.081
  :sample-games-2020.09.29.02.07.57.540
  (def one [{:sample-games-2020.09.29.02.07.57.540 :bar}
            {:schema-init-2020.09.29.02.07.52.081 :foo}])
  (migration-tag->name (ffirst (first one)))
  (migration-tag->creation-date (ffirst (first one)))
  (ppi (conditionally-apply-migration! (first one)))

  (-> (name :sample-games-2020.09.29.02.07.57.540)
      (clojure.string/split #"\-"))
  )

(comment

  (def client (-> integrant.repl.state/system :persistence/datomic :opts :client))
  (def db-name (-> integrant.repl.state/config :persistence/datomic :datomic :db-name))

  (def create-result (create-database! client db-name))
  (def conn (connect-to-database client db-name))

  (apply-norms!)


  ;; B
  (def schem-init
    (-> "migration/schema-init-2020.09.28:21:47:47.552.edn"
        resource slurp
        read-string))

  (def schema-files (file-seq (clojure.java.io/file "resources/migration/")))
  (def schema-data (->> schema-files
                        (filter #(.isFile %))
                        (map #(.getAbsolutePath %))
                        (map slurp)
                        (map read-string)))

  (-> (filter #(some #{:default}
                     (into #{} (keys %)))
              schema-data)
      count)


  (filter #(some #{:default}
                 (into #{} (keys %)))
          [{:default {:migrationa 1}}
           {:foo {:migrationb 2}}])


  ;; D
  (def conn (-> state/system :persistence/datomic :opts :conn))
  (apply-migrations conn
                    #{:default :development}
                    [{:default {:migrationa 1}}
                     {:test {:migrationb 2}}])

  (binding [*print-length* nil]
    (pr schema-datomic))

  (binding [*print-length* nil]
    (pprint schema-datomic))

  )


;; Run default
;; Run default + development
;;
;; Migration run?
;;
;; Run development
;; Run production
