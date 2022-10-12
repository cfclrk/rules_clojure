(ns rules-clojure.persistent-classloader
  (:require [clojure.data]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [rules-clojure.util :as util]
            [rules-clojure.persistentClassLoader])
  (:import [java.net URL URLClassLoader]
           java.util.jar.JarFile
           java.lang.ref.SoftReference
           rules_clojure.persistentClassLoader))

;; We want a clean, deterministic build. The naive way to do that is
;; to construct a new URLClassloader containing exactly what the user
;; requested for each build. Unfortunately, that's too slow, because
;; every time we construct a new classloader, the code has to be
;; reloaded. Using caching to remove reloads as much as possible.

(defn new-classloader-
  ([cp]
   (new-classloader- cp (.getParent (ClassLoader/getSystemClassLoader))))
  ([cp parent]
   {:pre [(every? string? cp)
          (instance? ClassLoader parent)]}
   (persistentClassLoader.
    (into-array URL (map #(.toURL (io/file %)) cp))
    parent)))

(defn add-url [cl p]
  (.addURL cl (-> p io/file .toURL)))

(defn jar-files [path]
  (-> (JarFile. (str path))
      (.entries)
      (enumeration-seq)
      (->> (map (fn [e]
                  (.getName e))))))

(defn jar? [path]
  (re-find #".jar$" path))

(defn clojure? [path]
  (or (re-find #"org/clojure/clojure/.*.jar$" path)
      (re-find #"org/clojure/spec.alpha/.*.jar$" path)
      (re-find #"org/clojure/core.specs.alpha/.*.jar$" path)))

(defprotocol ClassLoaderStrategy
  (with-classloader [this args f]
    "Given a classpath, calls f, a fn of one arg, the classloader. Args is
     a map that must contain `:classloader`, and any protocol
     implementation specific keys "))

(defn slow-naive
  "Use a new classloader for every compile. Works. Slow."
  []
  (reify ClassLoaderStrategy
    (with-classloader [_this {:keys [classpath]} f]
      (f (new-classloader- classpath)))))

(defn dirty-fast
  "Use a single classloader for all compiles, and always use. Works, only in single-threaded mode"
  []
  (let [dirty-classloader (new-classloader- [])]
    (reify ClassLoaderStrategy
      (with-classloader[this {:keys [classpath]} f]
        (doseq [p classpath]
          (add-url dirty-classloader p))
        (f dirty-classloader)))))

(defn clear-dead-refs [cache]
  (swap! cache (fn [cache]
                 (->> cache
                      (filter (fn [[k v]]
                                (.get v)))
                      (into {})))))

(defn cache-classloader [cache key cl]
  {:pre [(map? @cache)]
   :post [(map? @cache)]}
  (swap! cache assoc key (SoftReference. cl))
  (clear-dead-refs cache))

(defn claim-classloader
  "Attempt to claim a cached classloader with key. Returns the classloader if successful, else nil"
  [cache key]
  (let [[old _new] (swap-vals! cache (fn [cache]
                                       (if-let [cl (get cache key)]
                                         (dissoc cache key)
                                         cache)))]
    (get old key)))

(defn parse-GAV-1
  "Given the path to a path, attempt to parse out maven Group Artifact Version coordinates, returns nil if it doesn't appear to be in a maven dir"
  [p]
  (let [[match group artifact version] (re-find #"repository/(.+)/([^\/]+)/([^\/]+)/\2-\3.jar" p)]
    (when match
      [(str group "/" artifact) version])))

(defn GAV-map
  "given a classpath, return a map of all parsed GAV coordinates"
  [classpath]
  (->> classpath
       (keep parse-GAV-1)
       (into {})))

(defn compatible-classpaths? [c1 c2]
  (let [gav-1 (GAV-map c1)
        gav-2 (GAV-map c2)
        common-keys (set/intersection (set (keys gav-1)) (set (keys gav-2)))]
    (= (select-keys gav-1 common-keys)
       (select-keys gav-2 common-keys))))

(defn new-classloader-cache [cache classpath]
  {:pre [(every? string? classpath)]}
  (let [cp-desired (set classpath)
        cp-jars (set (filter jar? classpath))
        cp-dirs (set (remove jar? classpath))
        cp-classes-dirs (filter (fn [p] (re-find #"\.classes$" p )) cp-dirs)
        _ (assert (= 1 (count cp-classes-dirs)))
        cache-deref @cache
        caches (->> cache-deref
                    (filter (fn [[cp-cache _cl-ref]]
                              (compatible-classpaths? cp-cache cp-desired))))
        cp-parent (when (seq caches)
                    (let [[cp-parent parent-ref] (apply max-key (fn [[cp-cache cl-ref]]
                                                                  (count (set/intersection cp-desired cp-cache))) caches)]
                      cp-parent))
        cl-cache (if (seq cache-deref)
                   (if cp-parent
                     (if-let [cl-ref (claim-classloader cache cp-parent)]
                       (if-let [cl (.get cl-ref)]
                         (let [cp-new (set/difference cp-jars cp-parent)]
                           (doseq [p cp-new]
                             (add-url cl p))
                           (do
                             ;; (println "hit")
                             cl))
                         (do
                           ;; (println "cache-miss GC")
                           (new-classloader- cp-desired)))
                       (do
                         ;; (println "cache failed claim. size:" (count cache-deref) )
                         (new-classloader- cp-desired)))
                     (do
                       ;; (println "cache miss incompatible:" (map (fn [[cp-cache _cl-ref]] (compatible-classpaths? cp-desired cp-cache)) cache-deref))
                       (new-classloader- cp-desired)))
                   (do
                     ;; (println "cache-miss empty")
                     (new-classloader- cp-desired)))
        cl-final (new-classloader- cp-dirs cl-cache)]
    (util/bind-compiler-loader cl-final)
    cl-final))

(defn caching-clean-GAV
  "Take a classloader from the cache, if the maven GAV coordinates are
  not incompatible. Reuse the classloader, iff the namespaces compiled
  do not contain protocols, because those will cause CLJ-1544 errors
  if reused. Works."
  []
  (let [cache (atom {})
        metadata (atom {})]
    (reify ClassLoaderStrategy
      (with-classloader [this {:keys [classpath
                                     aot-nses]} f]
        (let [classloader (new-classloader-cache cache classpath)
              cacheable-classloader (.getParent classloader)]
          (swap! metadata assoc cacheable-classloader {:classpath classpath
                                                       :aot-nses aot-nses})
          (f classloader)
          (let [script (str `(do
                               (require 'rules-clojure.compile)
                               (some (fn [n#]
                                       (or (rules-clojure.compile/contains-protocols? (symbol n#))
                                           ;; I don't think deftype is necessary here, but it works around a java-time bug
                                           (rules-clojure.compile/contains-deftypes? (symbol n#)))) [~@aot-nses])))
                ret (util/shim-eval classloader script)]
            (if-not ret
              (cache-classloader cache (set (filter jar? classpath)) cacheable-classloader)
              (swap! metadata dissoc classloader))))))))


(defn caching-clean-GAV-thread-local
  "Take a classloader from the cache, if the maven GAV coordinates are
  not incompatible. Reuse the classloader, iff the namespaces compiled
  do not contain protocols, because those will cause CLJ-1544 errors
  if reused. Fastest working implementation."
  []
  (let [cache (ThreadLocal.)]
    (reify ClassLoaderStrategy
      (with-classloader [this {:keys [classpath aot-nses]} f]
        (let [{cl-cache :classloader
               cp-cache :classpath} (.get cache)
              cp-desired (set classpath)
              cp-jars (set (filter jar? classpath))
              cp-dirs (set (remove jar? classpath))
              cacheable-classloader (if (and cl-cache (compatible-classpaths? cp-jars cp-cache))
                                      (let [cp-new (set/difference cp-jars cp-cache)]
                                        (doseq [p cp-new]
                                          (add-url cl-cache p))
                                        cl-cache)
                                      (new-classloader- cp-jars))
              classloader (new-classloader- cp-dirs cacheable-classloader)]
          (let [ret (f classloader)
                script (str `(do
                               (require 'rules-clojure.compile)
                               (some (fn [n#]
                                       (or (rules-clojure.compile/contains-protocols? (symbol n#))
                                           ;; I don't think deftype is necessary here, but it works around a java-time bug
                                           (rules-clojure.compile/contains-deftypes? (symbol n#)))) [~@aot-nses])))
                ret (util/shim-eval classloader script)]
            (if-not ret
              (.set cache {:classpath classpath
                           :classloader cacheable-classloader})
              (.set cache nil))
            ret))))))