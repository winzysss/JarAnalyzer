# Jar Analyzer

*Made by Winzys*

Bilgisayardaki **tüm disklerdeki tüm JAR dosyalarını** bulur, **hepsini decompile eder**,
decompile edilen kodun üzerinden **blacklist** terimlerini geçirir ve sonuç üretir.
Decompile edilemeyen, obfuscate edilmiş veya şifrelenmiş bir JAR **şüpheli** olarak
işaretlenir — okunamayan bir arşiv, gizlenecek bir şeyin duracağı yerin ta kendisidir.

Arayüz iskeleti Luyten (Apache-2.0) üzerine kurulu; decompile için CFR kullanılır.

---

## Ne yapar

**1. Keşif.** Önce NTFS **ana dosya tablosu** (MFT) doğrudan okunur — Everything'in
tüm diski saniyeler içinde taramasının sebebi budur. Dizinleri gezmek her dizin
için bir sistem çağrısı demektir ve diski birim boyunca oradan oraya arattırır;
MFT ise NTFS'in bildiği her dosyanın tek ve bitişik bir indeksidir,
`FSCTL_ENUM_USN_DATA` bunu büyük ardışık bloklar halinde akıtır.

Bu **yönetici yetkisi** ister ve uygulama tam da bu yüzden zorunlu yönetici
olarak açılır. Yine de yetki bir şekilde yoksa (örneğin jar doğrudan
çalıştırıldığında) araç sessizce dizin yürüyüşüne düşer — her sürücü kökü için
ayrı bir yürüyücü, symlink takip edilmez, çünkü Windows'taki junction'lar döngü
oluşturur ve tarama hiç bitmez.

MFT'den gelen yollar üst-klasör bağlarından yeniden inşa edilir. Bu inşa hatalıysa
yollar var olmaz — araç bunu sayar ve bir birimin yollarının çoğu çözülemiyorsa o
birimi dizin yürüyüşüne devreder. Sessizce eksik sonuç vermek, yavaş olmaktan
daha kötü bir hatadır.

**2. Tespit — constant pool.** Her sınıf okunur ve **constant pool**'u ayrıştırılır.
Aranabilecek her şey — sınıf adları, metot/alan adları, tip referansları, string
sabitleri — zaten orada düz metin olarak durur.

Bu, aracın en önemli tasarım kararı ve ölçümle alındı. Tek bir
`minecraft-client.jar` (2507 sınıf, 8 MB) üzerinde:

| İşlem | Süre |
|---|---|
| Sınıfları arşivden okumak | 0,43 sn |
| Constant pool'dan her string'i çıkarmak | 0,06 sn |
| Aynı sınıfları decompile etmek (CFR) | **131 sn** |

Son satırın bu kadar az şey kazandırmasının sebebi: decompiler'ın çıktısı zaten
constant pool'**dan** inşa edilir. CFR iki dakikayı kontrol akışını yeniden
kurmaya harcar — bu bir insanın kodu okuması için paha biçilmezdir, bir metin
eşleşmesi için hiçbir şey ifade etmez.

**3. Decompile — istendiğinde.** Kaynak kodu üretmek tespit için değil, senin
okuman için çalışır. **Tarama sırasında hiç decompile edilmez;** kod ancak bir
satırı seçtiğinde veya bir jar'ı Decompile sekmesinde açtığında, sadece o arşiv
için üretilir. İki katman: **CFR** gerçek Java üretir, pes ettiğinde **ASM**
bytecode listesi çıkarır. Bir sınıf ancak her iki katman da başarısız olduğunda
"okunamadı" sayılır.

Bu ölçümle karara bağlandı ve **hiçbir tespit kaybettirmez.** Bu makinedeki 2033
gerçek arşivde:

| | Bulgu | Karar |
|---|---|---|
| Constant pool | 1.391 | **hepsi** |
| Decompile edilmiş kaynak | 893 | **sıfır** |

