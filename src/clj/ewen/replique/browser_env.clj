(ns ewen.replique.browser-env
  (:refer-clojure :exclude [loaded-libs])
  (:require [cljs.repl :as repl]
            [cljs.closure :as cljsc]
            [clojure.string :as string]
            [cljs.util :as util]
            [cljs.env :as env]
            [clojure.java.io :as io]
            [cljs.repl.browser :as benv]))

(defn env-setup [repl-env opts]
  (println "setup")
  (benv/setup repl-env opts))

(defn env-evaluate [js]
  (println "evaluate")
  (benv/browser-eval js))

(defn env-load [this provides url]
  (println "load")
  (benv/load-javascript this provides url))

(defn env-tear-down []
  (println "tear-down")
  (reset! benv/browser-state {}))

(defrecord BrowserEnv []
  repl/IJavaScriptEnv
  (-setup [this opts]
    (env-setup this opts))
  (-evaluate [_ _ _ js]
    (env-evaluate js))
  (-load [this provides url]
    (env-load this provides url))
  (-tear-down [_]
    (env-tear-down)))




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
                                (reset! benv/preloaded-libs
                                        (set (concat
                                               (@#'benv/always-preload opts)
                                               (map str (:preloaded-libs opts)))))
                                (reset! benv/loaded-libs @benv/preloaded-libs)
                                (println "Compiling client js ...")
                                (swap! benv/browser-state
                                       (fn [old]
                                         (assoc old :client-js
                                                    (benv/create-client-js-file
                                                      opts
                                                      (io/file (:working-dir opts) "client.js")))))
                                (println "Waiting for browser to connect ...")
                                opts)))


