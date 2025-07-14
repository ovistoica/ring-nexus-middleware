(ns ring-nexus-middleware.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ring-nexus-middleware.core :as core]))

(deftest wrap-nexus-test
  (testing "Ring response effects"
    (let [handler (-> (fn [req] [[:http-response/ok {:message "Success"}]])
                      (core/wrap-nexus {} {}))]
      (is (= (handler {})
             {:status 200
              :body {:message "Success"}})))
    ))
