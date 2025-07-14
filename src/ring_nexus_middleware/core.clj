(ns ring-nexus-middleware.core
  (:require [nexus.core :as nexus]))

(defn prepare-nexus
  [nexus request respond raise]
  (-> nexus
      (assoc-in [:nexus/effects :http/respond]
                (fn [_ _ response-map] (respond response-map)))
      (assoc-in [:nexus/actions :http-response/ok]
                (fn [_ response-body] [[:http/respond {:status 200 :body response-body}]]))))

(defn wrap-nexus
  [handler nexus-map system]
  (fn [request respond raise]
    (let [actions (handler request)]
      (nexus/dispatch (prepare-nexus nexus-map request respond raise)
                      system
                      {:request request}
                      actions))))
