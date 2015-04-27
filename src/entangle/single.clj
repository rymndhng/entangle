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
   [entangle.core :as e]))

; An entangle is a single atom to sync
(def entangle-atom (atom ""))

;; Setup the atom
(def changes-in (a/chan))
(def changes-out (a/chan))

(e/start-sync entangle-atom changes-in changes-out "Single Atom Sync")

;; Watch for messages to be tapped in and out
#_(let [tap-in (a/chan)
      tap-out (a/chan)]
  (a/tap changes-in tap-in)
  (a/tap changes-out tap-out)
  (a/go-loop []
    (let [[message port] (a/alts! [tap-in tap-out])
          port-name (if (= tap-in port) "Into Atom" "Out of Atom")]
      (println port-name ":" message)
      )
    (recur)))


(defn web-handler [_]
  {:status 200
   :headers {"content-type" "text/html"}
   :body "Join me!"})

(defn sync-handler
  [req]
  (d/let-flow [conn (d/catch (http/websocket-connection req)
                        (fn [_] nil))]
    (if-not conn
      {:status 400
       :headers {"content-type" "application/text"}
       :body "Expected a websocket request."}

      (d/let-flow [init-message (s/take! conn)]
        (println "Got: " init-message)

        ;; deserialize data, write into entangle
        (s/connect
          (s/transform (map edn/read-string) conn)
          changes-in)

        ;; serialize, write out to stream
        ;; TODO: does this cause deadlock if the channel gacks?
        (s/connect
          (s/transform
            (map (fn [x]
                   (println x)
                   (pr-str x))) changes-out)
          conn)

        (println "Connected successfully")))))

;; Create the aleph server to synchronize with
(def handler
  (params/wrap-params
    (compojure/routes
      (GET "/sync" [] sync-handler)
      (GET "/" [] web-handler)
      (route/not-found "No such page."))))

(comment
  (.close s)
  (def s (http/start-server handler {:port 10000}))
  )

;; Let's create some tests
(comment

  (future
    (def ws-atom (atom ""))
    (def remote-conn @(http/websocket-client "ws://localhost:10000/sync"))

    ;; write a diff message into the websocket
    (s/put! remote-conn "HERRO")
    (s/put! remote-conn (str (clj-diff.core/diff  "" "FOO-BAR")))
    (def result (s/take! remote-conn)))

  (s/close! remote-conn)
)
