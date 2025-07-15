(ns ring-nexus-middleware-examples.reitit-datomic
  (:require [datomic.api :as d]
            [hato.client :as http]
            [jsonista.core :as json]
            [muuntaja.core :as mtj]
            [nexus.registry :as nxr]
            [reitit.coercion.spec :as coercion-spec]
            [reitit.dev.pretty :as pretty]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ring-nexus-middleware.core :as ring-nexus]
            [ring.adapter.jetty :as jetty])
  (:import (java.util UUID)))

(defn uuid ([] (UUID/randomUUID)) ([s] (if (string? s) (UUID/fromString s) s)))

;; Datomic setup

(def datomic-uri "datomic:mem://test-db")

(defn create-conn
  []
  (d/create-database datomic-uri)
  (let [conn (d/connect datomic-uri)]
    (d/transact conn
                [{:db/id #db/id [:db.part/db],
                  :db/ident :user-account/id,
                  :db/valueType :db.type/uuid,
                  :db/cardinality :db.cardinality/one,
                  :db/unique :db.unique/identity,
                  :db/doc "Unique identifier for the user account",
                  :db.install/_attribute :db.part/db}
                 {:db/id #db/id [:db.part/db],
                  :db/ident :user-account/username,
                  :db/cardinality :db.cardinality/one,
                  :db/valueType :db.type/string,
                  :db/doc "user's username - used for login and display name",
                  :db/unique :db.unique/identity,
                  :db.install/_attribute :db.part/db}
                 {:db/id #db/id [:db.part/db],
                  :db/ident :user-account/email,
                  :db/cardinality :db.cardinality/one,
                  :db/valueType :db.type/string,
                  :db/doc "User's email - can be used for login. Unique",
                  :db/unique :db.unique/identity,
                  :db.install/_attribute :db.part/db}])
    conn))

(defonce conn (create-conn))

