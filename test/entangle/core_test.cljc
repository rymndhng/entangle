(ns entangle.core-test
  #?@(:clj  [(:require [clojure.core.async :as a]
                       [clj-diff.core :as diff]
                       [clojure.test :refer [deftest is testing run-tests]]
                       [entangle.core :as e])]
      :cljs [(:require-macros [cljs.core.async.macros :as a :refer [go alt!]])
             (:require [cljs.core.async :as a]
                       [clj-diff.core :as diff]
                       [cljs.test :refer-macros [deftest is testing async]]
                       [entangle.core :as e])]))

(defn test-async
  "Asynchronous test awaiting ch to produce a value or close."
  [ch]
  #?(:clj   (a/<!! ch)
     :cljs  (async done
              (a/take! ch (fn [_] (done))))))

(defn generate-entangle [key]
  "Test helper to create an "
  (let [in (a/chan 1)
        out (a/chan 1)
        state (atom "")
        ack (e/start-sync state in out key)]
    {:in    in
     :out   out
     :state state
     :ack   ack}))

(deftest normal-operation
  (test-async
    (a/go
      (let [{:keys [in out state ack]} (generate-entangle :foo)]
        (testing "Creating a patch sends a patch to the other side."
          (reset! state "foo")
          (is (= [{:n 0 :m 0 :diff (diff/diff "" "foo")}]
                 (a/<! out))))
        (testing "Other side sends a patch back for new state change."
          (a/>! in [{:n 1 :m 0 :diff (diff/diff "foo" "foobar")}])
          ;; Here, we look at sending something out so we know that the state has changed
          (a/<! out)
          (is (= "foobar" @state)))))))

(deftest duplicate-packet
  (let [{:keys [in out state ack]} (generate-entangle :foo)
        called (atom 0)]
    (add-watch state :watcher (fn [_ _ _ _] (swap! called inc)))
    (test-async
      (a/go
        (testing "Sends first packet and is received"
          (a/>! in [{:n 0 :m 0 :diff (diff/diff "" "foobar")}])
          (a/<! out)
          (is (= "foobar" @state)))
        (testing "Sending duplicate packet is ignored"
          (a/>! in [{:n 0 :m 0 :diff (diff/diff "" "foobar")}])
          (a/>! in [{:n 0 :m 0 :diff (diff/diff "" "foobar")}])
          (a/>! in [{:n 0 :m 0 :diff (diff/diff "" "foobar")}])
          (a/>! in [{:n 0 :m 0 :diff (diff/diff "" "foobar")}])
          (is (= 1 @called)))))))


(deftest lost-outbound-packet
  (let [{:keys [in out state ack]} (generate-entangle :foo)]
    (test-async
      (a/go
        (testing "Lost outbound packets queue up diffs"
          (reset! state "foo")
          (is (= [{:n 0 :m 0 :diff (diff/diff "" "foo")}]
                 (a/<! out)))
          (reset! state "foobar")
          (is (= [{:n 0 :m 0 :diff (diff/diff "" "foo")}
                  {:n 0 :m 1 :diff (diff/diff "foo" "foobar")}]
                 (a/<! out)))
          (reset! state "foobarbaz")
          (is (= [{:n 0 :m 0 :diff (diff/diff "" "foo")}
                  {:n 0 :m 1 :diff (diff/diff "foo" "foobar")}
                  {:n 0 :m 2 :diff (diff/diff "foobar" "foobarbaz")}]
                 (a/<! out))))
        (testing "When acknowledged, queue empties out"
          (a/>! in [{:n 3 :m 0 :diff (diff/diff "foobarbaz" "foobarbazqux")}])
          (is (= [{:n 1 :m 3 :diff (diff/diff "" "")}]
                 (a/<! out))))))))


(deftest lost-returning-packet
  (let [{:keys [in out state ack]} (generate-entangle :foo)]
    (test-async
      (a/go
        (testing "Queuing up first change"
          (a/>! in [{:n 0 :m 0 :diff (diff/diff "" "foo")}])
          (is (= [{:n 1 :m 0 :diff (diff/diff "" "")}]
                 (a/<! out)))
          (is (= "foo" @state)))
        (testing "Recovers after lost return packet"
          (a/>! in [{:n 0 :m 0 :diff (diff/diff "" "foo")}
                    {:n 0 :m 1 :diff (diff/diff "foo" "bar")}])
          (is (= [{:n 2 :m 0 :diff (diff/diff "" "")}]
                 (a/<! out)))
          (is (= "bar" @state)))))))

(deftest entangling-two-atoms
  (let [A->B (a/chan 1) B->A (a/chan 1)
        stateA (atom "")
        stateB (atom "")
        ackA (e/start-sync stateA B->A A->B :atom-a)
        ackB (e/start-sync stateB A->B B->A :atom-b)]
    (test-async
      (a/go
        (a/<! ackA)
        (a/<! ackB)

        (testing "Changing an atoms value updates the other"
          (reset! stateA "foo")
          (a/<! ackA)
          (a/<! ackB)
          (is (= "foo" @stateB))

          (reset! stateB "bar")
          (a/<! ackA)
          (a/<! ackB)
          (is (= "bar" @stateA) "Reseting stateB will update stateA"))

        (testing "Changes to the atoms keep working"
          (reset! stateA "FOO")
          (a/<! ackB)
          (a/<! ackA)
          (is (= "FOO" @stateB))

          (reset! stateA "FOOBAR")
          (a/<! ackB)
          (a/<! ackA)
          (is (= "FOOBAR" @stateB))

          (reset! stateA "FOO")
          (a/<! ackB)
          (a/<! ackA)
          (is (= "FOO" @stateB))

          (reset! stateB "HELLO")
          (a/<! ackB)
          (a/<! ackA)
          (is (= "HELLO" @stateA))

          (reset! stateB "HOW ARE YOU")
          (a/<! ackB)
          (a/<! ackA)
          (is (= "HOW ARE YOU" @stateA)))


        (a/close! A->B)))))

;; (deftest shutdown-on-error
;;   (let [A->B (a/chan 1) B->A (a/chan 1)
;;         stateA (atom "")
;;         stateB (atom "")
;;         ackA (e/start-sync stateA B->A A->B 1)
;;         ackB (e/start-sync stateB A->B B->A 2)]
;;     (testing "Exceptions shuts down the channels and removes the watch")

;;   )

#?(
:clj
(defn test-ns-hook
  "Because we're doing some IO-related stuff, we run the tests in a separate
  thread and bind the execution time.

  This way, we don't need to restart the repl because the repl thread has hung.
  "
  []
  (let [f (future (do (normal-operation)
                      (duplicate-packet)
                      (lost-outbound-packet)
                      (entangling-two-atoms)))]
    (.get f 1000 java.util.concurrent.TimeUnit/MILLISECONDS)))

:cljs
(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (if (cljs.test/successful? m)
    (println "Success!")
    (println "FAIL"))))

#_(cljs.test/run-tests)
