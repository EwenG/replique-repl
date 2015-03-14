(ns ewen.replique.repl
  (:require [ewen.replique.browser-env :refer []]
            [ewen.replique.server :refer []]
            [cljs.repl :as repl]
            [cljs.repl.browser :as benv]
            [clojure.tools.nrepl
             [transport :as t]
             [middleware :refer [set-descriptor!]]
             [misc :refer [response-for]]]
            [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [interruptible-eval]]
            [clojure.tools.nrepl.middleware.session :refer [session]]
            [clojure.tools.nrepl.middleware.load-file :refer [wrap-load-file]])
  (:import (java.io StringReader)
           (clojure.lang LineNumberingPushbackReader)))





(defonce started-cljs-session (atom #{}))

;Use wrap-cljs-repl* as a var instead of a function to enable code reloading at the REPL.
;Maybe there is a better way?
(declare captured-h)

(defn cljs-repl*
  [{:keys [op session transport cljs] :as msg}]
  (if (and (get @started-cljs-session (:id (meta session)))
           (= op "eval"))
    (let [env (get @session #'ewen.replique.server/browser-env)
          *state* (get @session #'ewen.replique.server/*state*)
          *browser-state* (get @session #'ewen.replique.server/*browser-state*)]
      (binding [ewen.replique.server/*state* *state*
                ewen.replique.server/*browser-state* *browser-state*]
        (prn (cljs.repl/repl env
                             :read (let [reader (LineNumberingPushbackReader. (StringReader. "\"e\""))]
                                     #(read reader false %2))
                             :prompt (fn [])
                             :need-prompt (constantly false))))
      (t/send transport (response-for msg :status :done)))
    (captured-h msg)))

(defn cljs-repl
  [h]
  (def captured-h h)
  #'cljs-repl*)

(set-descriptor! #'cljs-repl
                 {:requires #{#'session #'ewen.replique.server/replique-server}
                  :expects #{#'interruptible-eval #'wrap-load-file}})


(comment




  (cljs.repl/repl (repl-env)
                  :read (let [reader (LineNumberingPushbackReader. (StringReader. "\"e\""))]
                          #(read reader false %2))
                  :prompt (fn [])
                  :need-prompt (constantly false))

  (:id (meta (:session clojure.tools.nrepl.middleware.interruptible-eval/*msg*)))

  (swap! started-cljs-session conj "090029d5-ff9a-4c1b-91d5-37948e6edb4e")

  (let [session (:id (meta (:session clojure.tools.nrepl.middleware.interruptible-eval/*msg*)))]
    (with-open [conn (nrepl/connect :port 57794)]
      (-> (nrepl/client conn 1000)                          ; message receive timeout required
          (nrepl/client-session :session session)
          (nrepl/message {:op "start-cljs-repl"})
          nrepl/response-values)))

  (let [session (:id (meta (:session clojure.tools.nrepl.middleware.interruptible-eval/*msg*)))]
    (with-open [conn (nrepl/connect :port 57794)]
      (-> (nrepl/client conn 1000)                          ; message receive timeout required
          (nrepl/client-session :session session)
          (nrepl/message {:op "stop-cljs-repl"})
          nrepl/response-values)))


  )
