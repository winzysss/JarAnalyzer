# =====================================================================
#  Rewrites the embedded application manifest of a built .exe so Windows
#  always launches it elevated.
#
#  jpackage has no option for this: it stamps its own manifest with
#  `asInvoker` and offers no way to override it, and an external
#  <exe>.manifest file is ignored whenever the binary already carries an
#  embedded one — which this launcher does. The only route left is to
#  replace the RT_MANIFEST resource in the finished PE, which is what
#  BeginUpdateResource/UpdateResource/EndUpdateResource exist for.
#
#  Usage:  RequireAdmin.ps1 -Exe "path\to\app.exe" [-Verify]
# =====================================================================

param(
    [Parameter(Mandatory = $true)][string]$Exe,
    [switch]$Verify
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Exe)) { throw "Bulunamadi: $Exe" }
$Exe = (Resolve-Path $Exe).Path

Add-Type -Namespace WJF -Name Res -MemberDefinition @'
[DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
public static extern IntPtr BeginUpdateResourceW(string pFileName, bool bDeleteExistingResources);

[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool UpdateResourceW(IntPtr hUpdate, IntPtr lpType, IntPtr lpName,
    ushort wLanguage, byte[] lpData, uint cb);

[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool EndUpdateResourceW(IntPtr hUpdate, bool fDiscard);

[DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
public static extern IntPtr LoadLibraryExW(string lpFileName, IntPtr hFile, uint dwFlags);

[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool FreeLibrary(IntPtr hModule);

[DllImport("kernel32.dll", SetLastError = true)]
public static extern IntPtr FindResourceW(IntPtr hModule, IntPtr lpName, IntPtr lpType);

[DllImport("kernel32.dll", SetLastError = true)]
public static extern IntPtr LoadResource(IntPtr hModule, IntPtr hResInfo);

[DllImport("kernel32.dll", SetLastError = true)]
public static extern IntPtr LockResource(IntPtr hResData);

[DllImport("kernel32.dll", SetLastError = true)]
public static extern uint SizeofResource(IntPtr hModule, IntPtr hResInfo);

[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool EnumResourceLanguagesW(IntPtr hModule, IntPtr lpType, IntPtr lpName,
    EnumResLangProc lpEnumFunc, IntPtr lParam);

public delegate bool EnumResLangProc(IntPtr hModule, IntPtr lpType, IntPtr lpName,
    ushort wLang, IntPtr lParam);
'@

# RT_MANIFEST = 24, CREATEPROCESS_MANIFEST_RESOURCE_ID = 1
$RT_MANIFEST = [IntPtr]24
$MANIFEST_ID = [IntPtr]1
$LOAD_LIBRARY_AS_DATAFILE = 0x00000002

function Read-Manifest([string]$path) {
    $h = [WJF.Res]::LoadLibraryExW($path, [IntPtr]::Zero, $LOAD_LIBRARY_AS_DATAFILE)
    if ($h -eq [IntPtr]::Zero) { return $null }
    try {
        $info = [WJF.Res]::FindResourceW($h, $MANIFEST_ID, $RT_MANIFEST)
        if ($info -eq [IntPtr]::Zero) { return $null }
        $size = [WJF.Res]::SizeofResource($h, $info)
        $data = [WJF.Res]::LockResource([WJF.Res]::LoadResource($h, $info))
        if ($data -eq [IntPtr]::Zero -or $size -eq 0) { return $null }
        $bytes = New-Object byte[] $size
        [System.Runtime.InteropServices.Marshal]::Copy($data, $bytes, 0, $size)
        return [System.Text.Encoding]::UTF8.GetString($bytes)
    }
    finally { [void][WJF.Res]::FreeLibrary($h) }
}

function Get-ManifestLanguage([string]$path) {
    # The replacement has to go in under the SAME language id as the existing
    # resource. Adding it under a different one leaves two manifests in the
    # binary and Windows picks by thread locale — so on a Turkish system the
    # elevation request would simply be ignored.
    $lang = $null
    $h = [WJF.Res]::LoadLibraryExW($path, [IntPtr]::Zero, $LOAD_LIBRARY_AS_DATAFILE)
    if ($h -eq [IntPtr]::Zero) { return 1033 }
    try {
        $cb = [WJF.Res+EnumResLangProc]{
            param($hm, $t, $n, $wLang, $lp)
            $script:foundLang = $wLang
            return $false   # first one is enough
        }
        $script:foundLang = $null
        [void][WJF.Res]::EnumResourceLanguagesW($h, $RT_MANIFEST, $MANIFEST_ID, $cb, [IntPtr]::Zero)
        $lang = $script:foundLang
    }
    finally { [void][WJF.Res]::FreeLibrary($h) }
    if ($null -eq $lang) { return 1033 }
    return [uint16]$lang
}

if ($Verify) {
    $m = Read-Manifest $Exe
    if (-not $m) { Write-Host "manifest OKUNAMADI" -ForegroundColor Red; exit 1 }
    if ($m -match 'requireAdministrator') {
        Write-Host "  manifest: requireAdministrator  (dogru)" -ForegroundColor Green
        exit 0
    }
    Write-Host "  manifest: requireAdministrator YOK" -ForegroundColor Red
    exit 1
}

$lang = Get-ManifestLanguage $Exe

# jpackage leaves its launcher read-only, and BeginUpdateResource fails with
# ERROR_WRITE_PROTECT (19) rather than anything that names the real cause.
$item = Get-Item $Exe
$wasReadOnly = $item.IsReadOnly
if ($wasReadOnly) { $item.IsReadOnly = $false }

$manifest = @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <assemblyIdentity version="2.0.0.0" processorArchitecture="amd64"
                    name="Winzy.JarFucker" type="win32"/>
  <description>Jar Analyzer</description>
  <trustInfo xmlns="urn:schemas-microsoft-com:asm.v2">
    <security>
      <requestedPrivileges xmlns="urn:schemas-microsoft-com:asm.v3">
        <requestedExecutionLevel level="requireAdministrator" uiAccess="false"/>
      </requestedPrivileges>
    </security>
  </trustInfo>
  <compatibility xmlns="urn:schemas-microsoft-com:compatibility.v1">
    <application>
      <supportedOS Id="{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}"/>
      <supportedOS Id="{1f676c76-80e1-4239-95bb-83d0f6d0da78}"/>
      <supportedOS Id="{4a2f28e3-53b9-4441-ba9c-d69d4a4a6e38}"/>
    </application>
  </compatibility>
  <application xmlns="urn:schemas-microsoft-com:asm.v3">
    <windowsSettings>
      <dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true/pm</dpiAware>
      <dpiAwareness xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">permonitorv2,permonitor</dpiAwareness>
      <longPathAware xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">true</longPathAware>
      <activeCodePage xmlns="http://schemas.microsoft.com/SMI/2019/WindowsSettings">UTF-8</activeCodePage>
    </windowsSettings>
  </application>
</assembly>
'@

$bytes = [System.Text.Encoding]::UTF8.GetBytes($manifest)

$h = [WJF.Res]::BeginUpdateResourceW($Exe, $false)
if ($h -eq [IntPtr]::Zero) {
    throw "BeginUpdateResource basarisiz (dosya kullanimda olabilir): $([System.Runtime.InteropServices.Marshal]::GetLastWin32Error())"
}

$ok = [WJF.Res]::UpdateResourceW($h, $RT_MANIFEST, $MANIFEST_ID, $lang, $bytes, [uint32]$bytes.Length)
if (-not $ok) {
    [void][WJF.Res]::EndUpdateResourceW($h, $true)
    throw "UpdateResource basarisiz: $([System.Runtime.InteropServices.Marshal]::GetLastWin32Error())"
}

if (-not [WJF.Res]::EndUpdateResourceW($h, $false)) {
    throw "EndUpdateResource basarisiz: $([System.Runtime.InteropServices.Marshal]::GetLastWin32Error())"
}

if ($wasReadOnly) { (Get-Item $Exe).IsReadOnly = $true }

$check = Read-Manifest $Exe
if ($check -match 'requireAdministrator') {
    Write-Host "  manifest guncellendi (lang=$lang) -> requireAdministrator" -ForegroundColor Green
} else {
    throw "Manifest yazildi ama geri okunamadi/dogrulanamadi"
}
