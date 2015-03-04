(ns ewen.replique.browser-env
  (:refer-clojure :exclude [loaded-libs])
  (:require [cljs.repl :as repl]
            [cljs.closure :as cljsc]
            [clojure.string :as string]
            [cljs.util :as util]
            [cljs.env :as env]
            [clojure.java.io :as io]))

(defn env-setup [opts]
  (println "setup"))

(defn env-evaluate [js]
  (println "evaluate"))

(defn env-load [provides url]
  (println "load"))

(defn env-tear-down []
  (println "tear-down"))

(defrecord BrowserEnv []
  repl/IJavaScriptEnv
  (-setup [this opts]
    (env-setup opts))
  (-evaluate [_ _ _ js]
    (env-evaluate js))
  (-load [this provides url]
    (env-load provides url))
  (-tear-down [_]
    (env-tear-down)))

(def loaded-libs (atom #{}))

(def preloaded-libs (atom #{}))

(defn- provides-and-requires
  "Return a flat list of all provided and required namespaces from a
  sequence of IJavaScripts."
  [deps]
  (flatten (mapcat (juxt :provides :requires) deps)))

(defn- always-preload
  "Return a list of all namespaces which are always loaded into the browser
  when using a browser-connected REPL.
  Uses the working-dir (see repl-env) to output intermediate compilation."
  [& [{:keys [working-dir]}]]
  (let [opts (if working-dir {:output-dir working-dir}
                             {})
        cljs (provides-and-requires
               (cljsc/cljs-dependencies opts ["clojure.browser.repl"]))
        goog (provides-and-requires
               (cljsc/js-dependencies opts cljs))]
    (disj (set (concat cljs goog)) nil)))

(defonce browser-state
         (atom {:return-value-fn nil
                :client-js nil}))

(defn compile-client-js [opts]
  (cljsc/build
    '[(ns clojure.browser.repl.client
        (:require [goog.events :as event]
                  [clojure.browser.repl :as repl]))
      (defn start [url]
        (event/listen js/window
                      "load"
                      (fn []
                        (repl/start-evaluator url))))]
    {:optimizations (:optimizations opts)
     :output-dir (:working-dir opts)}))

(defn create-client-js-file [opts file-path]
  (let [file (io/file file-path)]
    (when (not (.exists file))
      (spit file (compile-client-js opts)))
    file))


(defn repl-env [& {:as opts}]
  (let [ups-deps (cljsc/get-upstream-deps (java.lang.ClassLoader/getSystemClassLoader))
        opts (assoc opts
               :ups-libs (:libs ups-deps)
               :ups-foreign-libs (:foreign-libs ups-deps))
        compiler-env (cljs.env/default-compiler-env opts)
        opts (merge (BrowserEnv.)
                    {:optimizations  :none
                     :working-dir    (or (:output-dir opts)
                                         (->> [".repl" (util/clojurescript-version)]
                                              (remove empty?) (string/join "-")))
                     :serve-static   true
                     :static-dir     (cond-> ["." "out/"]
                                             (:output-dir opts)
                                             (conj (:output-dir opts)))
                     :preloaded-libs []
                     :src            "src/"
                     ::env/compiler  compiler-env
                     :source-map     false}
                    opts)]
    (cljs.env/with-compiler-env compiler-env
                                (reset! preloaded-libs
                                        (set (concat
                                               (always-preload opts)
                                               (map str (:preloaded-libs opts)))))
                                (reset! loaded-libs @preloaded-libs)
                                (println "Compiling client js ...")
                                (swap! browser-state
                                       (fn [old]
                                         (assoc old :client-js
                                                    (create-client-js-file
                                                      opts
                                                      (io/file (:working-dir opts) "client.js")))))
                                (println "Waiting for browser to connect ...")
                                opts)))


