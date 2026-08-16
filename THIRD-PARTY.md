# Third-Party Components

Jar Analyzer bundles the following open-source libraries. Each remains under
its own license, reproduced or linked below. These licenses apply only to the
respective components, not to Jar Analyzer's own source code.

| Component | Version | Purpose | License |
|---|---|---|---|
| [CFR](https://github.com/leibnitz27/cfr) | 0.152 | Java decompiler (Decompile tab) | MIT |
| [ASM](https://asm.ow2.io/) | 9.10.1 | Bytecode reading & disassembly | BSD-3-Clause |
| [Gson](https://github.com/google/gson) | 2.11.0 | JSON read/write (settings, blacklist, reports) | Apache-2.0 |
| [JNA](https://github.com/java-native-access/jna) | 5.14.0 | Native calls (NTFS MFT scan, process command lines) | Apache-2.0 / LGPL-2.1 |

The user-interface shell (class tree, tabbed code view, font chooser and
related Swing plumbing) derives from **[Luyten](https://github.com/deathmarine/Luyten)**,
which is licensed under the **Apache License 2.0**.

Full license texts:

- MIT — https://opensource.org/license/mit
- BSD-3-Clause — https://opensource.org/license/bsd-3-clause
- Apache-2.0 — https://www.apache.org/licenses/LICENSE-2.0
- LGPL-2.1 — https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html

JNA is dual-licensed; this project relies on it under the Apache-2.0 terms.
