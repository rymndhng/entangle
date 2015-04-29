(defproject entangle "0.1.0-SNAPSHOT"
  :description "Keeping remote atoms in sync."
  :url "github.com/rymndhng/entangle"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-beta1"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [clj-diff "1.0.0-SNAPSHOT"]
                 [com.taoensso/timbre "3.4.0"]

                 ;; TODO: move these out once I get the sample project going
                 [aleph "0.4.0"]
                 [compojure "1.3.3"]]
  )
