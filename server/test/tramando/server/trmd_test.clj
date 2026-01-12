(ns tramando.server.trmd-test
  "Tests for .trmd parser and serializer"
  (:require [clojure.test :refer [deftest testing is]]
            [tramando.server.trmd :as trmd]))

;; =============================================================================
;; Sample Content
;; =============================================================================

(def sample-trmd
  "---
title: \"Test Book\"
author: \"Test Author\"
language: \"it\"
year: 2024
---

[C:cap-1\"Primo capitolo\"]
Contenuto del primo capitolo.

  [C:scene-1\"Prima scena\"][@pers-1]
  Contenuto della prima scena.

  [C:scene-2\"Seconda scena\"][#owner:alice]
  Contenuto della seconda scena.

[C:cap-2\"Secondo capitolo\"]
Altro contenuto.

[C:personaggi\"Personaggi\"]

  [C:pers-1\"Mario Rossi\"][#priority:5]
  Protagonista della storia.")

(def sample-trmd-no-frontmatter
  "[C:cap-1\"Capitolo uno\"]
Contenuto.")

;; =============================================================================
;; Parser Tests
;; =============================================================================

(deftest parse-yaml-frontmatter-test
  (testing "Parse frontmatter with metadata"
    (let [result (trmd/parse-trmd sample-trmd)]
      (is (= "Test Book" (get-in result [:metadata :title])))
      (is (= "Test Author" (get-in result [:metadata :author])))
      (is (= "it" (get-in result [:metadata :language])))
      (is (= 2024 (get-in result [:metadata :year])))))

  (testing "Parse without frontmatter uses defaults"
    (let [result (trmd/parse-trmd sample-trmd-no-frontmatter)]
      (is (= "" (get-in result [:metadata :title])))
      (is (= "it" (get-in result [:metadata :language]))))))

(deftest parse-chunks-test
  (testing "Parse all chunks"
    (let [result (trmd/parse-trmd sample-trmd)
          chunks (:chunks result)
          ids (set (map :id chunks))]
      (is (= 6 (count chunks)))
      (is (contains? ids "cap-1"))
      (is (contains? ids "scene-1"))
      (is (contains? ids "scene-2"))
      (is (contains? ids "cap-2"))
      (is (contains? ids "personaggi"))
      (is (contains? ids "pers-1"))))

  (testing "Parse parent-child relationships"
    (let [result (trmd/parse-trmd sample-trmd)
          chunks (:chunks result)
          by-id (into {} (map (juxt :id identity) chunks))]
      (is (nil? (:parent-id (by-id "cap-1"))))
      (is (= "cap-1" (:parent-id (by-id "scene-1"))))
      (is (= "cap-1" (:parent-id (by-id "scene-2"))))
      (is (nil? (:parent-id (by-id "cap-2"))))
      (is (= "personaggi" (:parent-id (by-id "pers-1"))))))

  (testing "Parse aspects"
    (let [result (trmd/parse-trmd sample-trmd)
          chunks (:chunks result)
          by-id (into {} (map (juxt :id identity) chunks))]
      (is (= #{"pers-1"} (:aspects (by-id "scene-1"))))
      (is (= #{} (:aspects (by-id "scene-2"))))))

  (testing "Parse owner attribute"
    (let [result (trmd/parse-trmd sample-trmd)
          chunks (:chunks result)
          by-id (into {} (map (juxt :id identity) chunks))]
      (is (= "local" (:owner (by-id "cap-1"))))
      (is (= "alice" (:owner (by-id "scene-2"))))))

  (testing "Parse priority attribute"
    (let [result (trmd/parse-trmd sample-trmd)
          chunks (:chunks result)
          by-id (into {} (map (juxt :id identity) chunks))]
      (is (nil? (:priority (by-id "cap-1"))))
      (is (= 5 (:priority (by-id "pers-1"))))))

  (testing "Parse content"
    (let [result (trmd/parse-trmd sample-trmd)
          chunks (:chunks result)
          by-id (into {} (map (juxt :id identity) chunks))]
      (is (= "Contenuto del primo capitolo." (:content (by-id "cap-1"))))
      (is (= "Contenuto della prima scena." (:content (by-id "scene-1")))))))

;; =============================================================================
;; Serializer Tests
;; =============================================================================

(deftest serialize-metadata-test
  (testing "Serialize metadata to YAML"
    (let [metadata {:title "My Book" :author "Author" :language "en" :year 2025}
          result (trmd/serialize-trmd metadata [])]
      (is (clojure.string/includes? result "---"))
      (is (clojure.string/includes? result "title: \"My Book\""))
      (is (clojure.string/includes? result "author: \"Author\""))
      (is (clojure.string/includes? result "year: 2025")))))

(deftest serialize-chunks-test
  (testing "Serialize simple chunk"
    (let [chunks [{:id "cap-1" :summary "Chapter 1" :content "Some text"
                   :parent-id nil :aspects #{} :owner "local" :discussion []}]
          result (trmd/serialize-chunks chunks)]
      (is (clojure.string/includes? result "[C:cap-1\"Chapter 1\"]"))
      (is (clojure.string/includes? result "Some text"))))

  (testing "Serialize chunk with aspects"
    (let [chunks [{:id "scene-1" :summary "Scene" :content "Text"
                   :parent-id nil :aspects #{"pers-1" "luogo-1"} :owner "local" :discussion []}]
          result (trmd/serialize-chunks chunks)]
      (is (clojure.string/includes? result "[@pers-1]"))
      (is (clojure.string/includes? result "[@luogo-1]"))))

  (testing "Serialize chunk with owner"
    (let [chunks [{:id "cap-1" :summary "Ch" :content ""
                   :parent-id nil :aspects #{} :owner "bob" :discussion []}]
          result (trmd/serialize-chunks chunks)]
      (is (clojure.string/includes? result "[#owner:bob]"))))

  (testing "Serialize chunk with priority"
    (let [chunks [{:id "pers-1" :summary "Mario" :content ""
                   :parent-id nil :aspects #{} :owner "local" :priority 8 :discussion []}]
          result (trmd/serialize-chunks chunks)]
      (is (clojure.string/includes? result "[#priority:8]"))))

  (testing "Serialize nested chunks with indentation"
    (let [chunks [{:id "cap-1" :summary "Chapter" :content "" :parent-id nil :aspects #{} :owner "local" :discussion []}
                  {:id "scene-1" :summary "Scene" :content "" :parent-id "cap-1" :aspects #{} :owner "local" :discussion []}]
          result (trmd/serialize-chunks chunks)]
      (is (clojure.string/includes? result "[C:cap-1\"Chapter\"]"))
      (is (clojure.string/includes? result "  [C:scene-1\"Scene\"]")))))

;; =============================================================================
;; Roundtrip Tests
;; =============================================================================

(deftest roundtrip-test
  (testing "Parse and serialize produces equivalent content"
    (let [parsed (trmd/parse-trmd sample-trmd)
          serialized (trmd/serialize-trmd (:metadata parsed) (:chunks parsed))
          reparsed (trmd/parse-trmd serialized)]
      ;; Metadata should match
      (is (= (:title (:metadata parsed)) (:title (:metadata reparsed))))
      (is (= (:author (:metadata parsed)) (:author (:metadata reparsed))))
      ;; Same number of chunks
      (is (= (count (:chunks parsed)) (count (:chunks reparsed))))
      ;; Same chunk IDs
      (is (= (set (map :id (:chunks parsed)))
             (set (map :id (:chunks reparsed)))))
      ;; Same parent relationships
      (let [parsed-parents (into {} (map (juxt :id :parent-id) (:chunks parsed)))
            reparsed-parents (into {} (map (juxt :id :parent-id) (:chunks reparsed)))]
        (is (= parsed-parents reparsed-parents))))))

;; =============================================================================
;; Chunk Operation Tests
;; =============================================================================

(deftest find-chunk-test
  (testing "Find existing chunk"
    (let [chunks [{:id "a"} {:id "b"} {:id "c"}]]
      (is (= {:id "b"} (trmd/find-chunk chunks "b")))))

  (testing "Find non-existing chunk returns nil"
    (let [chunks [{:id "a"}]]
      (is (nil? (trmd/find-chunk chunks "x"))))))

(deftest add-chunk-test
  (testing "Add chunk to list"
    (let [chunks [{:id "a"}]
          new-chunk {:id "b" :summary "New"}
          result (trmd/add-chunk chunks new-chunk)]
      (is (= 2 (count result)))
      (is (some #(= "b" (:id %)) result)))))

(deftest remove-chunk-test
  (testing "Remove chunk by ID"
    (let [chunks [{:id "a"} {:id "b"} {:id "c"}]
          result (trmd/remove-chunk chunks "b")]
      (is (= 2 (count result)))
      (is (not (some #(= "b" (:id %)) result)))))

  (testing "Remove chunk also removes children"
    (let [chunks [{:id "parent" :parent-id nil}
                  {:id "child1" :parent-id "parent"}
                  {:id "child2" :parent-id "parent"}
                  {:id "other" :parent-id nil}]
          result (trmd/remove-chunk chunks "parent")]
      (is (= 1 (count result)))
      (is (= "other" (:id (first result))))))

  (testing "Remove chunk cleans up aspect references"
    (let [chunks [{:id "a" :aspects #{"b"}}
                  {:id "b" :aspects #{}}]
          result (trmd/remove-chunk chunks "b")]
      (is (= 1 (count result)))
      (is (= #{} (:aspects (first result)))))))

(deftest generate-id-test
  (testing "Generate ID for root chunk"
    (let [chunks []
          id (trmd/generate-id chunks nil)]
      (is (= "cap-1" id))))

  (testing "Generate ID with existing chunks"
    (let [chunks [{:id "cap-1"} {:id "cap-2"} {:id "cap-4"}]
          id (trmd/generate-id chunks nil)]
      (is (= "cap-3" id))))

  (testing "Generate ID for aspect"
    (let [chunks []
          id (trmd/generate-id chunks "personaggi")]
      (is (= "pers-1" id))))

  (testing "Generate ID for scene"
    (let [chunks []
          id (trmd/generate-id chunks "cap-1")]
      (is (= "scene-1" id)))))

(deftest is-aspect-test
  (testing "Chunk under aspect container is aspect"
    (is (trmd/is-aspect? {:id "pers-1" :parent-id "personaggi"}))
    (is (trmd/is-aspect? {:id "luogo-1" :parent-id "luoghi"})))

  (testing "Regular chunk is not aspect"
    (is (not (trmd/is-aspect? {:id "cap-1" :parent-id nil})))
    (is (not (trmd/is-aspect? {:id "scene-1" :parent-id "cap-1"})))))
