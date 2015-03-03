(set-env! :dependencies '[[org.clojure/tools.reader "0.8.15"]
                          [org.clojure/tools.nrepl "0.2.7"]
                          [boot/core "2.0.0-rc10"]
                          [boot/worker "2.0.0-rc10"]
                          [org.clojure/clojurescript "0.0-SNAPSHOT"]
                          [ewen.boot/boot-misc "0.0.1" :scope "test"]
                          [ewen.boot/boot-maven "0.0.1" :scope "test"]
                          [ewen.boot/boot-checkouts "0.0.1-SNAPSHOT" :scope "test"]]
          :source-paths #{"src/clj" "src/cljs"}
          :test-paths #{"test/clj"})

(require '[ewen.boot.boot-maven :refer [gen-pom]]
         '[ewen.boot.boot-misc :refer [add-src]]
         '[ewen.boot.boot-checkouts :refer [checkouts]])

(require '[clojure.tools.nrepl.server :refer [start-server default-handler]]
         '[boot.repl-server])






(deftask nrepl []
         (let [start (delay (start-server :bind "127.0.0.1"
                                          :port 57794
                                          #_:transport-fn #_multi-transport
                                          :handler (default-handler (@#'boot.repl-server/wrap-init-ns 'boot.user))))]
           (with-pre-wrap fileset
                          @start
                          (println "nRepl server started")
                          fileset)))

(deftask run []
         (comp (checkouts) (gen-pom) (nrepl) (wait)))

(deftask install-jar []
         (comp (add-src) (pom) (jar) (install)))

(let [project-info {:project 'ewen.replique/replique-repl
                    :version "0.0.1-SNAPSHOT"}]
  (task-options!
    gen-pom project-info
    pom project-info))
