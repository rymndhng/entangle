;; Step 1: Implement a single atom which is multiplexed over an aleph manifold
(ns entangle.daemon
  (:gen-class)
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
   [hiccup.core :as h]
   [hiccup.element :as he]))

(timbre/set-level! :debug)

(defn template
  "Takes a single argument: module -- the clojurescript module to load."
  [module]
  (h/html
    [:html
     [:head
      [:link {:rel "stylesheet" :href "https://maxcdn.bootstrapcdn.com/bootstrap/3.3.4/css/bootstrap.min.css"}]
      [:script {:type "text/javascript" :src "public/js/out/goog/base.js"}]
      [:script {:type "text/javascript" :src "public/js/out/cljs_deps.js"}]
      (he/javascript-tag (str  "
if (typeof goog != 'undefined') {
    goog.require('cljs.user');
} else { console.warn('ClojureScript could not load :main, did you forget to specify :asset-path?'); };

goog.require('" module "');"))]
     [:body
      [:div {:class "container"}
       [:div {:class "row"}
        [:div {:class "col-xs-12"}
         [:h1 "A Demo Below"]]]
       [:div {:class "row"}
        [:div {:class "col-xs-12"}
         [:div#app]]]]]]))


; An entangle is a single atom to sync. This will make interacting with it simpler
(def entangle-atom (atom ""))

;; Simple web handler
(defn web-handler [module]
  {:status 200
   :headers {"content-type" "text/html"}
   :body (template module)})

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

(defn sync-handler
  "Defines the steps to setup a synchronizing atom starting from an
  initial web socket requests.

  Once the websocket connection is established, we start listening to
  changes to `entangle-atom`. This sets up:

  1. Duplex connection for listening and writing diffs to the websocket
  2. Event handler for listening to when `entangle-atom` changes and also how to
  write and read diffs from the websocket
  3. Throttling mechanism to control the rate of change processing.
  "
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
          (let [data-in  (a/chan)
                data-out (a/chan)
                sync     (a/chan (a/dropping-buffer 1))
                changes  (a/chan)
                debounce (a/chan)]
            (timbre/debug "Client connected: " client-id)

            (e/start-sync entangle-atom data-in data-out client-id sync changes)
            (add-watch entangle-atom (gensym client-id) (fn [_ _ _ _ ] (a/put! debounce :watch)))

            ;; Controls the frequency of event processing. This is arbitrariliy
            ;; chosen to be 500 ms so that changes can coalesce from multiple
            ;; clients
            (a/go-loop []
              (a/<! debounce)
              (a/<! (a/timeout 16))
              (a/>! sync :watch)
              (timbre/warn "Next tiem!")
              (recur))

            ;; (a/go-loop []
            ;;   (a/timeout 10000)
            ;;   (a/>! sync :flush)
            ;;   (recur))

            (a/go-loop []
              (a/<! changes)
              (recur))

            ;; Serialize and de-serialize the channels into the websocket
            (let [deserialize (map edn/read-string)
                  serialize (map (comp pr-str cljs-compat-char))]
              (s/connect (s/transform deserialize (s/buffer 100 conn)) data-in)
              (s/connect (s/transform serialize data-out) conn))))))))

;; Create the aleph server to synchronize with
(def handler
  (params/wrap-params
    (compojure/routes
      (GET "/sync" [] sync-handler)
      (GET "/" [module] (web-handler module))
      (route/resources "/public")
      (route/not-found "No such page."))))

(defn -main
  [& args]
  (let [port (or (first args) 10000)]
    (let [server-var (find-var 'entangle.daemon/server)]
      (when (bound? server-var)
        (.close (var-get server-var))))
    (timbre/info "Serving entangle on port " port)
    (def server (http/start-server handler {:port port}))))
