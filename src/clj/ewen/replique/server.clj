(ns ewen.replique.server
  (:refer-clojure :exclude [loaded-libs])
  (:require [clojure.tools.nrepl
             [transport :as t]
             [middleware :refer [set-descriptor!]]
             [misc :refer [response-for]]]
            [cljs.repl :as repl]
            [cljs.repl.browser :as benv]
            [cljs.env :as env]
            [clojure.java.io :as io]
            [cljs.closure :as cljsc]
            [clojure.tools.reader.edn :as edn]))



(declare ^:dynamic loaded-libs)
(declare ^:dynamic ordering)

#_{:socket        nil
   :connection    (atom nil)
   :promised-conn (atom nil)}
(declare ^:dynamic *state*)

#_{:client-js "target/out/client.js"
   :return-fn (atom (fn []))}
(declare ^:dynamic *browser-state*)
(declare ^:dynamic browser-env)
(declare ^:dynamic preloaded-libs)












(defn connection
  []
  (let [p    (promise)
        {:keys [transport msg] :as conn} @(:connection *state*)]
    (if conn
      (do
        (deliver p conn)
        p)
      (do
        (reset! (:promised-conn *state*)  p)
        p))))

(defn set-return-value-fn
  [f]
  (reset! (:return-value-fn *browser-state*) f))

(defn send-for-eval [{:keys [transport msg] :as conn} form return-value-fn]
  (set-return-value-fn return-value-fn)
  (t/send transport (response-for msg
                                  :status :done
                                  :body form)))


(defn env-setup [repl-env opts]
  (println "setup"))


(defn env-evaluate [js]
  (println "evaluate")
  (let [return-value (promise)]
    (send-for-eval @(connection)
                   js
                   (fn [val] (deliver return-value val)))
    (let [ret @return-value]
      (try
        (read-string ret)
        (catch Exception e
          {:status :error
           :value (str "Could not read return value: " ret)})))))

