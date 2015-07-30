(ns entangle.core-test
  #?@(:clj  [(:require [clojure.core.async :as a]
                       [clj-diff.core :as diff]
                       [clojure.test :refer [deftest is testing run-tests]]
                       [entangle.core :as e])]
      :cljs [(:require-macros [cljs.core.async.macros :as a :refer [go alt!]])
             (:require [cljs.core.async :as a]
                       [clj-diff.core :as diff]
                       [cljs.test :refer-macros [deftest is testing async run-tests]]
                       [entangle.core :as e])]))

(defn test-async
  "Asynchronous test awaiting ch to produce a value or close."
  [ch]
  #?(:clj   (a/<!! ch)
     :cljs  (async done
              (a/take! ch (fn [_] (done))))))

(defn generate-entangle [key]
  "Test helper to create an "
  (let [in    (a/chan 1)
        out   (a/chan 1)
        sync  (a/chan)
        state (a/chan)
        ref   (atom "")]
    (e/start-sync ref in out key sync state)
    {:in    in
     :out   out
     :ref   ref
     :sync  sync
     :state state}))

(deftest normal-operation
  (test-async
    (a/go
      (let [{:keys [in out ref ack sync state]} (generate-entangle :foo)]
        (testing "Creating a patch sends a patch to the other side."
          (reset! ref "foo")
          (a/<! state)
          (a/>! sync :manual)
          (a/<! state)
          (is (= [{:n 0 :m 0 :diff (diff/diff "" "foo")}]
                 (a/<! out))))

        (testing "Other side sends a patch back for new ref change."
          (a/>! in [{:n 1 :m 0 :diff (diff/diff "foo" "foobar")}])
          (a/<! state)
          ;; Here, we look at sending something out so we know that the ref has changed
          (is (= "foobar" @ref)))))))

(deftest duplicate-packet
  (let [{:keys [in out ref sync state]} (generate-entangle :foo)
        called (atom 0)]
    (add-watch ref :watcher (fn [_ _ _ _] (swap! called inc)))
    (test-async
      (a/go
        (testing "Sends first packet and is received"
          (a/>! in [{:n 0 :m 0 :diff (diff/diff "" "foobar")}])
          (a/<! state)
          (is (= "foobar" @ref)))
        (testing "Sending duplicate packet is ignored"
          (a/>! in [{:n 0 :m 0 :diff (diff/diff "" "foobar")}])
          (a/<! state)
          (a/>! in [{:n 0 :m 0 :diff (diff/diff "" "foobar")}])
          (a/<! state)
          (a/>! in [{:n 0 :m 0 :diff (diff/diff "" "foobar")}])
          (a/<! state)
          (is (= 1 @called)))))))


