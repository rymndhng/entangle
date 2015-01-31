(ns entangle.core-test
  (:require [clojure.core.async :as a]
            [clj-diff.core :as diff]
            [clojure.test :refer :all]
            [entangle.core :refer :all]))

(deftest state-change-handling
  (let [data-in (a/chan 1)
        data-out (a/chan 1)
        state (atom "")
        ack (start-sync state data-in data-out 1)]

    (testing "Changing an atom sends the patches to the other side"
      (a/go (swap! state #(str % "foo")))
      (is (= (diff/diff "" "foo") (a/<!! data-out))))

    (testing "Sending a patch to the atom eventually alters it's value"
      (a/>!! data-in (diff/diff "foo" "foobar"))
      (a/<!! ack)
      (is (= @state "foobar")))))

(deftest entangling-two-atoms
  (let [A->B (a/chan 1) B->A (a/chan 1)
        stateA (atom "")
        stateB (atom "")
        ackA (start-sync stateA B->A A->B 1)
        ackB (start-sync stateB A->B B->A 2)]

    (testing "Changing an atoms value updates the other"
      (swap! stateA (fn [thing] (str thing "foo")))
      (a/<!! ackB)
      (a/<!! ackA)
      (is (= "foo" @stateB))

      (reset! stateB "bar")
      (a/<!! ackA)
      (a/<!! ackB)
      (is (= "bar" @stateA)))

    (testing "Changes to the same atom twice work"
      (reset! stateA "a")
      (a/<!! ackB)
      (a/<!! ackA)
      (reset! stateA "b")
      (a/<!! ackB)
      (a/<!! ackA)
      (reset! stateA "c")
      (a/<!! ackB)
      (a/<!! ackA)
      (is (= "c" @stateB)))
      ))

(defn test-ns-hook
  "Because we're doing some IO-related stuff, we run the tests in a separate
  thread and bind the execution time.

  This way, we don't need to restart the repl because the repl thread has hung.
  "
  []
  (let [f (future (do (state-change-handling)
                      (entangling-two-atoms)))]
    (.get f 1000 java.util.concurrent.TimeUnit/MILLISECONDS)))
