(ns entangle.client
  (:require [entangle.core :as e]
            [entangle.websocket :as ws]
            [cljs.core.async :as a]
            [cljs.reader :as reader]
            [taoensso.timbre :as timbre])
  (:require-macros [cljs.core.async.macros :as a]))

;; State
(defonce entangle-atom (atom ""))
(defonce websocket* (atom nil))

(defn start-reactive-textarea [write-ch]
  "Wrapper to setup the textarea to reactively render when 'textarea'
  changes."
  (let [dom-textarea (.getElementById js/document "app")]
    (.setAttribute dom-textarea "contenteditable" true)
    ;; reactively re-render
    (add-watch entangle-atom :ui-render
      (fn [key ref old-state new-state]
        ;; (timbre/debug "New state:" new-state)
        (when (not (= @entangle-atom (.-innerHTML dom-textarea)))
          (aset dom-textarea "innerHTML" @entangle-atom))))

    ;; TODO: this is currently buggy and blows up
    (aset dom-textarea "onkeyup"
      (fn [x]
        (when-not (= (.-innerHTML dom-textarea) @entangle-atom)
          (reset! entangle-atom (.-innerHTML dom-textarea))
          (a/put! write-ch :doit))

        #_(a/put! write-ch (str (aget dom-textarea "value")))))))

(defn main []
  (let [<in (a/chan)
        <out (a/chan)
        <ready? (a/chan)
        <sync (a/chan (a/dropping-buffer 1))
        <changes (a/chan)
        <text-changes (a/chan (a/sliding-buffer 1))]
    (start-reactive-textarea <sync)
    (ws/bind-websocket! websocket* entangle-atom <in <out <ready?)

    (a/go
      (a/<! <ready?)
      (e/start-sync entangle-atom <in <out :waow <sync <changes))

    (a/go-loop []
      (println (a/<! <changes))
      (recur))

    (a/go-loop []
      (a/<! <text-changes)
      (a/<! (a/timeout 16))
      (a/>! <sync :watch)
      (recur))))

(.addEventListener js/document "DOMContentLoaded"
  (fn []
    (main)
    ;; reactive text box!
    ;; enable start via main
    ))
