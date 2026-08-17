# =====================================================================
#  Jar Analyzer - build script
#
#  Maven is not required. Every dependency lives in lib\ as a plain jar,
#  so a stock JDK (javac + jar) is enough to produce the runnable fat jar.
#
#    .\build.ps1            compile + package  ->  build\JarAnalyzer.jar
#    .\build.ps1 -Run       ...and launch it
#    .\build.ps1 -Clean     wipe build\ first
# =====================================================================

param(
    [switch]$Run,
    [switch]$Clean,
    [switch]$Quiet,
    [switch]$Package,
    [switch]$SingleFile,
    [switch]$Icon
)

$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 turns anything a native exe writes to stderr into an
# ErrorRecord, which under -ErrorAction Stop aborts the script even when the
# tool exited 0 (javac writes deprecation notes there routinely). Native calls
# are wrapped in this helper so only the real exit code decides success.
function Invoke-Native {
    param([string]$Exe, [string[]]$Arguments, [string]$What)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Exe @Arguments 2>&1 | ForEach-Object { Write-Host $_ }
        $code = $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $prev }
    if ($code -ne 0) {
        Write-Host "$What BASARISIZ (exit $code)" -ForegroundColor Red
        exit 1
    }
}

$root = $PSScriptRoot
$srcDir = Join-Path $root 'src'
$libDir = Join-Path $root 'lib'
$buildDir = Join-Path $root 'build'
$classesDir = Join-Path $buildDir 'classes'
$stageDir = Join-Path $buildDir 'stage'
$outJar = Join-Path $buildDir 'JarAnalyzer.jar'
$mainClass = 'com.jaranalyzer.JarAnalyzer'

function Say($msg, $color = 'Gray') {
    if (-not $Quiet) { Write-Host $msg -ForegroundColor $color }
}

# ---- locate the JDK -------------------------------------------------

