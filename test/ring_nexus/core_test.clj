(ns ring-nexus.core-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ring-nexus.core :as core])
  (:import
   (clojure.lang ExceptionInfo)))

(defn test-action-handler [req] (:body req))

(defn make-test-handler
  [nexus system]
  (-> test-action-handler
      (core/wrap-nexus nexus system)))

; Global state for collecting responses
(def responses (atom []))

;; Global respond function that appends to responses
(defn respond [response] (swap! responses conj response))

;; Fixture to clear responses between tests
(defn clear-responses-fixture [test-fn] (reset! responses []) (test-fn))

(use-fixtures :each clear-responses-fixture)

(deftest http-respond-effect-test
  (testing "Returns correct response body"
    (let [handler (make-test-handler {:nexus/system->state identity} nil)]
      (handler {:body [[:http/respond
                        {:status 200, :body {:message "Success"}}]]}
               respond
               identity)
      (is (= @responses
             [{:status 200, :headers {}, :body {:message "Success"}}]))
      (testing "Single arity handler works too"
        (is (= (handler {:body [[:http/respond
                                 {:status 200, :body {:message "Success"}}]]})
               {:status 200, :headers {}, :body {:message "Success"}}))))))

(deftest ring-response-convenience-actions
  (testing "Ring response convenience actions"
    (let [handler (make-test-handler {:nexus/system->state identity} nil)]
      (doseq [actions [[[:http-response/ok {:message "ok"}]]
                       [[:http-response/bad-request {:message "bad-request"}]]
                       [[:http-response/unauthorized {:message "unauthorized"}]]
                       [[:http-response/created {:message "Created"} "/get/created"]]
                       [[:http-response/created {:message "Created2"}]]
                       [[:http-response/not-found {:message "not-found"}]]
                       [[:http-response/internal-server-error
                         {:message "internal-server-error"}]]
                       [[:http-response/forbidden {:message "forbidden"}]]]]
        (handler {:body actions} respond identity))
      (is
       (= @responses
          [{:body {:message "ok"}, :headers {}, :status 200}
           {:body {:message "bad-request"}, :headers {}, :status 400}
           {:body {:message "unauthorized"}, :headers {}, :status 401}
           {:body {:message "Created"}, :headers {"Location" "/get/created"}, :status 201}
           {:body {:message "Created2"}, :headers {}, :status 201}
           {:body {:message "not-found"}, :headers {}, :status 404}
           {:body {:message "internal-server-error"}, :headers {}, :status 500}
           {:body {:message "forbidden"}, :headers {}, :status 403}])))))

(deftest write-to-store
  (testing "handler that writes to state"
    (let [store (atom {})
          nexus {:nexus/system->state deref
                 :nexus/effects {:effects/save
                                 (fn [_ store path v]
                                   (swap! store assoc-in path v))}}
          handler (make-test-handler nexus store)]
      (handler {:body [[:effects/save [:a :b] 1]
                       [:http-response/ok {:message "Write succeeded"}]]}
               respond
               identity)
      (is (= @responses
             [{:body {:message "Write succeeded"}, :headers {}, :status 200}]))
      (is (= @store {:a {:b 1}})))))

(deftest subsequent-actions-test
  (testing "Test if subsequently dispatched actions are handled"
    (let [store (atom {})
          nexus {:nexus/system->state deref
                 :nexus/effects {:effects/save (fn [_ store path v]
                                                 (swap! store assoc-in path v))
                                 :effects/delay (fn [{:keys [dispatch]} _ ms
                                                     actions]
                                                  (Thread/sleep ms)
                                                  (dispatch actions))}}
          handler (make-test-handler nexus store)]
      (is (= (handler {:body [[:effects/delay 100
                               [[:effects/save [:a :b] 1]
                                [:http-response/ok
                                 {:message "Write succeeded after delay"}]]]]})
             {:body {:message "Write succeeded after delay"}
              :headers {}
              :status 200}))
      (is (= @store {:a {:b 1}})))))

(deftest state-snapshot-default-k
  (testing "Attaches the state snapshot to the ring request"
    (let [store (atom {})
          nexus {:nexus/system->state deref
                 :nexus/effects {:effects/save
                                 (fn [_ store path v]
                                   (swap! store assoc-in path v))}}
          handler (-> (fn [{:keys [nexus/state], :as req}]
                        (into (:body req) [[:http-response/ok state]]))
                      (core/wrap-nexus nexus store))]
      (is (= (handler {:body [[:effects/save [:a :b] 1]]})
             {:body {}, :headers {}, :status 200}))
      (is (= (handler {:body [[:effects/save [:c] {:hello :world}]]})
             {:body {:a {:b 1}}, :headers {}, :status 200}))
      (is (= @store {:a {:b 1} :c {:hello :world}})))))

