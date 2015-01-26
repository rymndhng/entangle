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
  [patch]
  (= patch {:+ [], :- []}))

(defn rebase
  "Rebases the current state against head after applying patch."
  [head base patch]
  (let [working-changes (diff/diff base head)]
    (-> base
      (diff/patch patch)
      (diff/patch working-changes))))

(defn start-sync
  "Start synchronization of atoms where data-in is a channel of incoming diffs
  and data-out is a channel to write changes to. This ref should be an atom.

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


(test/deftest state-change-handling
  (let [data-in (a/chan 1)
        data-out (a/chan 1)
        state (atom "")
        ack (start-sync state data-in data-out 1)]

    (test/testing "Changing an atom sends the patches to the other side"
      (a/go (swap! state #(str % "foo")))
      (test/is (= (diff/diff "" "foo") (a/<!! data-out))))

    (test/testing "Sending a patch to the state alters it's value"
      (a/>!! data-in (diff/diff "foo" "foobar"))
      (a/<!! ack)
      (test/is (= @state "foobar")))))

(test/deftest entangling-two-atoms
  (let [A->B (a/chan 1) B->A (a/chan 1)
        stateA (atom "")
        stateB (atom "")
        ackA (start-sync stateA B->A A->B 1)
        ackB (start-sync stateB A->B B->A 2)]

    (test/testing "Changing an atoms value updates the other"
      (swap! stateA (fn [thing] (str thing "foo")))
      (a/alts!! [ackB])
      (a/alts!! [ackA])
      (test/is (= "foo" @stateB))

      (reset! stateB "bar")
      (a/alts!! [ackA])
      (a/alts!! [ackB])
      (test/is (= "bar" @stateA)))

    (test/testing "Changes to the same atom twice work"
      (reset! stateA "a")
      (a/alts!! [ackB])
      (a/alts!! [ackA])
      (reset! stateA "b")
      (a/alts!! [ackB])
      (a/alts!! [ackA])
      (reset! stateA "c")
      (a/alts!! [ackB])
      (a/alts!! [ackA])
      (test/is (= "c" @stateB)))
      ))

(defn test-ns-hook
  "Tests to run on this hook"
  []
  (state-change-handling)
  (entangling-two-atoms)
  )

(defn run-tests
  "Bound the tests to under 1 second"
  []
  (let [f (future (test/run-tests))]
    (.get f 3000 java.util.concurrent.TimeUnit/MILLISECONDS)))
