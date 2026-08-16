# Jar Analyzer

*Made by Winzys* · [Türkçe README](README.tr.md)

A Minecraft cheat-detection forensics tool for Windows. It finds **every JAR on
every drive**, runs an editable **blacklist** over each class, and flags anything
it cannot read — obfuscated, encrypted, or disguised archives — as **suspicious**.
An archive that refuses to be read is exactly where something worth hiding lives.

Detection works off the parsed **constant pool**, not decompilation, so a
full-disk sweep takes seconds per hundred JARs instead of minutes. Decompiled
source is produced on demand for the one archive you actually open.

---

## What it does

1. **Discovery** — reads the NTFS Master File Table directly (like Everything) for
   a whole-disk sweep in seconds, falling back to a directory walk when not
   elevated.
2. **Detection** — parses each class's constant pool and runs the blacklist over
   it with an Aho-Corasick automaton. Every searchable string — class names,
   members, type references, string literals — is already there in plain text.
3. **Disguise** — reads the first four bytes (and, for prefixed files, the ZIP
   end-of-central-directory record) so a cheat renamed `killaura.jar` → `d3d9.dll`
   is still caught by content, not name.
4. **Recycle bin** — deleted files are kept intact under `$R…` names and are
   scanned too.
5. **Running JVMs** — reads what live Java processes have actually loaded. A cheat
   loaded and then deleted leaves nothing on disk but stays on the JVM's
   classpath. Two routes: the JDK Attach API, and — because a JVM started with
   `-XX:+DisableAttachMechanism` refuses attachment — reading process command
   lines straight from Windows, which no flag can hide.
6. **Verdict** — CLEAN / NOTABLE / SUSPICIOUS / DETECTED / CRITICAL / UNREADABLE,
   with the reason (Encrypted, Obfuscated, Broken…) shown next to it.

Findings are evidence for a human to weigh, not proof of guilt. The blacklist is
tuned to unambiguous terms so vanilla Minecraft, Fabric API and the JDK stay
clean; see the Turkish README for the false-positive rules in detail.

---

## Running

Download the packaged app from **Releases** and run `Jar Analyzer.exe`. It
requests administrator rights, which the fast MFT disk sweep needs. No Java
installation is required — a runtime is bundled.

---

## Building from source

Requires a JDK 17+ (for `javac`, `jar`, `jpackage`). No Maven or Gradle; every
dependency is a plain jar in `lib/`.

```powershell
.\build.ps1            # compile + fat jar  -> build\JarAnalyzer.jar
.\build.ps1 -Run       # ...and launch it
.\build.ps1 -Package   # Windows app image  -> dist\Jar Analyzer\
```

Command-line scan (no UI):

```powershell
java -jar build\JarAnalyzer.jar --scan-all report-dir
java -jar build\JarAnalyzer.jar --scan "<folder-or-jar>" report-dir
```

---

## License

The source is published **for inspection only** — you may read and study it, but
not copy, modify, redistribute or reuse it in your own projects without written
permission. The compiled application may be freely downloaded and run. See
[`LICENSE`](LICENSE).

Third-party components keep their own licenses — CFR (MIT), ASM (BSD-3), Gson
(Apache-2.0), JNA (Apache-2.0 / LGPL-2.1) — and the UI shell derives from
[Luyten](https://github.com/deathmarine/Luyten) (Apache-2.0). Full list and
versions in [`THIRD-PARTY.md`](THIRD-PARTY.md).

**Release exe SHA-256:**
`fcbe4eccd024332aa69f1b28cc5574074f6d2d0d83224a956d1df0e3d76a53b6`
(Help → About shows the running copy's hash; if they match, your copy is
unmodified.)
