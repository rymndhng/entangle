(ns entangle.counter
  (:require [cljs.user :as user]
            [cljs.core.async :as a]
            [entangle.websocket :as ws]
            [entangle.core :as e]
            [reagent.core :as r])
  (:require-macros [cljs.core.async.macros :as a]))

;; Reagent
(defonce click-count (r/atom [0]))

(defn counting-component []
  [:div
   "The atom " [:code "click-count"] " has value: "
   (first @click-count) ". "
   [:input {:type "button" :value "Click me!"
            :on-click #(swap! click-count update 0 inc)}]])

;; Unset at first
(defonce entangle-atom (atom nil))
(defonce websocket (atom nil))
(defonce <in (a/chan))
(defonce <out (a/chan))
(defonce <sync (a/chan))
(defonce <snapshot (a/chan))
(defonce <ready? (a/chan))

(defn start
  []
  (ws/bind-websocket! websocket entangle-atom <in <out <ready?)
  (a/go-loop []
    (a/<! (a/timeout 100))
    (println "syncing")
    (when (a/>! <sync :now)
      (recur)))

  (a/go-loop []
    (when-let [val (a/<! <snapshot)]
      (println val)
      (recur)))

  (a/go (a/<! <ready?)
        (e/start-sync click-count <in <out :counter <sync <snapshot)
        (r/render-component [counting-component] (.getElementById js/document "app"))
        (println "I'm ready!")))

(.addEventListener js/document "DOMContentLoaded" (fn [] (start)))
