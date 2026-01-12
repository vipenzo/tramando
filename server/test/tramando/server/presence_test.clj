(ns tramando.server.presence-test
  "Tests for presence tracking functionality."
  (:require [clojure.test :refer :all]
            [tramando.server.presence :as presence]))

;; =============================================================================
;; Setup/Teardown
;; =============================================================================

(use-fixtures :each (fn [f]
                      (presence/clear-all!)
                      (f)
                      (presence/clear-all!)))

;; =============================================================================
;; Basic Presence Tests
;; =============================================================================

(deftest test-notify-editing
  (testing "Notify editing adds user to chunk"
    (presence/notify-editing! "project-1" "chunk-1" "alice")
    (let [editors (presence/get-editors "project-1" "chunk-1")]
      (is (contains? editors "alice")))))

(deftest test-notify-multiple-users
  (testing "Multiple users can edit same chunk"
    (presence/notify-editing! "project-1" "chunk-1" "alice")
    (presence/notify-editing! "project-1" "chunk-1" "bob")
    (let [editors (presence/get-editors "project-1" "chunk-1")]
      (is (contains? editors "alice"))
      (is (contains? editors "bob"))
      (is (= 2 (count editors))))))

(deftest test-notify-stopped-editing
  (testing "Notify stopped removes user from chunk"
    (presence/notify-editing! "project-1" "chunk-1" "alice")
    (presence/notify-editing! "project-1" "chunk-1" "bob")
    (presence/notify-stopped-editing! "project-1" "chunk-1" "alice")
    (let [editors (presence/get-editors "project-1" "chunk-1")]
      (is (not (contains? editors "alice")))
      (is (contains? editors "bob")))))

(deftest test-get-project-presence
  (testing "Get all presence info for project"
    (presence/notify-editing! "project-1" "chunk-1" "alice")
    (presence/notify-editing! "project-1" "chunk-2" "bob")
    (presence/notify-editing! "project-1" "chunk-2" "charlie")
    (let [presence-map (presence/get-project-presence "project-1")]
      (is (= ["alice"] (get presence-map "chunk-1")))
      (is (= 2 (count (get presence-map "chunk-2")))))))

(deftest test-get-project-presence-excluding
  (testing "Exclude self from presence info"
    (presence/notify-editing! "project-1" "chunk-1" "alice")
    (presence/notify-editing! "project-1" "chunk-1" "bob")
    (let [presence-map (presence/get-project-presence-excluding "project-1" "alice")]
      (is (= ["bob"] (get presence-map "chunk-1"))))))

(deftest test-is-chunk-being-edited
  (testing "Check if chunk is being edited by others"
    (presence/notify-editing! "project-1" "chunk-1" "alice")
    (presence/notify-editing! "project-1" "chunk-1" "bob")
    ;; Alice checks - bob is editing
    (is (presence/is-chunk-being-edited? "project-1" "chunk-1" "alice"))
    ;; When only alice is editing, she doesn't count as "others"
    (presence/notify-stopped-editing! "project-1" "chunk-1" "bob")
    (is (not (presence/is-chunk-being-edited? "project-1" "chunk-1" "alice")))))

;; =============================================================================
;; Project Isolation Tests
;; =============================================================================

(deftest test-project-isolation
  (testing "Different projects are isolated"
    (presence/notify-editing! "project-1" "chunk-1" "alice")
    (presence/notify-editing! "project-2" "chunk-1" "bob")
    (let [p1-presence (presence/get-project-presence "project-1")
          p2-presence (presence/get-project-presence "project-2")]
      (is (= ["alice"] (get p1-presence "chunk-1")))
      (is (= ["bob"] (get p2-presence "chunk-1"))))))

;; =============================================================================
;; Cleanup Tests
;; =============================================================================

(deftest test-cleanup-project
  (testing "Cleanup project removes all presence"
    (presence/notify-editing! "project-1" "chunk-1" "alice")
    (presence/notify-editing! "project-1" "chunk-2" "bob")
    (presence/cleanup-project! "project-1")
    (let [presence-map (presence/get-project-presence "project-1")]
      (is (empty? presence-map)))))

(deftest test-cleanup-user
  (testing "Cleanup user removes from all projects"
    (presence/notify-editing! "project-1" "chunk-1" "alice")
    (presence/notify-editing! "project-2" "chunk-1" "alice")
    (presence/notify-editing! "project-1" "chunk-2" "bob")
    (presence/cleanup-user! "alice")
    (let [p1 (presence/get-project-presence "project-1")
          p2 (presence/get-project-presence "project-2")]
      ;; Alice removed from project-1 chunk-1
      (is (nil? (get p1 "chunk-1")))
      ;; Bob still in project-1 chunk-2
      (is (= ["bob"] (get p1 "chunk-2")))
      ;; Alice removed from project-2
      (is (empty? p2)))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest test-empty-project
  (testing "Empty project returns empty map"
    (let [presence-map (presence/get-project-presence "nonexistent")]
      (is (= {} presence-map)))))

(deftest test-stop-editing-nonexistent
  (testing "Stop editing nonexistent user is no-op"
    (presence/notify-stopped-editing! "project-1" "chunk-1" "ghost")
    ;; Should not throw
    (is (= {} (presence/get-project-presence "project-1")))))

(deftest test-double-notify-editing
  (testing "Double notify editing is idempotent"
    (presence/notify-editing! "project-1" "chunk-1" "alice")
    (presence/notify-editing! "project-1" "chunk-1" "alice")
    (let [editors (presence/get-editors "project-1" "chunk-1")]
      (is (= 1 (count editors)))
      (is (contains? editors "alice")))))
