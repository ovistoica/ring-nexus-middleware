(ns ring-nexus-middleware.core
  (:require [nexus.core :as nexus]))

(defn prepare-nexus
  [nexus request respond raise]
  (-> nexus
      (assoc-in [:nexus/effects :http/respond]
                (fn [_ _ {:keys [body status headers], :as response-map}]
                  (respond (cond-> response-map
                             (nil? body) (assoc :body "")
                             (nil? headers) (assoc :headers {})
                             (nil? status) (assoc :status 200)))))
      (assoc-in [:nexus/actions :http-response/ok]
                (fn [_ response-body] [[:http/respond
                                        {:status 200, :body response-body}]]))
      (assoc-in [:nexus/actions :http-response/created]
                (fn [_ response-body] [[:http/respond
                                        {:status 201, :body response-body}]]))
      (assoc-in [:nexus/actions :http-response/bad-request]
                (fn [_ response-body] [[:http/respond
                                        {:status 400, :body response-body}]]))
      (assoc-in [:nexus/actions :http-response/unauthorized]
                (fn [_ response-body] [[:http/respond
                                        {:status 401, :body response-body}]]))
      (assoc-in [:nexus/actions :http-response/forbidden]
                (fn [_ response-body] [[:http/respond
                                        {:status 403, :body response-body}]]))
      (assoc-in [:nexus/actions :http-response/not-found]
                (fn [_ response-body] [[:http/respond
                                        {:status 404, :body response-body}]]))
      (assoc-in [:nexus/actions :http-response/internal-server-error]
                (fn [_ response-body] [[:http/respond
                                        {:status 500, :body response-body}]]))))

(defn wrap-nexus
  "Given a nexus config map and a live system, wrap nexus action dispatch to
  provide FCIS (Functional Core Imperative Shell) support.

  Parameters:

  handler - The handler on which to wrap

  nexus - The nexus config map. See https://github.com/cjohansen/nexus for more
  info on what the nexus map requires

  system - The live system that will be passed to nexus. Can be an atom, a DB
  connection, a map containing multiple atoms, datomic connections or any link
  to a live system. N.B. nexus requires a :nexus/system->state functon that
  transforms the system into a immutable state snapshot."
  [handler nexus system]
  (fn [request respond raise]
    (try (let [actions (handler request)]
           (nexus/dispatch (prepare-nexus nexus request respond raise)
                           system
                           {:request request}
                           actions))
         (catch Exception e (raise e)))))
