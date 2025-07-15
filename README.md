# Ring Nexus Middleware

[![Clojars Project](https://img.shields.io/clojars/v/com.ovistoica/ring-nexus-middleware.svg)](https://clojars.org/com.ovistoica/ring-nexus-middleware)

Middleware to support FCIS (Functional Core, Imperative Shell) style programming in ring handlers through [Nexus](https://github.com/cjohansen/nexus) - a zero-dependency data-driven action dispatch system.

## Table of Contents

- [Why?](#why)
- [Getting started](#getting-started)
  - [Basic usage](#basic-usage)
  - [Fetch & display google homepage example](#fetch--display-google-homepage-example)
  - [All examples](#all-examples)
- [Default actions](#default-actions)
- [State snapshot](#state-snapshot)
- [Mixing normal ring handlers with FCIS handlers](#mixing-normal-ring-handlers-with-fcis-handlers)
- [Error handling](#error-handling)
- [Recommendations](#recommendations)
  - [Be careful using `nexus.registry` when using `nexus` both on frontend & backend](#be-careful-using-nexusregistry-when-using-nexus-both-on-frontend--backend)
  - [Read multiple times, write once](#read-multiple-times-write-once)
  - [Use an immutable DB like datomic](#use-an-immutable-db-like-datomic)
- [Acknowledgments](#acknowledgments)
- [License: MIT](#license-mit)

## Why?

Classic ring handlers are hard to test and 99% of time are impure functions. The classic ring handler goes like this:

1. Add dependencies (DB connection, client with internal secret, etc) to the request map (or to scope with a hoc)
2. Do impure stuff in the request handler body
3. Return a ring map based on the results

This is the "status quo" ring handler:
```clojure
(defn impure-handler
  [system] ;; inject dependencies through HoF
  (fn [req]
    (let [{:keys [conn]} system
          db (d/db conn)
          input (:body req)
          stuff (get-stuff db)
          bad-input? (compute-bad-input db req)]
      (when bad-input?
        ;; throw response for early exit
        (http-response/bad-request! {:message "Bad input"}))

      (when (should-call-external-service? input)
        (try
          @(notify-service! input)
          (catch ExceptionInfo e
            (http-response/internal-error! {:message "Something went wrong"})))
        (http-response/ok! {:message "All good with notification"}))

      (if
        (should-be-parallel? req)
        (do
          (notify-service! input)
          (http-response/ok {:message "Request issued"}))

        (do
          @(d/transact conn [input])
          (http-response/ok {:message "All good"}))))))
```

This works, but it is hard to test independently, unless you start-up your entire component system, making your tests be at minimum integration tests or E2E tests.

Using an action dispatch system, ring handlers can become pure:

```clojure
(defn pure-handler
  [req]
  (let [{:keys [db]} (:nexus/state req)
        input (:body req)
        stuff (get-stuff db)
        bad-input? (compute-bad-input db req)]
    (cond
      bad-input? [[:http-response/bad-request {:message "Bad input"}]]

      (should-call-external-service? input)
      [[:service/notify input
        {:on-success [[:db/transact [(merge stuff input)]]
                      [:http-response/ok
                       {:message "All good with notification"}]],
         :on-fail [[:http-response/internal-error
                    {:message "Something went wrong"}]]}]]

      (should-be-parallel? req)
      [[:process/parallel ;; parallel execution of the actions/effects
        [[:service/notify input]
         [:http-response/ok
          {:message "Request issued"}]]]]

      :else ;; All good, actions executed sequentially
      [[:db/transact [input]]
       [:http-response/ok {:message "All good!"}]])))
```

> **NOTE**: In the above example, the DB is a pure snapshot (like datomic). For SQL dbs, there needs to be a pre-requisite step of getting all of the required info from the DB

## Getting started

```clojure
 com.ovistoica/ring-nexus-middleware {:mvn/version "2025.07.20"}
```

### Basic usage

```clojure
(require '[ring-nexus-middleware.core :refer [wrap-nexus]])

(def store (atom {}))

;; See https://github.com/cjohansen/nexus for all config options for nexus
(def nexus {:nexus/system->state deref,
            :nexus/effects {:effects/save
                              (fn [_ store path v]
                                (swap! store assoc-in path v))}})

(defn handler
  [req]
  [[:effects/save [:it] "works!"]
   [:http-response/ok {:message "Saved to state"}]])

(def nexus-handler (wrap-nexus #'handler nexus store))

(nexus-handler dummy-req) ;; => {:status 200 :body {:message "Saved to state"}}

@store ;; => {:it "works!"}
```

### Fetch & display google homepage example. See [example code](./examples/src/ring_nexus_examples/google_page.clj)

```clojure
(ns ring-nexus-middleware-examples.google-page
  (:require [hato.client :as http]
            [ring-nexus-middleware.core :refer [wrap-nexus]]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params])
  (:import (clojure.lang ExceptionInfo)))

(def store (atom {}))

(defn get*
  "Util used to get keys from placeholder data"
  [m k]
  (if (vector? k) (get-in m k) (get m k)))

(def nexus
  {:nexus/system->state deref,
   :nexus/effects
     {:effects/save (fn [_ store path v] (swap! store assoc-in path v)),
      :effects/delay (fn [{:keys [dispatch]} _ ms actions]
                       (Thread/sleep ms)
                       (dispatch actions)),
      :effects/http
        (fn [{:keys [dispatch]} _ request-map & [{:keys [on-success on-fail]}]]
          (prn "Fetching request" request-map)
          (try (let [response (http/request request-map)]
                 (when (seq on-success)
                   (dispatch on-success {:http-response response})))
               (catch ExceptionInfo e
                 (when (seq on-fail)
                   (dispatch on-fail {:http-response (ex-data e)})))))},
   :nexus/placeholders {:http-response
                          (fn [{:keys [http-response]} ks]
                            (if http-response
                              (if ks (get* http-response ks) http-response)
                              ;; Return the original placeholder vector if
                              ;; no http-response
                              (if ks [:http-response ks] [:http-response])))}})

(defn fetch-google-handler
  "Fetch the main page of Google, return it as a response and store in the store"
  [{:keys [uri request-method]}]
  (if (and (= "/" uri) (= request-method :get))
    [[:effects/http {:method :get, :url "https://www.google.com"}
      {:on-success [[:effects/save [:google-page] [:http-response :body]]
                    [:http/respond
                     {:body [:http-response :body],
                      :headers {"content-type" "text/html"}}]]}]]
    [[:http-response/not-found "Not found"]]))

(defn start-server
  [port]
  (jetty/run-jetty (-> #'fetch-google-handler
                       (wrap-nexus nexus store))
                   {:port port, :join? false, :async? true}))
```

### All examples

See [all examples here](./examples/src/ring_nexus_examples/)

## Default actions
`ring-nexus-` by default provides several ring related effects/actions:

- `:http/respond` effect - takes a ring response map and responds to the request with it.

Convenience actions over `:http/respond`
- `:http-response/ok`
- `:http-response/bad-request`
- `:http-response/unauthorized`
- `:http-response/not-found`
- `:http-response/internal-server-error`
- `:http-response/forbidden`

## State snapshot

It's useful to have a state snapshot in the request, as we do in [pure nexus actions](https://github.com/cjohansen/nexus#pure-actions). To achieve this, `ring-nexus` provides a snapshot of the state at the time of the request. The default key containing the state is `:nexus/state`:

```clojure
(require '[ring-nexus-middleware :as ring-nexus])

(def store (atom {:hello :world}))

(def nexus
  {:nexus/system->state deref, ;; take store and get a snapshot
   :nexus/effects {:effects/save (fn [_ store path v]
                                   (swap! store assoc-in path v))}})

(defn print-state-handler
  [req]
  (let [state (:nexus/state req)] ;; pure snapshot
    [[:http-response/ok state]]))

(ring-nexus/wrap-nexus print-state-handler nexus store)

```

The state key can also be changed:

```clojure
(require '[ring-nexus-middleware :as ring-nexus])

(defn create-user
  [req]
  (let [user-input (:body req)
        state (:my.cool/state req)]
    (if (conflict-input? state (:body req))
      [[:http-response/bad-request {:message "Email aleary exists"}]]
      [[:effects/save [:users (:email user-input)] user-input]
       [:http-response/ok {:message "User saved succesfully"}]])))

(ring-nexus/wrap-nexus create-user nexus store {:ring-nexus/state-k :my.cool/state})
```

## Mixing normal ring handlers with FCIS handlers

`ring-nexus` mixes seamlessly with classing ring handlers. Simply return a classic ring map and the middleware will be bypassed. The action handler is only triggered when the return type is a vector (of actions).

```clojure
(defn normal [req] {:status 200 :body {:message "I am normal ring response"}})

(defn nexus [req] [[:http-response/ok {:message "I am FCIS ring response"}]])
```


## Error handling

By default, `ring-nexus` will throw any errors created in the handlers or during action/effect evaluation. The default error management strategy is [fail-fast strategy](https://github.com/cjohansen/nexus?tab=readme-ov-file#error-handling).

You can overwrite this by passing `:ring-nexus/fail-fast?` `false` to `wrap-nexus`.

`ring-nexus` acceps `:ring-nexus/on-error` callback config option. This function will be called when an error triggers during action/effect execution. Combine this with `:ring-nexus/fail-fast?` `false` to make your FCIS handlers return regardless of errors.

```clojure
(def store (atom {}))

(def nexus {:nexus/system->state deref
            :nexus/effects {:effects/save
                            (fn [_ store path v]
                              (throw (ex-info "Error saving to state" {:path path :v v}))
                              (swap! store assoc-in path v))}})

(defn no-throws-please
  [_]
  [[:effects/save [:a] 1]
   [:http-response/ok {:message "No error"}]])

(def handler (wrap-nexus no-throws-please nexus store {:ring-nexus/fail-fast? false
                                                       :ring-nexus/on-error #(prn "Error: " %)}))

(handler {}) ;;  => {:status 200 :body {:message "No error"} :headers {}}

;; Your console will print the error
```

## Recommendations

### Be careful using `nexus.registry` when using `nexus` both on frontend & backend

All of your actions/effects will be combined in the same registry, which can cause conflicts. You can either:
1. Use the registry in one scenario and a nexus map in the other
2. Create separate registries for frontend & backend

### Read multiple times, write once

Given the nature of FCIS, you cannot have multiple writes throughout the handler so you need to structure your handler logic to accomodate for this limitation.

### Use an immutable DB like datomic

This recommandation is optional, but it helps to have an entire snapshot of your DB in the handler to make assertions.

To replicate this with an SQL DB, you'd have to put a middleware before the final handler that receives the queries you need and puts the result into the request map.

## Acknowledgments

This library couldn't be possible without the libraries and FCIS promotion work of [James Reeves](https://www.booleanknot.com/) ([@weavejester](https://github.com/weavejester)), [Magnar Sveen](https://magnars.com) ([@magnars](https://github.com/magnars)), [Christian Johansen](https://cjohansen.no) ([@cjohansen](https://github.com/cjohansen)) and [Teodor Heggelund](https://play.teod.eu/) ([@teodorlu](https://github.com/teodorlu)).


## License: MIT

Copyright © 2025 Ovidiu Stoica.
Distributed under the [MIT License](https://opensource.org/license/mit).
