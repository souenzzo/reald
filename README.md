# REALD

> Read Eval Analyse Loop Daemon

## Hacking

- Clone the repo `git clone https://github.com/souenzzo/reald`

- Enter the directory `cd reald`

- Install npm deps `npm install`

- Start a REPL for development `clj -A:test:cljs`

- Start developing server:
 
```clojure
((requiring-resolve `reald.dev/-main))
```

