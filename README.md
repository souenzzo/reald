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

## TODO

- [x] Given a project, show it's `clojure.test/deftest` definitions
- [x] Allow to run singular `deftest's` inside a REPL
- [ ] Given a project, with selected profiles, show it's `clojure.test/deftest` definitions
- [ ] Operation "run last test in REPL"
- [ ] Show files inside classpath and it's namespaces
- [ ] Allow to "load file to REPL"
- [ ] Show issues from kondo
- [ ] When I click on a "kondo issue", it send my editor to it's line/file
- [ ] Use 'EDITOR' command
  Stacktraces
  - [ ] Goto definitions on stacktraces (how to handle JAR's/git deps?)
  - [ ] pprint stacktraces
  - [ ] Highlight the traces from project and "externel" traces
- [ ] Given a var/namespace/symbol, GOTO definition
