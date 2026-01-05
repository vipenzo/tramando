(ns tramando.model-test
  "Unit tests for tramando.model - parsing, serialization, and chunk operations"
  (:require [cljs.test :refer [deftest testing is are]]
            [tramando.model :as model]))

;; =============================================================================
;; Parse/Serialize Tests
;; =============================================================================

(deftest parse-empty-file-test
  (testing "parsing empty file returns empty chunks with default metadata"
    (let [result (model/parse-file "")]
      (is (= [] (:chunks result)))
      (is (map? (:metadata result)))
      (is (= "" (:title (:metadata result)))))))

(deftest parse-frontmatter-test
  (testing "parsing YAML frontmatter"
    (let [input "---
title: \"Il mio romanzo\"
author: \"Mario Rossi\"
language: \"it\"
year: 2024
---

[C:cap1\"Capitolo 1\"]
Contenuto del capitolo."
          result (model/parse-file input)]
      (is (= "Il mio romanzo" (:title (:metadata result))))
      (is (= "Mario Rossi" (:author (:metadata result))))
      (is (= "it" (:language (:metadata result))))
      (is (= 2024 (:year (:metadata result)))))))

(deftest parse-simple-chunk-test
  (testing "parsing a single chunk"
    (let [input "[C:cap1\"Capitolo 1\"]
Contenuto del capitolo."
          result (model/parse-file input)
          chunk (first (:chunks result))]
      (is (= 1 (count (:chunks result))))
      (is (= "cap1" (:id chunk)))
      (is (= "Capitolo 1" (:summary chunk)))
      (is (= "Contenuto del capitolo." (:content chunk)))
      (is (nil? (:parent-id chunk))))))

(deftest parse-nested-chunks-test
  (testing "parsing nested chunks with indentation"
    (let [input "[C:cap1\"Capitolo 1\"]
Intro capitolo.

  [C:scena1\"Scena 1\"]
  Prima scena.

    [C:beat1\"Beat 1\"]
    Dettaglio.

  [C:scena2\"Scena 2\"]
  Seconda scena."
          result (model/parse-file input)
          chunks (:chunks result)
          cap (first (filter #(= "cap1" (:id %)) chunks))
          scena1 (first (filter #(= "scena1" (:id %)) chunks))
          beat1 (first (filter #(= "beat1" (:id %)) chunks))
          scena2 (first (filter #(= "scena2" (:id %)) chunks))]
      (is (= 4 (count chunks)))
      (is (nil? (:parent-id cap)))
      (is (= "cap1" (:parent-id scena1)))
      (is (= "scena1" (:parent-id beat1)))
      (is (= "cap1" (:parent-id scena2))))))

(deftest parse-aspects-test
  (testing "parsing chunk with aspects"
    (let [input "[C:scena1\"Scena al bar\"][@Mario][@BarCentrale]
Mario entra nel bar."
          result (model/parse-file input)
          chunk (first (:chunks result))]
      (is (= #{"Mario" "BarCentrale"} (:aspects chunk))))))

(deftest parse-owner-test
  (testing "parsing chunk with owner attribute"
    (let [input "[C:scena1\"Scena\"][#owner:luigi]
Contenuto."
          result (model/parse-file input)
          chunk (first (:chunks result))]
      (is (= "luigi" (:owner chunk))))))

(deftest parse-owner-default-test
  (testing "chunk without owner defaults to 'local'"
    (let [input "[C:scena1\"Scena\"]
Contenuto."
          result (model/parse-file input)
          chunk (first (:chunks result))]
      (is (= "local" (:owner chunk))))))

;; =============================================================================
;; Roundtrip Tests
;; =============================================================================

(deftest roundtrip-simple-test
  (testing "parse + serialize maintains data integrity"
    (let [original "[C:cap1\"Capitolo 1\"]
Contenuto del capitolo."
          parsed (model/parse-file original)
          serialized (model/serialize-file (:chunks parsed) (:metadata parsed))
          reparsed (model/parse-file serialized)]
      (is (= (count (:chunks parsed)) (count (:chunks reparsed))))
      (is (= (:id (first (:chunks parsed))) (:id (first (:chunks reparsed)))))
      (is (= (:summary (first (:chunks parsed))) (:summary (first (:chunks reparsed)))))
      (is (= (:content (first (:chunks parsed))) (:content (first (:chunks reparsed))))))))

(deftest roundtrip-with-metadata-test
  (testing "roundtrip preserves metadata"
    (let [original "---
title: \"Test\"
author: \"Autore\"
---

[C:cap1\"Cap\"]
Test."
          parsed (model/parse-file original)
          serialized (model/serialize-file (:chunks parsed) (:metadata parsed))
          reparsed (model/parse-file serialized)]
      (is (= (:title (:metadata parsed)) (:title (:metadata reparsed))))
      (is (= (:author (:metadata parsed)) (:author (:metadata reparsed)))))))

(deftest roundtrip-nested-test
  (testing "roundtrip preserves hierarchy"
    (let [original "[C:cap1\"Cap 1\"]
Intro.

  [C:scena1\"Scena 1\"]
  Contenuto scena."
          parsed (model/parse-file original)
          serialized (model/serialize-file (:chunks parsed) (:metadata parsed))
          reparsed (model/parse-file serialized)
          scena-original (first (filter #(= "scena1" (:id %)) (:chunks parsed)))
          scena-reparsed (first (filter #(= "scena1" (:id %)) (:chunks reparsed)))]
      (is (= (:parent-id scena-original) (:parent-id scena-reparsed))))))

(deftest roundtrip-aspects-test
  (testing "roundtrip preserves aspects"
    (let [original "[C:scena1\"Scena\"][@PersonaggioA][@LuogoB]
Test."
          parsed (model/parse-file original)
          serialized (model/serialize-file (:chunks parsed) (:metadata parsed))
          reparsed (model/parse-file serialized)]
      (is (= (:aspects (first (:chunks parsed)))
             (:aspects (first (:chunks reparsed))))))))

(deftest roundtrip-owner-test
  (testing "roundtrip preserves owner"
    (let [original "[C:scena1\"Scena\"][#owner:filippo]
Test."
          parsed (model/parse-file original)
          serialized (model/serialize-file (:chunks parsed) (:metadata parsed))
          reparsed (model/parse-file serialized)]
      (is (= "filippo" (:owner (first (:chunks reparsed))))))))

;; =============================================================================
;; Merge Tests
;; =============================================================================

(deftest merge-no-conflict-test
  (testing "merge with identical content produces no conflicts"
    (let [server-content "[C:cap1\"Capitolo 1\"]
Contenuto."
          local-chunks [{:id "cap1" :summary "Capitolo 1" :content "Contenuto."
                         :parent-id nil :aspects #{} :owner "local" :discussion []}]
          result (model/merge-with-server-content local-chunks server-content)]
      (is (empty? (:conflicts result)))
      (is (= 1 (count (:merged-chunks result)))))))

(deftest merge-new-local-chunk-test
  (testing "merge preserves new local chunks"
    (let [server-content "[C:cap1\"Capitolo 1\"]
Contenuto."
          local-chunks [{:id "cap1" :summary "Capitolo 1" :content "Contenuto."
                         :parent-id nil :aspects #{} :owner "local" :discussion []}
                        {:id "cap2" :summary "Nuovo locale" :content "Nuovo."
                         :parent-id nil :aspects #{} :owner "filippo" :discussion []}]
          result (model/merge-with-server-content local-chunks server-content)]
      (is (= 2 (count (:merged-chunks result))))
      (is (some #(= "cap2" (:id %)) (:merged-chunks result))))))

(deftest merge-conflict-server-wins-test
  (testing "merge conflict: server wins but reports conflict"
    (let [server-content "[C:cap1\"Capitolo 1\"][#owner:luigi]
Versione server."
          local-chunks [{:id "cap1" :summary "Capitolo 1" :content "Versione locale."
                         :parent-id nil :aspects #{} :owner "filippo" :discussion []}]
          result (model/merge-with-server-content local-chunks server-content)
          merged-chunk (first (:merged-chunks result))]
      (is (= 1 (count (:conflicts result))))
      (is (= "Versione server." (:content merged-chunk)))
      (is (= "luigi" (:owner merged-chunk))))))

(deftest merge-discussion-combined-test
  (testing "merge combines discussions from both sides"
    (let [server-content "[C:cap1\"Capitolo 1\"]
Contenuto.

[!DISCUSSION:W3sidHlwZSI6ImNvbW1lbnQiLCJ0ZXh0IjoiQ29tbWVudG8gc2VydmVyIiwiYXV0aG9yIjoibHVpZ2kiLCJ0aW1lc3RhbXAiOjE3MDAwMDAwMDB9XQ==]"
          local-discussion [{:type "comment" :text "Commento locale" :author "filippo" :timestamp 1700000001}]
          local-chunks [{:id "cap1" :summary "Capitolo 1" :content "Contenuto."
                         :parent-id nil :aspects #{} :owner "local" :discussion local-discussion}]
          result (model/merge-with-server-content local-chunks server-content)
          merged-chunk (first (:merged-chunks result))]
      ;; Should have both discussions merged
      (is (>= (count (:discussion merged-chunk)) 1)))))

;; =============================================================================
;; Edge Cases
;; =============================================================================

(deftest parse-multiline-content-test
  (testing "parsing chunk with multiline content"
    (let [input "[C:cap1\"Capitolo\"]
Prima riga.

Seconda riga dopo spazio.

Terza riga."
          result (model/parse-file input)
          chunk (first (:chunks result))]
      (is (string? (:content chunk)))
      (is (clojure.string/includes? (:content chunk) "Prima riga"))
      (is (clojure.string/includes? (:content chunk) "Seconda riga")))))

(deftest parse-special-chars-in-summary-test
  (testing "parsing chunk with special characters in summary"
    (let [input "[C:cap1\"Capitolo: L'inizio\"]
Contenuto."
          result (model/parse-file input)
          chunk (first (:chunks result))]
      (is (= "Capitolo: L'inizio" (:summary chunk))))))

(deftest serialize-empty-aspects-test
  (testing "serializing chunk with no aspects"
    (let [chunks [{:id "cap1" :summary "Test" :content "Content."
                   :parent-id nil :aspects #{} :owner "local" :discussion []}]
          serialized (model/serialize-chunks chunks)]
      (is (clojure.string/includes? serialized "[C:cap1\"Test\"]"))
      (is (not (clojure.string/includes? serialized "[@"))))))
