(defproject ewen.replique/replique-repl "0.0.1-SNAPSHOT"
            :description ""
            :url ""
            :license {:name "Eclipse Public License"
                      :url "http://www.eclipse.org/legal/epl-v10.html"}
            :min-lein-version "2.0.0"
            :source-paths ["src/clj" "src/cljs"]
            :test-paths ["test/clj"]
            :resource-paths ["resources"]
            :dependencies [[org.clojure/clojure "1.7.0-alpha5"]
                           [org.clojure/clojurescript "0.0-SNAPSHOT"]
                           [org.clojure/tools.nrepl "0.2.6"]])