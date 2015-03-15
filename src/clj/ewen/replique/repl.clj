(ns ewen.replique.repl
  (:require [ewen.replique.browser-env :refer []]
            [ewen.replique.server :refer []]
            [cljs.repl :as repl]
            [cljs.repl.browser :as benv]
            [cljs.env :as env]
            [clojure.tools.nrepl
             [transport :as t]
             [middleware :refer [set-descriptor!]]
             [misc :refer [response-for]]]
            [clojure.tools.nrepl :as nrepl]
            [clojure.tools.nrepl.middleware.interruptible-eval :refer [interruptible-eval *msg*]]

            [clojure.tools.nrepl.middleware.session :refer [session]]
            [clojure.tools.nrepl.middleware.load-file :refer [wrap-load-file]]
            [cljs.analyzer :as ana]
            [cljs.analyzer.api :as ana-api]
            [clojure.tools.reader.reader-types :as readers]
            [clojure.java.io :as io]
            [cljs.util :as util]
            [cljs.compiler :as comp]
            [cljs.tagged-literals :as tags]
            [cljs.closure :as cljsc]
            [clojure.tools.reader :as reader])
  (:import (java.io StringReader Writer PushbackReader FileWriter)
           (clojure.lang LineNumberingPushbackReader)))





(defonce started-cljs-session (atom #{}))

;Use wrap-cljs-repl* as a var instead of a function to enable code reloading at the REPL.
;Maybe there is a better way?
(declare captured-h)
(declare captured-executor)

(defn repl*
  [repl-env {:keys [init need-prompt prompt flush read eval print caught reader
                    print-no-newline source-map-inline wrap repl-requires]
             :or   {init              #()
                    need-prompt       #(if (readers/indexing-reader? *in*)
                                        (== (readers/get-column-number *in*) 1)
                                        (identity true))
                    prompt            repl/repl-prompt
                    flush             flush
                    read              repl/repl-read
                    eval              @#'cljs.repl/eval-cljs
                    print             println
                    caught            repl/repl-caught
                    reader            #(readers/source-logging-push-back-reader
                                        (PushbackReader. (io/reader *in*))
                                        1 "NO_SOURCE_FILE")
                    print-no-newline  print
                    source-map-inline true
                    repl-requires     '[[cljs.repl :refer-macros [source doc find-doc apropos dir pst]]]}
             :as   opts}]
  (let [repl-opts (repl/-repl-options repl-env)
        repl-requires (into repl-requires (:repl-requires repl-opts))
        {:keys [analyze-path repl-verbose warn-on-undeclared special-fns static-fns] :as opts
         :or   {warn-on-undeclared true}}
        (merge
          {:cache-analysis true :source-map true}
          (cljsc/add-implicit-options
            (merge-with (fn [a b] (if (nil? b) a b))
                        repl-opts
                        opts
                        {:init init
                         :need-prompt prompt
                         :flush flush
                         :read read
                         :print print
                         :caught caught
                         :reader reader
                         :print-no-newline print-no-newline
                         :source-map-inline source-map-inline})))]
    (env/with-compiler-env
      (or (::env/compiler repl-env) (env/default-compiler-env opts))
      (binding [#_ana/*cljs-ns* #_'cljs.user                ;Don't reset the cljs-ns every time repl* is called
                repl/*cljs-verbose* repl-verbose
                ana/*cljs-warnings*
                (assoc ana/*cljs-warnings*
                  :unprovided warn-on-undeclared
                  :undeclared-var warn-on-undeclared
                  :undeclared-ns warn-on-undeclared
                  :undeclared-ns-form warn-on-undeclared)
                ana/*cljs-static-fns* static-fns
                repl/*repl-opts* opts]
        ;; TODO: the follow should become dead code when the REPL is
        ;; sufficiently enhanced to understand :cache-analysis - David
        (let [env {:context :expr :locals {}}
              special-fns (merge repl/default-special-fns special-fns)
              is-special-fn? (set (keys special-fns))
              request-prompt (Object.)
              request-exit (Object.)
              opts (try
                     (if-let [merge-opts (:merge-opts (repl/-setup repl-env opts))]
                       (merge opts merge-opts)
                       opts)
                     (catch Throwable e
                       (caught e repl-env opts)
                       opts))
              read-eval-print
              (fn []
                (let [input (binding [*ns* (create-ns ana/*cljs-ns*)
                                      reader/*data-readers* tags/*cljs-data-readers*
                                      reader/*alias-map*
                                      (apply merge
                                             ((juxt :requires :require-macros)
                                               (ana/get-namespace ana/*cljs-ns*)))]
                              (read request-prompt request-exit))]
                  (or ({request-exit request-exit
                        :cljs/quit request-exit
                        request-prompt request-prompt} input)
                      (if (and (seq? input) (is-special-fn? (first input)))
                        (do
                          ((get special-fns (first input)) repl-env env input opts)
                          (print nil))
                        (let [value (eval repl-env env input opts)]
                          (print value))))))]
          (comp/with-core-cljs opts
                               (fn []
                                 (binding [repl/*repl-opts* opts]
                                   (try
                                     (init)
                                     (when analyze-path
                                       (repl/analyze-source analyze-path opts))
                                     ;This makes a lot of env eval calls each time repl* is called
                                     ;Commented out for the moment. Need to find a better solution
                                     ;The user can still require the repl-require functions/macros by hand
                                     #_(repl/evaluate-form repl-env env "<cljs repl>"
                                                    (with-meta
                                                      `(~'ns ~'cljs.user
                                                         (:require ~@repl-requires))
                                                      {:line 1 :column 1})
                                                    identity opts)
                                     (catch Throwable e
                                       (caught e repl-env opts)))
                                   (when-let [src (:watch opts)]
                                     (future
                                       (let [log-file (io/file (util/output-directory opts) "watch.log")]
                                         (print (str "Watch compilation log available at:" log-file))
                                         (flush)
                                         (try
                                           (let [log-out (FileWriter. log-file)]
                                             (binding [*err* log-out
                                                       *out* log-out]
                                               (cljsc/watch src (dissoc opts :watch))))
                                           (catch Throwable e
                                             (caught e repl-env opts))))))
                                   ;; let any setup async messages flush
                                   (Thread/sleep 50)
                                   (binding [*in* (if (true? (:source-map-inline opts))
                                                    *in*
                                                    (reader))]
                                     (when (need-prompt)
                                       (print (str "To quit, type:" :cljs/quit))
                                       (prompt)
                                       (flush))
                                     (loop []
                                       (when-not
                                         (try
                                           (identical? (read-eval-print) request-exit)
                                           (catch Throwable e
                                             (caught e repl-env opts)
                                             nil))
                                         (when (need-prompt)
                                           (prompt)
                                           (flush))
                                         (recur))))))))
        (repl/-tear-down repl-env)))))

(defn repl
  [repl-env & opts]
  (assert (even? (count opts))
          "Arguments after repl-env must be interleaved key value pairs")
  (repl* repl-env (apply hash-map opts)))

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
          (repl env
            :eval (if eval (find-var (symbol eval)) #'cljs.repl/eval-cljs)
            :read (if (string? code)
                    (let [reader (LineNumberingPushbackReader. (StringReader. code))]
                      #(read reader false %2))
                    (let [code (.iterator ^Iterable code)]
                      #(or (and (.hasNext code) (.next code)) %2)))
            :prompt (fn [])
            :need-prompt (constantly false)
            ; TODO pretty-print?
            :print (fn [v]
                     (.flush ^Writer err)
                     (.flush ^Writer out)
                     (reset! session (-> (assoc @bindings #'ana/*cljs-ns* ana/*cljs-ns*)
                                         maybe-restore-original-ns))
                     (t/send transport (response-for msg
                                                     {:value (if (nil? v) "nil" v)
                                                      :ns    (-> ana/*cljs-ns* str)})))
            ; TODO customizable exception prints
            :caught (fn [e env opts]
                      (let [root-ex (#'clojure.main/root-cause e)]
                        (when-not (instance? ThreadDeath root-ex)
                          (reset! bindings (assoc (#'clojure.tools.nrepl.middleware.interruptible-eval/capture-thread-bindings) #'*e e))
                          (reset! session (maybe-restore-original-ns @bindings))
                          (t/send transport (response-for msg {:status :eval-error
                                                               :ex (-> e class str)
                                                               :root-ex (-> root-ex class str)}))
                          (cljs.repl/repl-caught e env opts)))))
          (finally
            (.flush ^Writer out)
            (.flush ^Writer err)))))
    (maybe-restore-original-ns @bindings)))

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



  (:id (meta (:session clojure.tools.nrepl.middleware.interruptible-eval/*msg*)))

  (swap! started-cljs-session conj "01f8a3bc-9403-4254-a353-2c2b02612cba")
  (reset! started-cljs-session #{})



  )
