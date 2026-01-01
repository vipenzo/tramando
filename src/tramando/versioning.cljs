(ns tramando.versioning
  "Conflict detection, automatic backup, and manual versioning system"
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [tramando.settings :as settings]
            [tramando.i18n :refer [t]]
            ["@tauri-apps/plugin-fs" :as fs]
            ["@tauri-apps/api/path" :as path]
            ["@tauri-apps/api/window" :refer [getCurrentWindow]]))

;; =============================================================================
;; State
;; =============================================================================

(defonce file-info
  (r/atom nil))  ;; {:path "..." :hash "..." :mtime 123456}

(defonce dirty?
  (r/atom false))

;; Dialog states
(defonce conflict-dialog (r/atom nil))
(defonce file-changed-dialog (r/atom nil))
(defonce save-version-dialog (r/atom nil))
(defonce version-list-dialog (r/atom nil))
(defonce restore-confirm-dialog (r/atom nil))
(defonce delete-version-dialog (r/atom nil))

;; Toast notifications
(defonce toast-message (r/atom nil))
(defonce toast-timer (r/atom nil))

;; =============================================================================
;; Toast Notifications
;; =============================================================================

(defn show-toast! [message]
  (when @toast-timer
    (js/clearTimeout @toast-timer))
  (reset! toast-message message)
  (reset! toast-timer
          (js/setTimeout #(reset! toast-message nil) 3000)))

;; =============================================================================
;; Hash Calculation
;; =============================================================================

(defn calculate-hash
  "Calculate SHA-256 hash of content. Returns a promise."
  [content]
  (let [encoder (js/TextEncoder.)
        data (.encode encoder content)]
    (-> (js/crypto.subtle.digest "SHA-256" data)
        (.then (fn [buffer]
                 (let [arr (js/Uint8Array. buffer)]
                   (->> arr
                        (map #(.toString % 16))
                        (map #(if (< (count %) 2) (str "0" %) %))
                        (apply str))))))))

;; =============================================================================
;; File Operations
;; =============================================================================

(defn get-parent-dir
  "Get parent directory from a file path"
  [path]
  (let [parts (str/split path #"/")]
    (str/join "/" (butlast parts))))

(defn get-filename
  "Get filename from a path"
  [path]
  (last (str/split path #"/")))

(defn get-filename-without-ext
  "Get filename without extension"
  [path]
  (let [filename (get-filename path)]
    (str/replace filename #"\.[^.]+$" "")))

(defn file-exists?
  "Check if file exists. Returns a promise."
  [path]
  (-> (fs/exists path)
      (.catch (fn [_] false))))

(defn read-file
  "Read file content. Returns a promise."
  [path]
  (fs/readTextFile path))

(defn write-file
  "Write content to file. Returns a promise."
  [path content]
  (fs/writeTextFile path content))

(defn copy-file
  "Copy file from src to dest. Returns a promise."
  [src dest]
  (-> (read-file src)
      (.then #(write-file dest %))))

(defn ensure-dir
  "Create directory if it doesn't exist. Returns a promise."
  [path]
  (-> (fs/exists path)
      (.then (fn [exists?]
               (when-not exists?
                 (fs/mkdir path #js {:recursive true}))))))

(defn list-dir
  "List files in directory. Returns a promise with array of entries."
  [path]
  (-> (fs/readDir path)
      (.catch (fn [_] []))))

(defn remove-file
  "Delete a file. Returns a promise."
  [path]
  (fs/remove path))

(defn get-file-stat
  "Get file metadata (mtime). Returns a promise."
  [path]
  (-> (fs/stat path)
      (.then (fn [stat]
               {:mtime (.-mtime stat)}))
      (.catch (fn [_] nil))))

;; =============================================================================
;; File Info Management
;; =============================================================================

(defn update-file-info!
  "Update file info after loading or saving"
  [path content]
  (-> (calculate-hash content)
      (.then (fn [hash]
               (-> (get-file-stat path)
                   (.then (fn [stat]
                            (reset! file-info
                                    {:path path
                                     :hash hash
                                     :mtime (:mtime stat)}))))))))

(defn clear-file-info!
  "Clear file info (for new files)"
  []
  (reset! file-info nil))

(defn mark-dirty!
  "Mark the project as having unsaved changes"
  []
  (reset! dirty? true))

(defn mark-clean!
  "Mark the project as saved"
  []
  (reset! dirty? false))

;; =============================================================================
;; Conflict Detection
;; =============================================================================

(defn check-conflict
  "Check if file has been modified externally. Returns a promise with result."
  []
  (if-let [{:keys [path hash mtime]} @file-info]
    (-> (file-exists? path)
        (.then (fn [exists?]
                 (if-not exists?
                   {:conflict? false :file-deleted? true}
                   (-> (read-file path)
                       (.then (fn [content]
                                (-> (calculate-hash content)
                                    (.then (fn [current-hash]
                                             (-> (get-file-stat path)
                                                 (.then (fn [stat]
                                                          {:conflict? (or (not= hash current-hash)
                                                                          (not= mtime (:mtime stat)))
                                                           :current-hash current-hash
                                                           :current-mtime (:mtime stat)
                                                           :disk-content content})))))))))))))
    ;; No file info = new file, no conflict
    (js/Promise.resolve {:conflict? false :new-file? true})))

;; =============================================================================
;; Backup Operations
;; =============================================================================

(defn get-backup-path
  "Get backup file path for a given file"
  [path]
  (str path ".backup"))

(defn create-backup!
  "Create a backup of the current file. Returns a promise."
  [path]
  (let [backup-path (get-backup-path path)]
    (-> (file-exists? path)
        (.then (fn [exists?]
                 (when exists?
                   (copy-file path backup-path)))))))

(defn restore-backup!
  "Restore from backup file. Returns a promise."
  [path on-reload!]
  (let [backup-path (get-backup-path path)]
    (-> (file-exists? backup-path)
        (.then (fn [exists?]
                 (if exists?
                   (-> (copy-file backup-path path)
                       (.then (fn []
                                (when on-reload!
                                  (on-reload!))
                                (show-toast! (t :backup-restored)))))
                   (show-toast! (t :no-backup-available))))))))

(defn has-backup?
  "Check if backup exists. Returns a promise."
  [path]
  (file-exists? (get-backup-path path)))

;; =============================================================================
;; Version Management
;; =============================================================================

(defn path-to-folder-name
  "Convert a file path to a safe folder name"
  [file-path]
  ;; Use base64 encoding truncated to create a unique folder name
  (let [encoded (js/btoa file-path)]
    (-> encoded
        (str/replace #"[/+=]" "-")
        (subs 0 (min 40 (count encoded))))))

(defn get-versions-dir
  "Get versions directory for a file (in app data directory). Returns a promise."
  [file-path]
  (-> (path/appDataDir)
      (.then (fn [app-dir]
               (let [folder-name (path-to-folder-name file-path)]
                 (str app-dir "versions/" folder-name "/"))))))

(defn slugify
  "Convert text to URL-safe slug"
  [text]
  (when (and text (not (empty? (str/trim text))))
    (-> text
        str/lower-case
        (str/replace #"[àáâãäå]" "a")
        (str/replace #"[èéêë]" "e")
        (str/replace #"[ìíîï]" "i")
        (str/replace #"[òóôõö]" "o")
        (str/replace #"[ùúûü]" "u")
        (str/replace #"[^a-z0-9]+" "-")
        (str/replace #"^-|-$" "")
        (subs 0 (min 30 (count text))))))

(defn format-timestamp
  "Format current date/time as yyyy-MM-dd_HH-mm"
  []
  (let [now (js/Date.)
        pad #(if (< % 10) (str "0" %) (str %))]
    (str (.getFullYear now) "-"
         (pad (inc (.getMonth now))) "-"
         (pad (.getDate now)) "_"
         (pad (.getHours now)) "-"
         (pad (.getMinutes now)))))

(defn parse-version-filename
  "Parse version filename to extract info"
  [filename]
  (let [base (str/replace filename #"\.trmd$" "")
        parts (str/split base #"_")
        ;; Format: yyyy-MM-dd_HH-mm or yyyy-MM-dd_HH-mm_description
        date-part (first parts)
        time-part (second parts)
        desc-parts (drop 2 parts)
        description (when (seq desc-parts)
                      (str/replace (str/join " " desc-parts) "-" " "))]
    {:filename filename
     :date date-part
     :time (str/replace (or time-part "") "-" ":")
     :description description
     :sort-key (str date-part "_" time-part)}))

(defn format-version-date
  "Format version date for display"
  [date-str time-str]
  (when date-str
    (let [[year month day] (str/split date-str #"-")
          months ["Gen" "Feb" "Mar" "Apr" "Mag" "Giu" "Lug" "Ago" "Set" "Ott" "Nov" "Dic"]
          month-name (get months (dec (js/parseInt month 10)) month)]
      (str (js/parseInt day 10) " " month-name " " year ", " time-str))))

(defn list-versions
  "List all saved versions. Returns a promise."
  [path]
  (-> (get-versions-dir path)
      (.then (fn [versions-dir]
               (-> (file-exists? versions-dir)
                   (.then (fn [exists?]
                            (if exists?
                              (-> (list-dir versions-dir)
                                  (.then (fn [entries]
                                           (->> entries
                                                (filter #(str/ends-with? (.-name %) ".trmd"))
                                                (map #(parse-version-filename (.-name %)))
                                                (sort-by :sort-key >)))))
                              []))))))))

(defn save-version!
  "Save current file as a version. Returns a promise."
  [path description]
  (let [timestamp (format-timestamp)
        slug (slugify description)
        version-filename (if slug
                           (str timestamp "_" slug ".trmd")
                           (str timestamp ".trmd"))]
    (-> (get-versions-dir path)
        (.then (fn [versions-dir]
                 (let [version-path (str versions-dir version-filename)]
                   (-> (ensure-dir versions-dir)
                       (.then #(copy-file path version-path))
                       (.then #(show-toast! (t :version-saved))))))))))

(defn restore-version!
  "Restore a specific version. Returns a promise."
  [path version-filename on-reload!]
  (-> (get-versions-dir path)
      (.then (fn [versions-dir]
               (let [version-path (str versions-dir version-filename)]
                 (-> (create-backup! path)
                     (.then #(copy-file version-path path))
                     (.then (fn []
                              (when on-reload!
                                (on-reload!))
                              (show-toast! (t :version-restored))))))))))

(defn delete-version!
  "Delete a specific version. Returns a promise."
  [path version-filename]
  (-> (get-versions-dir path)
      (.then (fn [versions-dir]
               (let [version-path (str versions-dir version-filename)]
                 (remove-file version-path))))))

(defn open-version-copy!
  "Open a version as a new unsaved document. Returns promise with content."
  [path version-filename]
  (-> (get-versions-dir path)
      (.then (fn [versions-dir]
               (let [version-path (str versions-dir version-filename)]
                 (read-file version-path))))))

;; =============================================================================
;; Window Focus Handler
;; =============================================================================

(defn setup-focus-listener!
  "Setup window focus listener to detect external file changes"
  [on-file-changed!]
  ;; Only set up listener if running in Tauri (not in browser dev mode)
  (when (exists? js/window.__TAURI__)
    (let [window (getCurrentWindow)]
      (.listen window "tauri://focus"
               (fn [_]
                 (when @file-info
                   (-> (check-conflict)
                       (.then (fn [{:keys [conflict? file-deleted?]}]
                                (when (and conflict? (not file-deleted?))
                                  (on-file-changed!)))))))))))

;; =============================================================================
;; UI Components - Dialogs
;; =============================================================================

(defn conflict-dialog-component
  "Dialog shown when file was modified externally during save"
  [{:keys [on-overwrite on-save-as on-reload on-cancel]}]
  (let [colors {:bg (settings/get-color :background)
                :text (settings/get-color :text)
                :muted (settings/get-color :text-muted)
                :border (settings/get-color :border)
                :accent (settings/get-color :accent)}]
    [:div.conflict-dialog-backdrop
     {:style {:position "fixed"
              :inset 0
              :background "rgba(0,0,0,0.5)"
              :z-index 1000
              :display "flex"
              :align-items "center"
              :justify-content "center"}
      :on-mouse-down on-cancel}
     [:div.conflict-dialog
      {:style {:background (:bg colors)
               :border-radius "12px"
               :padding "24px"
               :max-width "500px"
               :box-shadow "0 8px 32px rgba(0,0,0,0.3)"}
       :on-mouse-down #(.stopPropagation %)}
      [:h3 {:style {:margin "0 0 16px 0"
                    :color (:text colors)
                    :display "flex"
                    :align-items "center"
                    :gap "8px"}}
       [:span {:style {:font-size "1.2rem"}} "⚠️"]
       (t :file-modified-conflict)]
      [:p {:style {:color (:muted colors)
                   :margin "0 0 20px 0"
                   :line-height "1.5"}}
       (t :file-modified-message)]
      [:div {:style {:display "flex"
                     :gap "8px"
                     :flex-wrap "wrap"}}
       [:button {:style {:background "#ff6b6b"
                         :color "white"
                         :border "none"
                         :padding "8px 16px"
                         :border-radius "6px"
                         :cursor "pointer"}
                 :on-click on-overwrite}
        (t :overwrite)]
       [:button {:style {:background (:accent colors)
                         :color "white"
                         :border "none"
                         :padding "8px 16px"
                         :border-radius "6px"
                         :cursor "pointer"}
                 :on-click on-save-as}
        (t :save-as)]
       [:button {:style {:background "transparent"
                         :color (:text colors)
                         :border (str "1px solid " (:border colors))
                         :padding "8px 16px"
                         :border-radius "6px"
                         :cursor "pointer"}
                 :on-click on-reload}
        (t :reload-from-disk)]]
      [:div {:style {:margin-top "16px"
                     :font-size "0.85rem"
                     :color (:muted colors)}}
       [:div {:style {:margin-bottom "4px"}}
        "• " (t :overwrite) ": " (t :overwrite-description)]
       [:div {:style {:margin-bottom "4px"}}
        "• " (t :save-as) ": " (t :save-as-description)]
       [:div
        "• " (t :reload-from-disk) ": " (t :reload-description)]]]]))

(defn file-changed-dialog-component
  "Soft dialog shown when file changed on disk (on window focus)"
  [{:keys [on-reload on-ignore]}]
  (let [colors {:bg (settings/get-color :background)
                :text (settings/get-color :text)
                :muted (settings/get-color :text-muted)
                :border (settings/get-color :border)
                :accent (settings/get-color :accent)}]
    [:div.file-changed-backdrop
     {:style {:position "fixed"
              :inset 0
              :background "rgba(0,0,0,0.4)"
              :z-index 1000
              :display "flex"
              :align-items "center"
              :justify-content "center"}
      :on-mouse-down on-ignore}
     [:div.file-changed-dialog
      {:style {:background (:bg colors)
               :border-radius "12px"
               :padding "24px"
               :max-width "400px"
               :box-shadow "0 8px 32px rgba(0,0,0,0.3)"}
       :on-mouse-down #(.stopPropagation %)}
      [:h3 {:style {:margin "0 0 12px 0"
                    :color (:text colors)}}
       (t :file-modified-conflict)]
      [:p {:style {:color (:muted colors)
                   :margin "0 0 20px 0"}}
       (t :file-changed-on-disk)]
      [:div {:style {:display "flex"
                     :gap "8px"
                     :justify-content "flex-end"}}
       [:button {:style {:background "transparent"
                         :color (:muted colors)
                         :border (str "1px solid " (:border colors))
                         :padding "8px 16px"
                         :border-radius "6px"
                         :cursor "pointer"}
                 :on-click on-ignore}
        (t :ignore)]
       [:button {:style {:background (:accent colors)
                         :color "white"
                         :border "none"
                         :padding "8px 16px"
                         :border-radius "6px"
                         :cursor "pointer"}
                 :on-click on-reload}
        (t :reload)]]]]))

(defn save-version-dialog-component
  "Dialog for saving a new version"
  [{:keys [on-save on-cancel]}]
  (let [description (r/atom "")
        colors {:bg (settings/get-color :background)
                :text (settings/get-color :text)
                :muted (settings/get-color :text-muted)
                :border (settings/get-color :border)
                :accent (settings/get-color :accent)
                :editor-bg (settings/get-color :editor-bg)}]
    (fn [{:keys [on-save on-cancel]}]
      [:div.save-version-backdrop
       {:style {:position "fixed"
                :inset 0
                :background "rgba(0,0,0,0.4)"
                :z-index 1000
                :display "flex"
                :align-items "center"
                :justify-content "center"}
        :on-mouse-down on-cancel}
       [:div.save-version-dialog
        {:style {:background (:bg colors)
                 :border-radius "12px"
                 :padding "24px"
                 :width "400px"
                 :box-shadow "0 8px 32px rgba(0,0,0,0.3)"}
         :on-mouse-down #(.stopPropagation %)}
        [:h3 {:style {:margin "0 0 16px 0"
                      :color (:text colors)}}
         (t :save-version)]
        [:div {:style {:margin-bottom "16px"}}
         [:label {:style {:display "block"
                          :margin-bottom "8px"
                          :color (:muted colors)
                          :font-size "0.9rem"}}
          (t :version-description)]
         [:input {:type "text"
                  :value @description
                  :placeholder (t :version-description)
                  :auto-focus true
                  :style {:width "100%"
                          :padding "10px 12px"
                          :border (str "1px solid " (:border colors))
                          :border-radius "6px"
                          :background (:editor-bg colors)
                          :color (:text colors)
                          :font-size "14px"
                          :outline "none"
                          :box-sizing "border-box"}
                  :on-change #(reset! description (-> % .-target .-value))
                  :on-key-down (fn [e]
                                 (case (.-key e)
                                   "Enter" (on-save @description)
                                   "Escape" (on-cancel)
                                   nil))}]]
        [:div {:style {:display "flex"
                       :gap "8px"
                       :justify-content "flex-end"}}
         [:button {:style {:background "transparent"
                           :color (:muted colors)
                           :border (str "1px solid " (:border colors))
                           :padding "8px 16px"
                           :border-radius "6px"
                           :cursor "pointer"}
                   :on-click on-cancel}
          (t :cancel)]
         [:button {:style {:background (:accent colors)
                           :color "white"
                           :border "none"
                           :padding "8px 16px"
                           :border-radius "6px"
                           :cursor "pointer"}
                   :on-click #(on-save @description)}
          (t :save-version)]]]])))

(defn version-list-dialog-component
  "Dialog showing list of saved versions"
  [{:keys [versions on-open-copy on-restore on-delete on-close filename]}]
  (let [colors {:bg (settings/get-color :background)
                :text (settings/get-color :text)
                :muted (settings/get-color :text-muted)
                :border (settings/get-color :border)
                :accent (settings/get-color :accent)
                :sidebar (settings/get-color :sidebar)}]
    [:div.version-list-backdrop
     {:style {:position "fixed"
              :inset 0
              :background "rgba(0,0,0,0.4)"
              :z-index 1000
              :display "flex"
              :align-items "center"
              :justify-content "center"}
      :on-mouse-down on-close}
     [:div.version-list-dialog
      {:style {:background (:bg colors)
               :border-radius "12px"
               :padding "24px"
               :width "500px"
               :max-height "70vh"
               :display "flex"
               :flex-direction "column"
               :box-shadow "0 8px 32px rgba(0,0,0,0.3)"}
       :on-mouse-down #(.stopPropagation %)}
      [:h3 {:style {:margin "0 0 8px 0"
                    :color (:text colors)}}
       (t :saved-versions)]
      [:div {:style {:color (:muted colors)
                     :font-size "0.9rem"
                     :margin-bottom "16px"
                     :display "flex"
                     :align-items "center"
                     :gap "6px"}}
       [:span "📄"]
       [:span filename]]
      [:div {:style {:flex 1
                     :overflow-y "auto"
                     :border (str "1px solid " (:border colors))
                     :border-radius "8px"
                     :min-height "200px"
                     :max-height "400px"}}
       (if (empty? versions)
         [:div {:style {:padding "40px 20px"
                        :text-align "center"
                        :color (:muted colors)}}
          (t :no-versions)]
         (doall
          (for [{:keys [filename date time description]} versions]
            ^{:key filename}
            [:div {:style {:padding "12px 16px"
                           :border-bottom (str "1px solid " (:border colors))
                           :display "flex"
                           :align-items "center"
                           :gap "12px"}}
             [:div {:style {:flex 1}}
              [:div {:style {:color (:text colors)
                             :font-weight "500"
                             :display "flex"
                             :align-items "center"
                             :gap "6px"}}
               [:span "📸"]
               [:span (format-version-date date time)]]
              [:div {:style {:color (:muted colors)
                             :font-size "0.85rem"
                             :margin-top "4px"
                             :font-style (if description "normal" "italic")}}
               (if description
                 (str "\"" description "\"")
                 (t :no-description))]]
             [:div {:style {:display "flex"
                            :gap "6px"}}
              [:button {:style {:background "transparent"
                                :color (:accent colors)
                                :border (str "1px solid " (:accent colors))
                                :padding "4px 10px"
                                :border-radius "4px"
                                :cursor "pointer"
                                :font-size "0.8rem"}
                        :on-click #(on-open-copy filename)}
               (t :open-copy)]
              [:button {:style {:background (:accent colors)
                                :color "white"
                                :border "none"
                                :padding "4px 10px"
                                :border-radius "4px"
                                :cursor "pointer"
                                :font-size "0.8rem"}
                        :on-click #(on-restore filename)}
               (t :restore)]
              [:button {:style {:background "transparent"
                                :color "#ff6b6b"
                                :border "none"
                                :padding "4px 8px"
                                :border-radius "4px"
                                :cursor "pointer"
                                :font-size "0.9rem"}
                        :title (t :delete-version)
                        :on-click #(on-delete filename)}
               "🗑️"]]])))]
      [:div {:style {:margin-top "16px"
                     :display "flex"
                     :justify-content "flex-end"}}
       [:button {:style {:background "transparent"
                         :color (:muted colors)
                         :border (str "1px solid " (:border colors))
                         :padding "8px 16px"
                         :border-radius "6px"
                         :cursor "pointer"}
                 :on-click on-close}
        (t :close)]]]]))

(defn restore-confirm-dialog-component
  "Confirmation dialog for restoring a version"
  [{:keys [version-info on-confirm on-cancel]}]
  (let [colors {:bg (settings/get-color :background)
                :text (settings/get-color :text)
                :muted (settings/get-color :text-muted)
                :border (settings/get-color :border)
                :accent (settings/get-color :accent)}]
    [:div.restore-confirm-backdrop
     {:style {:position "fixed"
              :inset 0
              :background "rgba(0,0,0,0.4)"
              :z-index 1001
              :display "flex"
              :align-items "center"
              :justify-content "center"}
      :on-mouse-down on-cancel}
     [:div.restore-confirm-dialog
      {:style {:background (:bg colors)
               :border-radius "12px"
               :padding "24px"
               :max-width "400px"
               :box-shadow "0 8px 32px rgba(0,0,0,0.3)"}
       :on-mouse-down #(.stopPropagation %)}
      [:h3 {:style {:margin "0 0 12px 0"
                    :color (:text colors)}}
       (t :restore)]
      [:p {:style {:color (:muted colors)
                   :margin "0 0 8px 0"}}
       (t :restore-version-confirm)]
      [:p {:style {:color (:muted colors)
                   :margin "0 0 20px 0"
                   :font-size "0.85rem"}}
       [:span {:style {:color "#ff9800"}} "⚠️ "]
       (t :backup-will-be-created)]
      [:div {:style {:display "flex"
                     :gap "8px"
                     :justify-content "flex-end"}}
       [:button {:style {:background "transparent"
                         :color (:muted colors)
                         :border (str "1px solid " (:border colors))
                         :padding "8px 16px"
                         :border-radius "6px"
                         :cursor "pointer"}
                 :on-click on-cancel}
        (t :cancel)]
       [:button {:style {:background (:accent colors)
                         :color "white"
                         :border "none"
                         :padding "8px 16px"
                         :border-radius "6px"
                         :cursor "pointer"}
                 :on-click on-confirm}
        (t :restore)]]]]))

(defn delete-confirm-dialog-component
  "Confirmation dialog for deleting a version"
  [{:keys [on-confirm on-cancel]}]
  (let [colors {:bg (settings/get-color :background)
                :text (settings/get-color :text)
                :muted (settings/get-color :text-muted)
                :border (settings/get-color :border)}]
    [:div.delete-confirm-backdrop
     {:style {:position "fixed"
              :inset 0
              :background "rgba(0,0,0,0.4)"
              :z-index 1001
              :display "flex"
              :align-items "center"
              :justify-content "center"}
      :on-mouse-down on-cancel}
     [:div.delete-confirm-dialog
      {:style {:background (:bg colors)
               :border-radius "12px"
               :padding "24px"
               :max-width "350px"
               :box-shadow "0 8px 32px rgba(0,0,0,0.3)"}
       :on-mouse-down #(.stopPropagation %)}
      [:h3 {:style {:margin "0 0 12px 0"
                    :color (:text colors)}}
       (t :delete-version)]
      [:p {:style {:color (:muted colors)
                   :margin "0 0 20px 0"}}
       (t :delete-version-confirm)]
      [:div {:style {:display "flex"
                     :gap "8px"
                     :justify-content "flex-end"}}
       [:button {:style {:background "transparent"
                         :color (:muted colors)
                         :border (str "1px solid " (:border colors))
                         :padding "8px 16px"
                         :border-radius "6px"
                         :cursor "pointer"}
                 :on-click on-cancel}
        (t :cancel)]
       [:button {:style {:background "#ff6b6b"
                         :color "white"
                         :border "none"
                         :padding "8px 16px"
                         :border-radius "6px"
                         :cursor "pointer"}
                 :on-click on-confirm}
        (t :delete)]]]]))

(defn toast-component
  "Toast notification component"
  []
  (when-let [message @toast-message]
    (let [colors {:bg (settings/get-color :sidebar)
                  :text (settings/get-color :text)
                  :border (settings/get-color :border)}]
      [:div.toast
       {:style {:position "fixed"
                :bottom "20px"
                :left "50%"
                :transform "translateX(-50%)"
                :background (:bg colors)
                :color (:text colors)
                :padding "12px 24px"
                :border-radius "8px"
                :box-shadow "0 4px 12px rgba(0,0,0,0.2)"
                :border (str "1px solid " (:border colors))
                :z-index 2000
                :animation "fadeIn 0.2s ease-out"}}
       message])))

;; =============================================================================
;; Version Dropdown Component
;; =============================================================================

(defn version-dropdown
  "Dropdown menu for version operations"
  [{:keys [on-save-version on-list-versions on-restore-backup]}]
  (let [open? (r/atom false)]
    (fn [{:keys [on-save-version on-list-versions on-restore-backup]}]
      (let [colors {:bg (settings/get-color :background)
                    :text (settings/get-color :text)
                    :muted (settings/get-color :text-muted)
                    :border (settings/get-color :border)
                    :accent (settings/get-color :accent)
                    :sidebar (settings/get-color :sidebar)}
            has-file? (some? (:path @file-info))]
        [:div {:style {:position "relative"}}
         [:button {:style {:background "transparent"
                           :color (:muted colors)
                           :border (str "1px solid " (:border colors))
                           :padding "6px 12px"
                           :border-radius "4px"
                           :cursor "pointer"
                           :font-size "0.85rem"
                           :display "flex"
                           :align-items "center"
                           :gap "4px"}
                   :on-click #(swap! open? not)}
          [:span "📸"]
          [:span (t :version)]
          [:span {:style {:font-size "0.7rem"}} "▼"]]
         (when @open?
           [:<>
            ;; Backdrop to close dropdown
            [:div {:style {:position "fixed"
                           :inset 0
                           :z-index 99}
                   :on-click #(reset! open? false)}]
            ;; Dropdown menu
            [:div {:style {:position "absolute"
                           :top "100%"
                           :left 0
                           :margin-top "4px"
                           :background (:bg colors)
                           :border (str "1px solid " (:border colors))
                           :border-radius "6px"
                           :box-shadow "0 4px 12px rgba(0,0,0,0.15)"
                           :z-index 100
                           :min-width "180px"}}
             [:div {:style {:padding "4px"}}
              [:button {:style {:display "block"
                                :width "100%"
                                :text-align "left"
                                :background "transparent"
                                :border "none"
                                :padding "8px 12px"
                                :color (if has-file? (:text colors) (:muted colors))
                                :cursor (if has-file? "pointer" "not-allowed")
                                :border-radius "4px"
                                :font-size "0.9rem"
                                :opacity (if has-file? 1 0.5)}
                        :on-click (fn []
                                    (reset! open? false)
                                    (if has-file?
                                      (on-save-version)
                                      (show-toast! (t :save-first))))}
               (t :save-version)]
              [:button {:style {:display "block"
                                :width "100%"
                                :text-align "left"
                                :background "transparent"
                                :border "none"
                                :padding "8px 12px"
                                :color (if has-file? (:text colors) (:muted colors))
                                :cursor (if has-file? "pointer" "not-allowed")
                                :border-radius "4px"
                                :font-size "0.9rem"
                                :opacity (if has-file? 1 0.5)}
                        :on-click (fn []
                                    (reset! open? false)
                                    (if has-file?
                                      (on-list-versions)
                                      (show-toast! (t :save-first))))}
               (t :version-list)]
              [:div {:style {:height "1px"
                             :background (:border colors)
                             :margin "4px 0"}}]
              [:button {:style {:display "block"
                                :width "100%"
                                :text-align "left"
                                :background "transparent"
                                :border "none"
                                :padding "8px 12px"
                                :color (if has-file? (:text colors) (:muted colors))
                                :cursor (if has-file? "pointer" "not-allowed")
                                :border-radius "4px"
                                :font-size "0.9rem"
                                :opacity (if has-file? 1 0.5)}
                        :on-click (fn []
                                    (reset! open? false)
                                    (if has-file?
                                      (on-restore-backup)
                                      (show-toast! (t :save-first))))}
               (t :restore-backup)]]]])]))))

;; =============================================================================
;; Main Dialog Container
;; =============================================================================

(defn dialogs-container
  "Container for all versioning dialogs"
  [{:keys [on-save-as on-reload on-reload-from-version]}]
  [:<>
   ;; Conflict dialog
   (when @conflict-dialog
     [conflict-dialog-component
      {:on-overwrite (:on-overwrite @conflict-dialog)
       :on-save-as (fn []
                     (reset! conflict-dialog nil)
                     (on-save-as))
       :on-reload (fn []
                    (reset! conflict-dialog nil)
                    (on-reload))
       :on-cancel #(reset! conflict-dialog nil)}])

   ;; File changed on disk dialog
   (when @file-changed-dialog
     [file-changed-dialog-component
      {:on-reload (fn []
                    (reset! file-changed-dialog nil)
                    (on-reload))
       :on-ignore #(reset! file-changed-dialog nil)}])

   ;; Save version dialog
   (when @save-version-dialog
     [save-version-dialog-component
      {:on-save (fn [desc]
                  (if-let [path (:path @file-info)]
                    (-> (save-version! path desc)
                        (.then #(reset! save-version-dialog nil))
                        (.catch (fn [err]
                                  (js/console.error "Save version error:" err)
                                  (show-toast! "Errore nel salvare la versione")
                                  (reset! save-version-dialog nil))))
                    (do
                      (show-toast! (t :save-first))
                      (reset! save-version-dialog nil))))
       :on-cancel #(reset! save-version-dialog nil)}])

   ;; Version list dialog
   (when @version-list-dialog
     [version-list-dialog-component
      {:versions (:versions @version-list-dialog)
       :filename (:filename @version-list-dialog)
       :on-open-copy (fn [filename]
                       (when-let [path (:path @file-info)]
                         (-> (open-version-copy! path filename)
                             (.then (fn [content]
                                      (reset! version-list-dialog nil)
                                      (on-reload-from-version content filename))))))
       :on-restore (fn [filename]
                     (reset! restore-confirm-dialog
                             {:filename filename}))
       :on-delete (fn [filename]
                    (reset! delete-version-dialog
                            {:filename filename}))
       :on-close #(reset! version-list-dialog nil)}])

   ;; Restore confirm dialog
   (when @restore-confirm-dialog
     [restore-confirm-dialog-component
      {:version-info @restore-confirm-dialog
       :on-confirm (fn []
                     (let [filename (:filename @restore-confirm-dialog)
                           path (:path @file-info)]
                       (-> (restore-version! path filename on-reload)
                           (.then (fn []
                                    (reset! restore-confirm-dialog nil)
                                    (reset! version-list-dialog nil))))))
       :on-cancel #(reset! restore-confirm-dialog nil)}])

   ;; Delete version confirm dialog
   (when @delete-version-dialog
     [delete-confirm-dialog-component
      {:on-confirm (fn []
                     (let [filename (:filename @delete-version-dialog)
                           path (:path @file-info)]
                       (-> (delete-version! path filename)
                           (.then (fn []
                                    (reset! delete-version-dialog nil)
                                    ;; Refresh version list
                                    (-> (list-versions path)
                                        (.then (fn [versions]
                                                 (swap! version-list-dialog assoc :versions versions)))))))))
       :on-cancel #(reset! delete-version-dialog nil)}])

   ;; Toast
   [toast-component]])

;; =============================================================================
;; Public API for Integration
;; =============================================================================

(defn open-save-version-dialog!
  "Open the save version dialog"
  []
  (when (:path @file-info)
    (reset! save-version-dialog true)))

(defn open-version-list-dialog!
  "Open the version list dialog"
  []
  (when-let [path (:path @file-info)]
    (-> (list-versions path)
        (.then (fn [versions]
                 (reset! version-list-dialog
                         {:versions versions
                          :filename (get-filename path)}))))))

(defn open-restore-backup-dialog!
  "Attempt to restore from backup"
  [on-reload!]
  (when-let [path (:path @file-info)]
    (-> (has-backup? path)
        (.then (fn [has-backup?]
                 (if has-backup?
                   (reset! restore-confirm-dialog
                           {:is-backup true
                            :on-confirm #(restore-backup! path on-reload!)})
                   (show-toast! (t :no-backup-available))))))))

(defn show-conflict-dialog!
  "Show the conflict dialog"
  [opts]
  (reset! conflict-dialog opts))

(defn show-file-changed-dialog!
  "Show the file changed dialog"
  []
  (reset! file-changed-dialog true))
