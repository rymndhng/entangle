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

(timbre/set-level! :info)

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
              :cljs js/Error) e
      (timbre/warn "Unable to apply to base: " base " Patch: " patch)
      base)))

(defn apply-all-edits-fuzzy [base edits]
  "Applies all sequential patches onto a base object. If it fails, well too bad!"
  (reduce try-patch base edits))

(defn apply-all-edits [base edits]
  "Applies all sequential patches onto a base object. This is fragile and may fail."
  (reduce diff/patch base edits))

(defn- prepare-patch
  "Implements part 2,3 of algorithm from state."
  [snapshot {:keys [shadow backup edits-queue] :as state}]
  (let [patch {:m (:n shadow)
               :diff (diff/diff (:content shadow) snapshot)}]
    {:shadow (-> shadow
               (update :n inc)
               (assoc :content snapshot))
     :backup shadow
     :edits-queue (conj edits-queue patch)}))

(defn- handle-incoming-patch
  "Implements part 5,6,7 of algorithm. Returns a tuple of two elements
  where the first element contains all the state for the next recur,
  and a list of diffs to apply to the state. "
  [{:keys [n] :as patch} {:keys [shadow backup edits-queue] :as state}]
  (let [incoming-edits (remove #(< (:m %) (:m shadow)) (:edits patch))
        outgoing-edits (into [] (filter #(< n (:m %)) edits-queue))]
    (if-not (< 0 (count incoming-edits))
      [(assoc state :edits-queue outgoing-edits)]
      (let [[base edits-queue] (condp = n
                                 (:n shadow) [shadow outgoing-edits]
                                 (:n backup) [backup []]
                                 nil)
            diffs (eduction (map :diff) (remove empty-patch?) incoming-edits)
            m' (+ (:m shadow) (count incoming-edits))]
        [(merge state {:shadow (-> base
                                 ;; Step (5)
                                 (update :content apply-all-edits diffs)
                                 ;; Step (6)
                                 (assoc :m m'))
                       :backup shadow

                       ;; acknowledge (filter out) all edit-queue items less than n
                       :edits-queue outgoing-edits})
         diffs]))))


(defn start-sync
  "Start synchronization of atoms whose state changes are propogated
  when it's state changes or when a patch is sent via data-in.

  ref       the reference object to synchronize
  data-in   core.async channel for writing patches to
  data-out  core.async channel for reading patches from
  id        id for debugging
  sync-ch   channel used for signalling diff-sync to perform remote synchronization
  state-changes-ch publishes the state changes of this object

  This is an implementation of Neil Fraser's `Differential Sync'.
  "
  ([ref data-in data-out id sync-ch state-changes-ch]
   (let [snapshot     @ref
         watch-id     (gensym :diff-sync)
         init-shadow  {:n 0 :m 0 :content snapshot}
         shutdown! (fn []
                     (timbre/info id "entangle shutting down... ")
                     (remove-watch ref watch-id)
                     (doall (map a/close! [data-in data-out sync-ch state-changes-ch]))
                     (when state-changes-ch
                       (a/close! state-changes-ch)))]
     (go-loop [state {:shadow      init-shadow
                      :backup      init-shadow
                      :edits-queue []}]
       (timbre/debug id " Start of loop " state)
       (if-let [[real-next-state action]
                (a/alt! sync-ch ([cause ch]
                                 (timbre/debug id " Syncing Cause " cause)
                                 ;; How do we guarantee lock access to @ref
                                 (let [{:keys [edits-queue] :as next-state} (prepare-patch @ref state)
                                       patch {:n (get-in state [:shadow :m]) :edits edits-queue}]
                                   (when (a/>! data-out patch)
                                     [next-state :sync])))
                        data-in ([patch ch]
                                 (timbre/debug id " Got data-in: " (pr-str patch))
                                 (when patch
                                   (let [[next-state diffs] (handle-incoming-patch patch state)]
                                     (when (= next-state state)
                                       (timbre/warn id " handle-incoming-patch was a no-op!"))
                                     ;; TODO: should I care or should I swap eagerly
                                     (try
                                       (swap! ref apply-all-edits-fuzzy diffs)
                                       [next-state :data]
                                       (catch Exception e
                                         nil))))))]

         (do (when state-changes-ch
               (a/>! state-changes-ch (assoc real-next-state :action action)))
             (recur real-next-state))

         ;; no next state? should shutdown!
         (shutdown!))))))