(defn env-load [this provides url]
  (println "load")
  #_(benv/load-javascript this provides url))

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




(defn init-env [session]
  (let [browser-env (merge (BrowserEnv.)
                           {:optimizations  :none
                            :working-dir    (:output-dir opts)
                            :serve-static   true
                            :static-dir     (cond-> ["." "target/out/"]
                                                    (:output-dir opts) (conj (:output-dir opts)))
                            :preloaded-libs []
                            :src            "src/"
                            ::env/compiler  compiler-env
                            :source-map     false}
                           opts)]
    (when (:src browser-env)
      (repl/analyze-source (:src browser-env)))
    browser-env))













(defn send-repl-index
  [{:keys [headers transport session] :as msg}]
  (let [url (format "http://%s/start?session=%s"
                    (:host headers)
                    (-> (meta session) :id))
        client-js (-> (get-in @session [#'*browser-state* :client-js])
                      slurp)]
    (t/send transport (response-for msg
                                    :status :done
                                    :body
                                    (str "<html><head><meta charset=\"UTF-8\"></head><body>
                                    <script type=\"text/javascript\" src=\"goog/base.js\"></script>
                                       <script type=\"text/javascript\">"
                                         client-js
                                         "</script>
                                         <script type=\"text/javascript\">
                                         goog.require('clojure.browser.repl');
                                         </script>
                                         <script type=\"text/javascript\">
                                         window.onload = function() {
                                            clojure.browser.repl.connect(" (pr-str url) ");
                                          }
                                        </script>"
                                         "</body></html>")))))

(defn send-repl-client-page
  [{:keys [headers transport session] :as msg}]
  (let [url (format "http://%s?session=%s"
                    (:host headers)
                    (-> (meta session) :id))
        client-js (-> (get-in @session [#'*browser-state* :client-js])
                      slurp)]
    (t/send transport (response-for msg
                                    :status :done
                                    :body
                                    (str "<html><head><meta charset=\"UTF-8\"></head><body>
                                    <script type=\"text/javascript\" src=\"goog/base.js\"></script>
                                       <script type=\"text/javascript\">"
                                         client-js
                                         "</script>
                                         <script type=\"text/javascript\">
                                         goog.require('clojure.browser.repl.client');
                                         </script>
                                         <script type=\"text/javascript\">
                                          clojure.browser.repl.client.start(" (pr-str url) ");
                                        </script>"
                                         "</body></html>")))))

(defmulti handle-msg :op)

(defmethod handle-msg "replique-ls-sessions"
  [{:keys [transport] :as msg}]
  (let [sessions (-> #'clojure.tools.nrepl.middleware.session/sessions
                     deref
                     deref
                     keys)
        session-links (map #(format "<li><a href=\"http://127.0.0.1:57794/connect?session=%s\">%s</a></li>\n" %1 %1)
                           sessions)
        session-links (apply str session-links)]
    (t/send transport (response-for msg
                                    :status :done
                                    :body
                                    (format "<html><head><meta charset=\"UTF-8\"></head><body>
                                    <ul>%s</ul>
                                    </body></html>"
                                            session-links)))))


(defn init-env! [session env]
  (swap! session assoc #'browser-env env)
  (cljs.env/with-compiler-env
    compiler-env
    (swap! session assoc #'preloaded-libs (set (concat
                                                       (@#'benv/always-preload env)
                                                       (map str (:preloaded-libs env))))))
  (when-not (get-in @session [#'*browser-state* :client-js])
    (cljs.env/with-compiler-env compiler-env
                                (swap! session assoc-in [#'*browser-state* :client-js]
                                       (benv/create-client-js-file
                                         env
                                         (io/file (:working-dir env) "client.js"))))))


(defmethod handle-msg "connect"
  [{:keys [transport session] :as msg}]
  (let [browser-env (init-env session)]
    (init-env! session browser-env))
  (send-repl-index msg))

(defmethod handle-msg "start"
  [{:keys [transport session] :as msg}]
  (let [browser-env (init-env session)]
    (init-env! session browser-env))
  (send-repl-client-page msg))

(defmethod handle-msg "ready"
  [{:keys [transport session] :as msg}]
  (let [preloaded-libs (get @session #'preloaded-libs)]
    (swap! session assoc #'loaded-libs preloaded-libs)
    (swap! session assoc #'ordering (agent {:expecting nil :fns {}}))
    (swap! session assoc #'*state* {:socket        nil
                                    :connection    (atom nil)
                                    :promised-conn (atom nil)})
    (swap! session assoc-in [#'*browser-state* :return-value-fn] (atom nil))
    (t/send transport (response-for msg
                                    :status :done
                                    :headers {:Content-Type "test/javascript"}
                                    :body
                                    (cljs.env/with-compiler-env compiler-env
                                                                (cljsc/-compile
                                                                  '[(ns cljs.user)
                                                                    (set! *print-fn* clojure.browser.repl/repl-print)] {}))))))

(defn set-connection [session transport msg]
  (if-let [promised-conn @(get-in @session [#'*state* :promised-conn])]
    (do
      (let [connection (get-in @session [#'*state* :connection])
            promised-conn (get-in @session [#'*state* :promised-conn])]
        (reset! connection nil)
        (reset! promised-conn nil))
      (deliver promised-conn {:transport transport :msg msg}))
    (let [connection (get-in @session [#'*state* :connection])]
      (reset! connection {:transport transport :msg msg}))))


(defn add-in-order [{:keys [expecting fns]} order f]
  {:expecting (or expecting order)
   :fns (assoc fns order f)})

(defn run-in-order [{:keys [expecting fns]}]
  (loop [order expecting fns fns]
    (if-let [f (get fns order)]
      (do
        (f)
        (recur (inc order) (dissoc fns order)))
      {:expecting order :fns fns})))

(defn constrain-order
  "Elements to be printed in the REPL will arrive out of order. Ensure
  that they are printed in the correct order."
  [ordering order f]
  (send-off ordering add-in-order order f)
  (send-off ordering run-in-order))

(defmethod handle-msg "result"
  [{:keys [transport session content] :as msg}]
  (let [*browser-state* (get @session #'*browser-state*)
        {:keys [order content]} (edn/read-string content)
        ordering (get @session #'ordering)]
    (constrain-order ordering order
                     (fn []
                       (when-let [f @(:return-value-fn *browser-state*)]
                         (f content))
                       (set-connection session transport msg)))))

(defmethod handle-msg "print"
  [{:keys [transport session content] :as msg}]
  (let [{:keys [order content]} (edn/read-string content)]
    (constrain-order ordering order
                          (fn []
                            (binding [*out* (get @session #'*out*)
                                      clojure.tools.nrepl.middleware.interruptible-eval/*msg* nil]
                              (print content)
                              (.flush *out*))))
    (t/send transport (response-for msg
                                    :status :done
                                    :body "ignore__"))))

(defmethod handle-msg :default
  [{:keys [transport path] :as msg}]
  (if (and true #_(:static-dir opts)
           (not= "favicon.ico" (first path)))
    (let [path (if (= 0 (count path)) ["index.html"] (into '() (reverse path)))
          st-dir ["." "target/out/"] #_(:static-dir opts)
          local-path
          (cond->
            (seq (for [x (if (string? st-dir) [st-dir] st-dir)
                       :when (.exists (apply io/file (conj path x)))]
                   (conj path x)))
            (complement nil?) first)]
      (when local-path
        (let [content-type
              (condp #(.endsWith %2 %1) (last path)
                ".html" "text/html"
                ".css" "text/css"
                ".html" "text/html"
                ".jpg" "image/jpeg"
                ".js" "text/javascript"
                ".cljs" "text/x-clojure"
                ".map" "application/json"
                ".png" "image/png"
                "text/plain")]
          (t/send transport (response-for msg
                                          :status :done
                                          :headers {:Content-Type content-type}
                                          :body (slurp (apply io/file local-path)))))))
    (t/send transport (response-for msg :status #{:done :unknow-op :error}))))


;Use replique-server* as a var instead of a function to enable code reloading at the REPL.
;Maybe there is a better way?
(declare captured-h)


(defn cljs-repl* [{:keys [op session transport] :as msg}]
  (if (:http-transport msg)
    (handle-msg msg)
    (captured-h msg)))

(defn replique-server
  [h]
  (def captured-h h)
  #'cljs-repl*)

(set-descriptor! #'replique-server
                 {:requires #{#'clojure.tools.nrepl.middleware.session/session}
                  :handles {"connect"
                            {:doc ""}
                            "start"
                            {:doc ""}
                            "ready"
                            {:doc ""}}})
