(ns ring-nexus-middleware.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ring-nexus-middleware.core :as core]))

(deftest wrap-nexus-test
  (testing "Ring response effect"
    (let [handler (-> (fn [_] [[:http/respond {:body {:message "Success"}
                                               :status 200}]])
                      (core/wrap-nexus {:nexus/system->state identity} {}))
          response (atom nil)
          respond #(reset! response %)]
      (handler {} respond identity)
      (is (= @response
             {:status 200
              :body {:message "Success"}}))))

  (testing "Ring response convenience actions"
    (let [handler (-> (fn [_] [[:http-response/ok {:message "Success"}]])
                      (core/wrap-nexus {:nexus/system->state identity} {}))
          response (atom nil)
          respond #(reset! response %)]
      (handler {} respond identity )
      (is (= @response
             {:status 200
              :body {:message "Success"}})))))
