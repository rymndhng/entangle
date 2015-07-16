(ns entangle.core-test
  (:require [clojure.core.async :as a]
            [clj-diff.core :as diff]
            [clojure.test :refer :all]
            [entangle.core :refer :all]))

(defn generate-entangle [key]
  "Test helper to create an "
  (let [in (a/chan 1)
        out (a/chan 1)
        state (atom "")]
    {:in  (a/chan 1)
     :out (a/chan 1)
     :state state
     :ack (start-sync state in out key)}))

(deftest state-change-handling
  (let [data-in (a/chan 1)
        data-out (a/chan 1)
        state (atom "")
        ack (start-sync state data-in data-out :state-change-handling)]

    (testing "Changing an atom sends the patches to the other side"
      (reset! state "foo")
      (is (= {:version 0 :patch (diff/diff "" "foo")}
             (a/<!! data-out))))

    (testing "Sending a patch to the atom alters it's value after acknowledgement"
      (a/>!! data-in {:version 1 :patch (diff/diff "foo" "foobar")})
      (is (= {:version 1 :patch (diff/diff "foobar" "foobar")}
             (a/<!! data-out)))
      (is (= "foobar" @state)))

    (testing "Sending a duplicate item is a no-operation."
      (a/>!! data-in {:version 1 :patch (diff/diff "foo" "foobar")})
      (a/<!! ack)
      (is (= "foobar" @state)))

    (testing "Sending two successive patches does not cause deadlock."
      (a/>!! data-in {:version 2 :patch (diff/diff "foobar" "foobar baz")})
      (a/<!! data-out)
      (a/>!! data-in {:version 3 :patch (diff/diff "foobar" "foobar baz qux")})
      (a/<!! data-out)
      (is (= "foobar baz qux") @state))

    (testing "Cleans up when data-in channel closes"
      (a/<!! ack) ; some cleanup from previous
      (a/close! data-in)
      (is (nil? (a/<!! ack)))
      (is (nil? (a/<!! data-in)))
      (is (nil? (a/<!! data-out))))))

(deftest entangling-two-atoms
  (let [A->B (a/chan 1) B->A (a/chan 1)
        stateA (atom "")
        stateB (atom "")
        ackA (start-sync stateA B->A A->B :atom-a)
        ackB (start-sync stateB A->B B->A :atom-b)]

    (testing "Changing an atoms value updates the other"
      (reset! stateA "foo")
      (a/<!! ackA)
      (a/<!! ackB)
      (is (= "foo" @stateB))

      (reset! stateB "bar")
      (a/<!! ackA)
      (a/<!! ackB)
      (is (= "bar" @stateA) "Reseting stateB will update stateA"))

    (testing "Changes to the atoms keep working"
      (reset! stateA "FOO")
      (a/<!! ackB)
      (a/<!! ackA)
      (is (= "FOO" @stateB))

      (reset! stateA "FOOBAR")
      (a/<!! ackB)
      (a/<!! ackA)
      (is (= "FOOBAR" @stateB))

      (reset! stateA "FOO")
      (a/<!! ackB)
      (a/<!! ackA)
      (is (= "FOO" @stateB))

      (reset! stateB "HELLO")
      (a/<!! ackB)
      (a/<!! ackA)
      (is (= "HELLO" @stateA))

      (reset! stateB "HOW ARE YOU")
      (a/<!! ackB)
      (a/<!! ackA)
      (is (= "HOW ARE YOU" @stateA)))


    (a/close! A->B)))

;; (deftest shutdown-on-error
;;   (let [A->B (a/chan 1) B->A (a/chan 1)
;;         stateA (atom "")
;;         stateB (atom "")
;;         ackA (start-sync stateA B->A A->B 1)
;;         ackB (start-sync stateB A->B B->A 2)]
;;     (testing "Exceptions shuts down the channels and removes the watch")

;;   )

(defn test-ns-hook
  "Because we're doing some IO-related stuff, we run the tests in a separate
  thread and bind the execution time.

  This way, we don't need to restart the repl because the repl thread has hung.
  "
  []
  (let [f (future (do (state-change-handling)
                      (entangling-two-atoms)))]
    (.get f 1000 java.util.concurrent.TimeUnit/MILLISECONDS)))