Decompile edilmiş kaynağın ürettiği 893 bulgunun tamamı, constant pool'un zaten
bulduğu bir terimin tekrarıydı — **tek bir jar'ın kararı bile** decompile sayesinde
değişmedi. Şaşırtıcı değil: decompiler çıktısını zaten o pool'dan inşa ediyor.
Teorideki tek kazanç (parçalara bölünmüş string'lerin birleşmesi) 2033 arşivde bir
kez bile gerçekleşmedi. Karşılığında maliyeti analiz süresinin **%56'sıydı**.

Ayarlar'dan hâlâ *işaretli* veya *tümü* seçilebilir; ikisi de artık bilinçli bir
tercih, varsayılan değil.

Decompile sekmesi **hiçbir dosyayı reddetmez.** Sınıf değilse metin, metin değilse
hex döküm gösterir; PNG/JPEG ise biçim ve boyut bilgisi verir; arşivin içindeki
arşiv normal şekilde açılır. Kılık değiştirmiş dosyalar da açılır — tarama sekmesi
`yks1233.dll` diye bir hile bulup Decompile sekmesi onu açmayı reddederse araç tam
da en iyi olduğu durumda işe yaramaz hale gelir. Açılabilirlik uzantıdan değil ilk
dört bayttan karar verilir. Çok büyük girdiler baştan kesilerek gösterilir ve
kesildiği yazılır; hiç açılmamak bir seçenek değil.

**Kod içinde arama** `Ctrl+F` ile açılır: tüm eşleşmeler aynı anda boyanır, üzerinde
durduğun daha parlak olur, `Enter` / `Shift+Enter` ileri geri gezer, `Esc` kapatır.
Sayaç kaç eşleşme olduğunu gösterir — "bu kelime her yerde" ile "bu kelime bir kez
geçiyor" arasındaki fark, kelimeyi tek tek gezmeden görünür.

**Tarama bulguları kodda işaretlenir.** Tarama sekmesinden bir jar'a çift tıkladığında
blacklist'in eşleştirdiği terimler kırmızıyla boyanır ve görünüm ilk eşleşmeye
kaydırılır. İki sekmeyi birbirine bağlayan şey bu: tarama "bu jar şüpheli" diyorsa,
onu açmak dört bin satırlık bir dosyanın 1. satırına değil, sebebin ta kendisine
indirmeli.

**4. Blacklist — Aho-Corasick.** Terimler `%APPDATA%\JarAnalyzer\blacklist.json`
içinde, arayüzden düzenlenebilir. Her terimin tipi (düz metin / tam kelime / regex),
önem derecesi, kategorisi ve hangi yüzeyde aranacağı (kod / yol / metin) vardır.

Terimler tek bir otomata derlenir, metin bir kez gezilir. Önceki hali 326 terimi
`a|b|c|...` şeklinde tek regex'e birleştiriyordu; Java'nın regex motoru bunu durum
makinesine çevirmez, her karakter pozisyonunda tüm alternatifleri dener. Aynı
Minecraft jar'ında bu **33,2 saniye** sürüyordu — pool'u ayrıştırmanın 0,06
saniyesine karşılık. Aho-Corasick ile **0,89 saniye**. Yan faydası: blacklist'e
bin terim daha eklesen tarama yavaşlamaz, ki bu tasarımın amacı zaten terim
eklemeyi teşvik etmek.

**5. Uzantısına değil içeriğine bakar.** Bir hile jar'ını gizlemenin en ucuz yolu
adını `d3d9.dll` yapmaktır; adlara bakan bir tarayıcı bunu göremez. Keşif sırasında
uzantısı tanınmayan her dosyanın ilk **4 baytı** okunur (`PK\x03\x04`), zip gibi
görünenlerin merkezi dizini açılıp içinde `.class` veya `MANIFEST.MF` var mı diye
bakılır. İki aşamalı olmasının sebebi ölçüm: dört bayt okumak neredeyse bedava,
merkezi dizini açmak değil — ve bir diskteki dosyaların binde birinden azı ilk
aşamayı geçer. Kılığı yakalanan arşiv **KRİTİK** bulgu üretir; hiç kimse bir
JAR'a kazara `.dll` adı vermez.

**Baştaki dört bayt tek başına yetmiyor.** Bir ZIP aslında sonundaki merkezi
dizinden tanımlanır; önüne istediğin kadar çöp bayt koyabilirsin, arşiv çalışmaya
devam eder. Ölçüldü: bir hile jar'ının önüne 17 bayt ekleyip adını `.dll` yapmak,
`jar tf`'in sorunsuz listelediği ve JVM'in sınıf yüklediği bir dosya üretiyor —
ama sıfır offsetine bakan tarayıcı hiçbir şey görmüyordu. Her launch4j exe'si de
tam olarak bu yapıda. Bu yüzden ilk dört bayt tutmazsa dosyanın **son 1 KB'ı**
merkezi dizin kaydı (`PK\x05\x06`) için taranır. Maliyeti ölçüldü: dosya zaten
açık olduğundan prob süresi değişmedi (16,1 sn).

Aynı hata `ArchiveInspector`'da da vardı: prefix'i hesaplıyor ama merkezi dizini
o kadar kaydırmadan okuyordu, dolayısıyla prefix'li bir arşiv **"okunabilir, 0
girdi"** diye geçiyordu — içindeki hile kodu hiç taranmadan. Bu yüzden `.jar`
adını taşısa bile temiz görünüyordu.

Bilinen arşiv uzantıları (`.jar`, `.zip`, `.jmod` …) bu probun dışında tutulur:
kendi formatını açıkça ilan eden bir dosya kılık değiştirmiş sayılmaz. Bu kural
ölçümle kondu — kuyruk kontrolü eklenince bir JDK'nın **143 `.jmod` modülü**
taramaya girip 146 "dikkat" satırı üretmişti.

**6. Geri dönüşüm kutusu.** Silinmiş dosya `$R…` adıyla bozulmadan durur ve
taramaya dahildir. Silinmiş olmak masumiyet değildir; çoğu zaman tersidir.

**7. Çalışan JVM'ler.** Disk taraması "bu makinede ne var" sorusunu yanıtlar.
*Çalışan Java'yı Tara* düğmesi asıl önemli olanı yanıtlar: **oyun şu anda neyi
çalıştırıyor.** JDK Attach API ile her Java sürecinin classpath'i, `-javaagent`
parametreleri ve yüklü jar'ları okunur — sürece hiçbir kod enjekte edilmeden,
yani incelenen kişi ekranı izlerken bile hiçbir şey değiştirmeden.

Buradaki en güçlü sinyal şu: bir hile açılıp jar'ı silinebilir, JVM bellekten
çalışmaya devam eder ve disk taraması hiçbir şey bulamaz. Classpath girdisi canlı
süreçte hâlâ durduğu için araç bunu görür ve **diskte var olmayan** bir dosya için
bile satır üretir.

**İki ayrı yol, çünkü birincisi kapatılabiliyor.** Attach API, incelenecek JVM'in
işbirliğine dayanır ve `-XX:+DisableAttachMechanism` ile başlatılan bir JVM her
attach isteğini reddeder. Ölçüldü: gizlenmiş bir hileyi bu bayrakla başlatmak
taramayı **1 JVM'den 0'a** düşürüyor — hile çalışmaya devam ederken tarama boş
dönüyor. Bilinen bypass tam olarak budur.

Bu yüzden ikinci bir yol var: **süreçlerin komut satırı doğrudan Windows'tan
okunur.** Hedefin işbirliği gerekmez. `NtQueryInformationProcess` ile sürecin PEB
adresi alınır, `ReadProcessMemory` ile komut satırı o sürecin belleğinden okunur.
Java'nın kendi `ProcessHandle` API'si burada işe yaramıyor — Windows'ta yalnızca
exe yolunu döndürüyor, `commandLine()` ve `arguments()` boş geliyor (yazmadan önce
ölçüldü).

Kazanç şu: `javaw.exe -cp yks1233.dll FakeGame` komutunda **gizlenmiş dosyanın adı
açıkça yazılıdır.** Araç bu yoldan hem dosyayı bulup analiz eder, hem de
`DisableAttachMechanism` bayrağının kendisini ayrı bir bulgu olarak raporlar —
normal bir oyun ya da derleme aracı o bayrağı koymaz; koyulmasının tek sebebi tam
da bu incelemeyi engellemektir.

**8. Karar.**

| Sonuç | Anlamı |
|---|---|
| `TEMİZ` | Sorunsuz okundu, blacklist eşleşmesi yok |
| `DİKKAT` | Okundu ama zayıf/genel bir eşleşme var |
| `ŞÜPHELİ` | **Okunamadı** — obfuscated, şifreli veya decompile edilemedi |
| `TESPİT` | Blacklist terimi kodun içinde bulundu |
| `KRİTİK` | Hem blacklist eşleşmesi var hem de arşiv analize direndi |
| `OKUNAMADI` | Geçerli bir arşiv değil — kesik, kilitli veya bozuk |

---

## Yanlış pozitife karşı

Bir tarayıcıya güvenilmez yapan şey, her eşleşmeyi suçlama sayması.

**Listeye girme kuralı: terimi bir JAR'da görmek tek başına tuhaf olmalı.** Hile
modülüne ad olmuş sıradan İngilizce kelimeler, modül ne kadar meşhur olursa olsun
listede yok — çünkü masum kodda sürekli geçerler. `phase` her animasyon
döngüsünde bir değişken, `step` her hareket modunda, `esp` üç harf, `Impact` ve
`Rise` birer kelime. Önceki sürüm bunların hepsini taşıyordu ve değiştirilmemiş
oyunu `KRİTİK`, imzalı her kütüphaneyi `ŞÜPHELİ` işaretliyordu.

Belirsiz client adları yalnızca bileşik halde (`VapeClient`, `Vape` değil) veya
bağlam isteyen bir regex'in arkasında tutulur. Genel JVM makinesi — reflection,
`ProcessBuilder`, `defineClass`, mixin ve coremod manifestleri — tamamen çıkarıldı:
meşru olsun olmasın neredeyse her modda var, ayrıca manifest çözümleyici agent ve
tweaker'ları zaten kendi başına raporluyor.

Sonuç: 221 terimin tamamı `KRİTİK` veya `YÜKSEK`. Gürültü katmanı yok.

Aynı gerekçeyle listede olmayan üç ad daha: **`Scaffold`** (`scaffolding` vanilla
bir blok), **`NoClip`** (her oyun motorunda geçen genel bir terim) ve
**`Wolfram`** (bilimsel yazılımda sık rastlanan bir isim). Bu üçü Decompile
sekmesinin kendi kelime listesinden de çıkarıldı — iki liste ayrı olduğu için
biri temizlenip diğeri unutulduğunda aynı JAR iki sekmede iki farklı sonuç
veriyordu.

**Sadece isimde geçen eşleşme KRİTİK olamaz.** Temiz bir JAR'ı `killaura.jar` diye
yeniden adlandırmak kendinden emin bir suçlama üretmemelidir.

**KRİTİK için iki bağımsız kanıt gerekir.** Blacklist eşleşmesi tek başına `TESPİT`
verir; `KRİTİK` ayrıca arşivin analize direnmiş olmasını şart koşar — gizlenmiş bir
hileyi, kelimeyi sadece anan bir moddan ayıran şey bu birleşimdir.

**Oyunun kendi varlıkları kanıt sayılmaz.** `assets/minecraft/` altındaki her şey
oyun verisidir. Vanilla Minecraft hem `textures/misc/forcefield.png` (dünya sınırı
dokusu) hem de `splashes.txt` içinde `"Phobos anomaly!"` taşır; ikisi de aynen
birer hile adıdır. Bir hile koduyla yakalanır, ikonunun veya açılış yazısının
adıyla değil.

Bu kuralların doğrulaması `Kontrol testleri` bölümünde.

---

## Basit bir tarayıcıdan farkı

| | İsme/ham byte'a bakan basit tarayıcı | Bu araç |
|---|---|---|
| Tespit yüzeyi | Ham byte veya dosya adı | **Constant pool + gerekince decompile** |
| Blacklist | Kodda gömülü liste | **JSON, arayüzden düzenlenir, regex + kategori + önem** |
| Decompile başarısızsa | Sessizce atlanır | **Bytecode'a düşer, sonra ŞÜPHELİ işaretler** |
| Şifreli arşiv | `JarFile` exception atar, bilgi yok | **Ham ZIP dizini okunur, şifreli girdi sayısı raporlanır** |
| Arşiv anomalileri | — | Yinelenen girdi adı, path traversal, arşiv sonrası/öncesi veri, zip bomb |

Ham ZIP dizini `java.util.zip` devreye girmeden **elle** okunur. Bunun sebebi:
ilgi çeken arşivler tam da standart API'nin açmayı reddettiği veya sessizce göz
ardı ettiği arşivlerdir. `JarFile` şifreli bir girdide exception atar ve nedenini
söylemez; aynı sınıfın ikinci bir kopyasını gizleyen yinelenen girdi adını da
sorunsuzca yutar — bu, decompiler ile JVM'in kodun ne olduğu konusunda anlaşmazlığa
düşmesini sağlayan bilinen bir tekniktir.

---

## Uygulama

```
dist\Jar Analyzer\Jar Analyzer.exe
```

Kendi Java çalışma zamanını içinde taşır — **makinede Java kurulu olmasına gerek
yoktur**. Klasörün tamamı taşınabilir; kopyaladığın yerden çalışır.

**Yönetici olarak açılır.** Exe'nin gömülü manifestinde `requireAdministrator`
var, yani Windows her açılışta UAC onayı ister ve onaysız başlatmaz. Bunun tek
sebebi MFT disk taraması: ham birim erişimi yönetici yetkisi olmadan mümkün değil
ve tüm diskleri saniyeler içinde tarayan yol o.

Bunun iki yan etkisi var, bilerek kabul edildi:

- Explorer'dan uygulamaya **sürükle-bırak çalışmaz.** Windows, yükseltilmiş bir
  pencereye yükseltilmemiş bir süreçten sürüklemeyi engeller (UIPI). Dosya
  seçmek için `Klasör / Dosya Seç` düğmesini veya `Dosya ▸ Aç` menüsünü kullan.
- Komut satırından `& "...exe"` ile doğrudan çağırmak
  *"İstenen işlem için yükseltme gerekiyor"* hatası verir. Yükseltilmemiş bir
  kabuktan çalıştırmak için `Start-Process` kullan (UAC istemi çıkar) veya
  `build\JarAnalyzer.jar`'ı çalıştır — jar'da manifest yoktur, yönetici
  istemez ama MFT yerine yavaş dizin yürüyüşüne düşer.

Hangi yolun kullanıldığını komut satırı başlığında görebilirsin:

```
disk scan : MFT (fast) on [C, D]
disk scan : directory walk (not elevated — MFT needs administrator)
```

---

## Derleme

Maven **gerekmez**. Tüm bağımlılıklar `lib/` altında düz jar olarak durur, bu yüzden
standart bir JDK 17+ yeterlidir.

```powershell
.\build.ps1
```

Uygulamayı (exe + gömülü JRE) üretmek için:

```powershell
.\build.ps1 -Package
```

İkonu yeniden üretmek için (`tools\IconGen.java` çizer):

```powershell
.\build.ps1 -Icon
```

Çıktılar: `build\JarAnalyzer.jar` ve `dist\Jar Analyzer\`

---

## Çalıştırma

Uygulama olarak: `dist` altındaki exe'ye çift tıkla.

Jar olarak:

```powershell
java -Xmx4g -jar build\JarAnalyzer.jar
```

Komut satırı — tüm diskler:

```powershell
java -Xmx6g -jar build\JarAnalyzer.jar --scan-all rapor-klasoru
```

Komut satırı — tek klasör veya dosya:

```powershell
java -Xmx4g -jar build\JarAnalyzer.jar --scan "C:\Users\ben\AppData\Roaming\.minecraft\mods" rapor-klasoru
```

Her iki mod da `report.html`, `report.json` ve `report.txt` üretir.

Arayüz, bulgular ve raporlar tamamen Türkçedir (İngilizceye Ayarlar'daki dil
kutusundan geçilebilir). Tek istisna `report.json`: o makine tarafından okunmak
için var, bu yüzden alan adları ve `verdict`/`severity` değerleri dilden bağımsız
olarak İngilizce kalır — aksi halde dili değiştirmek raporu tüketen her betiği
bozardı.

---

## Arayüz

Dört sekme:

- **Tarama** — tarama kontrolleri, canlı sayaçlar, sonuç tablosu ve detay paneli
  (Özet / Bulgular / Decompile Kod / Ayrıntılar)
- **Blacklist** — terim ekle-sil-düzenle, içe/dışa aktar, varsayılanlara dön
- **Decompile** — JD-GUI tarzı ağaç görünümü; Tarama sekmesinden çift tıklanan
  jar burada açılır, ayrıca elle de açılabilir
- **Ayarlar** — dört temel ayar görünür, gerisi katlanır "Gelişmiş" bölümünde

Tarama ekranındaki paneller dosya gezginindeki gibi davranır: tablo ile detay
paneli arasındaki ayırıcı sürüklenebilir, **Sayaçlar** ve **Detay** düğmeleriyle
o bölümler gizlenip açılabilir, ayırıcıya çift tıklamak detay panelini katlar.
Seçtiğin düzen kapanışta kaydedilir.

**Sonuç satırları:** çift tıklamak jar'ı **Decompile** sekmesinde açar; sağ tık
menüsünde ayrıca *klasörde göster*, *yolu kopyala* ve *SHA-256 kopyala* vardır.
Tablodaki **Değiştirilme** sütunu dosya tarihini gösterir ve son 1 gün / 7 gün /
30 gün içinde değişenler giderek daha koyu renklenir; üstteki tarih kutusu
tabloyu bu aralıklara göre filtreler. Bir hile çoğunlukla yakın zamanda indirilir,
dolayısıyla tarihe göre sıralamak bakılacak ilk yeri söyler.

Renk şeması Ayarlar'dan değiştirilebilir (kırmızı / mor / cyan / zümrüt /
kehribar). Varsayılan kırmızı.

Kırmızıyı arayüze harcamanın bir bedeli var: "kritik bulgu" için en bariz renk
odur. Bu yüzden önem ölçeği kırmızıdan uzağa dağıtıldı — temiz yeşil, dikkat
mavi, şüpheli kehribar, tespit turuncu — ve yalnızca ölçeğin en tepesi kırmızı
kaldı, üstelik arayüzdeki her şeyden daha parlak ve daha doygun olarak.

Zemin tek bir düz renk değil, beş kademeli bir rampa: her adım biraz daha açık ve
biraz daha az doygun, tamamı kırmızıya çalıyor. Panel, giriş kutusu ve tablo
satırları birbirinden tek bir çerçeve çizilmeden, sadece tonla ayrılıyor.

**İkon** `tools/IconGen.java` içinde kodla çizilir: Jar Fwcker'ın büyüteci, siyah
yerine kırmızı. Kopyalanmadı, yeniden çizildi — orijinal dosya üzerinde "pngtree"
filigranı olan hazır bir görseldi ve açık kaynak yayınlanan bir şeyin içinde
dağıtılamaz. Her boyut kendi çözünürlüğünde çiziliyor; 256 px'lik bir bitmap'i
küçültmek 16 px'te bulanık bir leke bırakıyor, ki görev çubuğunda görünen boyut
tam olarak o. 32 px altında iç halka, 48 px altında camdaki parlama düşüyor —
o boyutlarda ikisi de dış halkayla birleşip okunmaz hale geliyor.

> Eskiden beşinci bir **Tespit** sekmesi vardı; kaldırıldı. Üç alt sekmesinden
> ikisini besleyen sınıf hiçbir yerden çağrılmıyordu, yani kalıcı olarak boştu;
> kalan liste ise Tarama sekmesindeki bulguların bir kopyasıydı. Dil
> değiştirildiğinde çevrilmiyordu bile. Onunla birlikte 2.833 satır ölü kod
> (`ScanPanel`, `DetectionPanel`, `DeobfuscationAnalysisPanel` ve
> `CheatDetector`'daki görsel çıkarma yolu) silindi.

---

## Ayarlar

`%APPDATA%\JarAnalyzer\` altında:

- `blacklist.json` — terim listesi
- `settings.json` — tarama ayarları

Önemli sınırlar (Ayarlar sekmesinden değiştirilir):

| Ayar | Varsayılan | Not |
|---|---|---|
| JAR başına max sınıf | 4000 | 0 = sınırsız |
| JAR başına zaman sınırı | 120 sn | Aşılırsa analiz kısmi kalır |
| İç içe JAR derinliği | 2 | JAR içindeki JAR |
| Max JAR boyutu | 512 MB | |
| Paralel iş parçacığı | otomatik | 0 = CPU sayısı - 1 |
| Temiz JAR'ların kodunu sakla | kapalı | Tam disk taramasında kapalı tutun; açıkken bellek kullanımı GB'lara çıkar |

---

## Kontrol testleri

Bir tespit listesinin "artık yanlış pozitif vermiyor" olması yetmez — hâlâ
ateşlediğini de göstermek gerekir. İki sentetik kontrol jar'ı ile ölçülen:

| Jar | İçerik | Sonuç |
|---|---|---|
| `fake-cheat.jar` | `KillAura`, `LiquidBounce`, `TriggerBot`, `ChestESP`, `Chams`, `AntiKnockback` | **TESPİT** — 9 bulgu, skor 754 |
| `fake-legit.jar` | `phase`, `step`, `sprint`, `esp`, `hitboxes` dolu sıradan render kodu | **TEMİZ** — 0 bulgu |
| vanilla `minecraft-client.jar` | 2507 sınıflık değiştirilmemiş oyun | **TEMİZ** — 0,9 sn |
| `fabric-item-api-v1.jar` | imzalı resmî Fabric API modülü | **TEMİZ** |
| `encrypted-test.jar` | ZIP girdileri parola korumalı | **ŞÜPHELİ** |
| `truncated-test.jar` | %40'ı kesilmiş arşiv | **OKUNAMADI** |
| `d3d9.dll` | `fake-cheat.jar`'ın adı değiştirilmiş hali | **TESPİT** — kılık + içerik |
| `config.dat` | `fake-legit.jar`'ın adı değiştirilmiş hali | **DİKKAT** — sadece kılık |
| `notazip.dll` | gerçekten zip olmayan 11 bayt | taramaya hiç girmez |

**Kılık değiştirme testi.** Aynı hile jar'ı 18 farklı biçimde saklandı ve hepsi
tarandı:

| Varyant | Sonuç |
|---|---|
| `.dll .exe .dat .bin .png .txt .log .cfg .pak .sav .db .tmp .resource .nodefile` ve uzantısız | **14 + 1 → hepsi TESPİT** |
| `suffixed.dll` (sonuna çöp eklenmiş) | **TESPİT** |
| `prefixed.dll` (başına çöp eklenmiş) | **TESPİT** |
| `prefixed_named.jar` (başına çöp, adı normal) | **TESPİT** |

**18 / 18.** Son üçü ilk turda kaçmıştı; kuyruk taraması ve `ArchiveInspector`
prefix düzeltmesi onları kapattı.

**Bypass testleri.** İki farklı gizleme tekniği elle yazılıp denendi:

| Teknik | Sonuç |
|---|---|
| String'ler XOR ile şifreli, sınıf adı tek harf | **ŞÜPHELİ — Obfuscate** (isimler okunamadı ama tekniğin kendisi yakalandı) |
| `"Kill".concat("Aura")` — çalışma anında birleşen string | **TEMİZ** — bilinen sınır, aşağıda açıklandı |
| `javaw.exe -cp yks1233.dll` + `-XX:+DisableAttachMechanism` | Attach API **0 JVM**; süreç tarayıcı **buldu**, dosyayı çıkardı, bayrağı ayrıca raporladı |

Bellek taraması, classpath'inde bir hile jar'ı olan ve o jar çalışırken silinen
bir Java süreci ile ölçüldü: silinen dosya **TESPİT** satırı olarak, *"Çalışıyor
ama diskte yok"* bulgusuyla göründü — hâlâ diskte duran hile ise normal şekilde
analiz edildi.

**Decompile sekmesi ayrı ölçülür.** `--probe-tree <jar>` ağaçtaki her yaprağı
tek tek açar ve açılamayanı sayar; "her dosyaya bakabiliyor muyum" sorusu koda
bakarak cevaplanamaz, çünkü bir girdinin hangi yolu izlediği baytlarına bağlıdır.

| Dosya | İçerik | Sonuç |
|---|---|---|
| `zoo.jar` | sınıf, PNG, JSON, TXT, uzantısız dosya, iç içe jar, 12 MB'lık blob, Türkçe adlı dosya | **10 / 10 açıldı** |
| `yks1233.dll` | adı değiştirilmiş `doomsday.jar` | **2 / 2 açıldı** |

---

## Hız

Bu makinede, 433.769 dosya ve 2.036 arşivlik gerçek bir tam disk taramasıyla
ölçüldü. Sayılar tahmin değil, `--scan-all` çıktısı ve iş parçası bazında ölçüm.

**Analiz süresi (aynı 2033 arşiv, üç ayrı ölçüm):**

| Sürüm | Süre |
|---|---|
| İşaretli arşivi tamamen decompile et (eski varsayılan) | 162 sn |
| Yalnızca eşleşen sınıfları decompile et | 151 sn |
| Yapısal işaretlilere de sınır koy | 102 sn |
| **Tarama sırasında hiç decompile etme** | **46 sn** |

**Kararlar üç ölçümde de aynı:** TESPİT 16, ŞÜPHELİ 3, KRİTİK 0, DİKKAT 283.
Kaybedilen tek şey, constant pool'un zaten bulduğu terimlerin tekrarı olan 893
bulgu satırıydı.

Sürenin nereye gittiği de ölçüldü: **2033 arşivin 135'i (%6,6) analiz süresinin
%94'ünü yiyordu** ve hepsi tam olarak decompile edilen büyük arşivlerdi. Aracın
kendi jar'ı, 12 eşleşme yüzünden 4000 sınıfı yeniden inşa ederek 113 saniye
sürüyordu.

**Keşif süresi** (sıcak önbellek, bir kullanıcı profili, 2033 arşiv):

| Aşama | Süre |
|---|---|
| Dizin yürüyüşü | 10,0 sn |
| Gizli arşiv probu (317 bin dosyanın ilk 4 baytı) | 16,0 sn |

Yönetici olarak çalıştırıldığında yürüyüş yerine MFT devreye girer ve bu ilk
satır saniyelere iner.

---

## Bilinen sınırlar

Keşfedilmeye bırakmak yerine açıkça yazmak daha doğru:

- **Tespit sezgiseldir.** Bulgular bir insanın tartması için kanıttır, kanıtlanmış
  suç değil. Yukarıdaki üç yanlış-pozitif kuralı tam da bunun için var.
- **Çalışma anında birleştirilen string'ler statik taramayı atlatabilir.** Bir hile,
  `KillAura` adını tek bir sabitte tutmak yerine `"Kill".concat("Aura")` diye parçalayıp
  çalışırken birleştirirse, constant pool'da bütün kelime hiç bulunmaz ve isim
  eşleşmesi onu göremez. Bu her statik metin tarayıcısının ortak sınırıdır. Kapatmak
  denendi ve **ölçümle reddedildi**: sabitleri ayraçsız birleştirip alt-dizi aramak,
  2033 gerçek arşivde 30 yanlış tespit üretti — `Cooldown`+`Bypass` vanilla
  `minecraft-client.jar`'ı, `Key`+`Logger` fabric-loom ve postgresql'i, `Name`+`Protect`
  Groovy ve Spring'i işaretledi. Bileşik hile adları sıradan İngilizce parçalardan
  oluşuyor ve meşru kodda ayrı sabitler olarak sürekli geçiyor; bu yüzden sabitler
  bilerek `\n` ile ayrılıp taranıyor. İki gerçek karşı-önlem duruyor: **obfuscation
  sezgiseli** (XOR ile string gizleyen sürümü yakalar — bu durumda ŞÜPHELİ verir) ve
  **çalışan JVM taraması** (hile yüklendiği an classpath'te görünür).
- **MFT yolunun yükseltilmiş hali hâlâ ölçülmedi.** Doğrulananlar: exe'nin
  manifesti `requireAdministrator` (PE'den geri okunarak) ve Windows yükseltmesiz
  çalıştırmayı reddediyor. Yetkisiz haldeki davranış da test edildi — anında ve
  temiz şekilde düşüyor, dizin yürüyüşü devralıyor. Ama yükseltilmiş bir süreçte
  MFT'nin gerçekten doğru yolları üretip üretmediği ölçülemedi; UAC onayı
  gerektiği için otomatik test edilemiyor. Yukarıdaki çözülemeyen-yol emniyeti
  (bir birimin yollarının yarısından fazlası çözülemezse dizin yürüyüşüne devret)
  tam da bu belirsizlik için var.

- **Bir sınır yüzünden eksik kalan analiz** sonuçta **"Analiz tamamlanmadı"**
  bulgusu olarak görünür — eksik okunan bir arşiv sessizce temiz sayılmaz.
- **Obfuscation suç değildir.** ProGuard ile küçültülmüş kütüphaneler her yerde.
  Bu yüzden obfuscation kararı tek bir sezgisele değil, birbirinden bağımsız en az
  iki sinyale dayanır.
- **Şifreli girdiler açılmaz.** Araç şifreli olduklarını tespit eder ve raporlar;
  parolayı kırmaya çalışmaz.
- **Bir sınıfın decompiler'ı sonsuz döngüye sokması mümkündür.** Bu bilinen bir
  anti-analiz tekniğidir; süre sınırını aşan işçi iş parçacığını kesen bir gözcü
  (watchdog) bunun için var.

---

## Lisans

Kaynak kod **inceleme amaçlı** yayınlanmıştır: okuyabilir, inceleyebilirsiniz,
ama izinsiz kopyalayamaz, değiştiremez veya kendi projenizde kullanamazsınız.
Derlenmiş uygulama (`Jar Analyzer.exe`) serbestçe indirilip çalıştırılabilir.
Ayrıntı için [`LICENSE`](LICENSE).

Kullanılan üçüncü taraf kütüphaneler kendi lisansları altındadır — CFR (MIT),
ASM (BSD-3), Gson (Apache-2.0), JNA (Apache-2.0 / LGPL-2.1) — ve arayüz iskeleti
[Luyten](https://github.com/deathmarine/Luyten) (Apache-2.0) üzerine kuruludur.
Tam liste ve sürümler: [`THIRD-PARTY.md`](THIRD-PARTY.md).

Uygulamanın kendi görselleri (ikon, pencere simgesi) `tools/IconGen.java` ile
kodda çizilir — dışarıdan alınmış hiçbir görsel dağıtılmaz.

**Yayın exe'si SHA-256:**
`fcbe4eccd024332aa69f1b28cc5574074f6d2d0d83224a956d1df0e3d76a53b6`
(Uygulamada Yardım → Hakkında, çalışan kopyanın hash'ini gösterir; ikisi
eşleşiyorsa kopyanız değiştirilmemiştir.)
