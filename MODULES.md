# MLPd modules - how users add their own (without editing build.xml)

## The principle

`build.xml` and `module.xml` are **framework source code**. A user adding a
module should never open them — the same way you don't edit `javac` to compile a
new class. The extension point is the **filesystem + a service contract**, not
the build script.

---

## Tier 1 - source drop-in (module authors who build from source)

**Contract:** a module is a directory under `src/modules/<name>/` containing

```
src/modules/<name>/<Name>Module.java      # package modules.<name>;  implements org.joedog.ann.Module
src/modules/<name>/*.xml                   # trained network file(s), optional
src/modules/<name>/*.java                  # any helper classes, optional
```

**To add one:** drop the directory in, run `ant` (or `ant modules`). No edits to
any build file. The build:

1. auto-discovers every `src/modules/*/` directory,
2. compiles it against `mlp-lite` + `gson`,
3. generates the SPI descriptor `META-INF/services/org.joedog.ann.Module`,
4. packages `lib/modules/<name>.jar`,
5. copies the trained `*.xml` to `out/modules/<name>/`.

Discovery is done natively with `<subant genericantfile="module.xml">` over a
`<dirset>` - the Ant equivalent of the shell script's `for dir in .../*/` loop,
with **no third-party Ant tasks** (no ant-contrib).

### Minimal module skeleton

```java
// src/modules/greeter/GreeterModule.java
package modules.greeter;

import org.joedog.ann.Module;

public class GreeterModule implements Module {
    @Override public String name() { return "greeter"; }
    // ... whatever the Module SPI actually requires (load(), evaluate(), etc.)
}
```

```
src/modules/greeter/greeter.xml     # its trained net, if any
```

```
$ ant modules
   [echo] built greeter -> lib/modules/greeter.jar (provider modules.greeter.GreeterModule)
```

That is the whole workflow. The name of the `*Module.java` file is what the
build reads to form the provider class name, so `fraud` - `FraudModule`,
`greeter` -`GreeterModule`, with no case-conversion tricks.

---

## Tier 2 - binary drop-in (end users, no build tool at all)

This is the tier I'd recommend you promote for third parties. Because modules
self-register through Java's `ServiceLoader` SPI, a **prebuilt module jar is a
true plugin**: the server finds it with zero configuration.

**Contract:** drop a prebuilt `<name>.jar` into a `modules/` (or `lib/modules/`)
directory and (re)start the server.

At startup the server does:

```java
for (Module m : ServiceLoader.load(Module.class)) {
    register(m);          // m.name(), m.load("<name>.xml"), etc.
}
```

- If `modules/` is on the classpath (e.g. `java -cp "MLPd.jar:lib/mlp-lite-*.jar:modules/*" ...`),
  `ServiceLoader.load(Module.class)` already discovers everything. **Nothing else to do.**
- If you want a fixed drop folder scanned even when it's *not* on the classpath,
  load it with a `URLClassLoader` at startup:

```java
File dir = new File("modules");
URL[] urls = Optional.ofNullable(dir.listFiles((d,n) -> n.endsWith(".jar")))
                     .stream().flatMap(Arrays::stream)
                     .map(f -> uncheck(() -> f.toURI().toURL()))
                     .toArray(URL[]::new);
ClassLoader cl = new URLClassLoader(urls, Main.class.getClassLoader());
for (Module m : ServiceLoader.load(Module.class, cl)) register(m);
```

That gives you the "download a plugin, drop it in `modules/`, restart" UX with
no build tool, no source, and no edits to anything.

I verified the SPI path end-to-end against this build's output:

```
$ java -cp "MLPd.jar:lib/mlp-lite-1.1.3.jar:lib/modules/*" org.joedog.mlpd.Main
loaded module: fraud
loaded module: adult
```

---

## Why this beats "edit build.xml"

| | edit build.xml | convention + SPI (this design) |
|---|---|---|
| user touches framework source | yes (risky) | no |
| per-module boilerplate | a target each | none |
| add a module | edit + rebuild | drop dir / drop jar |
| third-party binary plugins | not possible | first-class |
| merge conflicts on build.xml | frequent | never |

The build file stays immutable; the module set is data, discovered at build time
(Tier 1) and again at run time (Tier 2).

---

## Files in this delivery

| file | role |
|---|---|
| `mlpd-build.xml` | main build — **rename to `build.xml`** in the MLPd project root. Targets: `all` (default), `clean`, `compile`, `server`, `modules`. |
| `module.xml` | generic per-module build invoked by `<subant>`. Framework-owned; place next to `build.xml`. Users never edit it. |

### Build commands

```bash
ant            # clean + server jar + all modules   (default target: all)
ant server     # just the runnable MLPd.jar
ant modules    # just (re)build the drop-in modules
ant clean
```

### Notes / assumptions
- Targets `--release 22` (matches `build.sh`); override with `-Djava.release=NN`.
- Dependency versions come from `mlp.version` (1.1.3) and `gson.version`
  (2.11.0) properties — bump them in one place if you upgrade.
- The server jar `MLPd.jar` is *thin* (like `build.sh`): run it with
  `mlp-lite` + `lib/modules/*` on the classpath. Say the word if you'd rather
  ship a fat jar with `mlp-lite` unpacked inside.
- `module.xml` uses Ant's native `<jar><service>` element to write the SPI
  descriptor, so there is no hand-maintained `META-INF/services` file anywhere.
