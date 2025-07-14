(ns ring-nexus-middleware.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [ring-nexus-middleware.core :as core]))

(deftest wrap-nexus-test
  (testing "Ring response effect"
    (let [handler (-> (fn [_] [[:http/respond
                                {:body {:message "Success"}, :status 200}]])
                      (core/wrap-nexus {:nexus/system->state identity} {}))
          response (atom nil)
          respond #(reset! response %)]
      (handler {} respond identity)
      (is (= @response
             {:status 200, :headers {}, :body {:message "Success"}}))))
  (testing "Ring response convenience actions"
    (let [handler (-> [req]
                      (fn
                        (let [{:keys [action action-body]} (:body req)]
                          [[action action-body]]))
                      (core/wrap-nexus {:nexus/system->state identity} {}))
          responses (atom [])
          respond #(swap! responses conj %)]
      (handler {:body {:action :http-response/ok, :action-body {:message "ok"}}}
               respond
               identity)
      (handler {:body {:action :http-response/bad-request,
                       :action-body {:message "bad-request"}}}
               respond
               identity)
      (handler {:body {:action :http-response/unauthorized,
                       :action-body {:message "unauthorized"}}}
               respond
               identity)
      (handler {:body {:action :http-response/not-found,
                       :action-body {:message "not-found"}}}
               respond
               identity)
      (handler {:body {:action :http-response/internal-server-error,
                       :action-body {:message "internal-server-error"}}}
               respond
               identity)
      (handler {:body {:action :http-response/forbidden,
                       :action-body {:message "forbidden"}}}
               respond
               identity)
      (is
        (= @responses
           [{:body {:message "ok"}, :headers {}, :status 200}
            {:body {:message "bad-request"}, :headers {}, :status 400}
            {:body {:message "unauthorized"}, :headers {}, :status 401}
            {:body {:message "not-found"}, :headers {}, :status 404}
            {:body {:message "internal-server-error"}, :headers {}, :status 500}
            {:body {:message "forbidden"}, :headers {}, :status 403}])))))
