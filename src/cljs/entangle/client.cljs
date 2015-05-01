(ns entangle.client
  (:require [figwheel.client :as fw]
            [entangle.core :as e]
            [cljs.core.async :as a]
            [cljs.reader :as reader])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn log [m]
  (.log js/console m))

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

;; Setup some websocket stuff
(defonce websocket* (atom nil))

(defn- main []
  (let [ws-chan (a/chan)]
    ;; Do the websocket dance
    (.log js/console "main")
    (.log js/console "establishing websocket...")
    (reset! websocket* (js/WebSocket. "ws://localhost:10000/sync"))

    ;; Setup the websocket object to respond to the messages
    (doall
      (map #(aset @websocket* (first %) (second %))
        [["onopen"    (fn []  (log "... websocket established!"))]
         ["onclose"   (fn []  (log "... websocket closed!"))]
         ["onerror"   (fn [e] (log (str "WS-ERROR:" e)))]
         ["onmessage" (fn [m] (go (a/>! ws-chan m)))]]))

    ;; Setup the first go-routine that reads messages from the websocket channel
    (go
      ;; Take the first message off ws-chan and print it
      (log (str "Handshake:" (a/<! ws-chan)))

      ;; Pre-process data before sending it off
      (a/pipeline 1
        (:textarea-in @client-state)
        (map reader/read-string)
        ws-chan))

    ;; Setup another go-routine that reads messages from data-out and syncs it
    (go (loop []
          (let [data (a/<! (:textarea-out @client-state))]
            (log (str "Sending diff to ws: " data))
            (.send @websocket* (pr-str data)))
          (recur))))

  (log "set all fields")

  (aset js/window "onunload"
    (fn []
      (.close @websocket*)
      (reset! @websocket* nil)))

  (log "unloaded")

  ;; Time to setup some data synchronization
  (let [synced-ch (e/start-sync textarea
                    (@client-state :textarea-in)
                    (@client-state :textarea-out))]

    ;; Tell us when we're synced. This would be interesting to know
    (go (loop []
          (a/<! synced-ch)
          (println "I am Synced.")
          (recur))))

  (log "ready to go"))
