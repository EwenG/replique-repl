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
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [interruptible-eval *msg*]]

            [clojure.tools.nrepl.middleware.session :refer [session]]
            [clojure.tools.nrepl.middleware.load-file :refer [wrap-load-file]]
            [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api])
  (:import (java.io StringReader Writer)
           (clojure.lang LineNumberingPushbackReader)))





(defonce started-cljs-session (atom #{}))

;Use wrap-cljs-repl* as a var instead of a function to enable code reloading at the REPL.
;Maybe there is a better way?
(declare captured-h)
(declare captured-executor)

(defn evaluate
  [bindings {:keys [code ns transport session eval] :as msg}]
  (let [explicit-ns-binding (when-let [ns (and ns (-> ns symbol ana-api/find-ns))]
                              {#'ana/*cljs-ns* ns})
        original-ns (bindings #'ana/*cljs-ns*)
        maybe-restore-original-ns (fn [bindings]
                                    (if-not explicit-ns-binding
                                      bindings
                                      (assoc bindings #'ana/*cljs-ns* original-ns)))
        bindings (atom (merge bindings explicit-ns-binding))
        session (or session (atom nil))
        out (@bindings #'*out*)
        err (@bindings #'*err*)
        env (get @bindings #'ewen.replique.server/browser-env)]
    (if (and ns (not explicit-ns-binding))
      (t/send transport (response-for msg {:status #{:error :namespace-not-found :done}}))
      (with-bindings @bindings
        (try
          (cljs.repl/repl env
            :eval (if eval (find-var (symbol eval)) #'cljs.repl/eval-cljs)
            ;; clojure.main/repl paves over certain vars even if they're already thread-bound
            #_:init #_#(do (set! *compile-path* (@bindings #'*compile-path*))
                       (set! *1 (@bindings #'*1))
                       (set! *2 (@bindings #'*2))
                       (set! *3 (@bindings #'*3))
                       (set! *e (@bindings #'*e)))
            :read (if (string? code)
                    (let [reader (LineNumberingPushbackReader. (StringReader. code))]
                      #(read reader false %2))
                    (let [code (.iterator ^Iterable code)]
                      #(or (and (.hasNext code) (.next code)) %2)))
            :prompt (fn [])
            :need-prompt (constantly false)
            ; TODO pretty-print?
            :print (fn [v]
                     #_(reset! bindings (assoc (capture-thread-bindings)
                                        #'*3 *2
                                        #'*2 *1
                                        #'*1 v))
                     (.flush ^Writer err)
                     (.flush ^Writer out)
                     (reset! session (maybe-restore-original-ns @bindings))
                     (t/send transport (response-for msg
                                                     {:value v
                                                      :ns (-> ana/*cljs-ns* ns-name str)})))
            ; TODO customizable exception prints
            #_:caught #_(fn [e]
                      (let [root-ex (#'clojure.main/root-cause e)]
                        (when-not (instance? ThreadDeath root-ex)
                          (reset! bindings (assoc (#'clojure.tools.nrepl.middleware.interruptible-eval/capture-thread-bindings) #'*e e))
                          (reset! session (maybe-restore-original-ns @bindings))
                          (t/send transport (response-for msg {:status :eval-error
                                                               :ex (-> e class str)
                                                               :root-ex (-> root-ex class str)}))
                          (clojure.main/repl-caught e)))))
          (finally
            (.flush ^Writer out)
            (.flush ^Writer err)))))
    (maybe-restore-original-ns @bindings)))

#_(defn cljs-repl*
  [{:keys [op session transport cljs] :as msg}]
  (if (and (get @started-cljs-session (:id (meta session)))
           (= op "eval"))
    (let [env (get @session #'ewen.replique.server/browser-env)
          *state* (get @session #'ewen.replique.server/*state*)
          *browser-state* (get @session #'ewen.replique.server/*browser-state*)]
      (binding [ewen.replique.server/*state* *state*
                ewen.replique.server/*browser-state* *browser-state*]
        (try (prn (cljs.repl/repl env
                                  :read (let [reader (LineNumberingPushbackReader. (StringReader. "\"e\""))]
                                          #(read reader false %2))
                                  :prompt (fn [])
                                  :need-prompt (constantly false)))
             (catch Exception e
               (.printStackTrace e)
               (t/send transport (response-for msg :status :done)))))
      (t/send transport (response-for msg :status :done)))
    (captured-h msg)))

(defn cljs-repl*
  [{:keys [op session interrupt-id id transport] :as msg}]
  (cond (and (get @started-cljs-session (:id (meta session)))
             (= op "eval"))
        (if-not (:code msg)
          (t/send transport (response-for msg :status #{:error :no-code}))
          (#'clojure.tools.nrepl.middleware.interruptible-eval/queue-eval session captured-executor
            (fn []
              (alter-meta! session assoc
                           :thread (Thread/currentThread)
                           :eval-msg msg)
              (binding [*msg* msg]
                (evaluate @session msg)
                (t/send transport (response-for msg :status :done))
                (alter-meta! session dissoc :thread :eval-msg)))))

        (and (get @started-cljs-session (:id (meta session)))
             (= op "interrupt"))
        ; interrupts are inherently racy; we'll check the agent's :eval-msg's :id and
        ; bail if it's different than the one provided, but it's possible for
        ; that message's eval to finish and another to start before we send
        ; the interrupt / .stop.
        (let [{:keys [id eval-msg ^Thread thread]} (meta session)]
          (if (or (not interrupt-id)
                  (= interrupt-id (:id eval-msg)))
            (if-not thread
              (t/send transport (response-for msg :status #{:done :session-idle}))
              (do
                ; notify of the interrupted status before we .stop the thread so
                ; it is received before the standard :done status (thereby ensuring
                ; that is stays within the scope of a clojure.tools.nrepl/message seq
                (t/send transport {:status  #{:interrupted}
                                   :id      (:id eval-msg)
                                   :session id})
                (.stop thread)
                (t/send transport (response-for msg :status #{:done}))))
            (t/send transport (response-for msg :status #{:error :interrupt-id-mismatch :done}))))
        :esle (captured-h msg)))

(defn cljs-repl
  [h & {:keys [executor] :or {executor (#'clojure.tools.nrepl.middleware.interruptible-eval/configure-executor)}}]
  (def captured-h h)
  (def captured-executor executor)
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

  (swap! started-cljs-session conj "9c52dbf5-499e-4af6-8d4e-c18aeb04b2e3")
  (reset! started-cljs-session #{})



  )
