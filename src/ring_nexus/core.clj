(ns ring-nexus.core
  (:require
   [nexus.core :as nexus]
   [nexus.strategies :as strategies]))

(defn has-fail-fast-strategy?
  [nexus]
  (contains? (set (:nexus/interceptors nexus)) strategies/fail-fast))

(defn maybe-add-fail-fast
  [nexus]
  (if (has-fail-fast-strategy? nexus)
    nexus
    (update-in nexus [:nexus/interceptors] (fnil conj []) strategies/fail-fast)))

(defn add-response-actions
  "Pure function to add HTTP response actions to a nexus configuration."
  [nexus]
  (-> nexus
      (assoc-in [:nexus/actions :http-response/ok]
                (fn [_ response-body] [[:http/respond
                                        {:status 200, :body response-body}]]))
      (assoc-in [:nexus/actions :http-response/created]
                (fn ([_ response-body]
                     [[:http/respond
                       {:status 201
                        :body response-body}]])
                  ([_ response-body location]
                   [[:http/respond
                     (cond-> {:status 201
                              :body response-body}
                       location (assoc-in [:headers "Location"] location))]])))
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

(defn prepare-nexus-template
  "Prepares the nexus template with all static configuration, leaving only
   the respond callback to be injected per request."
  [nexus {:ring-nexus/keys [fail-fast? add-response-actions?] :or {fail-fast? true add-response-actions? true}}]
  (cond-> nexus
    add-response-actions? add-response-actions
    fail-fast? maybe-add-fail-fast))

(defn add-respond-effect
  "Pure function to add the respond callback to a prepared nexus template."
  [nexus-template respond]
  (assoc-in nexus-template [:nexus/effects :http/respond]
            (fn [_ _ {:keys [body status headers], :as response-map}]
              (respond (cond-> response-map
                         (nil? body) (assoc :body "")
                         (nil? headers) (assoc :headers {})
                         (nil? status) (assoc :status 200))))))

(defn wrap-nexus
  "Given a nexus config map and a live system, wrap nexus action dispatch to
  provide FCIS (Functional Core Imperative Shell) support.

  Parameters:

  - handler - The handler on which to wrap

  - nexus - The nexus config map. See https://github.com/cjohansen/nexus for more
            info on what the nexus map requires

  - system - The live system that will be passed to nexus. Can be an atom, a DB
             connection, a map containing multiple atoms, datomic connections or any link
             to a live system. N.B. nexus requires a :nexus/system->state functon that
             transforms the system into a immutable state snapshot. `wrap-nexus` will use
             `nexus/system->state` to add a snapshot of the state to the ring request

  - opts  - Last parameter is an optional config map:

    - `:ring-nexus/state-k` - the key on which to put the state snapshot

    - `:ring-nexus/fail-fast?` - If nexus should fail fast if one of the dispatch actions
                                 failed. Default is true.

    - `:ring-nexus/on-error` - callback used with error from actions/effects. Defaults to throw

    - `:ring-nexus/add-response-actions?` - Whether the middleware should add convenience response
                                            actions or not. See `add-response-actions` for the actions.
                                            Defaults to true."
  [handler {:keys [nexus/system->state], :as nexus} system &
   [{:ring-nexus/keys [state-k on-error],
     :or {state-k :nexus/state on-error #(throw %)} :as opts}]]
  ;; Memoize the nexus preparation at wrapper creation time
  (let [nexus-template (prepare-nexus-template nexus opts)]
    (fn
      ;; 1-arity: synchronous handler (returns response directly)
      ([request]
       (let [response (handler (assoc request state-k (system->state system)))]
         (if (vector? response)
           ;; For sync handlers, we need to create a promise-based dispatch
           (let [response-promise (promise)
                 respond #(deliver response-promise %)
                 prepared-nexus (add-respond-effect nexus-template respond)
                 {:keys [errors]} (nexus/dispatch prepared-nexus
                                                  system
                                                  {:request request}
                                                  response)]
             (when-let [error (->> errors (keep :err) first)]
               (on-error error))

             @response-promise) ;; block until nexus response is created
           response))) ;; classic ring map response
      ;; 3-arity: asynchronous handler (uses respond/raise callbacks)
      ([request respond raise]
       (try
         (let [response (handler (assoc request state-k (system->state system)))]
           (if (vector? response)
             (let [prepared-nexus (add-respond-effect nexus-template respond)
                   {:keys [errors]}
                   (nexus/dispatch prepared-nexus
                                   system
                                   {:request request}
                                   response)
                   on-error (or raise on-error)]
               (when-let [error (->> errors (keep :err) first)]
                 (on-error error)))

             (respond response))) ;; classic ring map
         (catch Exception e (raise e)))))))
