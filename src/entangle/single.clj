;; Step 1: Implement a single atom which is multiplexed over an aleph manifold
(ns entangle.single
  (:require
   [compojure.core :as compojure :refer [GET]]
   [ring.middleware.params :as params]
   [compojure.route :as route]
   [aleph.http :as http]
   [manifold.stream :as s]
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
      [:div {:class "container"}
       [:div {:class "row"}
        [:div {:class "col-xs-12"}
         [:h1 "Hello, Start Syncing?"]]]
       [:div {:class "row"}
        [:div.col-xs-12
         [:form.form-inline
          [:div.form-group
           [:label {:for "form-name"}]
           [:input.form-control {:id "form-name"
                                 :type "text" :name "name" :placeholder "Your Name"}]]
          " "
          [:input.btn.btn-primary {:id "start-btn" :type "button" :value "Start"}]]]]
       [:div {:class "row"}
        [:div {:class "col-xs-6"}
         [:textarea.form-control {:id "render-text" :cols 80 :rows 10 :disabled true}]]
        [:div {:class "col-xs-6"}
         [:p "Changes"]
         [:div#repr]]]]]]))

; An entangle is a single atom to sync. This will make interacting with it simpler
(def entangle-atom (atom ""))

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

      (d/let-flow [client-id (s/take! conn)]

        ;; every new connection gets to sync with the same atom
        (let [changes-in (a/chan)
              changes-out (a/chan)]

          ;; Co-ordinate reading with starting sync
          (d/on-realized
            (dosync
              (e/start-sync entangle-atom changes-in changes-out client-id)
              (s/put! conn (pr-str @entangle-atom)))
            (fn [x] (timbre/warn "Client handshake complete: " client-id))
            (fn []  (timbre/warn "Client refused initial state. " client-id)
                   (s/close! changes-in)))

          ;; Use init message as the connected user
          ;; TODO: this needs to be co-ordinated with the initial value ()


          ;; deserialize data, write into entangle
          ;; FIXME: according to the docs this will close the downstream channel
          ;;        automatically. Verify that
          (s/connect (s/transform (map edn/read-string) conn) changes-in)

          ;; serialize, write out to stream
          ;; TODO: does this cause deadlock if the channel gacks?
          (s/connect
            (s/transform
              (map (fn [diff]
                     (timbre/debug "Server sending:" diff)
                     (->> diff
                       ;; FIXME: Stringify all characters because
                       ;; ClojureScript's reader does not support rich literals.
                       ;; See http://dev.clojure.org/jira/browse/CLJS-1299
                       (clojure.walk/prewalk (fn [x] (if (= (type x) java.lang.Character)
                                                      (str x) x)))
                       pr-str)))
              changes-out)
            conn))

        (timbre/debug "Client connected: " client-id)
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
    (e/start-sync ws-atom ws-in ws-out :foo)

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
