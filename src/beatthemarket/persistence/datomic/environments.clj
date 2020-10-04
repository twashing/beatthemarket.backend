(ns beatthemarket.persistence.datomic.environments
  (:require [datomic.client.api :as d]))


(defmulti ->datomic-client :env)
(defmulti close-db-connection! :env)


(defmethod ->datomic-client :production [{:keys [db-name config env]}]

  (let [client (d/client config)]

    (hash-map
      :env env
      :db-name db-name
      :client client
      :conn (d/connect client {:db-name db-name}))))

(defmethod ->datomic-client :development [{:keys [db-name config env]}]

  (let [client (d/client config)]

    (hash-map
      :env env
      :db-name db-name
      :client client
      :conn (d/connect client {:db-name db-name}))))

(defmethod ->datomic-client :test [{:keys [db-name config env]}]

  (let [client (d/client config)]

    (d/create-database client {:db-name db-name})

    (hash-map
      :env env
      :db-name db-name
      :client client
      :conn (d/connect client {:db-name db-name}))))

(defmethod close-db-connection! :production [_])
(defmethod close-db-connection! :development [{client :client db-name :db-name}])
(defmethod close-db-connection! :test [{client :client db-name :db-name}]
  (d/delete-database client {:db-name db-name}))
