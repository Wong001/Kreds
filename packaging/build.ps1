# Build the Kreds distributable: a stable top-level launcher (Kreds.exe,
# packaging/launcher.spec, one-file) that re-verifies + applies any staged
# core update on every start, plus the versioned payload it runs
# (packaging/kreds.spec, one-folder, staged under versions/<CORE_VERSION>/).
#
# Steps:
#   1. Stage a real tor.exe into packaging/tor/tor.exe (reusing
#      hearth.tor.ensure_tor_binary's pinned, hash-verified fetch/cache --
#      never a second, separate download path).
#   2. Resolve CORE_VERSION from hearth.__version__.
#   3. Build the PAYLOAD (kreds.spec) into an intermediate dist\_core\Kreds\.
#   4. Build the LAUNCHER (launcher.spec) into an intermediate
#      dist\_launcher\Kreds.exe.
#   5. Assemble the final dist\Kreds\ tree:
#        dist\Kreds\Kreds.exe                   <- the launcher
#        dist\Kreds\versions\<CORE_VERSION>\    <- the payload build
#        dist\Kreds\current                     <- text file, CORE_VERSION
#   6. Print the resulting dist\Kreds path.
#
# Run from anywhere; paths are resolved relative to this script.

$ErrorActionPreference = "Stop"

$PackagingDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $PackagingDir
$TorDir = Join-Path $PackagingDir "tor"
$TorExe = Join-Path $TorDir "tor.exe"
$Python = Join-Path $RepoRoot ".venv\Scripts\python.exe"

if (-not (Test-Path $Python)) {
    Write-Error "venv python not found at $Python -- create/activate .venv first."
    exit 1
}

Write-Host "== Staging tor.exe into packaging/tor/ =="
if (Test-Path $TorExe) {
    Write-Host "  already staged: $TorExe"
} else {
    New-Item -ItemType Directory -Force -Path $TorDir | Out-Null
    # ensure_tor_binary(download=True) fetches+verifies the pinned Tor
    # Expert Bundle into the shared per-machine cache (LOCALAPPDATA/Loop/tor/...)
    # if it isn't already cached there; we then copy the resolved tor.exe
    # into packaging/tor/ for the spec's `datas` to pick up.
    $srcExe = & $Python -c "from hearth.tor import ensure_tor_binary; print(ensure_tor_binary(bundled_dir=None, download=True))"
    if ($LASTEXITCODE -ne 0 -or -not $srcExe) {
        Write-Error "ensure_tor_binary failed to resolve a tor.exe"
        exit 1
    }
    $srcExe = $srcExe.Trim()
    Write-Host "  resolved: $srcExe"
    Copy-Item -Path $srcExe -Destination $TorExe -Force
    Write-Host "  staged:   $TorExe"
}

$CoreVersion = (& $Python -c "from hearth import __version__; print(__version__)").Trim()
if ($LASTEXITCODE -ne 0 -or -not $CoreVersion) {
    Write-Error "could not resolve hearth.__version__ (CORE_VERSION)"
    exit 1
}
Write-Host "== Core version: $CoreVersion =="

function Invoke-PyInstaller {
    param($SpecPath, $DistPath, $WorkPath)
    # PyInstaller logs its normal INFO progress to stderr. Under
    # $ErrorActionPreference = "Stop" (set above), PowerShell 5.1 promotes
    # every stderr line from a native exe into a terminating
    # NativeCommandError -- aborting the script on the FIRST log line even
    # though PyInstaller hasn't failed. Relax to "Continue" for just this
    # call and check $LASTEXITCODE (the real success/failure signal)
    # afterward instead.
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $Python -m PyInstaller $SpecPath --distpath $DistPath --workpath $WorkPath --noconfirm
    $exitCode = $LASTEXITCODE
    $ErrorActionPreference = $prevEap
    if ($exitCode -ne 0) {
        Write-Error "PyInstaller build failed (exit $exitCode) for $SpecPath"
        exit 1
    }
}

$CoreDistDir = Join-Path $RepoRoot "dist\_core"
$LauncherDistDir = Join-Path $RepoRoot "dist\_launcher"
$FinalDistDir = Join-Path $RepoRoot "dist\Kreds"

