(ns ewen.replique.repl
  (:require [ewen.replique.browser-env :refer []]
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


(defn connection
  [session]
  (let [p    (promise)
        {:keys [transport msg] :as conn} (get-in @session [#'cljs.repl.server/state :connection])]
    (if conn
      (do
        (deliver p conn)
        p)
      (do
        (swap! session assoc-in [#'cljs.repl.server/state :promised-conn] p)
        p))))

(defn send-for-eval [{:keys [transport msg]}]
  (prn (str "transport " transport))
  (prn (str "msg " msg))
  (t/send transport (response-for msg :status :done
                                      :body "ignore__")))


(defonce started-cljs-session (atom #{}))

;Use wrap-cljs-repl* as a var instead of a function to enable code reloading at the REPL.
;Maybe there is a better way?
(declare captured-h)

(defn cljs-repl*
  [{:keys [op session transport cljs] :as msg}]
  (if (and (get @started-cljs-session (:id (meta session)))
           (= op "eval"))
    (do (send-for-eval @(connection session))
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

  (swap! started-cljs-session conj "3d68ac19-e3b8-4fbb-a7c8-da52dd6ee882")

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