(deftest state-snapshot-other-k
  (testing "Attaches the state snapshot to the ring request on the specified key"
    (let [store (atom {})
          nexus {:nexus/system->state deref
                 :nexus/effects {:effects/save
                                 (fn [_ store path v]
                                   (swap! store assoc-in path v))}}
          handler (-> (fn [{::keys [state], :as req}]
                        (into (:body req) [[:http-response/ok state]]))
                      (core/wrap-nexus nexus store {:ring-nexus/state-k ::state}))]
      (handler {:body [[:effects/save [:a :b] 1]]} respond identity)
      (handler {:body [[:effects/save [:c] {:hello :world}]]} respond identity)
      (is (= @responses
             [{:body {}, :headers {}, :status 200}
              {:body {:a {:b 1}}, :headers {}, :status 200}]))
      (is (= @store {:a {:b 1} :c {:hello :world}})))))

(deftest passthrough-normal-ring-responses
  (testing "Normal ring responses are just passed through"
    (let [store (atom {})
          nexus {:nexus/system->state deref
                 :nexus/effects {:effects/save
                                 (fn [_ store path v]
                                   (swap! store assoc-in path v))}}
          handler (-> (fn [_] {:status 200 :body {:message "I'm a classic ring handler"}})
                      (core/wrap-nexus nexus store))]

      (is (= (handler {:body {}} respond identity)
             [{:status 200 :body {:message "I'm a classic ring handler"}}])))))

(deftest error-in-handler-test
  (testing "Errors from the handler are raised"
    (let [store (atom {})
          nexus {:nexus/system->state deref
                 :nexus/effects {:effects/save
                                 (fn [_ store path v]
                                   (swap! store assoc-in path v))}}
          handler (-> (fn [_]
                        (throw (ex-info "Boom!" {:boom true}))
                        [[:effects/save [:a] 1]
                         [:http-response/ok {:message "Saved to state"}]])
                      (core/wrap-nexus nexus store))]
      (is (thrown-with-msg? ExceptionInfo #"Boom!" (handler {})))
      (is (= @store {})))))

(deftest error-in-action-effect-test
  (testing "Errors from the action dispatch are raised on default config"
    (let [store (atom {})
          nexus {:nexus/system->state deref
                 :nexus/effects {:effects/save
                                 (fn [_ store path v]
                                   (throw (ex-info "Error saving to state" {:path path :v v}))
                                   (swap! store assoc-in path v))}}
          handler (-> (fn [_]
                        [[:effects/save [:a] 1]
                         [:http-response/ok {:message "Saved to state"}]])
                      (core/wrap-nexus nexus store))]
      (is (thrown-with-msg? ExceptionInfo #"Error saving to state" (handler {})))
      (is (= @store {}))))

  (testing "Handler returns response when :ring-nexus/fail-fast? is false and :ring-nexus/on-error doesn't throw"
    (let [store (atom {})
          error (atom nil)
          nexus {:nexus/system->state identity
                 :nexus/effects {:effects/save
                                 (fn [_ store path v]
                                   (throw (ex-info "Error saving to state" {:path path :v v}))
                                   (swap! store assoc-in path v))}}
          handler (-> (fn [_]
                        [[:effects/save [:a] 1]
                         [:http-response/ok {:message "Saved to state"}]])
                      (core/wrap-nexus nexus store {:ring-nexus/fail-fast? false
                                                    :ring-nexus/on-error #(reset! error %)}))]
      (is (= (handler {}) {:body {:message "Saved to state"}, :headers {}, :status 200}))
      (is (= (ex-data @error) {:path [:a] :v 1}))
      (is (= (ex-message @error) "Error saving to state")))))

(deftest ring-response-convenience-actions-disabled
  (testing "Ring response convenience actions disabled"
    (let [handler (-> test-action-handler
                      (core/wrap-nexus {:nexus/system->state identity
                                        :nexus/actions {:http-response/ok
                                                        (fn [_ response-body]
                                                          [[:http/respond ;; this effect is added in `wrap-nexus`
                                                            {:status 200
                                                             :headers {"X-Custom-Header" "My Custom value"}
                                                             :body response-body}]])}}
                                       nil
                                       {:ring-nexus/add-response-actions? false}))]
      (is (= (handler {:body [[:http-response/ok {:message "Action does not exist"}]]})
             {:status 200, :headers {"X-Custom-Header" "My Custom value"}, :body {:message "Action does not exist"}}))

      (is (thrown-with-msg? ExceptionInfo #"No such effect"
                            (handler {:body [[:http-response/created {:message "Action does not exist"}]]}))))))
