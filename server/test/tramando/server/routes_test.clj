(ns tramando.server.routes-test
  "Tests for content validation in routes"
  (:require [clojure.test :refer [deftest testing is]]
            [tramando.server.routes :as routes]))

;; Access private functions for testing
(def validate-trmd-content #'routes/validate-trmd-content)
(def extract-chunk-ids-and-summaries #'routes/extract-chunk-ids-and-summaries)

;; =============================================================================
;; Extract Chunk IDs Tests
;; =============================================================================

(deftest extract-chunks-simple-test
  (testing "extracts single chunk"
    (let [content "[C:cap1\"Capitolo 1\"]\nContenuto."
          chunks (extract-chunk-ids-and-summaries content)]
      (is (= 1 (count chunks)))
      (is (= "cap1" (:id (first chunks))))
      (is (= "Capitolo 1" (:summary (first chunks)))))))

(deftest extract-chunks-multiple-test
  (testing "extracts multiple chunks"
    (let [content "[C:cap1\"Primo\"]\nTesto.\n\n[C:cap2\"Secondo\"]\nAltro testo."
          chunks (extract-chunk-ids-and-summaries content)]
      (is (= 2 (count chunks)))
      (is (= "cap1" (:id (first chunks))))
      (is (= "cap2" (:id (second chunks)))))))

(deftest extract-chunks-with-aspects-test
  (testing "extracts chunks with aspects (aspects ignored)"
    (let [content "[C:scena1\"Scena\"][@Mario][@Luogo]\nContenuto."
          chunks (extract-chunk-ids-and-summaries content)]
      (is (= 1 (count chunks)))
      (is (= "scena1" (:id (first chunks))))
      (is (= "Scena" (:summary (first chunks)))))))

(deftest extract-chunks-empty-test
  (testing "returns nil for empty content"
    (is (nil? (extract-chunk-ids-and-summaries nil)))
    (is (empty? (extract-chunk-ids-and-summaries "")))))

;; =============================================================================
;; Validation Tests
;; =============================================================================

(deftest validate-empty-content-test
  (testing "empty content is valid"
    (is (:ok? (validate-trmd-content "")))
    (is (:ok? (validate-trmd-content nil)))
    (is (:ok? (validate-trmd-content "   ")))))

(deftest validate-valid-content-test
  (testing "valid content passes validation"
    (let [content "[C:cap1\"Capitolo 1\"]\nContenuto.\n\n[C:cap2\"Capitolo 2\"]\nAltro."
          result (validate-trmd-content content)]
      (is (:ok? result))
      (is (nil? (:errors result))))))

(deftest validate-duplicate-ids-test
  (testing "duplicate IDs are detected"
    (let [content "[C:cap1\"Primo\"]\nTesto.\n\n[C:cap1\"Duplicato\"]\nAltro."
          result (validate-trmd-content content)]
      (is (not (:ok? result)))
      (is (= 1 (count (:errors result))))
      (is (= :duplicate-id (:type (first (:errors result)))))
      (is (= "cap1" (:id (first (:errors result))))))))

(deftest validate-quotes-in-summary-test
  (testing "malformed header with quotes is not parsed (protection by format)"
    ;; When summary contains quotes, the regex [C:id"summary"] won't match
    ;; This means the chunk is effectively invisible/broken
    ;; The frontend validation catches this when chunks are in memory
    (let [content "[C:cap1\"Lui disse \"ciao\"\"]\nContenuto."
          chunks (extract-chunk-ids-and-summaries content)]
      ;; The malformed header is not parsed at all
      (is (empty? chunks)))))

(deftest validate-multiple-errors-test
  (testing "multiple duplicate IDs are all reported"
    (let [content "[C:dup1\"A\"]\n\n[C:dup1\"B\"]\n\n[C:dup2\"C\"]\n\n[C:dup2\"D\"]"
          result (validate-trmd-content content)]
      (is (not (:ok? result)))
      (is (= 2 (count (:errors result))))
      (is (every? #(= :duplicate-id (:type %)) (:errors result))))))

(deftest validate-nested-chunks-test
  (testing "nested chunks with unique IDs are valid"
    (let [content "---
title: \"Test\"
---

[C:cap1\"Capitolo 1\"]
Intro.

  [C:scena1\"Scena 1\"]
  Contenuto scena.

    [C:beat1\"Beat 1\"]
    Dettaglio.

[C:cap2\"Capitolo 2\"]
Altro capitolo."
          result (validate-trmd-content content)]
      (is (:ok? result)))))
