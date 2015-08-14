(defproject entangle "0.1.0-SNAPSHOT"
  :description "Keeping remote atoms in sync."
  :url "github.com/rymndhng/entangle"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "0.0-3308"]

                 [org.clojars.rymndhng/clj-diff "1.1.1-SNAPSHOT"]

                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [com.taoensso/timbre "4.0.2"]

                 [aleph "0.4.0"]
                 [compojure "1.3.3"]

                 ;; frontend
                 [hiccup "1.0.5"]
                 [reagent "0.5.0"]]

  :main entangle.daemon
  ;; Contains generated javascripts
  :resource-paths ["resources"]

  :plugins [[lein-cljsbuild "1.0.6"]]

  :cljsbuild {:builds {:app {:source-paths ["src" "src/cljs"]
                             :compiler {:main "cljs.user"
                                        :asset-path "public/js/out"
                                        :output-to  "resources/public/js/app.js"
                                        :output-dir "resources/public/js/out"
                                        :source-map "resources/public/js/out.js.map"
                                        :optimizations :none
                                        :source-maps true}}}}

  :profiles {:dev {:source-paths ["env/dev"]
                   :dependencies [[figwheel "0.2.7"]]
                   :plugins [[lein-figwheel "0.3.7"]]
                   :cljsbuild {:test-commands {"unit-tests" ["node" "target/testable.js"]}
                               :builds {:app {:source-paths ["env/dev"]}
                                        :test {:source-paths ["src" "test"]
                                               :compiler {:output-to "target/testable.js"
                                                          :optimizations :simple
                                                          :pretty-print true}}}}}
             :uberjar {:hooks [leiningen.cljsbuild]
                       :aot :all
                       :omit-source true
                       :cljsbuild {:builds {:app
                                            {:source-paths ["env/prod"]
                                             :compiler
                                             {:optimizations :advanced
                                              :pretty-print false}}}}}})
