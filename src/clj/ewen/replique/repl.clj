(ns ewen.replique.repl
  (:require [ewen.replique.browser-env :refer [repl-env]]
            [cljs.repl])
  (:import (java.io StringReader)
           (clojure.lang LineNumberingPushbackReader)))

(comment

  (cljs.repl/repl (repl-env)
                  :read (let [reader (LineNumberingPushbackReader. (StringReader. "\"e\""))]
                          #_#(read reader false %2)
                          (fn [_ request-exit]
                            request-exit))
                  :prompt (fn [])
                  :need-prompt (constantly false))
  )
