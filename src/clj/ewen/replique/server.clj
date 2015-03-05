(ns ewen.replique.server
  (:require [clojure.tools.nrepl
             [transport :as t]
             [middleware :refer [set-descriptor!]]
             [misc :refer [response-for]]]
            [cljs.repl.browser :as benv]))


(defn send-repl-client-page
  [{:keys [headers transport session] :as msg}]
  (t/send transport (response-for msg
                                  :status :done
                                  :body
                                  (str "<html><head><meta charset=\"UTF-8\"></head><body>
          <script type=\"text/javascript\">"
                                       (benv/repl-client-js)
                                       "</script>"
                                       "<script type=\"text/javascript\">
                                        clojure.browser.repl.client.start(\"http://" (:host headers) "\");
          </script>"
                                       "</body></html>"))))

(defmulti handle-msg :op)

(defmethod handle-msg "replique-ls-sessions"
  [{:keys [transport] :as msg}]
  (let [sessions (-> #'clojure.tools.nrepl.middleware.session/sessions
                     deref
                     deref
                     keys)
        session-links (map #(format "<li><a href=\"http://127.0.0.1:57794/start?session=%s\">%s</a></li>\n" %1 %1)
                           sessions)
        session-links (apply str session-links)]
    (t/send transport (response-for msg
                                    :status :done
                                    :body
                                    (format "<html><head><meta charset=\"UTF-8\"></head><body>
                                    <ul>%s</ul>
                                    </body></html>"
                                            session-links)))))

(defmethod handle-msg "start"
  [{:keys [transport] :as msg}]
  (send-repl-client-page msg))

(defmethod handle-msg :default
  [{:keys [transport] :as msg}]
  (t/send transport (response-for msg :status #{:done :unknow-op :error})))


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
