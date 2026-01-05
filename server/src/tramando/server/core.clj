(ns tramando.server.core
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]
            [reitit.ring :as ring]
            [tramando.server.config :refer [config]]
            [tramando.server.db :as db]
            [tramando.server.auth :as auth]
            [tramando.server.routes :as routes])
  (:gen-class))

;; =============================================================================
;; Ring Application
;; =============================================================================

;; Middleware to handle OPTIONS preflight requests
(defn wrap-preflight [handler]
  (fn [request]
    (if (= :options (:request-method request))
      {:status 200
       :headers {"Access-Control-Allow-Origin" "*"
                 "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS"
                 "Access-Control-Allow-Headers" "Content-Type, Authorization"
                 "Access-Control-Max-Age" "86400"}
       :body ""}
      (handler request))))

(def app
  (-> (ring/ring-handler
        routes/router
        (ring/routes
          (ring/create-resource-handler {:path "/"})
          (ring/create-default-handler
            {:not-found (constantly {:status 404 :body {:error "Not found"}})
             :method-not-allowed (constantly {:status 405 :body {:error "Method not allowed"}})})))
      (auth/wrap-auth)
      (wrap-json-response)
      (wrap-params)
      (wrap-preflight)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :put :delete :options]
                 :access-control-allow-headers [:content-type :authorization])))

;; =============================================================================
;; Server
;; =============================================================================

(defonce server (atom nil))

(defn start-server! []
  (when @server
    (.stop @server))
  (db/init-db!)
  (println (str "Starting server on port " (:port config) "..."))
  (println (str "Database: " (:db-path config)))
  (println (str "Projects: " (:projects-path config)))
  (println (str "Registration: " (if (:allow-registration config) "enabled" "disabled")))
  (reset! server
          (jetty/run-jetty app {:port (:port config) :join? false}))
  (println "Server started."))

(defn stop-server! []
  (when @server
    (.stop @server)
    (reset! server nil)
    (println "Server stopped.")))

(defn -main [& _args]
  (start-server!)
  ;; Keep the main thread alive
  @(promise))
