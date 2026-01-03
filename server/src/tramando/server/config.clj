(ns tramando.server.config
  (:require [environ.core :refer [env]]))

(def config
  {:db-path (or (env :tramando-db-path) "data/tramando.db")
   :projects-path (or (env :tramando-projects-path) "data/projects")
   :jwt-secret (or (env :tramando-jwt-secret) "change-me-in-production")
   :jwt-expiration-hours (or (some-> (env :tramando-jwt-expiration) Integer/parseInt) 24)
   :port (or (some-> (env :port) Integer/parseInt) 3000)
   :allow-registration (not= "false" (env :tramando-allow-registration))})
