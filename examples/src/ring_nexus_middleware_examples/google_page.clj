(ns ring-nexus-middleware-examples.google-page
  (:require [hato.client :as http]
            [ring-nexus-middleware.core :refer [wrap-nexus]]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params])
  (:import (clojure.lang ExceptionInfo)))

(def store (atom {}))

(defn get*
  "Util used to get keys from placeholder data"
  [m k]
  (if (vector? k) (get-in m k) (get m k)))

(def nexus
  {:nexus/system->state deref,
   :nexus/effects
     {:effects/save (fn [_ store path v] (swap! store assoc-in path v)),
      :effects/delay (fn [{:keys [dispatch]} _ ms actions]
                       (Thread/sleep ms)
                       (dispatch actions)),
      :effects/http
        (fn [{:keys [dispatch]} _ request-map & [{:keys [on-success on-fail]}]]
          (prn "Fetching request" request-map)
          (try (let [response (http/request request-map)]
                 (when (seq on-success)
                   (dispatch on-success {:http-response response})))
               (catch ExceptionInfo e
                 (when (seq on-fail)
                   (dispatch on-fail {:http-response (ex-data e)})))))},
   :nexus/placeholders {:http-response
                          (fn [{:keys [http-response]} ks]
                            (if http-response
                              (if ks (get* http-response ks) http-response)
                              ;; Return the original placeholder vector if
                              ;; no http-response
                              (if ks [:http-response ks] [:http-response])))}})

(defn fetch-google-handler
  "Fetch the main page of Google, return it as a response and store in the store"
  [{:keys [uri request-method]}]
  (if (and (= "/" uri) (= request-method :get))
    [[:effects/http {:method :get, :url "https://www.google.com"}
      {:on-success [[:effects/save [:google-page] [:http-response :body]]
                    [:http/respond
                     {:body [:http-response :body],
                      :headers {"content-type" "text/html"}}]]}]]
    [[:http-response/not-found "Not found"]]))

(defn start-server
  [port]
  (jetty/run-jetty (-> #'fetch-google-handler
                       (wrap-nexus nexus store))
                   {:port port, :join? false, :async? true}))

(defn stop-server [server] (.stop server))


(comment
  (def srv (start-server 3123))
  (stop-server srv)


  (http/request {:method :get, :url "http://localhost:3123"})

  
  @store ;; => {:google-page "<!doctype ...?

  )
