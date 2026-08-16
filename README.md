# Jar Analyzer

*Made by Winzys*

Minecraft hile tespit aracı. Bilgisayardaki **tüm disklerdeki tüm JAR dosyalarını**
bulur, her sınıfın içini blacklist ile tarar ve okunamayan arşivleri —
obfuscate edilmiş, şifrelenmiş veya uzantısı gizlenmiş — **şüpheli** olarak işaretler.

## Ne yapar

- **Tüm diskleri tarar.** NTFS ana dosya tablosunu doğrudan okur, saniyeler sürer.
- **Sınıfların içine bakar.** JAR adı, içindeki sınıf yolları ve her sınıfın
  constant pool'u (isimler, metotlar, metin sabitleri) taranır.
- **Uzantıya kanmaz.** `doomsday.jar` → `d3d9.dll` yapılsa bile içeriğinden bulur.
  Başına çöp bayt eklenmiş arşivleri de yakalar.
- **Geri dönüşüm kutusunu tarar.** Silinmiş dosyalar da incelenir.
- **Çalışan oyunu tarar.** Açık Java süreçlerinin gerçekte ne yüklediğini okur —
  hile açılıp dosyası silinse bile görünür. Attach mekanizması kapatılmış
  (`-XX:+DisableAttachMechanism`) süreçler de Windows üzerinden bulunur.
- **Kodu okutur.** Şüpheli bir JAR'a çift tıklayınca decompile edilir; bulunan
  hile terimleri kodun içinde kırmızı ile işaretlenir. `Ctrl+F` ile arama yapılır.

## Sonuçlar

| Sonuç | Anlamı |
|---|---|
| `TEMİZ` | Sorunsuz okundu, eşleşme yok |
| `DİKKAT` | Zayıf/genel bir eşleşme var |
| `ŞÜPHELİ` | Okunamadı — obfuscate, şifreli veya bozuk |
| `TESPİT` | Blacklist terimi kodun içinde bulundu |
| `KRİTİK` | Hem eşleşme var hem arşiv analize direndi |

Bulgular bir insanın değerlendirmesi için kanıttır, kesin suç değildir. Blacklist
yalnızca tek başına tuhaf olan terimleri içerir; değiştirilmemiş Minecraft, Fabric
API ve JDK temiz kalır.

## Kurulum

[**Releases**](../../releases) bölümünden ZIP'i indir, aç, `Jar Analyzer.exe`
dosyasını çalıştır. Java kurulu olmasına gerek yok. Hızlı disk taraması için
yönetici olarak açılır.

## Kaynaktan derleme

JDK 17+ gerekir. Maven/Gradle yok.

```powershell
.\build.ps1            # derle -> build\JarAnalyzer.jar
.\build.ps1 -Run       # derle ve çalıştır
.\build.ps1 -Package   # exe üret -> dist\Jar Analyzer\
```

Komut satırından tarama:

```powershell
java -jar build\JarAnalyzer.jar --scan-all rapor-klasoru
java -jar build\JarAnalyzer.jar --scan "<klasör-veya-jar>" rapor-klasoru
```

## Lisans

Kaynak kod **inceleme amaçlıdır**: okuyabilirsin, ama izinsiz kopyalayamaz,
değiştiremez veya kendi projende kullanamazsın. Derlenmiş uygulama serbestçe
indirilip çalıştırılabilir. Bkz. [`LICENSE`](LICENSE) ve
[`THIRD-PARTY.md`](THIRD-PARTY.md).

**Exe SHA-256:** `fcbe4eccd024332aa69f1b28cc5574074f6d2d0d83224a956d1df0e3d76a53b6`
(Yardım → Hakkında çalışan kopyanın hash'ini gösterir.)

---

# English

Minecraft cheat-detection tool for Windows. Finds **every JAR on every drive**,
runs a blacklist through the inside of every class, and flags archives it cannot
read — obfuscated, encrypted, or disguised — as **suspicious**.

## What it does

- **Scans all drives.** Reads the NTFS Master File Table directly; takes seconds.
- **Looks inside classes.** The JAR name, entry paths, and each class's constant
  pool (names, methods, string literals) are all scanned.
- **Ignores the extension.** A cheat renamed `doomsday.jar` → `d3d9.dll` is still
  found by content, as are archives with junk bytes prepended.
- **Scans the recycle bin.** Deleted files are inspected too.
- **Scans the running game.** Reads what live Java processes actually loaded — a
  cheat stays visible even if its file was deleted after launch. Processes started
  with `-XX:+DisableAttachMechanism` are still found via Windows.
- **Shows you the code.** Double-click a suspicious JAR to decompile it; matched
  cheat terms are highlighted in the source. `Ctrl+F` to search.

## Verdicts

| Verdict | Meaning |
|---|---|
| `CLEAN` | Read fine, no matches |
| `NOTABLE` | A weak or generic match |
| `SUSPICIOUS` | Unreadable — obfuscated, encrypted or broken |
| `DETECTED` | A blacklist term was found in the code |
| `CRITICAL` | Both a match and an archive that resisted analysis |

Findings are evidence for a person to weigh, not proof of guilt. The blacklist
holds only terms that are strange on their own, so unmodified Minecraft, Fabric
API and the JDK stay clean.

## Install

Download the ZIP from [**Releases**](../../releases), extract it and run
`Jar Analyzer.exe`. No Java installation needed. It requests administrator rights
for the fast disk sweep.

## Build from source

Requires JDK 17+. No Maven or Gradle.

```powershell
.\build.ps1            # compile -> build\JarAnalyzer.jar
.\build.ps1 -Run       # compile and launch
.\build.ps1 -Package   # produce exe -> dist\Jar Analyzer\
```

Command-line scan:

```powershell
java -jar build\JarAnalyzer.jar --scan-all report-dir
java -jar build\JarAnalyzer.jar --scan "<folder-or-jar>" report-dir
```

## License

The source is published **for inspection only** — you may read it, but not copy,
modify or reuse it without permission. The compiled application may be freely
downloaded and run. See [`LICENSE`](LICENSE) and [`THIRD-PARTY.md`](THIRD-PARTY.md).

**Exe SHA-256:** `fcbe4eccd024332aa69f1b28cc5574074f6d2d0d83224a956d1df0e3d76a53b6`
(Help → About shows the running copy's hash.)
