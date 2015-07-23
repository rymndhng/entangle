(defproject entangle "0.1.0-SNAPSHOT"
  :description "Keeping remote atoms in sync."
  :url "github.com/rymndhng/entangle"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "0.0-3308"]

                 [org.clojars.rymndhng/clj-diff "1.1.1-SNAPSHOT"]

                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [com.taoensso/timbre "3.4.0"]

                 [aleph "0.4.0"]
                 [compojure "1.3.3"]

                 ;; frontend
                 [hiccup "1.0.5"]
                 [figwheel "0.2.7"]]

  :plugins [[lein-cljsbuild "1.0.6"]
            [lein-figwheel "0.3.7"]]

  :cljsbuild {:builds [{:id "local"
                        :source-paths ["src"]
                        :compiler {:output-to  "resources/public/js/app.js"
                                   :output-dir "resources/public/js/out"
                                   :source-map "resources/publis/js/out.js.map"
                                   :optimizations :none
                                   :source-maps true}}
                       {:id "test"
                        :source-paths ["src" "test"]
                        :compiler {:output-to "target/testable.js"
                                   :optimizations :simple
                                   :pretty-print true}}]
              :test-commands {"unit-tests" ["node" "target/testable.js"]}})
