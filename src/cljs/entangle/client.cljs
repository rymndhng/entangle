(ns entangle.client
  (:require [entangle.core :as e]
            [cljs.core.async :as a]
            [cljs.reader :as reader]
            [taoensso.timbre :as timbre]
            [clojure.string :as string])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(defn log [m] (println m))

;; Some global state objects
(defonce codeMirror (js/CodeMirror
                      (.getElementById js/document "render-text")
                      #js {:value ""
                           :mode "javascript"
                           :inputStyle "contenteditable"}))

(defonce codeMirrorShadow (atom ""))

(defonce entangle-atom (atom ""))
(defonce websocket* (atom nil))

(defn to-codemirror-patch
  "Converts a clj.diff patch into a single replaceRange command."
  [lookup patch]
  (let [start-index (first patch)
        passed      (into [] (take-while #(< % start-index) (keys lookup)))
        line-no     (count passed)
        column      (- start-index (last passed))]
    [(clojure.string/join (rest patch)) {:line line-no :ch column}]))

(defn patch-to-codemirror-diffs [base patches]
  (let [lines (clojure.string/split-lines base)
        index (reduce #(conj %1 (+ (last %1) (.-length %2))) [0] lines)
        lookup (zipmap index lines)]
    lookup))

(defn start-reactive-textarea [write-ch]
  "Wrapper to setup the textarea to reactively render when 'textarea'
  changes."

  ;; TODO: rework this to use patches instead... but I'm lazy for now.
  (.on codeMirror "changes" (fn [cm changes]
                              #_(println changes)
                              (reset! entangle-atom (.getValue cm))))

  #_(go-loop []
    (a/<! (a/timeout 1000))
    ;; get diffs and update the range
    (.setValue codeMirror @entangle-atom)
    (recur)))

(defn main []
  (let [ws-chan (a/chan 20) ;; buffer changes against external environment
        data-in (a/chan)
        data-out (a/chan)
        sync-ch (a/chan (a/dropping-buffer 1))
        changes-ch nil
        <text-changes (a/chan (a/sliding-buffer 1))
        ws-path (str "ws:"
                  (string/join "" (drop 5 (aget js/window "location" "href")))
                  "sync")]
    ;; Do the websocket dance
    (log "main")
    (log "establishing websocket...")
    (reset! websocket* (js/WebSocket. ws-path))

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
                        #_(log (str "GOT:" (aget m "data")))
                        (a/put! ws-chan (aget m "data")))]]))

    ;; Setup another go-routine that prepares data for outward flow
    (go-loop []
        (when-let [data (a/<! data-out)]
          #_(log (str "Sending diff to ws: " data))
          (.send @websocket* (pr-str data))
          (recur))
        (log "Writing out closed!"))

    ;; when we send an outbound request, don't send another one until we get one back

    ;; do syncing in 500 ms intervals
    ;; Setup a go-loop that sends data whenever a snapshot even thappens
    (go-loop []
      (when (a/>! sync-ch :now)
        (a/<! (a/timeout 1000))
        (recur))
      (timbre/debug "Watching changes closed."))

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
      #_(aset (.getElementById js/document "render-text") "disabled" nil))

    ;; start that textarea
    (start-reactive-textarea sync-ch)

    ;; debounce changes to the nearest 500 ms
    #_(go-loop []
      (when-let [init-text (a/<! <text-changes)]
        (->> (loop [text init-text
                    <timeout (a/timeout 32)]
               (let [[content ch] (a/alts! [<timeout <text-changes])]
                 (if (= ch <timeout)
                   (do
                     (log (str "Debounce ended:" text))
                     text)
                   (do
                     (log "Debouncing")
                     (recur content <timeout)))))
          (a/>! sync-ch :do-it))
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
