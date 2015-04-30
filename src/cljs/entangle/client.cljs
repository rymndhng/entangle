(ns entangle.client
  (:require [figwheel.client :as fw]
            [entangle.core :as e]
            [cljs.core.async :as a])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(enable-console-print!)

(fw/start {
           ;; configure a websocket url if you are using your own server
           :websocket-url "ws://localhost:3449/figwheel-ws"

           ;; optional callback
           :on-jsload (fn [] (print "Reloaded baby"))

           ;; The heads up display is enabled by default
           ;; to disable it:
           ;; :heads-up-display false

           ;; when the compiler emits warnings figwheel
           ;; blocks the loading of files.
           ;; To disable this behavior:
           ;; :load-warninged-code true

           ;; if figwheel is watching more than one build
           ;; it can be helpful to specify a build id for
           ;; the client to focus on
           ;; :build-id "example"
           })

(defonce client-state
  (atom {:textarea-in (a/chan)
         :textarea-out (a/chan)}))

(defonce textarea (atom ""))


(println "foo")

;; Tell us when we're synced. This would be interesting to know
(let [synced-ch (e/start-sync textarea
                  (@client-state :textarea-in)
                  (@client-state :textarea-out))]
  (go (loop []
        (a/<! synced-ch)
        (println "I am Synced.")
        (recur))))

;; Setup some websocket stuff
(def websocket* (atom nil))

(defn- main []
  (log "main")
  (log "establishing websocket...")
  (reset! websocket* (js/WebSocket. "ws://localhost:10000/sync")))
