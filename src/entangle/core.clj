;;## Implementation of Neil Fraser's Differential Sync Algorithm in Clojure
;;Link: https://neil.fraser.name/writing/sync/
;;
;; Implementats synchronization of reference data types to keep two atoms in
;; sync using diffing.
;;
;; This library uses core.async and clj-diff
(ns entangle.core
  (:require [clojure.core.async :as a]
            [clojure.test :as test]
            [clj-diff.core :as diff]))

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
     (add-watch ref :diff-sync #(future (a/>!! user-changes %&)))
     (a/go-loop [shadow cur-value]
       (a/alt!
         data-in ([patch ch]
                  ;; (println (str  id " patch " patch ":" shadow))
                  (if (empty-patch? patch)
                    (a/>! synced-ch true)
                    (a/thread (swap! ref rebase shadow patch)))
                  (recur (diff/patch shadow patch)))
         user-changes ([[key ref old-state new-state] ch]
                       ;; (println (str id " watch " key ":" ref ":" old-state ":" new-state ":" shadow))
                       (let [patch (diff/diff shadow new-state)]
                         (when (empty-patch? patch)
                           (a/>! synced-ch true))
                         (a/>! data-out patch)
                         (recur new-state)))))
     synced-ch)))
