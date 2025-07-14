(ns ring-nexus-middleware.core
  (:require [nexus.core :as nexus]))

(defn wrap-nexus
  [handler nexus-map system]
  (fn [request]
    (let [response (atom nil)
          actions (handler request)]
      (nexus/dispatch nexus-map system {:request request} actions)
      ;; This could be done with core async better
      (loop []
        (if @response
          @response
          (do 
            (Thread/sleep 50)
            (recur)))))))
