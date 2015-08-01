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

(timbre/set-level! :debug)

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

(defn cljs-compat-char
  "Serializes data for consumption in CLJS. Converts any clojure rich
  literals to use single strings.

  This is a compatibility patch for ClojureScript's lack of rich
  literal support in the reader.

  See See http://dev.clojure.org/jira/browse/CLJS-1299 "
  [data]
  (->> data
    (clojure.walk/prewalk (fn [x] (if (= (type x) java.lang.Character)
                                   (str x) x)))))


;; Handler for aleph websockets
(defn sync-handler
  [req]

  (d/let-flow [conn (d/catch (http/websocket-connection req)
                        #(timbre/error %))]
    (if-not conn
      {:status 400
       :headers {"content-type" "application/text"}
       :body "Expected a websocket request."}

      (d/let-flow [client-id (s/take! conn)
                   successful (s/put! conn (pr-str @entangle-atom))]
        (if-not (or client-id successful)
          (timbre/warn "Initial handshake failed." client-id)

          ;; every new connection gets to sync with the same atom
          (let [data-in  (a/chan)
                data-out (a/chan)
                sync     (a/chan)
                changes  (a/chan)]
            (e/start-sync entangle-atom data-in data-out client-id sync changes)

            ;; Setup a go-loop that sends data whenever a snapshot even thappens
            (a/go-loop []
              (let [change (a/<! changes)]
                (when (= :snapshot (:action change))
                  (timbre/debug "Syncing to client.")
                  (a/>! sync :pre-emptive)))
              (recur))

            (timbre/debug "Client connected: " client-id)

            ;; server should pre-emptively push and then sleepy for 500 ms


            ;; deserialize data, write into entangle
            ;; FIXME: according to the docs this will close the downstream channel
            ;;        automatically. Verify that
            (s/connect (s/transform (map edn/read-string) conn) data-in)

            ;; serialize, write out to stream
            ;; TODO: does this cause deadlock if the channel gacks?
            (s/connect
              (s/transform (map (comp pr-str cljs-compat-char)) data-out)
              conn)))))))

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
)
