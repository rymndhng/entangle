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

(defn poke [ref]
  "Pokes the ref so that a sync is propogated."
  (swap! ref identity))

(defn poke-every
  "Pokes the ref every msec to trigger a sync. This is useful when one
  client is producing no changes. "
  [ref msec]
  (go-loop []
    (a/<! (a/timeout msec))
    (poke ref)
    (recur)))

(defn patch-compatible? [shadow patch]
  "Are the versions of shadow and patch compatible"
  (and (= (get shadow :n :not-in-shadow)
          (get patch  :n :not-in-patch))))

(defn apply-all-edits [base edits]
  "Applies all sequential patches onto a base object"
  (reduce diff/patch base edits))

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

  This is an implementation of Neil Fraser's `Differential Sync'.
  "
  ([ref data-in data-out id]
   (let [watch-id (gensym :diff-sync)
         init-state   {:n 0 :m 0 :content @ref}
         user-changes (a/chan)
         synced-ch (a/chan (a/sliding-buffer 1))
         shutdown! (fn []
                     (timbre/info id "entangle shutting down... ")
                     (remove-watch ref watch-id)
                     (doall (map a/close! [data-in data-out synced-ch])))]
     ;; In Neil Fraser's Paper, this is the start of (1)
     (add-watch ref watch-id #(go (a/>! user-changes %&)))

     (go-loop [shadow init-state
               backup init-state
               edits-queue []]

       (timbre/debug id \newline
         "State         : " @ref   \newline
         "Shadow        : " shadow \newline
         "Backup shadow : " backup \newline)

       (when (apply = (map :content [shadow backup]))
         (a/>! synced-ch true))

       (a/alt! user-changes
               ([[_ _ _ new-state] ch]
                (let [;; Step (2)
                      patch {:n (:m shadow)
                             :m (:n shadow)
                             :diff (diff/diff (:content shadow) new-state)}
                      edits (conj edits-queue patch)
                      shadow' (-> shadow (update :n inc)
                                (assoc :content new-state))]
                  ;; Step (3)
                  (if (a/>! data-out edits)
                    ;; TODO: filter items from outbound queue
                    (recur shadow' shadow edits)
                    (shutdown!))))

               data-in
               ([data ch]
                (timbre/debug "Got data-in: " (pr-str data))
                (if-not data
                  (shutdown!)
                  ;; remove already acknowledged packets in case of lost packets
                  (let [edits (remove #(< (:m %)
                                          (:m shadow)) data)]

                    ;; figure out which of shadow/backup is compatible
                    (if-let [[base edits-queue'] (condp patch-compatible? (first edits)
                                                   shadow [shadow edits-queue]
                                                   backup [backup []]
                                                   nil)]
                      (let [diffs (eduction (map :diff) (remove empty-patch?) edits)
                            m' (-> edits last :m inc)
                            base' (-> base
                                    ;; Step (5)
                                    (update :content apply-all-edits diffs)
                                    ;; Step (6)
                                    (assoc :m m'))
                            edits-queue'' (filter #(< m' (:n %)) edits-queue')]
                        ;; Step (8). Avoid no-op patches because create chatty noises
                        (when (seq diffs)
                          (timbre/debug "Applying: " (pr-str diffs))
                          (swap! ref apply-all-edits diffs))

                        ;; Step (7)
                        (recur base' base edits-queue''))

                      ;; Patch does not match... ignore it
                      (do
                        (timbre/debug "Diff not compatible:" edits)
                        (recur shadow backup edits-queue))))))))
     synced-ch)))

(timbre/set-level! :warn)
