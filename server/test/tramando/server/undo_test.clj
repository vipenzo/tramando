(ns tramando.server.undo-test
  "Tests for server-side undo/redo functionality."
  (:require [clojure.test :refer :all]
            [tramando.server.undo :as undo]))

;; =============================================================================
;; Setup/Teardown
;; =============================================================================

(defn reset-undo-state! []
  (undo/clear-stacks! "test-project"))

(use-fixtures :each (fn [f]
                      (reset-undo-state!)
                      (f)
                      (reset-undo-state!)))

;; =============================================================================
;; Basic Undo/Redo Tests
;; =============================================================================

(deftest test-push-undo
  (testing "Push undo adds to stack"
    (undo/push-undo! "test-project" "content-v1")
    (is (undo/can-undo? "test-project"))
    (is (not (undo/can-redo? "test-project")))
    (is (= 1 (undo/get-undo-count "test-project")))))

(deftest test-push-multiple-undos
  (testing "Multiple push undo operations"
    (undo/push-undo! "test-project" "content-v1")
    (undo/push-undo! "test-project" "content-v2")
    (undo/push-undo! "test-project" "content-v3")
    (is (= 3 (undo/get-undo-count "test-project")))
    (is (= 0 (undo/get-redo-count "test-project")))))

(deftest test-pop-undo
  (testing "Pop undo returns previous content"
    (undo/push-undo! "test-project" "content-v1")
    (undo/push-undo! "test-project" "content-v2")
    (let [result (undo/pop-undo! "test-project" "content-v3")]
      (is (= "content-v2" result))
      (is (= 1 (undo/get-undo-count "test-project")))
      (is (= 1 (undo/get-redo-count "test-project"))))))

(deftest test-pop-undo-empty
  (testing "Pop undo on empty stack returns nil"
    (let [result (undo/pop-undo! "test-project" "current-content")]
      (is (nil? result))
      (is (= 0 (undo/get-undo-count "test-project"))))))

(deftest test-pop-redo
  (testing "Pop redo returns undone content"
    (undo/push-undo! "test-project" "content-v1")
    (undo/push-undo! "test-project" "content-v2")
    ;; Undo from v3 to v2
    (undo/pop-undo! "test-project" "content-v3")
    ;; Redo back to v3
    (let [result (undo/pop-redo! "test-project" "content-v2")]
      (is (= "content-v3" result))
      (is (= 2 (undo/get-undo-count "test-project")))
      (is (= 0 (undo/get-redo-count "test-project"))))))

(deftest test-pop-redo-empty
  (testing "Pop redo on empty stack returns nil"
    (let [result (undo/pop-redo! "test-project" "current-content")]
      (is (nil? result)))))

(deftest test-new-edit-clears-redo
  (testing "New edit (push) clears redo stack"
    (undo/push-undo! "test-project" "content-v1")
    (undo/push-undo! "test-project" "content-v2")
    ;; Undo to create redo entry
    (undo/pop-undo! "test-project" "content-v3")
    (is (= 1 (undo/get-redo-count "test-project")))
    ;; New edit should clear redo
    (undo/push-undo! "test-project" "content-v2-modified")
    (is (= 0 (undo/get-redo-count "test-project")))
    (is (= 2 (undo/get-undo-count "test-project")))))

(deftest test-clear-stacks
  (testing "Clear stacks removes all state"
    (undo/push-undo! "test-project" "content-v1")
    (undo/push-undo! "test-project" "content-v2")
    (undo/pop-undo! "test-project" "content-v3")
    (is (pos? (undo/get-undo-count "test-project")))
    (is (pos? (undo/get-redo-count "test-project")))
    (undo/clear-stacks! "test-project")
    (is (= 0 (undo/get-undo-count "test-project")))
    (is (= 0 (undo/get-redo-count "test-project")))))

;; =============================================================================
;; Multi-Project Isolation Tests
;; =============================================================================

(deftest test-project-isolation
  (testing "Different projects have separate stacks"
    (undo/push-undo! "project-a" "a-content-1")
    (undo/push-undo! "project-a" "a-content-2")
    (undo/push-undo! "project-b" "b-content-1")
    (is (= 2 (undo/get-undo-count "project-a")))
    (is (= 1 (undo/get-undo-count "project-b")))
    ;; Clear one project doesn't affect other
    (undo/clear-stacks! "project-a")
    (is (= 0 (undo/get-undo-count "project-a")))
    (is (= 1 (undo/get-undo-count "project-b")))
    ;; Cleanup
    (undo/clear-stacks! "project-b")))

;; =============================================================================
;; Full Undo/Redo Flow Test
;; =============================================================================

(deftest test-full-undo-redo-flow
  (testing "Complete undo/redo workflow"
    ;; Start editing
    (undo/push-undo! "test-project" "Initial content")
    (undo/push-undo! "test-project" "After edit 1")
    (undo/push-undo! "test-project" "After edit 2")

    ;; Current state: After edit 3 (not in stack yet, it's the "live" version)
    (is (= 3 (undo/get-undo-count "test-project")))

    ;; Undo twice
    (let [v2 (undo/pop-undo! "test-project" "After edit 3")]
      (is (= "After edit 2" v2)))
    (let [v1 (undo/pop-undo! "test-project" "After edit 2")]
      (is (= "After edit 1" v1)))

    ;; Now redo stack has 2 entries
    (is (= 2 (undo/get-redo-count "test-project")))
    (is (= 1 (undo/get-undo-count "test-project")))

    ;; Redo once
    (let [v2-again (undo/pop-redo! "test-project" "After edit 1")]
      (is (= "After edit 2" v2-again)))

    ;; State now
    (is (= 1 (undo/get-redo-count "test-project")))
    (is (= 2 (undo/get-undo-count "test-project")))))
