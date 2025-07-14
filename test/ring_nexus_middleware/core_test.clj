(ns ring-nexus-middleware.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [ring-nexus-middleware.core :as core]))

(defn test-action-handler [req] (:body req))

; Global state for collecting responses
(def responses (atom []))

;; Global respond function that appends to responses
(defn respond [response]
  (swap! responses conj response))

;; Fixture to clear responses between tests
(defn clear-responses-fixture [test-fn]
  (reset! responses [])
  (test-fn))

(use-fixtures :each clear-responses-fixture)


(deftest http-respond-effect-test
  (testing "Returns correct response body"
    (let [handler (-> test-action-handler
                      (core/wrap-nexus {:nexus/system->state identity} {}))]
      (handler {:body [[:http/respond
                        {:status 200, :body {:message "Success"}}]]}
               respond
               identity)
      (is (= @responses [{:status 200, :headers {}, :body {:message "Success"}}])))))


(deftest ring-response-convenience-actions
  (testing "Ring response convenience actions"
    (let [handler (-> test-action-handler
                      (core/wrap-nexus {:nexus/system->state identity} {}))]
      (doseq [actions [[[:http-response/ok {:message "ok"}]]
                       [[:http-response/bad-request {:message "bad-request"}]]
                       [[:http-response/unauthorized {:message "unauthorized"}]]
                       [[:http-response/not-found {:message "not-found"}]]
                       [[:http-response/internal-server-error {:message "internal-server-error"}]]
                       [[:http-response/forbidden {:message "forbidden"}]]]]
        (handler {:body actions} respond identity))
      (is (= @responses
             [{:body {:message "ok"}, :headers {}, :status 200}
              {:body {:message "bad-request"}, :headers {}, :status 400}
              {:body {:message "unauthorized"}, :headers {}, :status 401}
              {:body {:message "not-found"}, :headers {}, :status 404}
              {:body {:message "internal-server-error"}, :headers {}, :status 500}
              {:body {:message "forbidden"}, :headers {}, :status 403}])))))

(deftest write-to-store
  (testing "handler that writes to state"
    (let [store (atom {})
          nexus {:nexus/system->state deref,
                 :nexus/effects {:effects/save
                                 (fn [_ store path v]
                                   (swap! store assoc-in path v))}}
          handler (-> test-action-handler
                      (core/wrap-nexus nexus store))]
      (handler {:body [[:effects/save [:a :b] 1]
                       [:http-response/ok {:message "Write succeeded"}]]} respond identity)
      (is (= @responses [{:body {:message "Write succeeded"}, :headers {}, :status 200}]))
      (is (= @store {:a {:b 1}})))))
