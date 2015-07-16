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

(defn valid-patch?
  "Validate an incoming patch object. It needs to be a map with
  keys :+ and :-"
  [patch]
  (and (map? patch)
    (contains? patch :+)
    (contains? patch :-)))

(defn empty-patch?
  "Does not check if the patch is valid."
  [patch]
  (= patch {:+ [], :- []}))

(defn rebase
  "Rebases the current state against head after applying patch.

  throws InvalidIndexException
  If the patch is applied to an index that does not exist. It'd be great if the
  patching algorithm was looser with application.
  "
  [head base patch]
  (let [working-changes (diff/diff base head)]
    ;; if the patch is the same, this is assumed to be an -ack-, otherwise
    ;; perform the rebase
    (if (= working-changes patch)
      head
      (-> base
        (diff/patch patch)
        (diff/patch working-changes)))))

(defn start-sync
  "Start synchronization of atoms whose state changes are propogated
  when it's state changes or when a patch is sent via data-in.

  Returns a channel which produces 'true' when both sender & receiver
  are in full sync.

  ref      - the reference object to synchronize
  data-in  - core.async channel for writing patches to
  data-out - core.async channel for reading patches from
  id       - id for debugging

  This is an implementation of Neil Fraser's `Differential Sync'.
  "
  ([ref data-in data-out id]
   (let [watch-id (gensym :diff-sync)
         init-state   @ref
         user-changes (a/chan)
         synced-ch    (a/chan (a/sliding-buffer 1))]
     (add-watch ref watch-id #(go (a/>! user-changes %&)))
     (go-loop [shadow        init-state
               backup-shadow init-state
               local-version 0
               ack-version   0]
       #?(:clj (timbre/debug id "Start" \newline
                 "Shadow        : " shadow \newline
                 "Backup shadow : " backup-shadow \newline
                 "Local version : " local-version \newline
                 "ACK version   : " ack-version \newline))
       (let [cur-state [shadow backup-shadow local-version ack-version]
             next-state
             (alt!
               data-in ([changes ch] (when-let [{:keys [version patch]} changes]
                                       (if (= version local-version)
                                         (do (when-not (empty-patch? patch)
                                               (swap! ref diff/patch patch))
                                             [(diff/patch shadow patch) shadow local-version (inc ack-version)])
                                         cur-state)))

               user-changes ([[key ref old-state new-state :as raw-data] ch]
                             (let [patch (diff/diff shadow new-state)]
                               (when (a/>! data-out {:version ack-version :patch patch})
                                 [new-state shadow (inc local-version) ack-version]))))]

         (if-let [[shadow' backup-shadow' local-version' ack-version'] next-state]
           (do
             #?(:clj (timbre/debug id "End" \newline
                       "shadow'        : " shadow' \newline
                       "backup-shadow' : " backup-shadow' \newline
                       "local-version' : " local-version' \newline
                       "ack-version'   : " ack-version' \newline))
             (when (= shadow' backup-shadow')
               (a/>! synced-ch true))
             (recur shadow' backup-shadow' local-version' ack-version'))

           (do
             #?(:clj (timbre/debug id "entangle shutting down... "))
             (remove-watch ref watch-id)
             (doall (map a/close! [data-in data-out synced-ch]))))))
     synced-ch)))

#?(:clj (timbre/set-level! :warn))