;; TODO: clean up this test -- it's ugly as hell
(deftest lost-outbound-packet
  (let [{:keys [in out ref sync state]} (generate-entangle :foo)]
    (test-async
      (a/go
        (testing "Lost outbound packets queue up diffs"
          (reset! ref "foo")
          (is (= {:action :snapshot
                  :snapshot "foo"
                  :shadow {:n 0 :m 0 :content ""}
                  :backup {:n 0 :m 0 :content ""}
                  :edits-queue []}
                 (a/<! state)))
          (a/>! sync :manual)
          (is (= [{:n 0 :m 0 :diff (diff/diff "" "foo")}]
                 (a/<! out)))
          (is (= {:action :sync
                  :snapshot "foo"
                  :shadow {:n 1 :m 0 :content "foo"}
                  :backup {:n 0 :m 0 :content ""}
                  :edits-queue [{:n 0 :m 0 :diff (diff/diff "" "foo")}]}
                 (a/<! state))))

        (testing "queue up more changes"
          (reset! ref "foobar")
          (is (= {:action :snapshot
                  :snapshot "foobar"
                  :shadow {:n 1 :m 0 :content "foo"}
                  :backup {:n 0 :m 0 :content ""}
                  :edits-queue [{:n 0 :m 0 :diff (diff/diff "" "foo")}]}
                 (a/<! state)))
          (a/>! sync :manual)
          (is (= [{:n 0 :m 0 :diff (diff/diff "" "foo")}
                  {:n 0 :m 1 :diff (diff/diff "foo" "foobar")}]
                 (a/<! out)))
          (is (= {:action :sync
                  :snapshot "foobar"
                  :shadow {:n 2 :m 0 :content "foobar"}
                  :backup {:n 1 :m 0 :content "foo"}
                  :edits-queue [{:n 0 :m 0 :diff (diff/diff "" "foo")}
                                {:n 0 :m 1 :diff (diff/diff "foo" "foobar")}]}
                 (a/<! state))))

        (testing "queueing up baz"
          (reset! ref "foobarbaz")
          (is (= {:action :snapshot
                  :snapshot "foobarbaz"
                  :shadow {:n 2 :m 0 :content "foobar"}
                  :backup {:n 1 :m 0 :content "foo"}
                  :edits-queue [{:n 0 :m 0 :diff (diff/diff "" "foo")}
                                {:n 0 :m 1 :diff (diff/diff "foo" "foobar")}]}
                 (a/<! state)))
          (a/>! sync :manual)
          (is (= [{:n 0 :m 0 :diff (diff/diff "" "foo")}
                  {:n 0 :m 1 :diff (diff/diff "foo" "foobar")}
                  {:n 0 :m 2 :diff (diff/diff "foobar" "foobarbaz")}]
                 (a/<! out)))
          (is (= {:action :sync
                  :snapshot "foobarbaz"
                  :shadow {:n 3 :m 0 :content "foobarbaz"}
                  :backup {:n 2 :m 0 :content "foobar"}
                  :edits-queue [{:n 0 :m 0 :diff (diff/diff "" "foo")}
                                {:n 0 :m 1 :diff (diff/diff "foo" "foobar")}
                                {:n 0 :m 2 :diff (diff/diff "foobar" "foobarbaz")}]}
                 (a/<! state))))

        (testing "When acknowledged, queue empties out"
          (a/>! in [{:n 3 :m 0 :diff (diff/diff "foobarbaz" "foobarbazqux")}])
          (is (= {:action :diff
                  :snapshot "foobarbaz"
                  :shadow {:n 3 :m 1 :content "foobarbazqux"}
                  :backup {:n 3 :m 0 :content "foobarbaz"}
                  :edits-queue []}
                 (a/<! state)))
          (is (= {:action :snapshot
                  :snapshot "foobarbazqux"
                  :shadow {:n 3 :m 1 :content "foobarbazqux"}
                  :backup {:n 3 :m 0 :content "foobarbaz"}
                  :edits-queue []}
                 (a/<! state)))
          (a/>! sync :manual)
          (is (= {:action :sync
                  :snapshot "foobarbazqux"
                  :shadow {:n 4 :m 1 :content "foobarbazqux"}
                  :backup {:n 3 :m 1 :content "foobarbazqux"}
                  :edits-queue [{:n 1 :m 3 :diff {:+ [] :- []}}]}
                 (a/<! state)))
          (is (= [{:n 1 :m 3 :diff (diff/diff "" "")}]
                 (a/<! out))))))))


(deftest lost-returning-packet
  (let [{:keys [in out ref sync state]} (generate-entangle :foo)]
    (test-async
      (a/go
        (testing "Queuing up first change"
          (a/>! in [{:n 0 :m 0 :diff (diff/diff "" "foo")}])
          (a/<! state)
          (a/>! sync :waow)
          (is (= [{:n 1 :m 0 :diff (diff/diff "" "")}]
                 (a/<! out)))
          (is (= "foo" @state)))

        (testing "Recovers after lost return packet"
          (a/>! in [{:n 0 :m 0 :diff (diff/diff "" "foo")}
                    {:n 0 :m 1 :diff (diff/diff "foo" "bar")}])
          (is (= [{:n 2 :m 0 :diff (diff/diff "" "")}]
                 (a/<! out)))
          (is (= "bar" @state)))))))


(defn- random-chars
  "Generates an infinite stream of character from the alphabet."
  ([]
   (random-chars [\a \b \c \d \e \f \g \h \i]))
  ([alphabet]
   (cons (rand-nth alphabet)
     (lazy-seq (random-chars alphabet)))))

(defn- random-strings
  "Generates an infinite stream of random strings with an optional
  length."
  ([]
   (random-strings 8))
  ([length]
   (cons (apply str (take length (random-chars)))
     (lazy-seq (random-strings length)))))

