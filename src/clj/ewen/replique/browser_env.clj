(ns ewen.replique.browser-env
  (:require [cljs.repl :as repl]
            [ewen.replique.multi-transport :refer [multi-transport]]
            [clojure.tools.nrepl.server :refer [start-server default-handler]]))

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


(defn repl-env []
  (BrowserEnv.))


(defn test-browser-env []
  (start-server :bind "127.0.0.1"
                :port 57794
                :transport-fn multi-transport
                :handler (default-handler)))
