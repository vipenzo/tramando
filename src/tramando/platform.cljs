(ns tramando.platform
  "Platform abstraction layer for file operations.
   Provides unified API that works in both Tauri (desktop) and webapp (browser) modes."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Platform Detection
;; =============================================================================

(defn tauri?
  "Check if running in Tauri (desktop) mode"
  []
  (boolean (.-__TAURI__ js/window)))

(defn webapp?
  "Check if running in webapp (browser) mode"
  []
  (not (tauri?)))

;; =============================================================================
;; Dynamic imports for Tauri plugins (only loaded when in Tauri mode)
;; =============================================================================

(defonce tauri-dialog (atom nil))
(defonce tauri-fs (atom nil))

(defn- ensure-tauri-imports!
  "Dynamically import Tauri plugins when needed"
  []
  (when (and (tauri?) (nil? @tauri-dialog))
    ;; Use js/eval to dynamically require - these are already bundled
    (reset! tauri-dialog (js/window.__TAURI__.dialog))
    (reset! tauri-fs (js/window.__TAURI__.fs)))
  (boolean @tauri-dialog))

;; =============================================================================
;; Webapp File Operations (using browser APIs)
;; =============================================================================

(defonce ^:private file-input-element (atom nil))
(defonce ^:private file-load-callback (atom nil))

;; Persistent file handle for File System Access API (webapp)
;; This allows saving to the same file without showing the dialog each time
(defonce current-file-handle (atom nil))

(defn- get-or-create-file-input!
  "Get or create a hidden file input element for file picking"
  []
  (if @file-input-element
    @file-input-element
    (let [input (js/document.createElement "input")]
      (set! (.-type input) "file")
      (set! (.-accept input) ".trmd,.md,.txt")
      (set! (.-style input) "display: none")
      (.addEventListener input "change"
        (fn [e]
          (when-let [file (-> e .-target .-files (aget 0))]
            (let [reader (js/FileReader.)]
              (set! (.-onload reader)
                (fn [e]
                  (when-let [callback @file-load-callback]
                    (callback {:content (-> e .-target .-result)
                               :filename (.-name file)}))))
              (.readAsText reader file)))
          ;; Reset the input so the same file can be selected again
          (set! (.-value input) "")))
      (js/document.body.appendChild input)
      (reset! file-input-element input)
      input)))

(defn- webapp-open-file!
  "Open file using File System Access API (if available) or fallback to file input.
   Note: File System Access API is only supported in Chrome/Edge. Brave, Safari, and Firefox
   use the fallback method which doesn't support persistent file handles (Save = download)."
  [callback]
  ;; Try File System Access API first (Chrome/Edge) - this gives us a handle for saving
  (if (.-showOpenFilePicker js/window)
    (-> (js/window.showOpenFilePicker
          #js {:types #js [#js {:description "Tramando files"
                                :accept #js {"text/plain" #js [".trmd" ".md" ".txt"]}}]
               :multiple false})
        (.then (fn [^js handles]
                 (let [^js handle (aget handles 0)]
                   ;; Store the handle for future saves
                   (reset! current-file-handle handle)
                   ;; Read the file content
                   (-> (.getFile handle)
                       (.then (fn [^js file]
                                (-> (.text file)
                                    (.then (fn [content]
                                             (callback {:content content
                                                        :filename (.-name file)}))))))))))
        (.catch (fn [^js err]
                  ;; User cancelled - don't show error
                  (when (not= (.-name err) "AbortError")
                    (js/console.warn "File System Access API failed:" err)))))
    ;; Fallback to old file input method (no handle, Save will show dialog)
    (do
      (reset! file-load-callback callback)
      (let [input (get-or-create-file-input!)]
        (.click input)))))

