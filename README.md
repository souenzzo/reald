# REALD

> Read Eval Analyse Loop Daemon

## Hacking

- Clone the repo `git clone https://github.com/souenzzo/reald`

- Enter the directory `cd reald`

- Start a REPL for development `clj -A:dev:cljs`

- Start developing server:
 
```clojure
(require 'reald.user)
(in-ns 'reald.user)
(-main)
;; After reload the namespace `reald.main` you may 
;; need to call `(-main)` again to see the changes
```

