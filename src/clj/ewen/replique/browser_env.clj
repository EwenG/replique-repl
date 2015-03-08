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
  (println "setup"))


(defn env-evaluate [js]
  (println "evaluate")
  (benv/browser-eval js))

(defn env-load [this provides url]
  (println "load")
  (benv/load-javascript this provides url))

(defn env-tear-down []
  (println "tear-down"))

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











(let [ups-deps (cljsc/get-upstream-deps (java.lang.ClassLoader/getSystemClassLoader))]
  (defonce opts {:output-dir   "target/out"
             :ups-libs         (:libs ups-deps)
             :ups-foreign-libs (:foreign-libs ups-deps)}))

(defonce compiler-env (cljs.env/default-compiler-env opts))

(defonce browser-env (merge (BrowserEnv.)
                        {:optimizations  :none
                         :working-dir    (or (:output-dir opts)
                                             (->> [".repl" (util/clojurescript-version)]
                                                  (remove empty?) (string/join "-")))
                         :serve-static   true
                         :static-dir     (cond-> ["." "target/out/"]
                                                 (:output-dir opts) (conj (:output-dir opts)))
                         :preloaded-libs []
                         :src            "src/"
                         ::env/compiler  compiler-env
                         :source-map     false}
                        opts))

(when (:src browser-env)
  (repl/analyze-source (:src browser-env)))

(cljs.env/with-compiler-env compiler-env
                            (defonce preloaded-libs (atom (set (concat
                                                             (@#'benv/always-preload browser-env)
                                                             (map str (:preloaded-libs browser-env)))))))





