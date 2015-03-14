(ns ewen.replique.server
  (:refer-clojure :exclude [loaded-libs])
  (:require [clojure.tools.nrepl
             [transport :as t]
             [middleware :refer [set-descriptor!]]
             [misc :refer [response-for]]]
            [cljs.repl.browser :as benv]
            [clojure.java.io :as io]
            [cljs.closure :as cljsc]
            [ewen.replique.browser-env :as rbenv]
            [clojure.tools.reader.edn :as edn]))



(declare ^:dynamic loaded-libs)
(declare ^:dynamic ordering)
(declare ^:dynamic state)



(defn send-repl-index
  [{:keys [headers transport session] :as msg}]
  (let [url (format "http://%s/start?session=%s"
                    (:host headers)
                    (-> (meta session) :id))
        client-js (-> (get-in @session [#'rbenv/browser-state :client-js])
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
        client-js (-> (get-in @session [#'rbenv/browser-state :client-js])
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





(defmethod handle-msg "connect"
  [{:keys [transport session] :as msg}]
  (rbenv/init-env! session)
  (send-repl-index msg))

(defmethod handle-msg "start"
  [{:keys [transport session] :as msg}]
  (rbenv/init-env! session)
  (send-repl-client-page msg))

(defmethod handle-msg "ready"
  [{:keys [transport session] :as msg}]
  (let [preloaded-libs (get @session #'rbenv/preloaded-libs)]
    (swap! session assoc #'loaded-libs preloaded-libs)
    (swap! session assoc #'ordering (agent {:expecting nil :fns {}}))
    (swap! session assoc #'state {:socket        nil
                                  :connection    nil
                                  :promised-conn nil})
    (swap! session assoc-in [#'rbenv/browser-state :return-value-fn] nil)
    (t/send transport (response-for msg
                                    :status :done
                                    :headers {:Content-Type "test/javascript"}
                                    :body
                                    (cljs.env/with-compiler-env rbenv/compiler-env
                                                                (cljsc/-compile
                                                                  '[(ns cljs.user)
                                                                    (set! *print-fn* clojure.browser.repl/repl-print)] {}))))))

(defn set-connection [session transport msg]
  (if-let [promised-conn (get-in @session [#'state :promised-conn])]
    (do
      (swap! session
             (fn [old]
               (-> old
                   (assoc-in [#'state :connection] nil)
                   (assoc-in [#'state :promised-conn] nil))))
      (deliver promised-conn {:transport transport :msg msg}))
    (swap! session assoc-in [#'state :connection]
           {:transport transport :msg msg})))


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
  (let [browser-state (get @session #'rbenv/browser-state)
        {:keys [order content]} (edn/read-string content)
        ordering (get @session #'ordering)]
    (constrain-order ordering order
                     (fn []
                       (when-let [f (:return-value-fn browser-state)]
                         (f content))
                       (set-connection session transport msg)))))

(defmethod handle-msg "print"
  [{:keys [transport session content] :as msg}]
  (let [{:keys [order content]} (edn/read-string content)]
    (benv/constrain-order order
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
