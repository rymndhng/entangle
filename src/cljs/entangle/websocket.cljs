(ns entangle.websocket
  (:require [cljs.core.async :as a]
            [clojure.string :as string]
            [cljs.reader :as reader])
  (:require-macros [cljs.core.async.macros :as a :refer [go go-loop]]))

;; this is something of the URL, don't change this.
(defonce *ws-path*
  (let [base (-> (aget js/window "location" "href")
               (.split "?")
               first
               (.slice 5))]
    (str "ws:" base "sync")))

(defn bind-websocket!
   "Connect data in and out to a websocket. Returns a channel that closes when
  setup is done. When the channel closes, you should do e/start-sync.

  websocket* - atom to put the websocket in
  ref        - atom to write initial state to
  <data-in   - channel to receive clj.diffs
  <data-out  - channel to send clj.diffs
  <ready?    - channel that gets closed when ready
  "
  [websocket* ref <data-in <data-out <ready?]
  (let [ws-chan (a/chan 20)]          ;; channel for buffering input

    ;; Setup some plumbling for handling websockets and resources
    (reset! websocket* (js/WebSocket. *ws-path*))
    (doall
      (map #(aset @websocket* (first %) (second %))
        [["onopen"    (fn []
                        (println "... websocket established!")
                        ;; send the name in
                        (.send @websocket* "HERROE!"))]
         ["onclose"   (fn []
                        (println "... websocket closed!")
                        (a/close! <data-in))]
         ["onerror"   (fn [e]
                        (println (str "WS-ERROR:" e))
                        (a/close! <data-in))]
         ["onmessage" (fn [m]
                        (println (str "GOT:" (aget m "data")))
                        (a/put! ws-chan (aget m "data")))]]))
    (aset js/window "onunload"
      (fn []
        (println "unloading")
        (a/close! <data-in)
        (.close @websocket*)
        (reset! @websocket* nil)))

    ;; Setup go-routine to write data out
    (a/go-loop []
      (when-let [data (a/<! <data-out)]
        #_(println (str "Sending diff to ws: " data))
        (.send @websocket* (pr-str data))
        (recur))
      (println "Writing out closed!"))

    ;; Setup in-coming data
    (go
      ;; Take the first message off ws-chan and use it to setup the initial state
      (let [initial-state (a/<! ws-chan)]
        (println (str "Initial State:" initial-state))
        (reset! ref (reader/read-string initial-state))

        ;; pipeline the rest into entangle-atom
        (a/pipeline 1 <data-in (map reader/read-string) ws-chan)
        (println "You should do start-sync now!")

        ;; do it!
        (a/close! <ready?)))))
