(ns ewen.replique.repl
  (:require [ewen.replique.browser-env :refer []]
            [cljs.repl :as repl]
            [cljs.repl.browser :as benv]
            [clojure.tools.nrepl
             [transport :as t]
             [middleware :refer [set-descriptor!]]
             [misc :refer [response-for]]]
            [clojure.tools.nrepl :as nrepl])
  (:import (java.io StringReader)
           (clojure.lang LineNumberingPushbackReader)))

(def cljs-env nil)

(defn init-browser-env! [])

;Use wrap-cljs-repl* as a var instead of a function to enable code reloading at the REPL.
;Maybe there is a better way?
(declare captured-h)

(defn cljs-repl*
  [{:keys [op session transport cljs] :as msg}]
  (when (= op "start-cljs-repl")
    (init-browser-env!))
  (captured-h msg))

(defn cljs-repl
  [h]
  (def captured-h h)
  #'cljs-repl*)

(set-descriptor! #'cljs-repl
                 {:requires #{"clone" #'ewen.replique.server/replique-server}
                  :expects #{"eval"}})


(comment




  (cljs.repl/repl (repl-env)
                  :read (let [reader (LineNumberingPushbackReader. (StringReader. "\"e\""))]
                          #(read reader false %2))
                  :prompt (fn [])
                  :need-prompt (constantly false))

  (:id (meta (:session clojure.tools.nrepl.middleware.interruptible-eval/*msg*)))

  (with-open [conn (nrepl/connect :port 57794)]
    (-> (nrepl/client conn 1000)    ; message receive timeout required
        (nrepl/message {:op "start-cljs-repl"})
        nrepl/response-values))

  )