Push-Location $RepoRoot
try {
    Write-Host "== Running PyInstaller (payload: kreds.spec) =="
    Invoke-PyInstaller (Join-Path $PackagingDir "kreds.spec") $CoreDistDir (Join-Path $RepoRoot "build\core")

    Write-Host "== Running PyInstaller (launcher: launcher.spec) =="
    Invoke-PyInstaller (Join-Path $PackagingDir "launcher.spec") $LauncherDistDir (Join-Path $RepoRoot "build\launcher")
} finally {
    Pop-Location
}

Write-Host "== Assembling dist\Kreds\ =="
# A running Kreds locks its own DLLs (pywebview/clr_loader) under dist\Kreds,
# so the rebuild below can't replace them. Fail early with a clear message.
$running = Get-Process -Name "Kreds" -ErrorAction SilentlyContinue
if ($running) {
    Write-Error "Kreds is running (PID $($running.Id -join ', ')) and is locking dist\Kreds. Fully quit Kreds (tray -> Quit, or Task Manager -> end 'Kreds'), then re-run this build."
    exit 1
}
if (Test-Path $FinalDistDir) {
    Remove-Item -Recurse -Force $FinalDistDir
}
New-Item -ItemType Directory -Force -Path (Join-Path $FinalDistDir "versions") | Out-Null

$LauncherExe = Join-Path $LauncherDistDir "Kreds.exe"
if (-not (Test-Path $LauncherExe)) {
    Write-Error "launcher build did not produce $LauncherExe"
    exit 1
}
Copy-Item -Path $LauncherExe -Destination (Join-Path $FinalDistDir "Kreds.exe") -Force
Write-Host "  launcher -> $(Join-Path $FinalDistDir 'Kreds.exe')"

$PayloadSrc = Join-Path $CoreDistDir "Kreds"
$PayloadDst = Join-Path $FinalDistDir "versions\$CoreVersion"
if (-not (Test-Path (Join-Path $PayloadSrc "Kreds.exe"))) {
    Write-Error "payload build did not produce $(Join-Path $PayloadSrc 'Kreds.exe')"
    exit 1
}
Move-Item -Path $PayloadSrc -Destination $PayloadDst
Write-Host "  payload  -> $PayloadDst"

Set-Content -Path (Join-Path $FinalDistDir "current") -Value $CoreVersion -NoNewline -Encoding ascii
Write-Host "  current  -> $CoreVersion"

Write-Host "== Build complete =="
Write-Host $FinalDistDir

# -- Installer (Inno Setup) ------------------------------------------------
# Files written by an installer carry no Mark-of-the-Web, which is what makes
# the pythonnet/.NET DLLs load on a downloaded copy (a portable zip needs a
# manual Unblock). Build KredsSetup.exe if the Inno Setup compiler is present.
Write-Host "== Installer (Inno Setup) =="
$Iss = Join-Path $PSScriptRoot "kreds.iss"
$Iscc = Get-Command "ISCC.exe" -ErrorAction SilentlyContinue
if (-not $Iscc) {
    foreach ($p in @("${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
                     "$env:ProgramFiles\Inno Setup 6\ISCC.exe",
                     "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe")) {
        if (Test-Path $p) { $Iscc = $p; break }
    }
}
if ($Iscc) {
    $IsccPath = if ($Iscc -is [string]) { $Iscc } else { $Iscc.Source }
    & $IsccPath "/DAppVersion=$CoreVersion" $Iss
    if ($LASTEXITCODE -eq 0) {
        Write-Host "  installer -> $(Join-Path $RepoRoot 'dist\KredsSetup.exe')"
    } else {
        Write-Warning "ISCC failed (exit $LASTEXITCODE) -- portable dist\Kreds is still usable."
    }
} else {
    Write-Warning "Inno Setup not found -- skipping installer. Install it from https://jrsoftware.org/isdl.php (or 'winget install JRSoftware.InnoSetup'), then re-run to produce dist\KredsSetup.exe. The portable dist\Kreds works too (recipients must right-click the zip -> Properties -> Unblock before extracting)."
}
