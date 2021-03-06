(ns clojars.routes.repo
  (:require [clojars.auth :refer [with-account require-authorization]]
            [clojars.db :as db]
            [clojars.config :refer [config]]
            [clojars.maven :as maven]
            [clojars.event :as ev]
            [clojars.errors :refer [report-error]]
            [compojure.core :refer [defroutes PUT ANY]]
            [compojure.route :refer [not-found]]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [ring.util.codec :as codec]
            [ring.util.response :as response])
  (:import java.io.StringReader))

(defn versions [group-id artifact-id]
  (->> (.listFiles (io/file (config :repo) group-id artifact-id))
       (filter (memfn isDirectory))
       (sort-by (comp - (memfn last-modified)))
       (map (memfn getName))))

(defn find-jar
  ([group-id artifact-id]
     (find-jar group-id artifact-id (first (versions group-id artifact-id))))
  ([group-id artifact-id version]
     (try
       (maven/pom-to-map (io/file (config :repo) group-id artifact-id version
                                  (format "%s-%s.pom" artifact-id version)))
       (catch java.io.FileNotFoundException e
         nil))))

(defn group-artifacts [group-id]
  (.list (io/file (config :repo) group-id)))

(defn user-artifacts [username]
  (mapcat group-artifacts (:groups (@ev/users username))))

(defn save-to-file [sent-file input]
  (-> sent-file
      .getParentFile
      .mkdirs)
  (io/copy input sent-file))

(defn- try-save-to-file [sent-file input]
  (try
    (save-to-file sent-file input)
    (catch java.io.IOException e
      (.delete sent-file)
      (throw e))))

(defn- pom? [filename]
  (.endsWith filename ".pom"))

(defn- get-pom-info [contents info]
  (-> contents
      StringReader.
      maven/pom-to-map
      (merge info)))

(defn- body-and-add-pom [body filename info account]
  (if (pom? filename)
    (let [contents (slurp body)]
      (db/add-jar account (get-pom-info contents info))
      contents)
    body))

(defmacro with-error-handling [& body]
  `(try
     ~@body
     ;; should we only do 201 if the file didn't already exist?
     {:status 201 :headers {} :body nil}
     (catch Exception e#
       (report-error e#)
       (let [data# (ex-data e#)]
         {:status (or (:status data#) 403)
          :status-message (:status-message data#)
          :headers {}
          :body (.getMessage e#)}))))

(defmacro put-req [groupname & body]
  `(with-account
     (require-authorization
      ~groupname
      (with-error-handling
        ~@body))))

(defn- handle-versioned-upload [body group artifact version filename]
  (let [groupname (string/replace group "/" ".")]
    (put-req
      groupname
      (let [file (io/file (config :repo) group artifact version filename)
            info {:group groupname
                  :name  artifact
                  :version version}]
        (ev/validate-deploy groupname artifact version filename)
        (db/check-and-add-group account groupname)

        (try-save-to-file file (body-and-add-pom body filename info account))
        ;; Be consistent with scp only recording pom or jar
        (when (some #(.endsWith filename %) [".pom" ".jar"])
          (ev/record-deploy info account file))))))

;; web handlers
(defroutes routes
  (PUT ["/:group/:artifact/:file"
        :group #".+" :artifact #"[^/]+" :file #"maven-metadata\.xml[^/]*"]
       {body :body {:keys [group artifact file]} :params}
       (if (ev/snapshot-version? artifact)
         ;; SNAPSHOT metadata will hit this route, but should be
         ;; treated as a versioned file upload.
         ;; See: https://github.com/ato/clojars-web/issues/319
         (let [version artifact
               group-parts (string/split group #"/")
               group (string/join "/" (butlast group-parts))
               artifact (last group-parts)]
           (handle-versioned-upload body group artifact version file))
         (let [groupname (string/replace group "/" ".")]
           (put-req
             groupname
             (let [file (io/file (config :repo) group artifact file)]
               (db/check-and-add-group account groupname)
               (try-save-to-file file body))))))
  (PUT ["/:group/:artifact/:version/:filename"
        :group #"[^\.]+" :artifact #"[^/]+" :version #"[^/]+"
        :filename #"[^/]+(\.pom|\.jar|\.sha1|\.md5|\.asc)$"]
       {body :body {:keys [group artifact version filename]} :params}
       (handle-versioned-upload body group artifact version filename))
  (PUT "*" _ {:status 400 :headers {}})
  (not-found "Page not found"))

(defn wrap-file [app dir]
  (fn [req]
    (if-not (= :get (:request-method req))
      (app req)
      (let [path (codec/url-decode (:path-info req))]
        (or (response/file-response path {:root dir})
            (app req))))))

(defn wrap-reject-double-dot [f]
  (fn [req]
    (if (re-find #"\.\." (:uri req))
      {:status 400 :headers {}}
      (f req))))