(deftest two-way-fuzz-testing
  (let [iterations 100
        str-len 100
        entangle-A (generate-entangle :a)
        entangle-B (generate-entangle :b)
        a-done (a/chan 1)
        b-done (a/chan 1)]
    ;; discard state information -- we don't care
    (a/go-loop []
      (a/alts! [(:state entangle-A) (:state entangle-B)])
      (recur))

    ;; connect friends
    (a/pipe (:in entangle-A) (:out entangle-B))
    (a/pipe (:in entangle-B) (:out entangle-A))

    (a/go (doseq [val (take iterations (random-strings str-len))]
            (reset! (:ref entangle-A) val)
            (a/<! (a/timeout (rand-int 10)))
            (a/>! (:sync entangle-A) :much-wow)
            (a/<! (a/timeout (rand-int 10))))
          (a/close! a-done))

    (a/go (doseq [val (take iterations (random-strings str-len))]
            (reset! (:ref entangle-B) val)
            (a/<! (a/timeout (rand-int 100)))
            (a/>! (:sync entangle-B) :much-amaze)
            (a/<! (a/timeout (rand-int 100))))
          (a/close! b-done))

    (a/go
      (a/<! a-done)
      (a/<! b-done)
      ;; trigger one more sync
      (a/>! (:sync entangle-A) :last-one)
      (is (= @(:ref entangle-A) @(:ref entangle-B))))))

(deftest concurrent-writes-are-deterministic
  []
  ;; TODO: WIP, curious if we write many changes outside of the go loop are we guaranteed that it will be in order
  )

;; This test isn't well designed yet IMO
#_(deftest three-way-fuzz-testing
  (let [iterations 1
        str-len 10
        timeout 100
        server (atom "")
        entangle-A (generate-entangle :a)
        entangle-B (generate-entangle :b)

        [server-a-sync server-a-state] (e/start-sync server
                                         (:out entangle-A)
                                         (:in entangle-A) :A)
        [server-b-sync server-b-state] (e/start-sync server
                                         (:out entangle-B)
                                         (:in entangle-B) :B)

        server-done  (a/chan 1)
        a-done      (a/chan 1)
        b-done      (a/chan 1)

        cleanup (a/go-loop []
                  (a/alts! [(:state entangle-A)
                            (:state entangle-B)
                            server-a-state
                            server-b-state])
                  (recur))]
    ;; discard state information -- we don't care


    (a/go (doseq [val (take iterations (random-strings str-len))]
            (reset! server val)
            (a/<! (a/timeout (rand-int timeout)))
            (a/>! server-a-sync :waow)
            (a/>! server-b-sync :waow)
            (a/<! (a/timeout (rand-int timeout))))
          (a/close! server-done))

    (a/go (doseq [val (take iterations (random-strings str-len))]
            (reset! (:ref entangle-A) val)
            (a/<! (a/timeout (rand-int timeout)))
            (a/>! (:sync entangle-A) :trigger)
            (a/<! (a/timeout (rand-int timeout))))
          (a/close! a-done))

    (a/go (doseq [val (take iterations (random-strings str-len))]
            (reset! (:ref entangle-B) val)
            (a/<! (a/timeout (rand-int timeout)))
            (a/>! (:sync entangle-B) :trigger)
            (a/<! (a/timeout (rand-int timeout))))
          (a/close! b-done))

    ;; when done, write one final thing in
    (a/go
      (a/<! server-done)
      (a/<! a-done)
      (a/<! b-done)
      (a/close! cleanup)
      (reset! server "DONT MATTER FOO!")
      (loop []
        (a/>! server-a-sync :waow)
        (a/>! server-b-sync :waow)))

    (a/go
      (a/<! server-done)
      (a/<! a-done)
      (a/<! b-done)
      (is (= @server @(:ref entangle-A) @(:ref entangle-B))))))


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
                      (two-way-fuzz-testing)))]
    (.get f 1000 java.util.concurrent.TimeUnit/MILLISECONDS)))

:cljs
(defmethod cljs.test/report [:cljs.test/default :end-run-tests] [m]
  (if (cljs.test/successful? m)
    (println "Success!")
    (println "FAIL"))))
