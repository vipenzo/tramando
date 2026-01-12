(ns tramando.server.versioning-test
  "Tests for git-based versioning functionality."
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [tramando.server.versioning :as versioning]
            [tramando.server.config :refer [config]])
  (:import [java.io File]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(def test-project-id "test-versioning-project")

(defn cleanup-test-project! []
  (let [project-dir (File. (versioning/project-dir-path test-project-id))]
    (when (.exists project-dir)
      (doseq [f (reverse (file-seq project-dir))]
        (.delete f)))))

(use-fixtures :each (fn [f]
                      (cleanup-test-project!)
                      (f)
                      (cleanup-test-project!)))

;; =============================================================================
;; Repository Management Tests
;; =============================================================================

(deftest test-repo-does-not-exist-initially
  (testing "New project has no repo"
    (is (not (versioning/repo-exists? test-project-id)))))

(deftest test-init-repo
  (testing "Init creates git repository"
    (let [result (versioning/init-repo! test-project-id)]
      (is (:ok result))
      (is (versioning/repo-exists? test-project-id)))))

(deftest test-init-repo-with-content
  (testing "Init with content creates initial commit"
    (versioning/init-repo! test-project-id "---\ntitle: Test\n---\n\n# Cap 1\nTest content")
    (is (versioning/repo-exists? test-project-id))
    ;; Verify file exists
    (let [content (versioning/load-from-repo test-project-id)]
      (is (some? content))
      (is (clojure.string/includes? content "Test content")))))

;; =============================================================================
;; Save and Load Tests
;; =============================================================================

(deftest test-save-and-load
  (testing "Save and load content"
    (versioning/init-repo! test-project-id)
    (versioning/save-to-repo! test-project-id "New content here")
    (let [loaded (versioning/load-from-repo test-project-id)]
      (is (= "New content here" loaded)))))

;; =============================================================================
;; Version Creation Tests
;; =============================================================================

(deftest test-create-auto-version
  (testing "Auto-version creates a commit"
    (versioning/init-repo! test-project-id "Initial content")
    ;; Make a change
    (versioning/save-to-repo! test-project-id "Modified content")
    ;; Create auto-version
    (let [result (versioning/create-auto-version! test-project-id)]
      (is (:ok result)))))

(deftest test-create-tagged-version
  (testing "Tagged version creates a git tag"
    (versioning/init-repo! test-project-id "Initial content")
    ;; Make a change and create tag
    (versioning/save-to-repo! test-project-id "Version 1.0 content")
    (let [result (versioning/create-tagged-version! test-project-id "v1.0" "First release")]
      (is (:ok result)))))

;; =============================================================================
;; Version Listing Tests
;; =============================================================================

(deftest test-list-versions
  (testing "List versions returns commit history"
    (versioning/init-repo! test-project-id "Initial content")
    ;; Create some versions
    (versioning/save-to-repo! test-project-id "Version 1")
    (versioning/create-auto-version! test-project-id)
    (versioning/save-to-repo! test-project-id "Version 2")
    (versioning/create-auto-version! test-project-id)
    ;; List versions
    (let [versions (versioning/list-versions test-project-id)]
      (is (vector? versions))
      (is (>= (count versions) 2)))))

(deftest test-list-versions-with-tags
  (testing "List versions includes tags"
    (versioning/init-repo! test-project-id "Initial content")
    ;; Create tagged version
    (versioning/save-to-repo! test-project-id "Tagged version")
    (versioning/create-tagged-version! test-project-id "v1.0" "Release")
    ;; List versions
    (let [versions (versioning/list-versions test-project-id)]
      (is (some :is-tag versions))
      (is (some #(= "v1.0" (:tag %)) versions)))))

;; =============================================================================
;; Version Content Retrieval Tests
;; =============================================================================

(deftest test-get-version-content
  (testing "Get content of a specific version"
    (versioning/init-repo! test-project-id "Initial content")
    ;; Create first version
    (versioning/save-to-repo! test-project-id "Version 1 content")
    (versioning/create-auto-version! test-project-id)
    ;; Create second version
    (versioning/save-to-repo! test-project-id "Version 2 content")
    (versioning/create-auto-version! test-project-id)
    ;; Get versions
    (let [versions (versioning/list-versions test-project-id)
          first-version (last versions)]  ;; last = oldest
      (when first-version
        ;; Current content should be version 2
        (let [current (versioning/load-from-repo test-project-id)]
          (is (= "Version 2 content" current)))
        ;; Can retrieve old version by ref
        (let [old-content (versioning/get-version-content test-project-id (:ref first-version))]
          (is (some? old-content)))))))

(deftest test-get-version-content-by-tag
  (testing "Get content by tag name"
    (versioning/init-repo! test-project-id "Initial content")
    (versioning/save-to-repo! test-project-id "Tagged version content")
    (versioning/create-tagged-version! test-project-id "release-1" "First release")
    ;; Make more changes
    (versioning/save-to-repo! test-project-id "Later content")
    (versioning/create-auto-version! test-project-id)
    ;; Retrieve by tag
    (let [tagged-content (versioning/get-version-content test-project-id "release-1")]
      (is (= "Tagged version content" tagged-content)))))

;; =============================================================================
;; Migration Tests (Legacy to Repo)
;; =============================================================================

(deftest test-ensure-repo-new-project
  (testing "Ensure repo on new project creates repo"
    (let [result (versioning/ensure-repo! test-project-id)]
      (is (:ok result))
      (is (versioning/repo-exists? test-project-id)))))

(deftest test-ensure-repo-existing
  (testing "Ensure repo on existing repo is no-op"
    (versioning/init-repo! test-project-id)
    (let [result (versioning/ensure-repo! test-project-id)]
      (is (:ok result)))))
