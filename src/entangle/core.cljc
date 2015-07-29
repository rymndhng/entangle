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
      [(:require-macros [cljs.core.async.macros :as a :refer [go go-loop alt!]])
       (:require [cljs.core.async :as a]
                 [clj-diff.core :as diff]
                 [taoensso.timbre :as timbre])]
      ))

(timbre/set-level! :warn)

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

(defn poke [ref]
  "Pokes the ref so that a sync is propogated."
  (swap! ref identity))

(defn poke!
  "Pokes the ref every msec to trigger a sync. This is useful when one
  client is producing no changes. "
  [sync-ch msec]
  (go-loop []
    (a/<! (a/timeout msec))
    (a/>! sync-ch :poke!)
    (recur)))

(defn patch-compatible? [shadow patch]
  "Are the versions of shadow and patch compatible"
  (and (= (get shadow :n :not-in-shadow)
          (get patch  :n :not-in-patch))))

(defn try-patch [base patch]
  "Tries to apply the diff which may fail. This fuzziness should be pushed down
  to the diffing algorithm."
  (try
    (diff/patch base patch)
    (catch #?(:clj Exception
              :cljs (js/Error.)) e
      (timbre/warn "Unable to apply to base: " base " Patch: " patch)
      base)))

(defn apply-all-edits [base edits]
  "Applies all sequential patches onto a base object"
  (reduce try-patch base edits))

(defn- prepare-patch
  "Implements part 2,3 of algorithm from state."
  [{:keys [snapshot shadow backup edits-queue] :as state}]
  (let [patch {:n (:m shadow)
               :m (:n shadow)
               :diff (diff/diff (:content shadow) snapshot)}]
    (merge state {:shadow (-> shadow
                            (update :n inc)
                            (assoc :content snapshot))
                  :backup shadow
                  :edits-queue (conj edits-queue patch)})))

(defn- handle-incoming-patch
  "Implements part 5,6,7 of algorithm. Returns a tuple of two elements
  where the first element contains all the state for the next recur,
  and a list of diffs to apply to the state. "
  [patch {:keys [shadow backup edits-queue] :as state}]
  (let [edits (remove #(< (:m %)
                          (:m shadow)) patch)]
    ;; figure out which of shadow/backup is compatible
    (if-let [[base edits-queue] (condp patch-compatible? (first edits)
                                  shadow [shadow edits-queue]
                                  backup [backup []]
                                  nil)]
      (let [diffs (eduction (map :diff) (remove empty-patch?) edits)
            m' (-> edits last :m inc)]
        [(merge state {:shadow (-> base
                                 ;; Step (5)
                                 (update :content apply-all-edits diffs)
                                 ;; Step (6)
                                 (assoc :m m'))
                       :backup shadow
                       :edits-queue (filter #(< m' (:n %)) edits-queue)})
         diffs])

      ;; Found no compatible patches, return current state
      [state])))


(defn start-sync
  "Start synchronization of atoms whose state changes are propogated
  when it's state changes or when a patch is sent via data-in.

  Returns a channel which produces 'true' when both sender & receiver
  are in full sync.

  ref      - the reference object to synchronize
  data-in  - core.async channel for writing patches to
  data-out - core.async channel for reading patches from
  id       - id for debugging

  For differential sync to work properly, the entangled atoms need to
  take turns talking to each other. This can be achieved by sending an
  empty patch using `poke`.

  Returns two channels [sync state]. A message should be sent to
  `sync` to flush changes to the other side. `state` watches the
  internal values of `shadow`, `backup`, `edits-queue` in each
  iteration of the event loop.

  This is an implementation of Neil Fraser's `Differential Sync'.
  "
  ([ref data-in data-out id]
   (let [snapshot     @ref
         watch-id     (gensym :diff-sync)
         init-shadow  {:n 0 :m 0 :content snapshot}
         user-changes (a/chan)
         sync         (a/chan)
         state-change (a/chan)
         shutdown! (fn []
                     (timbre/info id "entangle shutting down... ")
                     (remove-watch ref watch-id)
                     (doall (map a/close! [data-in data-out sync state-change])))]

     ;; In Neil Fraser's Paper, this is the start of (1)
     (add-watch ref watch-id #(go (a/>! user-changes %&)))

     (go-loop [state {:snapshot    snapshot
                      :shadow      init-shadow
                      :backup      init-shadow
                      :edits-queue []}]

       (if-let [real-next-state
                (a/alt! sync ([cause ch]
                              (timbre/debug "Sync triggered by " cause)
                              (let [{:keys [edits-queue] :as next-state} (prepare-patch state)]
                                (when (a/>! data-out edits-queue)
                                  next-state)))

                        user-changes ([[_ _ _ snapshot] ch]
                                      (timbre/debug "User changes: " snapshot)
                                      (assoc state :snapshot snapshot))

                        data-in ([patch ch]
                                 (timbre/debug "Got data-in: " (pr-str patch))
                                 (let [[next-state diffs] (handle-incoming-patch patch state)]
                                   ;; TODO: should I care or should I swap eagerly
                                   (when diffs
                                     (swap! ref apply-all-edits diffs))
                                   next-state)))]

         (do (a/>! state-change real-next-state)
             (recur real-next-state))

         ;; no next state? should shutdown!
         (shutdown!)))
     [sync state-change])))
