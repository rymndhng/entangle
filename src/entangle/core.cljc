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
  "Start synchronization of atoms whose state changes are propogated when it's
  state changes or when a patch is sent via data-in.

  Returns a channel which produces 'true' when both sender & receiver are in
  full sync.

  ref      - the reference object to synchronize
  data-in  - core.async channel for writing patches to
  data-out - core.async channel for reading patches from
  version  - initial counter for the patch version applied
  id       - id for debugging

  TODO: this is broken because there's no concept of version numbering
  "
  ([ref data-in data-out] (start-sync ref data-in data-out 0 nil))
  ([ref data-in data-out version id]
   (let [init-state   @ref
         user-changes (a/chan)
         synced-ch    (a/chan (a/sliding-buffer 1))]
     (add-watch ref :diff-sync #(go (a/>! user-changes %&)))
     (go-loop [shadow        init-state
               backup-shadow init-state
               local-version version
               ack-version version]
       #?(:clj (timbre/debug id "Start: "
                 shadow \, backup-shadow \, local-version \, ack-version))
       (let [cur-state [shadow backup-shadow local-version ack-version]
             next-state
             (alt!
               ;; handle incoming patches updates the shadow and the client text
               data-in
               ([changes ch]
                (when-let [{:keys [version patch]} changes]
                  #? (:clj (timbre/debug id "data-in: " cur-state changes))

                  ;; TODO: this ignores older diffs
                  (if (and (= version local-version)
                        (not (empty-patch? patch)))

                    ;; If the version is equal to the expected ack-version, perform an update
                    (do
                      (swap! ref diff/patch patch)
                      [(diff/patch shadow patch) shadow local-version (inc ack-version)])

                    ;; otherwise ignore it (because we've already applied it)
                    ;; TODO: queue up messages instead..
                    cur-state)))

               ;; handle changes to the ref which should fire off a sync
               user-changes
               ([[key ref old-state new-state :as raw-data] ch]
                #?(:clj (timbre/debug id "user-changes: " raw-data))
                (let [patch (diff/diff shadow new-state)]
                  ;; in the event the channel returns nil, we assume the channel
                  ;; die and set the state to `nil` so we exit and perform
                  ;; cleanup
                  (when (a/>! data-out {:version ack-version
                                        :patch patch})
                    (if (empty-patch? patch)
                      cur-state
                      [new-state shadow (inc local-version) ack-version])))))]

         (if-let [[shadow' backup-shadow' local-version' ack-version'] next-state]
           (do
             #?(:clj (timbre/debug id "cmp" cur-state next-state))
             ;; If state has not changed, you are synced
             (when (and (= shadow shadow') (= local-version local-version')
                     (= ack-version ack-version'))
               (a/>! synced-ch true))
             (recur shadow' backup-shadow' local-version' ack-version'))

           ;; cleanup otherwise
           (do
             #?(:clj (timbre/debug id "entangle shutting down... "))
             (remove-watch ref :diff-sync)
             (doall (map a/close! [data-in data-out synced-ch]))
             #?(:clj (timbre/debug "entangle finished ... " id))))))
     synced-ch)))

#?(:clj (timbre/set-level! :debug))
