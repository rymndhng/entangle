;; Step 1: Implement a single atom which is multiplexed over an aleph manifold
(ns entangle.single
  (:require
   [compojure.core :as compojure :refer [GET]]
   [ring.middleware.params :as params]
   [compojure.route :as route]
   [aleph.http :as http]
   [byte-streams :as bs]
   [manifold.stream :as s]
   [manifold.bus :as bus]
   [manifold.deferred :as d]
   [clojure.core.async :as a]
   [clojure.edn :as edn]
   [entangle.core :as e]
   [taoensso.timbre :as timbre]
   [hiccup.core :as h]))

(def homepage
  (h/html
    [:html
     [:head
      [:link {:rel "stylesheet" :href "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.4/css/bootstrap.min.css"}]
      [:script {:type "text/javascript" :src "public/js/out/goog/base.js"}]
      [:script {:type "text/javascript" :src "public/js/app.js"}]
      [:script {:type "text/javascript"} "goog.require('entangle.client');"]
      ]
     [:body
      [:h1 "Hello, Start Syncing?"]
      [:input {:id "form-name" :name "name" :placeholder "What is your name?"}]
      [:input.btn.btn-primary {:id "start-btn":type "button" :value "Start"}]
      [:br]
      [:textarea {:cols 80 :rows 10}]
      ]
     ]))

; An entangle is a single atom to sync
(def entangle-atom (atom ""))

;; Setup the atom
(def changes-in (a/chan))
(def changes-out (a/chan))

(e/start-sync entangle-atom changes-in changes-out "Single Atom Sync")

;; Simple web handler
(defn web-handler [_]
  {:status 200
   :headers {"content-type" "text/html"}
   :body homepage})


;; Handler for aleph websockets
(defn sync-handler
  [req]
  (d/let-flow [conn (d/catch (http/websocket-connection req)
                        (fn [_] nil))]
    (if-not conn
      {:status 400
       :headers {"content-type" "application/text"}
       :body "Expected a websocket request."}

      (d/let-flow [init-message (s/take! conn)]

        ;; deserialize data, write into entangle
        (s/connect
          (s/transform (map edn/read-string) conn)
          changes-in)

        ;; serialize, write out to stream
        ;; TODO: does this cause deadlock if the channel gacks?
        (s/connect
          (s/transform
            (map (fn [diff]
                   (timbre/debug "Server sending:" diff)
                   (pr-str diff))) changes-out)
          conn)
        (timbre/debug "Client connected: " init-message)
        ))))

;; Create the aleph server to synchronize with
(def handler
  (params/wrap-params
    (compojure/routes
      (GET "/sync" [] sync-handler)
      (GET "/" [] web-handler)
      (route/resources "/public")
      (route/not-found "No such page."))))

;; Check that things are actually working
(comment
  (def s (http/start-server handler {:port 10000}))
  (.close s)
  (future
    (def ws-atom (atom ""))
    (def ws-in (a/chan))
    (def ws-out (a/chan))
    (e/start-sync ws-atom ws-in ws-out)

    (def remote-conn @(http/websocket-client "ws://localhost:10000/sync"))
    (s/connect
      (s/transform (map edn/read-string) remote-conn)
      ws-in)
    (s/connect
      (s/transform
        (map (fn [x]
               (println "remote:" x)
               (pr-str x))) ws-out)
      remote-conn)

    ;; write a diff message into the websocket
    (s/put! remote-conn "HERRO")
    (s/put! remote-conn (str (clj-diff.core/diff  "" "FOO-BAR")))
    (def result (s/take! remote-conn))
    )
  (= @entangle-atom @ws-atom)
  (s/close! remote-conn)
)
