(ns entangle.client
  (:require [figwheel.client :as fw]
            [entangle.core :as e]
            [cljs.core.async :as a]
            [cljs.reader :as reader])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn log [m] (println m))

(fw/enable-repl-print!)
; (enable-console-print!)

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

(defonce textarea (atom ""))

;; Setup some websocket stuff
(defonce websocket* (atom nil))

(defn- main []
  (let [ws-chan (a/chan)
        data-in (a/chan)
        data-out (a/chan)]
    ;; Do the websocket dance
    (log "main")
    (log "establishing websocket...")
    (reset! websocket* (js/WebSocket. "ws://localhost:10000/sync"))

    ;; Setup the websocket object to respond to the messages
    (doall
      (map #(aset @websocket* (first %) (second %))
        [["onopen"    (fn []
                        (log "... websocket established!")
                        ;; send the name in
                        (.send @websocket* "HERROE!"))]
         ["onclose"   (fn []
                        (log "... websocket closed!")
                        (a/close! data-in))]
         ["onerror"   (fn [e]
                        (log (str "WS-ERROR:" e))
                        (a/close! data-in))]
         ["onmessage" (fn [m]
                        (log (str "GOT:" (aget m "data")))
                        (go (a/>! ws-chan (aget m "data"))))]]))

    ;; Setup the first go-routine that reads messages from the websocket channel
    (go
      ;; Take the first message off ws-chan and use it to setup the initial state
      (let [initial-state (a/<! ws-chan)]
        (log (str "Initial State:" initial-state))
        (reset! textarea initial-state)

        ;; TODO: rework the frontend so we can easily notify when synced
        (e/start-sync textarea
          data-in
          data-out
          "webclient"))

      ;; pipeline the rest into textarea
      (a/pipeline 1 data-in (map reader/read-string) ws-chan))

    ;; Setup another go-routine that prepares data for outward flow
    (go (loop []
          (let [data (a/<! data-out)]
            (log (str "Sending diff to ws: " data))
            (.send @websocket* (pr-str data)))
          (recur)))

    (aset js/window "onunload"
      (fn []
        (log "unloading")
        (a/close! data-in)
        (.close @websocket*)
        (reset! @websocket* nil))))

  (log "ready to go"))
