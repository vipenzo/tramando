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

(deftest parse-multiple-chapters-test
  (testing "parsing multiple chapters with interleaved scenes"
    (let [input "[C:cap1\"Capitolo 1\"]
Intro primo capitolo.

  [C:scena1-1\"Scena 1.1\"]
  Prima scena del primo capitolo.

  [C:scena1-2\"Scena 1.2\"]
  Seconda scena del primo capitolo.

[C:cap2\"Capitolo 2\"]
Intro secondo capitolo.

  [C:scena2-1\"Scena 2.1\"]
  Prima scena del secondo capitolo.

    [C:beat2-1-1\"Beat profondo\"]
    Dettaglio annidato nel secondo capitolo.

  [C:scena2-2\"Scena 2.2\"]
  Seconda scena del secondo capitolo.

[C:cap3\"Capitolo 3\"]
Terzo capitolo senza scene."
          result (model/parse-file input)
          chunks (:chunks result)
          get-chunk (fn [id] (first (filter #(= id (:id %)) chunks)))]
      ;; Verify count
      (is (= 8 (count chunks)))
      ;; Verify root chapters have no parent
      (is (nil? (:parent-id (get-chunk "cap1"))))
      (is (nil? (:parent-id (get-chunk "cap2"))))
      (is (nil? (:parent-id (get-chunk "cap3"))))
      ;; Verify scenes belong to correct chapters
      (is (= "cap1" (:parent-id (get-chunk "scena1-1"))))
      (is (= "cap1" (:parent-id (get-chunk "scena1-2"))))
      (is (= "cap2" (:parent-id (get-chunk "scena2-1"))))
      (is (= "cap2" (:parent-id (get-chunk "scena2-2"))))
      ;; Verify deep nesting
      (is (= "scena2-1" (:parent-id (get-chunk "beat2-1-1")))))))

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

(deftest roundtrip-discussion-special-chars-test
  (testing "roundtrip preserves discussion with special characters (emoji, accents)"
    (let [special-text "Perché? 😅 àèìòù"
          chunks [{:id "cap1" :summary "Test" :content "Contenuto."
                   :parent-id nil :aspects #{} :owner "local"
                   :discussion [{:type "comment"
                                 :text special-text
                                 :author "mario"
                                 :timestamp 1700000000}]}]
          serialized (model/serialize-file chunks {})
          reparsed (model/parse-file serialized)
          reparsed-discussion (:discussion (first (:chunks reparsed)))]
      (is (= 1 (count reparsed-discussion)))
      (is (= special-text (:text (first reparsed-discussion)))))))

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

;; =============================================================================
;; Validation Tests
;; =============================================================================

(deftest validate-valid-project-test
  (testing "valid project returns ok"
    (let [chunks [{:id "cap1" :summary "Capitolo 1" :content "Test"
                   :parent-id nil :aspects #{}}
                  {:id "scena1" :summary "Scena 1" :content "Test"
                   :parent-id "cap1" :aspects #{"cap1"}}]
          result (model/validate-project chunks)]
      (is (:ok? result))
      (is (nil? (:errors result))))))

(deftest validate-duplicate-ids-test
  (testing "duplicate IDs are detected"
    (let [chunks [{:id "cap1" :summary "Capitolo 1" :content "Test"
                   :parent-id nil :aspects #{}}
                  {:id "cap1" :summary "Capitolo 1 bis" :content "Test"
                   :parent-id nil :aspects #{}}]
          result (model/validate-project chunks)]
      (is (not (:ok? result)))
      (is (= 1 (count (:errors result))))
      (is (= :duplicate-id (:type (first (:errors result)))))
      (is (= "cap1" (:id (first (:errors result))))))))

(deftest validate-missing-parent-test
  (testing "missing parent-id is detected"
    (let [chunks [{:id "cap1" :summary "Capitolo 1" :content "Test"
                   :parent-id nil :aspects #{}}
                  {:id "scena1" :summary "Scena 1" :content "Test"
                   :parent-id "nonexistent" :aspects #{}}]
          result (model/validate-project chunks)]
      (is (not (:ok? result)))
      (is (= 1 (count (:errors result))))
      (is (= :missing-parent (:type (first (:errors result)))))
      (is (= "scena1" (:id (first (:errors result)))))
      (is (= "nonexistent" (:parent-id (first (:errors result))))))))

(deftest validate-cycle-test
  (testing "cycle in parent-child graph is detected"
    (let [chunks [{:id "a" :summary "A" :content "Test"
                   :parent-id "b" :aspects #{}}
                  {:id "b" :summary "B" :content "Test"
                   :parent-id "a" :aspects #{}}]
          result (model/validate-project chunks)]
      (is (not (:ok? result)))
      (is (some #(= :cycle (:type %)) (:errors result))))))

(deftest validate-self-cycle-test
  (testing "self-referencing parent-id is detected as cycle"
    (let [chunks [{:id "a" :summary "A" :content "Test"
                   :parent-id "a" :aspects #{}}]
          result (model/validate-project chunks)]
      (is (not (:ok? result)))
      (is (some #(= :cycle (:type %)) (:errors result))))))

(deftest validate-dangling-aspect-test
  (testing "aspect referencing non-existent chunk is detected"
    (let [chunks [{:id "cap1" :summary "Capitolo 1" :content "Test"
                   :parent-id nil :aspects #{"pers-999"}}]
          result (model/validate-project chunks)]
      (is (not (:ok? result)))
      (is (= 1 (count (:errors result))))
      (is (= :dangling-aspect (:type (first (:errors result)))))
      (is (= "cap1" (:id (first (:errors result)))))
      (is (= "pers-999" (:aspect-id (first (:errors result))))))))

(deftest validate-multiple-errors-test
  (testing "multiple errors are all reported"
    (let [chunks [{:id "cap1" :summary "Capitolo 1" :content "Test"
                   :parent-id nil :aspects #{"nonexistent"}}
                  {:id "cap1" :summary "Duplicate" :content "Test"
                   :parent-id "missing" :aspects #{}}]
          result (model/validate-project chunks)]
      (is (not (:ok? result)))
      ;; Should have: duplicate-id, missing-parent, dangling-aspect
      (is (>= (count (:errors result)) 2)))))

(deftest validate-quotes-in-summary-test
  (testing "summary containing quotes is detected as invalid"
    (let [chunks [{:id "cap1" :summary "He said \"ciao\"" :content "Test"
                   :parent-id nil :aspects #{}}]
          result (model/validate-project chunks)]
      (is (not (:ok? result)))
      (is (= 1 (count (:errors result))))
      (is (= :invalid-summary (:type (first (:errors result)))))
      (is (= "cap1" (:id (first (:errors result))))))))

;; =============================================================================
;; Proposal Tests
;; =============================================================================

(deftest proposal-insert-basic-test
  (testing "insert proposal marker in content"
    (let [content "Il gatto nero corre."
          ;; Select "gatto nero" (positions 3-13)
          result (model/insert-proposal-in-content content 3 13 "cane bianco" "mario")]
      (is (clojure.string/includes? result "[!PROPOSAL:gatto nero:Pmario:"))
      (is (clojure.string/includes? result "Il "))
      (is (clojure.string/includes? result " corre.")))))

(deftest proposal-with-whitespace-test
  (testing "proposal with leading/trailing whitespace in selection"
    (let [content "Testo con  spazi  extra."
          ;; "Testo con  spazi  extra." has double spaces
          ;; positions 9-17 selects "  spazi " (two leading spaces, one trailing)
          result (model/insert-proposal-in-content content 9 17 "parole" "luigi")]
      ;; The marker should contain the exact selected whitespace
      (is (clojure.string/includes? result "[!PROPOSAL:  spazi :Pluigi:")))))

(deftest proposal-with-regex-special-chars-test
  (testing "proposal with regex special characters in text"
    (let [content "Formula: (a+b)*c = x?"
          ;; Select "(a+b)*c = x?" - lots of regex special chars
          result (model/insert-proposal-in-content content 9 21 "y^2" "mario")]
      (is (clojure.string/includes? result "[!PROPOSAL:(a+b)*c = x?:Pmario:"))
      ;; Verify the marker is properly formed
      (is (clojure.string/includes? result "Formula: [!PROPOSAL:")))))

(deftest proposal-marker-roundtrip-test
  (testing "make-proposal-marker creates valid marker"
    (let [original-text "vecchio"
          proposed-text "nuovo"
          marker (model/make-proposal-marker original-text proposed-text "mario" 0)]
      ;; Verify marker format
      (is (clojure.string/starts-with? marker "[!PROPOSAL:vecchio:Pmario:"))
      (is (clojure.string/ends-with? marker "]"))
      ;; Verify base64 part exists
      (is (re-find #":P[^:]+:[A-Za-z0-9+/=]+\]$" marker)))))

(deftest proposal-reject-restores-original-test
  (testing "reject proposal restores original text including whitespace"
    (let [original " spazi "
          marker (model/make-proposal-marker original "senza_spazi" "luigi" 1)
          content (str "Testo" marker "qui.")
          annotation {:selected-text (clojure.string/trim original)
                      :priority {:proposal-from "luigi"}}
          result (model/reject-proposal-in-content content annotation)]
      ;; After reject, original text (with spaces) should be restored
      (is (clojure.string/includes? result "Testo spazi qui.")))))

(deftest proposal-multiple-occurrences-test
  (testing "proposal marker is unique even with repeated text"
    (let [content "casa bella casa bella casa"
          ;; Insert proposal on second "casa" (position 11-15)
          result (model/insert-proposal-in-content content 11 15 "villa" "mario")]
      ;; First "casa" should remain unchanged
      (is (clojure.string/starts-with? result "casa bella "))
      ;; Second "casa" should have the proposal
      (is (clojure.string/includes? result "[!PROPOSAL:casa:Pmario:"))
      ;; Third "casa" should remain unchanged
      (is (clojure.string/ends-with? result " bella casa")))))
