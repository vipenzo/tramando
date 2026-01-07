(ns tramando.server.core
  (:require [ring.adapter.jetty :as jetty]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.response :as response]
            [reitit.ring :as ring]
            [clojure.string :as str]
            [clojure.java.io :as io]
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

;; Middleware to strip BASE_PATH prefix from requests
(defn wrap-base-path [handler]
  (let [base-path (:base-path config)]
    (if (empty? base-path)
      handler
      (fn [request]
        (let [uri (:uri request)]
          (if (str/starts-with? uri base-path)
            (let [new-uri (subs uri (count base-path))]
              (handler (assoc request :uri (if (empty? new-uri) "/" new-uri))))
            ;; Se non inizia con base-path, redirect
            (if (= uri "/")
              (response/redirect (str base-path "/"))
              {:status 404 :body {:error "Not found"}})))))))

;; Handler per servire file statici dalla cartella public
(defn static-file-handler [request]
  (let [uri (:uri request)
        ;; Rimuovi leading slash e cerca il file
        path (if (= uri "/") "index.html" (subs uri 1))
        ;; Prima prova nella cartella public esterna (Docker)
        external-file (io/file "/app/public" path)]
    (cond
      ;; File esterno esiste (Docker deployment)
      (.exists external-file)
      (-> (response/file-response (.getPath external-file))
          (response/content-type (cond
                                   (str/ends-with? path ".html") "text/html"
                                   (str/ends-with? path ".js") "application/javascript"
                                   (str/ends-with? path ".css") "text/css"
                                   (str/ends-with? path ".png") "image/png"
                                   (str/ends-with? path ".svg") "image/svg+xml"
                                   :else "application/octet-stream")))
      ;; Fallback per SPA: se richiesta non è un file, servi index.html
      (and (not (str/includes? uri "."))
           (not (str/starts-with? uri "/api")))
      (when (.exists (io/file "/app/public/index.html"))
        (-> (response/file-response "/app/public/index.html")
            (response/content-type "text/html")))

      :else nil)))

(def app
  (-> (ring/ring-handler
        routes/router
        (ring/routes
          ;; Handler per file statici
          (fn [request] (static-file-handler request))
          ;; Fallback per risorsa nel classpath (sviluppo locale)
          (ring/create-resource-handler {:path "/"})
          (ring/create-default-handler
            {:not-found (constantly {:status 404 :body {:error "Not found"}})
             :method-not-allowed (constantly {:status 405 :body {:error "Method not allowed"}})})))
      (auth/wrap-auth)
      (wrap-json-response)
      (wrap-params)
      (wrap-preflight)
      (wrap-base-path)
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post :put :delete :options]
                 :access-control-allow-headers [:content-type :authorization])))

;; =============================================================================
;; Server
;; =============================================================================

(defonce server (atom nil))
(defonce cleanup-scheduler (atom nil))

(defn- start-cleanup-scheduler!
  "Start a background thread that cleans up old pending users every hour"
  []
  (when-not @cleanup-scheduler
    (reset! cleanup-scheduler
            (future
              (loop []
                ;; Wait 1 hour between cleanup runs
                (Thread/sleep (* 60 60 1000))
                (try
                  (let [deleted (db/cleanup-old-pending-users!)]
                    (when (pos? deleted)
                      (println (str "[Cleanup] Deleted " deleted " old pending user(s)"))))
                  (catch Exception e
                    (println (str "[Cleanup] Error: " (.getMessage e)))))
                (recur))))))

(defn- stop-cleanup-scheduler!
  "Stop the cleanup scheduler"
  []
  (when @cleanup-scheduler
    (future-cancel @cleanup-scheduler)
    (reset! cleanup-scheduler nil)))

(defn start-server! []
  (when @server
    (.stop @server))
  (db/init-db!)
  ;; Run initial cleanup on startup
  (let [deleted (db/cleanup-old-pending-users!)]
    (when (pos? deleted)
      (println (str "[Cleanup] Deleted " deleted " old pending user(s) on startup"))))
  ;; Start background cleanup scheduler
  (start-cleanup-scheduler!)
  (println (str "Starting server on port " (:port config) "..."))
  (println (str "Database: " (:db-path config)))
  (println (str "Projects: " (:projects-path config)))
  (println (str "Base path: " (if (empty? (:base-path config)) "/" (:base-path config))))
  (println (str "Registration: " (if (:allow-registration config) "enabled" "disabled")))
  (reset! server
          (jetty/run-jetty app {:port (:port config) :join? false}))
  (println "Server started."))

(defn stop-server! []
  (stop-cleanup-scheduler!)
  (when @server
    (.stop @server)
    (reset! server nil)
    (println "Server stopped.")))

(defn -main [& _args]
  (start-server!)
  ;; Keep the main thread alive
  @(promise))
