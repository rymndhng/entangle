(require
  '[cljs.repl :as repl]
  '[cljs.repl.node :as node])

(cljs.repl/repl (cljs.repl.node/repl-env))
