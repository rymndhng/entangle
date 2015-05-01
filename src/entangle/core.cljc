;;## Implementation of Neil Fraser's Differential Sync Algorithm in Clojure
;;Link: https://neil.fraser.name/writing/sync/
;;
;; Implementats synchronization of reference data types to keep two atoms in
;; sync using diffing.
;;
;; This library uses core.async and clj-diff
(ns entangle.core
  #?@(:clj
      [(:require [clojure.core.async :as a :refer [go go-loop alt!]]
                 [clj-diff.core :as diff]
                 [taoensso.timbre :as timbre])]
      :cljs
      [(:require-macros [cljs.core.async.macros :refer [go go-loop alt!]])
       (:require [cljs.core.async :as a]
                 [clj-diff.core :as diff])]
      ))

#?(:clj (timbre/set-level! :warn))

(defn empty-patch?
  "Needs work."
  [patch]
  (or (empty? patch)
      (= patch {:+ [], :- []})))

(defn rebase
  "Rebases the current state against head after applying patch."
  [head base patch]
  (let [working-changes (diff/diff base head)]
    (-> base
      (diff/patch patch)
      (diff/patch working-changes))))

(defn start-sync
  "Start synchronization of atoms whose state changes are propogated when it's
  state changes or when a patch is sent via data-in.

  Returns a channel which produces 'true' when both sender & receiver are in
  full sync.

  ref      - the reference object to synchronize
  data-in  - core.async channel for writing patches to
  data-out - core.async channel for reading patches from
  id       - id for debugging
  "
  ([ref data-in data-out] (start-sync ref data-in data-out nil))
  ([ref data-in data-out id]
   (let [cur-value @ref
         user-changes (a/chan)
         synced-ch (a/chan (a/sliding-buffer 1))]
     (add-watch ref :diff-sync #(go (a/>! user-changes %&)))
     (go-loop [shadow cur-value]
       (alt!
         data-in ([patch ch]
                  #?(:clj (timbre/debug :default println id " patch " patch ":" shadow))

                  ;; If an empty patch comes through, we're fully synced
                  (if (empty-patch? patch)
                    (a/>! synced-ch true)

                    ;; We can use swap because add-watch's callback is async as well
                    (swap! ref rebase shadow patch))


                  (recur (diff/patch shadow patch)))

         user-changes ([[key ref old-state new-state] ch]
                       #?(:clj (timbre/debug id " watch " key ":" ref ":" old-state ":" new-state ":" shadow) )
                       (let [patch (diff/diff shadow new-state)]
                         (when (empty-patch? patch)
                           (a/>! synced-ch true))
                         (a/>! data-out patch)
                         (recur new-state)))))
     synced-ch)))