(def unique-attrs
  #{:user-account/id :user-account/email :user-account/username})

(defn prepare-tx-with-retractions
  "Transform transactions by connverting nil values to retractions.
   Returns a sequence of transactions ready for submission.
   Throws an exception if a retraction is needed but no entity identity is available."
  ([txes] (prepare-tx-with-retractions txes unique-attrs))
  ([txes unique-attrs]
   (mapcat
     (fn [tx]
       (if (map? tx)
         (let [nil-ks (map key (filter (comp nil? val) tx))
               db-id (:db/id tx)
               unique-attr (when-let [attr (some #(when (unique-attrs %) %)
                                                 (keys tx))]
                             [attr (attr tx)])
               identity (or db-id unique-attr)]
           (if (seq nil-ks)
             (if identity
               (if db-id
                 ;; Has :db/id: cleaned map first, then retractions
                 (concat [(apply dissoc tx nil-ks)]
                         (for [k nil-ks] [:db/retract identity k]))
                 ;; Has unique attribute: retractions first, then cleaned
                 ;; map
                 (concat (for [k nil-ks] [:db/retract identity k])
                         [(apply dissoc tx nil-ks)]))
               ;; No identity available for retractions
               (throw
                 (ex-info
                   "Cannot create retractions for entity without :db/id or unique attribute"
                   {:entity tx, :nil-keys nil-ks, :unique-attrs unique-attrs})))
             [tx]))
         [tx]))
     txes)))


(defn batch-transactions
  "Given a list of transaction actions, batch them into a single transaction,
  adding retractions if a transaction action, has the
  property :transact-w-nils?"
  ([transact-actions] (batch-transactions transact-actions unique-attrs))
  ([transact-actions unique-attrs]
   (->> (reduce (fn [acc [txs opts]]
                  (let [txs (if (:transact-w-nils? opts)
                              (prepare-tx-with-retractions txs unique-attrs)
                              txs)]
                    (into acc txs)))
          []
          transact-actions)
        (distinct)
        (vec))))


(defn username-exists?
  "Checks if a username is available. Returns false if the username is already taken by a user"
  [db username]
  (boolean (d/entity db [:user-account/username username])))

(defn id-exists?
  "Checks if a username is available. Returns false if the username is already taken by a user"
  [db id]
  (boolean (d/entity db [:user-account/id id])))

(defn email-exists?
  "Checks if a email address is available. Returns false if the username is already taken by a user"
  [db email]
  (boolean (d/entity db [:user-account/email email])))

;; Nexus config

(defn system->state [{:keys [conn]}] {:now (java.util.Date.), :db (d/db conn)})

(nxr/register-effect! :db/transact
  ^:nexus/batch
  (fn [_ {:keys [conn]} transact-actions]
    (prn transact-actions (batch-transactions transact-actions))
    @(d/transact conn (batch-transactions transact-actions))))

(nxr/register-system->state! system->state)

;; Reitit handlers & setup

(defn create-user
  [req]
  (let [{:keys [db]} (:nexus/state req)
        {:keys [email username id]} (:body-params req)
        user-id (uuid id)]
    (cond (username-exists? db username)
            [[:http-response/bad-request
              {:message (str "Username " username " is taken."),
               :data (:body-params req)}]]
          (email-exists? db username)
            [[:http-response/bad-request
              {:message (str "Email " email " is taken."),
               :data (:body-params req)}]]
          (id-exists? db username)
            [[:http-response/bad-request
              {:message (str "Id " id " is taken."), :data (:body-params req)}]]
          :else [[:db/transact
                  [{:user-account/email email,
                    :user-account/username username,
                    :user-account/id user-id}] {:transact-w-nils? false}]
                 [:http-response/created
                  {:message "User created succesfully"}]])))

(defn get-user
  ([req]
   (let [{:keys [db]} (:nexus/state req)
         {:keys [id]} (:path-params req)
         user (d/entity db [:user-account/id (uuid id)])]
     (if user
       [[:http-response/ok (into {} user)]]
       [[:http-response/not-found
         {:message (str "User with id " id ", not found.")}]])))
  ([req res raise]
   (try (let [{:keys [db]} (:nexus/state req)
              {:keys [id]} (:path-params req)
              user (d/entity db [:user-account/id (uuid id)])]
          (res (if user
                 [[:http-response/ok (into {} user)]]
                 [[:http-response/not-found
                   {:message (str "User with id " id ", not found.")}]])))
        (catch Exception e (raise e)))))

(def routes
  [["/user" {:middleware [[ring-nexus/wrap-nexus (nxr/get-registry) {:conn conn}]]}
    ["/"
     {:summary "Create a user",
      :post {:handler create-user,
             :parameters {:body
                            {:email string?, :username string?, :id string?}}}}]
    ["/:id" {:summary "Get created user", :get {:handler get-user}}]]])

(def app
  (ring/ring-handler
    (ring/router routes
                 {:exception pretty/exception,
                  :coercion coercion-spec/coercion,
                  :data {:muuntaja mtj/instance,
                         :middleware [;; query-params & form-params
                                      parameters/parameters-middleware
                                      ;; content-negotiation
                                      muuntaja/format-negotiate-middleware
                                      ;; encoding response body
                                      muuntaja/format-response-middleware
                                      ;; decoding request body
                                      muuntaja/format-request-middleware
                                      ;; coercing response bodys
                                      coercion/coerce-response-middleware
                                      ;; coercing request parameters
                                      coercion/coerce-request-middleware]}})
    (ring/create-default-handler)))

(defn start-server
  [port]
  (jetty/run-jetty #'app {:port port, :join? false, :async? true}))

(defn stop-server [server] (.stop server))


(comment
  (def id (uuid))
  (def srv (start-server 3123))
  (http/request {:method :post,
                 :url "http://localhost:3123/user/",
                 :headers {"content-type" "application/json"},
                 :as :json,
                 :body (json/write-value-as-string {:id (str id),
                                                    :email "hello@world.com",
                                                    :username "helloworld"}),
                 :throw-exceptions? false})
  (d/entity (d/db conn) [:user-account/email "hello@world.com"])
  (http/request {:method :get,
                 :url (str "http://localhost:3123/user/" id),
                 :as :json,
                 :throw-exceptions? false})
  (stop-server srv))
