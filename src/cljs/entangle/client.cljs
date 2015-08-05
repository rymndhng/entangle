(ns entangle.client
  (:require [figwheel.client :as fw]
            [entangle.core :as e]
            [cljs.core.async :as a]
            [cljs.reader :as reader]
            [taoensso.timbre :as timbre])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

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

(defonce entangle-atom (atom ""))

;; Setup some websocket stuff
(defonce websocket* (atom nil))

(defn start-reactive-textarea [write-ch]
  "Wrapper to setup the textarea to reactively render when 'textarea'
  changes."
  (let [dom-textarea (.getElementById js/document "render-text")]
    ;; reactively re-render
    (add-watch entangle-atom :ui-render
      (fn [key ref old-state new-state]
        ;; (timbre/debug "New state:" new-state)
        (when (not (= @entangle-atom (aget dom-textarea "value")))
          (aset dom-textarea "value" new-state))))

    ;; TODO: this is currently buggy and blows up
    (aset dom-textarea "onkeyup"
      (fn [x]
        (a/put! write-ch (str (aget dom-textarea "value")))))))

(defn- main []
  (let [ws-chan (a/chan)
        data-in (a/chan)
        data-out (a/chan)
        sync-ch (a/chan (a/dropping-buffer 1))
        changes-ch (a/chan)
        <text-changes (a/chan (a/sliding-buffer 1))
        host (aget js/window "location" "host")]
    ;; Do the websocket dance
    (log "main")
    (log "establishing websocket...")
    (reset! websocket* (js/WebSocket. (str "ws://" host "/sync")))

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

    ;; Setup another go-routine that prepares data for outward flow
    (go-loop []
        (when-let [data (a/<! data-out)]
          (log (str "Sending diff to ws: " data))
          (.send @websocket* (pr-str data))
          (recur))
        (log "Writing out closed!"))

    ;; do syncing in 500 ms intervals
    ;; Setup a go-loop that sends data whenever a snapshot even thappens
    (go-loop []
      (if-let [change (a/<! changes-ch)]
        (do
          (log (str "Changes: " change))
          (a/timeout 200)
          (recur))
        (log "Writing out closed!")))

    ;; Setup the first go-routine that reads messages from the websocket channel
    (go
      ;; Take the first message off ws-chan and use it to setup the initial state
      (let [initial-state (a/<! ws-chan)]
        (reset! entangle-atom (reader/read-string initial-state))
        (e/start-sync entangle-atom data-in data-out "webclient" sync-ch changes-ch)

        (log (str "Initial State:" initial-state))

        ;; pipeline the rest into entangle-atom
        (a/pipeline 1 data-in (map reader/read-string) ws-chan)

        ;; trigger an initial sync
        (swap! entangle-atom identity)

        ;; TODO: rework the frontend so we can easily notify when synced
        )
      (aset (.getElementById js/document "render-text") "disabled" nil))

    ;; start that textarea
    (start-reactive-textarea <text-changes)

    ;; debounce changes to the nearest 500 ms
    (go-loop []
      (when-let [init-text (a/<! <text-changes)]
        (->> (loop [text init-text
                    <timeout (a/timeout 16)]
               (let [[content ch] (a/alts! [<timeout <text-changes])]
                 (if (= ch <timeout)
                   (do
                     (log (str "Debounce ended:" text))
                     text)
                   (do
                     (log "Debouncing")
                     (recur content <timeout)))))
          (reset! entangle-atom))
        (recur)))

    (aset js/window "onunload"
      (fn []
        (log "unloading")
        (a/close! data-in)
        (.close @websocket*)
        (reset! @websocket* nil))))

  (log "ready to go"))

(aset js/window "onload"
  (fn []
    ;; reactive text box!
    ;; enable start via main
    (aset (.getElementById js/document "start-btn") "onclick" main)))