(defn- webapp-download-file!
  "Fallback: download file using blob and anchor trick"
  [content filename callback]
  (let [blob (js/Blob. #js [content] #js {:type "text/plain;charset=utf-8"})
        url (js/URL.createObjectURL blob)
        a (js/document.createElement "a")]
    (set! (.-href a) url)
    (set! (.-download a) filename)
    (js/document.body.appendChild a)
    (.click a)
    (js/document.body.removeChild a)
    (js/URL.revokeObjectURL url)
    (when callback
      (callback {:success true :filename filename :downloaded true}))))

(defn- webapp-save-to-handle!
  "Save content to an existing file handle"
  [^js handle content callback]
  (-> (.createWritable handle)
      (.then (fn [^js writable]
               (-> (.write writable content)
                   (.then #(.close writable))
                   (.then #(when callback
                             (callback {:success true
                                        :filename (.-name handle)}))))))
      (.catch (fn [^js err]
                (js/console.error "Failed to save to file handle:" err)
                ;; Handle might be invalid, clear it
                (reset! current-file-handle nil)
                (when callback
                  (callback {:success false :error err}))))))

(defn- webapp-save-file!
  "Save file using existing handle, File System Access API, or download fallback"
  [content filename callback]
  ;; If we have a file handle, use it directly
  (if @current-file-handle
    (webapp-save-to-handle! @current-file-handle content callback)
    ;; Otherwise try File System Access API (Chrome/Edge)
    (if (.-showSaveFilePicker js/window)
        (-> (js/window.showSaveFilePicker
            #js {:suggestedName filename
                 :types #js [#js {:description "Tramando files"
                                  :accept #js {"text/plain" #js [".trmd" ".md" ".txt"]}}]})
          (.then (fn [^js handle]
                   ;; Store the handle for future saves
                   (reset! current-file-handle handle)
                   (webapp-save-to-handle! handle content callback)))
          (.catch (fn [^js err]
                    ;; User cancelled or error - fallback to download
                    (when (not= (.-name err) "AbortError")
                      (js/console.warn "File System Access API failed, using download fallback:" err)
                      (webapp-download-file! content filename callback)))))
      ;; Fallback: trigger download
      (webapp-download-file! content filename callback))))

(defn- webapp-save-file-as!
  "Save file with 'save as' dialog - always shows picker"
  [content filename callback]
  ;; Clear existing handle to force showing the dialog
  (reset! current-file-handle nil)
  ;; Try File System Access API (Chrome/Edge)
  (if (.-showSaveFilePicker js/window)
    (-> (js/window.showSaveFilePicker
          #js {:suggestedName filename
               :types #js [#js {:description "Tramando files"
                                :accept #js {"text/plain" #js [".trmd" ".md" ".txt"]}}]})
        (.then (fn [^js handle]
                 ;; Store the new handle for future saves
                 (reset! current-file-handle handle)
                 (webapp-save-to-handle! handle content callback)))
        (.catch (fn [^js err]
                  ;; User cancelled or error - fallback to download
                  (when (not= (.-name err) "AbortError")
                    (js/console.warn "File System Access API failed, using download fallback:" err)
                    (webapp-download-file! content filename callback)))))
    ;; Fallback: trigger download
    (webapp-download-file! content filename callback)))

;; =============================================================================
;; Tauri File Operations
;; =============================================================================

(defn- tauri-open-file!
  "Open file using Tauri dialog"
  [default-folder callback]
  (ensure-tauri-imports!)
  (let [^js dialog @tauri-dialog
        ^js fs @tauri-fs]
    (-> (.open dialog #js {:defaultPath (when (not (empty? default-folder)) default-folder)
                           :filters #js [#js {:name "Tramando" :extensions #js ["trmd" "md" "txt"]}]
                           :multiple false})
        (.then (fn [^js path]
                 (when path
                   (-> (.readTextFile fs path)
                       (.then (fn [content]
                                (let [filename (last (str/split path #"/"))]
                                  (callback {:content content
                                             :filename filename
                                             :filepath path}))))
                       (.catch (fn [err]
                                 (js/console.error "Read file error:" err)))))))
        (.catch (fn [err]
                  (js/console.error "Open dialog error:" err))))))

(defn- tauri-save-file!
  "Save file to existing path in Tauri"
  [filepath content callback]
  (ensure-tauri-imports!)
  (let [^js fs @tauri-fs]
    (-> (.writeTextFile fs filepath content)
        (.then (fn []
                 (when callback
                   (callback {:success true
                              :filepath filepath
                              :filename (last (str/split filepath #"/"))}))))
        (.catch (fn [err]
                  (js/console.error "Save file error:" err)
                  (when callback
                    (callback {:success false :error err})))))))

(defn- tauri-save-file-as!
  "Save file with dialog in Tauri"
  [default-folder filename content callback]
  (ensure-tauri-imports!)
  (let [^js dialog @tauri-dialog
        ^js fs @tauri-fs]
    (-> (.save dialog #js {:defaultPath (if (empty? default-folder)
                                          filename
                                          (str default-folder "/" filename))
                           :filters #js [#js {:name "Tramando" :extensions #js ["trmd"]}]})
        (.then (fn [^js path]
                 (when path
                   (-> (.writeTextFile fs path content)
                       (.then (fn []
                                (when callback
                                  (callback {:success true
                                             :filepath path
                                             :filename (last (str/split path #"/"))}))))
                       (.catch (fn [err]
                                 (js/console.error "Write file error:" err)
                                 (when callback
                                   (callback {:success false :error err}))))))))
        (.catch (fn [err]
                  (js/console.error "Save dialog error:" err))))))

(defn- tauri-read-file!
  "Read file from path in Tauri"
  [filepath callback]
  (ensure-tauri-imports!)
  (let [^js fs @tauri-fs]
    (-> (.readTextFile fs filepath)
        (.then (fn [content]
                 (callback {:content content
                            :filepath filepath
                            :filename (last (str/split filepath #"/"))})))
        (.catch (fn [err]
                  (js/console.error "Read file error:" err)
                  (callback {:error err}))))))

(defn- tauri-file-exists?
  "Check if file exists in Tauri"
  [filepath callback]
  (ensure-tauri-imports!)
  (let [^js fs @tauri-fs]
    (-> (.exists fs filepath)
        (.then callback)
        (.catch (fn [_] (callback false))))))

(defn- tauri-get-file-info!
  "Get file modification time in Tauri"
  [filepath callback]
  (ensure-tauri-imports!)
  (let [^js fs @tauri-fs]
    (-> (.stat fs filepath)
        (.then (fn [^js stat]
                 (callback {:mtime (.-mtime stat)
                            :size (.-size stat)})))
        (.catch (fn [err]
                  (callback {:error err}))))))

;; =============================================================================
;; Unified Public API
;; =============================================================================

(defn open-file!
  "Open a file with platform-appropriate dialog.
   Callback receives {:content :filename :filepath (tauri only)}"
  ([callback]
   (open-file! nil callback))
  ([default-folder callback]
   (if (tauri?)
     (tauri-open-file! default-folder callback)
     (webapp-open-file! callback))))

(defn save-file!
  "Save content to a file.
   - In Tauri with filepath: saves directly
   - In Tauri without filepath: shows save dialog
   - In webapp: shows save dialog or triggers download
   Callback receives {:success :filepath :filename}"
  ([content filename callback]
   (save-file! content filename nil nil callback))
  ([content filename filepath callback]
   (save-file! content filename filepath nil callback))
  ([content filename filepath default-folder callback]
   (if (tauri?)
     (if filepath
       (tauri-save-file! filepath content callback)
       (tauri-save-file-as! default-folder filename content callback))
     (webapp-save-file! content filename callback))))

(defn save-file-as!
  "Save content with 'save as' dialog.
   Callback receives {:success :filepath :filename}"
  ([content filename callback]
   (save-file-as! content filename nil callback))
  ([content filename default-folder callback]
   (if (tauri?)
     (tauri-save-file-as! default-folder filename content callback)
     (webapp-save-file-as! content filename callback))))

(defn read-file!
  "Read a file from path (Tauri only, no-op in webapp).
   Callback receives {:content :filepath :filename} or {:error}"
  [filepath callback]
  (if (tauri?)
    (tauri-read-file! filepath callback)
    (callback {:error "Direct file reading not supported in webapp mode"})))

(defn file-exists?
  "Check if file exists (Tauri only, always false in webapp).
   Callback receives boolean"
  [filepath callback]
  (if (tauri?)
    (tauri-file-exists? filepath callback)
    (callback false)))

(defn get-file-info!
  "Get file metadata (Tauri only).
   Callback receives {:mtime :size} or {:error}"
  [filepath callback]
  (if (tauri?)
    (tauri-get-file-info! filepath callback)
    (callback {:error "File info not available in webapp mode"})))

(defn clear-file-handle!
  "Clear the current file handle (webapp only).
   Call this when creating a new project or opening a different file."
  []
  (reset! current-file-handle nil))

(defn has-file-handle?
  "Check if there's a current file handle (webapp only).
   In Tauri, this always returns false (filepath is used instead)."
  []
  (boolean @current-file-handle))