function Resolve-JdkTool($name) {
    if ($env:JAVA_HOME) {
        $p = Join-Path $env:JAVA_HOME "bin\$name.exe"
        if (Test-Path $p) { return $p }
    }
    $cmd = Get-Command "$name.exe" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

$javac = Resolve-JdkTool 'javac'
$jarTool = Resolve-JdkTool 'jar'
$javaExe = Resolve-JdkTool 'java'

if (-not $javac -or -not $jarTool) {
    Write-Host "HATA: javac/jar bulunamadi. JDK 17+ kurun veya JAVA_HOME ayarlayin." -ForegroundColor Red
    exit 1
}

Say "======================================================" 'DarkMagenta'
Say "  Jar Analyzer - build" 'Magenta'
Say "======================================================" 'DarkMagenta'
Say "  javac : $javac"

# ---- clean ----------------------------------------------------------

if ($Clean -and (Test-Path $buildDir)) {
    Say "[0/4] build\ temizleniyor..."
    Remove-Item $buildDir -Recurse -Force
}

# javac only writes the classes it compiles; it never removes one whose source
# is gone. Left alone, a deleted class stays in build\classes and is packaged
# into every jar after it -- which is how dead code ships long after it was
# removed from the repository. The class output is cheap to rebuild, so it is
# always emptied rather than only under -Clean.
if (Test-Path $classesDir) { Remove-Item $classesDir -Recurse -Force }
New-Item -ItemType Directory -Force $classesDir | Out-Null

# ---- classpath ------------------------------------------------------

$libs = Get-ChildItem $libDir -Filter *.jar -ErrorAction SilentlyContinue
if (-not $libs) {
    Write-Host "HATA: lib\ icinde jar yok." -ForegroundColor Red
    exit 1
}
$classpath = ($libs | ForEach-Object { $_.FullName }) -join ';'

# ---- compile --------------------------------------------------------

$sources = Get-ChildItem $srcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Say "[1/4] $($sources.Count) kaynak dosya derleniyor..."

$argFile = Join-Path $buildDir 'sources.txt'
# javac's @argfile parser treats \ as an escape, so paths need doubling, and it
# chokes on a UTF-8 BOM — hence WriteAllLines with an explicit no-BOM encoding.
$argLines = $sources | ForEach-Object { '"' + ($_ -replace '\\', '\\') + '"' }
[System.IO.File]::WriteAllLines($argFile, $argLines, (New-Object System.Text.UTF8Encoding($false)))

$javacArgs = @(
    '-encoding', 'UTF-8',
    '--release', '17',
    '-nowarn',
    '-cp', $classpath,
    '-d', $classesDir,
    "@$argFile"
)

Invoke-Native $javac $javacArgs 'DERLEME'
Say "      derleme tamam" 'Green'

# ---- resources ------------------------------------------------------

Say "[2/4] kaynak dosyalari (resources) kopyalaniyor..."
Get-ChildItem $srcDir -Recurse -File | Where-Object { $_.Extension -ne '.java' } | ForEach-Object {
    $rel = $_.FullName.Substring($srcDir.Length + 1)
    $dest = Join-Path $classesDir $rel
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    Copy-Item $_.FullName $dest -Force
}

# ---- stage the fat jar ---------------------------------------------

Say "[3/4] bagimliliklar aciliyor (fat jar)..."
if (Test-Path $stageDir) { Remove-Item $stageDir -Recurse -Force }
New-Item -ItemType Directory -Force $stageDir | Out-Null

Push-Location $stageDir
try {
    foreach ($lib in $libs) {
        Invoke-Native $jarTool @('xf', $lib.FullName) "ACMA ($($lib.Name))"
    }
    # Signatures from the shaded deps would invalidate the repackaged jar.
    Remove-Item 'META-INF\*.SF', 'META-INF\*.DSA', 'META-INF\*.RSA', 'META-INF\MANIFEST.MF' `
        -Force -ErrorAction SilentlyContinue
    Remove-Item 'module-info.class' -Force -ErrorAction SilentlyContinue
}
finally { Pop-Location }

# App classes go in last so they win over anything with the same name.
Copy-Item "$classesDir\*" $stageDir -Recurse -Force

# ---- package --------------------------------------------------------

Say "[4/4] paketleniyor..."
$manifest = Join-Path $buildDir 'MANIFEST.MF'
@(
    "Manifest-Version: 1.0",
    "Main-Class: $mainClass",
    "Implementation-Title: Jar Analyzer",
    "Implementation-Version: 2.1.0",
    "Enable-Native-Access: ALL-UNNAMED",
    ""
) | Set-Content $manifest -Encoding ASCII

if (Test-Path $outJar) { Remove-Item $outJar -Force }
Push-Location $stageDir
try { Invoke-Native $jarTool @('cfm', $outJar, $manifest, '.') 'PAKETLEME' }
finally { Pop-Location }

if (-not (Test-Path $outJar)) {
    Write-Host "PAKETLEME BASARISIZ" -ForegroundColor Red
    exit 1
}

$size = [math]::Round((Get-Item $outJar).Length / 1MB, 1)
Say ""
Say "  TAMAM -> $outJar  ($size MB)" 'Green'
Say "  Calistir: java -jar `"$outJar`"" 'DarkGray'
Say "======================================================" 'DarkMagenta'

# ---- icon ------------------------------------------------------------

$iconFile = Join-Path $root 'jaranalyzer.ico'

if ($Icon -or ($Package -and -not (Test-Path $iconFile))) {
    Say ""
    Say "[ikon] jaranalyzer.ico uretiliyor..."
    $toolsOut = Join-Path $buildDir 'tools'
    New-Item -ItemType Directory -Force $toolsOut | Out-Null
    Invoke-Native $javac @('-encoding', 'UTF-8', '-nowarn', '-d', $toolsOut,
        (Join-Path $root 'tools\IconGen.java')) 'IKON DERLEME'
    Invoke-Native $javaExe @('-cp', $toolsOut, 'IconGen', $iconFile) 'IKON URETIMI'
}

# ---- package as a Windows application --------------------------------

if ($Package) {
    $jpackage = Resolve-JdkTool 'jpackage'
    if (-not $jpackage) {
        Write-Host "HATA: jpackage bulunamadi (JDK 17+ gerekir)." -ForegroundColor Red
        exit 1
    }

    Say ""
    Say "[paket] Windows uygulamasi olusturuluyor..." 'Magenta'

    # jpackage copies everything in --input next to the app, so the fat jar is
    # staged alone rather than pointing it at build\ (which holds classes,
    # the stage tree and the argfile).
    $pkgInput = Join-Path $buildDir 'pkg-input'
    if (Test-Path $pkgInput) { Remove-Item $pkgInput -Recurse -Force }
    New-Item -ItemType Directory -Force $pkgInput | Out-Null
    Copy-Item $outJar $pkgInput

    # Work out which JDK modules the bundled runtime needs.
    #
    # This has to be computed, not guessed. Passing --add-modules to jpackage
    # REPLACES the set it would have derived rather than adding to it, so the list
    # must be complete or the runtime ships without java.desktop and the Swing UI
    # cannot open a window. jdeps reads the actual bytecode; jdk.attach is appended
    # because the JVM scanner reaches it reflectively and jdeps cannot see that.
    $prevEA = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $jdeps = Resolve-JdkTool 'jdeps'
    $detected = & $jdeps --print-module-deps --ignore-missing-deps --multi-release 21 $outJar 2>$null |
        Select-Object -Last 1
    $ErrorActionPreference = $prevEA

    if (-not $detected) {
        Write-Host "HATA: jdeps modul setini hesaplayamadi." -ForegroundColor Red
        exit 1
    }

    $modules = ($detected.Trim() + ',jdk.attach,java.management,java.logging')
    Say "      moduller: $modules"

    $distDir = Join-Path $root 'dist'
    $appDir = Join-Path $distDir 'Jar Analyzer'
    if (Test-Path $appDir) { Remove-Item $appDir -Recurse -Force }
    New-Item -ItemType Directory -Force $distDir | Out-Null

    $jpArgs = @(
        '--type', 'app-image',
        '--name', 'Jar Analyzer',
        '--app-version', '2.1.0',
        '--vendor', 'Winzys',
        '--description', 'Full-disk JAR discovery, decompilation and blacklist forensics',
        '--input', $pkgInput,
        '--main-jar', (Split-Path $outJar -Leaf),
        '--main-class', $mainClass,
        '--dest', $distDir,
        '--java-options', '-Xmx6g',
        '--java-options', '-Dsun.java2d.dpiaware=true',
        '--java-options', '--enable-native-access=ALL-UNNAMED',
        '--add-modules', $modules
    )
    if (Test-Path $iconFile) { $jpArgs += @('--icon', $iconFile) }

    Invoke-Native $jpackage $jpArgs 'PAKETLEME (jpackage)'

    $exePath = Join-Path $appDir 'Jar Analyzer.exe'
    if (-not (Test-Path $exePath)) {
        Write-Host "PAKETLEME BASARISIZ: exe olusmadi" -ForegroundColor Red
        exit 1
    }

    # jpackage stamps its launcher with an `asInvoker` manifest and gives no way
    # to change it, so the finished exe's manifest resource is rewritten to ask
    # for administrator. The MFT disk sweep needs raw volume access, which is
    # the one thing that genuinely requires elevation.
    Say "      yonetici manifesti yaziliyor..."
    & powershell.exe -NoProfile -ExecutionPolicy Bypass `
        -File (Join-Path $root 'tools\RequireAdmin.ps1') -Exe $exePath
    if ($LASTEXITCODE -ne 0) {
        Write-Host "MANIFEST YAZILAMADI" -ForegroundColor Red
        exit 1
    }

    $appSize = [math]::Round((Get-ChildItem $appDir -Recurse -File |
        Measure-Object Length -Sum).Sum / 1MB, 1)

    Say ""
    Say "  UYGULAMA -> $exePath" 'Green'
    Say "  Boyut: $appSize MB  (Java kurulu olmasi gerekmez)" 'DarkGray'
    Say "======================================================" 'DarkMagenta'
}

# ---------------------------------------------------------------------------
#  Single-file launcher
#
#  -Package produces a directory: a launcher, a Java runtime and the jar. That
#  is not something you can hand to someone as one download, so this wraps the
#  whole directory in one exe that unpacks itself on first run. The release
#  asset is built here rather than by hand, so what is published is always the
#  same thing the repository describes.
# ---------------------------------------------------------------------------
if ($SingleFile) {
    $distDir = Join-Path $root 'dist'
    $appDir = Join-Path $distDir 'Jar Analyzer'
    if (-not (Test-Path $appDir)) {
        Write-Host "ONCE -Package calistirilmali (dist\Jar Analyzer yok)" -ForegroundColor Red
        exit 1
    }

    $csc = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
    if (-not (Test-Path $csc)) {
        Write-Host "csc.exe bulunamadi: $csc" -ForegroundColor Red
        exit 1
    }

    Say ""
    Say "[tek dosya] payload paketleniyor..." 'Cyan'

    $payload = Join-Path $distDir 'payload.zip'
    if (Test-Path $payload) { Remove-Item $payload -Force }
    Compress-Archive -Path $appDir -DestinationPath $payload -CompressionLevel Optimal

    $outExe = Join-Path $distDir 'JarAnalyzer.exe'
    if (Test-Path $outExe) { Remove-Item $outExe -Force }

    Say "[tek dosya] launcher derleniyor..."
    & $csc '/nologo' '/target:winexe' '/optimize+' `
        "/out:$outExe" `
        "/win32icon:$iconFile" `
        "/win32manifest:$(Join-Path $root 'tools\launcher.manifest')" `
        "/resource:$payload,payload.zip" `
        '/reference:System.dll' '/reference:System.Drawing.dll' `
        '/reference:System.Windows.Forms.dll' `
        '/reference:System.IO.Compression.dll' `
        '/reference:System.IO.Compression.FileSystem.dll' `
        (Join-Path $root 'tools\Launcher.cs')
    if ($LASTEXITCODE -ne 0) {
        Write-Host "LAUNCHER DERLENEMEDI" -ForegroundColor Red
        exit 1
    }

    Remove-Item $payload -Force
    $mb = [math]::Round((Get-Item $outExe).Length / 1MB, 1)
    $sha = (Get-FileHash $outExe -Algorithm SHA256).Hash.ToLower()

    Say ""
    Say "  TEK DOSYA -> $outExe" 'Green'
    Say "  Boyut: $mb MB" 'DarkGray'
    Say "  SHA-256: $sha" 'DarkGray'
    Say "======================================================" 'DarkMagenta'
}

if ($Run) {
    Say ""
    Say "Baslatiliyor..." 'Cyan'
    & $javaExe '-Xmx4g' '-jar' $outJar
}
