(ns tramando.server.annotations-test
  "Tests for annotation parsing with special characters.

   These tests verify that annotations (TODO, NOTE, FIX, PROPOSAL)
   correctly handle special characters like colons, newlines, Unicode, etc."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]))

;; =============================================================================
;; Helper Functions (copied from routes.clj for isolated testing)
;; =============================================================================

(defn- encode-proposal-data
  "Encode proposal data to Base64. Format: {:text proposed-text :sel 0|1}"
  [proposed-text sel]
  (let [edn-str (pr-str {:text proposed-text :sel sel})
        encoded (-> edn-str
                    (java.net.URLEncoder/encode "UTF-8")
                    (.getBytes "UTF-8"))]
    (.encodeToString (java.util.Base64/getEncoder) encoded)))

(defn- decode-proposal-data
  "Decode proposal data from Base64. Returns {:text string :sel int} or nil."
  [b64-str]
  (try
    (let [decoded-bytes (.decode (java.util.Base64/getDecoder) b64-str)
          decoded-str (java.net.URLDecoder/decode (String. decoded-bytes "UTF-8") "UTF-8")]
      (read-string decoded-str))
    (catch Exception _ nil)))

(defn- find-proposal-at-position
  "Find a PROPOSAL annotation at or near position."
  [content position]
  ;; Use non-greedy (.+?) to avoid matching across multiple proposals
  (let [pattern #"\[!PROPOSAL(?:@([^:]+))?:(.+?):P([^:]+):([^\]:]+)\]"
        matcher (re-matcher pattern content)]
    (loop [matches []]
      (if (.find matcher)
        (recur (conj matches {:start (.start matcher)
                              :end (.end matcher)
                              :text (.group matcher)
                              :author (.group matcher 1)
                              :original (.group matcher 2)
                              :user (.group matcher 3)
                              :data (decode-proposal-data (.group matcher 4))}))
        (first (filter #(<= (Math/abs (- (:start %) position)) 5) matches))))))

(defn- insert-proposal
  "Insert a PROPOSAL annotation into content."
  [content original-text proposed-text position author user]
  (let [author-part (if (and author (seq author) (not= author "local"))
                      (str "@" author)
                      "")
        proposal-marker (str "[!PROPOSAL" author-part ":" original-text ":P" user ":"
                             (encode-proposal-data proposed-text 0) "]")]
    (cond
      (and (number? position) (>= position 0))
      (let [before (subs content 0 (min position (count content)))
            after (subs content (min position (count content)))
            idx (str/index-of after original-text)]
        (if (and idx (= idx 0))
          (str before proposal-marker (subs after (count original-text)))
          (if-let [full-idx (str/index-of content original-text)]
            (str (subs content 0 full-idx)
                 proposal-marker
                 (subs content (+ full-idx (count original-text))))
            (str content "\n" proposal-marker))))
      :else
      (if-let [idx (str/index-of content original-text)]
        (str (subs content 0 idx)
             proposal-marker
             (subs content (+ idx (count original-text))))
        (str content "\n" proposal-marker)))))

;; =============================================================================
;; Standard Annotation Pattern Tests
;; =============================================================================

(def annotation-pattern #"\[!(TODO|NOTE|FIX)(?:@([^:]+))?:([^:]*):([^:]*):([^\]]*)\]")

(deftest standard-annotation-parsing-test
  (testing "Parse simple TODO annotation"
    (let [content "[!TODO:fix this bug::remember to check edge cases]"
          match (re-find annotation-pattern content)]
      (is (some? match))
      (is (= "TODO" (nth match 1)))
      (is (= "fix this bug" (nth match 3)))
      (is (= "remember to check edge cases" (nth match 5)))))

  (testing "Parse NOTE with author"
    (let [content "[!NOTE@alice:important point:5:needs review]"
          match (re-find annotation-pattern content)]
      (is (some? match))
      (is (= "NOTE" (nth match 1)))
      (is (= "alice" (nth match 2)))
      (is (= "important point" (nth match 3)))
      (is (= "5" (nth match 4)))))

  (testing "Parse FIX with empty fields"
    (let [content "[!FIX:broken code::]"
          match (re-find annotation-pattern content)]
      (is (some? match))
      (is (= "FIX" (nth match 1)))
      (is (= "broken code" (nth match 3)))
      (is (= "" (nth match 4)))
      (is (= "" (nth match 5))))))

;; =============================================================================
;; PROPOSAL Pattern Tests - Special Characters
;; =============================================================================

(def proposal-pattern #"\[!PROPOSAL(?:@([^:]+))?:(.+):P([^:]+):([^\]:]+)\]")

(deftest proposal-with-colons-test
  (testing "PROPOSAL with colons in original text"
    (let [original "La vita col falegname: fame, maltrattamenti, ma familiarità."
          proposed "La vita col falegname:\n- fame\n- maltrattamenti\n- ma familiarità."
          content (insert-proposal (str "Test: " original " Fine.") original proposed 6 "alice" "alice")
          proposal (find-proposal-at-position content 6)]
      (is (some? proposal) "Should find proposal")
      (is (= original (:original proposal)) "Original text should match")
      (is (= proposed (get-in proposal [:data :text])) "Proposed text should match")))

  (testing "PROPOSAL with multiple colons"
    (let [original "Orario: 10:30 - Luogo: Roma"
          proposed "Orario: 11:00 - Luogo: Milano"
          content (insert-proposal original original proposed 0 "bob" "bob")
          proposal (find-proposal-at-position content 0)]
      (is (some? proposal))
      (is (= original (:original proposal)))
      (is (= proposed (get-in proposal [:data :text])))))

  (testing "PROPOSAL with colon at start"
    (let [original ":inizio con due punti"
          proposed ": inizio con due punti e spazio"
          content (insert-proposal (str "Testo " original " fine") original proposed 6 "user" "user")
          proposal (find-proposal-at-position content 6)]
      (is (some? proposal))
      (is (= original (:original proposal)))))

  (testing "PROPOSAL with consecutive colons"
    (let [original "test::doppi::punti"
          proposed "test: doppi: punti"
          content (insert-proposal original original proposed 0 "user" "user")
          proposal (find-proposal-at-position content 0)]
      (is (some? proposal))
      (is (= original (:original proposal))))))

(deftest proposal-with-unicode-test
  (testing "PROPOSAL with emoji"
    (let [original "Questo è un test 😀 con emoji 🎉"
          proposed "Questo è un test 🎊 con emoji diversi 🚀"
          content (insert-proposal original original proposed 0 "user" "user")
          proposal (find-proposal-at-position content 0)]
      (is (some? proposal))
      (is (= original (:original proposal)))
      (is (= proposed (get-in proposal [:data :text])))))

  (testing "PROPOSAL with accented characters"
    (let [original "Città caffè perché così"
          proposed "Città, caffè, perché, così"
          content (insert-proposal original original proposed 0 "user" "user")
          proposal (find-proposal-at-position content 0)]
      (is (some? proposal))
      (is (= original (:original proposal)))))

  (testing "PROPOSAL with Chinese characters"
    (let [original "你好世界"
          proposed "你好，世界！"
          content (insert-proposal original original proposed 0 "user" "user")
          proposal (find-proposal-at-position content 0)]
      (is (some? proposal))
      (is (= original (:original proposal)))))

  (testing "PROPOSAL with Arabic"
    (let [original "مرحبا بالعالم"
          proposed "مرحبا بالعالم!"
          content (insert-proposal original original proposed 0 "user" "user")
          proposal (find-proposal-at-position content 0)]
      (is (some? proposal))
      (is (= original (:original proposal))))))

(deftest proposal-with-special-regex-chars-test
  (testing "PROPOSAL with brackets"
    (let [original "Array[0] e Map{key}"
          proposed "[Array[0]] e [Map{key}]"
          content (insert-proposal original original proposed 0 "user" "user")
          proposal (find-proposal-at-position content 0)]
      (is (some? proposal))
      (is (= original (:original proposal)))))

  (testing "PROPOSAL with regex special characters"
    (let [original "Prezzo: $100.00 (50% off)"
          proposed "Prezzo: €85,00 (50% di sconto)"
          content (insert-proposal original original proposed 0 "user" "user")
          proposal (find-proposal-at-position content 0)]
      (is (some? proposal))
      (is (= original (:original proposal)))))

  (testing "PROPOSAL with asterisks and underscores"
    (let [original "*bold* _italic_ **strong**"
          proposed "**grassetto** *corsivo* __sottolineato__"
          content (insert-proposal original original proposed 0 "user" "user")
          proposal (find-proposal-at-position content 0)]
      (is (some? proposal))
      (is (= original (:original proposal)))))

  (testing "PROPOSAL with pipes and backslashes"
    (let [original "path\\to\\file | grep pattern"
          proposed "path/to/file | grep -E 'pattern'"
          content (insert-proposal original original proposed 0 "user" "user")
          proposal (find-proposal-at-position content 0)]
      (is (some? proposal))
      (is (= original (:original proposal))))))

(deftest proposal-with-whitespace-test
  (testing "PROPOSAL with newlines in proposed text"
    (let [original "Una riga singola"
          proposed "Prima riga\nSeconda riga\nTerza riga"
          content (insert-proposal original original proposed 0 "user" "user")
          proposal (find-proposal-at-position content 0)]
      (is (some? proposal))
      (is (= original (:original proposal)))
      (is (= proposed (get-in proposal [:data :text])))))

  (testing "PROPOSAL with tabs"
    (let [original "Testo senza tab"
          proposed "Testo\tcon\ttab"
          content (insert-proposal original original proposed 0 "user" "user")
          proposal (find-proposal-at-position content 0)]
      (is (some? proposal))
      (is (= proposed (get-in proposal [:data :text])))))

  (testing "PROPOSAL with leading/trailing whitespace"
    (let [original "  spazi intorno  "
          proposed "senza spazi intorno"
          content (insert-proposal (str "Testo" original "fine") original proposed 5 "user" "user")
          proposal (find-proposal-at-position content 5)]
      (is (some? proposal))
      (is (= original (:original proposal))))))

(deftest proposal-edge-cases-test
  (testing "PROPOSAL with very long text"
    (let [original (apply str (repeat 1000 "a"))
          proposed (apply str (repeat 1000 "b"))
          content (insert-proposal original original proposed 0 "user" "user")
          proposal (find-proposal-at-position content 0)]
      (is (some? proposal))
      (is (= 1000 (count (:original proposal))))
      (is (= 1000 (count (get-in proposal [:data :text]))))))

  (testing "PROPOSAL with empty original (should not match)"
    (let [content "[!PROPOSAL@user::Puser:dGVzdA==]"]
      ;; Empty original text shouldn't really be valid, but let's see what happens
      (let [match (re-find proposal-pattern content)]
        ;; The (.+) requires at least one character
        (is (nil? match) "Empty original should not match"))))

  (testing "PROPOSAL with single character"
    (let [original "x"
          proposed "y"
          content (insert-proposal (str "a" original "b") original proposed 1 "user" "user")
          proposal (find-proposal-at-position content 1)]
      (is (some? proposal))
      (is (= original (:original proposal))))))

;; =============================================================================
;; Base64 Encoding/Decoding Tests
;; =============================================================================

(deftest base64-roundtrip-test
  (testing "Base64 roundtrip with simple text"
    (let [text "Simple text"
          encoded (encode-proposal-data text 0)
          decoded (decode-proposal-data encoded)]
      (is (= text (:text decoded)))
      (is (= 0 (:sel decoded)))))

  (testing "Base64 roundtrip with special characters"
    (let [text "Text with: colons, émojis 🎉, and \"quotes\""
          encoded (encode-proposal-data text 1)
          decoded (decode-proposal-data encoded)]
      (is (= text (:text decoded)))
      (is (= 1 (:sel decoded)))))

  (testing "Base64 roundtrip with newlines"
    (let [text "Line 1\nLine 2\nLine 3"
          encoded (encode-proposal-data text 0)
          decoded (decode-proposal-data encoded)]
      (is (= text (:text decoded)))))

  (testing "Base64 roundtrip with Unicode"
    (let [text "日本語 中文 العربية"
          encoded (encode-proposal-data text 0)
          decoded (decode-proposal-data encoded)]
      (is (= text (:text decoded))))))

;; =============================================================================
;; Multiple Proposals Test
;; =============================================================================

(deftest multiple-proposals-test
  (testing "Find correct proposal among multiple"
    (let [content (str "[!PROPOSAL@a:first:Pa:dGVzdDE=] middle "
                       "[!PROPOSAL@b:second: with colon:Pb:dGVzdDI=] end")]
      ;; First proposal at position 0
      (let [p1 (find-proposal-at-position content 0)]
        (is (some? p1))
        (is (= "first" (:original p1)))
        (is (= "a" (:author p1))))
      ;; Second proposal - find its position
      (let [pos2 (str/index-of content "[!PROPOSAL@b")
            p2 (find-proposal-at-position content pos2)]
        (is (some? p2))
        (is (= "second: with colon" (:original p2)))
        (is (= "b" (:author p2)))))))

;; =============================================================================
;; Real-world Bug Reproduction Test (Project 23)
;; =============================================================================

(deftest kashtanka-proposal-test
  (testing "PROPOSAL with the exact text from project 23 bug report"
    (let [original-text "La vita col falegname: fame, maltrattamenti, ma familiarità. La vita con l'addestratore: cibo, cure, ma estraneità. Kashtanka sceglie l'appartenenza."
          proposed-text "La vita col falegname: fame, maltrattamenti, ma familiarità.\nLa vita con l'addestratore: cibo, cure, ma estraneità.\nKashtanka sceglie l'appartenenza."
          ;; Insert proposal into some content
          before-text "[C:appartenenza\"Appartenenza vs. comfort\"]\n"
          content-with-original (str before-text original-text)
          content-with-proposal (insert-proposal content-with-original original-text proposed-text
                                                 (count before-text) nil "local")]

      ;; Verify the proposal was inserted
      (is (str/includes? content-with-proposal "[!PROPOSAL:"))
      ;; The original text IS inside the proposal marker (that's the format)
      ;; but should NOT appear twice (once in marker, once outside)
      (is (= 1 (count (re-seq (re-pattern (java.util.regex.Pattern/quote original-text)) content-with-proposal)))
          "Original text should appear exactly once (inside the proposal marker)")

      ;; Find the proposal
      (let [proposal (find-proposal-at-position content-with-proposal (count before-text))]
        (is (some? proposal) "Should find proposal at position")
        (is (= original-text (:original proposal)) "Original text should match exactly")
        (is (= "local" (:user proposal)) "User should be 'local'")
        (is (some? (:data proposal)) "Should have decoded data")
        (is (= proposed-text (get-in proposal [:data :text])) "Proposed text should match")
        (is (= 0 (get-in proposal [:data :sel])) "Selection should be 0 (original)")))))

(deftest kashtanka-accept-reject-test
  (testing "Accept and reject proposal with colons in text"
    (let [original-text "La vita col falegname: fame, maltrattamenti, ma familiarità. La vita con l'addestratore: cibo, cure, ma estraneità. Kashtanka sceglie l'appartenenza."
          proposed-text "La vita col falegname: fame, maltrattamenti, ma familiarità.\nLa vita con l'addestratore: cibo, cure, ma estraneità.\nKashtanka sceglie l'appartenenza."
          before-text "Testo prima. "
          after-text " Testo dopo."
          content-with-original (str before-text original-text after-text)
          content-with-proposal (insert-proposal content-with-original original-text proposed-text
                                                 (count before-text) nil "local")
          position (count before-text)]

      ;; Test accept
      (testing "Accept proposal"
        (let [proposal (find-proposal-at-position content-with-proposal position)]
          (is (some? proposal))
          ;; Simulate accept: replace annotation with proposed text
          (let [accepted-content (str (subs content-with-proposal 0 (:start proposal))
                                      proposed-text
                                      (subs content-with-proposal (:end proposal)))]
            (is (str/includes? accepted-content proposed-text))
            (is (not (str/includes? accepted-content "[!PROPOSAL"))))))

      ;; Test reject
      (testing "Reject proposal"
        (let [proposal (find-proposal-at-position content-with-proposal position)]
          (is (some? proposal))
          ;; Simulate reject: replace annotation with original text
          (let [rejected-content (str (subs content-with-proposal 0 (:start proposal))
                                      (:original proposal)
                                      (subs content-with-proposal (:end proposal)))]
            (is (str/includes? rejected-content original-text))
            (is (not (str/includes? rejected-content "[!PROPOSAL")))))))))

(deftest proposal-roundtrip-with-multiple-colons-test
  (testing "Full roundtrip: insert, find, accept/reject with multiple colons"
    (let [test-cases [;; Original Kashtanka case
                      {:original "La vita col falegname: fame, maltrattamenti, ma familiarità."
                       :proposed "La vita col falegname:\n- fame\n- maltrattamenti\n- ma familiarità."}
                      ;; Multiple colons in sequence
                      {:original "Orario: 10:30:45 - Data: 2024:01:15"
                       :proposed "Orario: 11:00:00 - Data: 2024:01:16"}
                      ;; Colon at start and end
                      {:original ":inizio:mezzo:fine:"
                       :proposed ": inizio : mezzo : fine :"}
                      ;; URL-like content
                      {:original "Vedi https://example.com:8080/path per dettagli"
                       :proposed "Vedi http://localhost:3000/test per test"}]]
      (doseq [{:keys [original proposed]} test-cases]
        (let [content (str "Prima " original " Dopo")
              position 6  ;; After "Prima "
              with-proposal (insert-proposal content original proposed position nil "testuser")]
          ;; Find proposal
          (let [found (find-proposal-at-position with-proposal position)]
            (is (some? found) (str "Should find proposal for: " (subs original 0 (min 30 (count original)))))
            (when found
              (is (= original (:original found)) "Original should match")
              (is (= proposed (get-in found [:data :text])) "Proposed should match"))))))))
