(ns tramando.server.core
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.json :refer [wrap-json-response]]
            [reitit.ring :as ring]
            [tramando.server.config :refer [config]]
            [tramando.server.db :as db]
            [tramando.server.auth :as auth]
            [tramando.server.routes :as routes])
  (:gen-class))

;; =============================================================================
;; Ring Application
;; =============================================================================

(def app
  (-> (ring/ring-handler
        routes/router
        (ring/create-default-handler
          {:not-found (constantly {:status 404 :body {:error "Not found"}})}))
      (auth/wrap-auth)
      (wrap-json-response)
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
