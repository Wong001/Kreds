; Inno Setup script for Kreds -- builds KredsSetup.exe from the PyInstaller
; one-folder build in ..\dist\Kreds (launcher Kreds.exe + versions\<ver>\ + current).
;
; WHY an installer: a portable zip downloaded from the internet carries the
; Mark-of-the-Web, and .NET Framework then refuses to run pywebview's bundled
; pythonnet DLLs ("Failed to resolve Python.Runtime.Loader.Initialize") -> the
; app won't launch on a clean machine without a manual Unblock. Files written
; by an installer carry NO Mark-of-the-Web, so this fixes it cleanly.
;
; INSTALL LOCATION: per-user {localappdata}\Programs\Kreds (PrivilegesRequired=
; lowest -> no admin/UAC). Crucially this dir is USER-WRITABLE, so the
; on-restart core-swap updater can drop new versions\<ver>\ payloads without
; elevation. User data lives separately in %APPDATA%\Kreds and is untouched by
; install/uninstall.
;
; BUILD: ISCC.exe /DAppVersion=<ver> packaging\kreds.iss  (build.ps1 does this
; automatically when Inno Setup is installed). Unsigned for now (SmartScreen
; warns on the installer -> "More info" -> "Run anyway"); Authenticode later.

#ifndef AppVersion
  #define AppVersion "0.0.0"
#endif

[Setup]
AppName=Kreds
AppVersion={#AppVersion}
AppPublisher=Kreds
DefaultDirName={localappdata}\Programs\Kreds
DisableProgramGroupPage=yes
DisableDirPage=yes
PrivilegesRequired=lowest
OutputBaseFilename=KredsSetup
OutputDir=..\dist
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
SetupIconFile=kreds.ico
UninstallDisplayIcon={app}\Kreds.exe
; The installed launcher; auto-update maintains versions\ underneath.
AppId={{9F4C2E7A-3B21-4D8E-9C5F-KREDS0AUTOUPD}}

[Files]
Source: "..\dist\Kreds\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
Name: "{autoprograms}\Kreds"; Filename: "{app}\Kreds.exe"
Name: "{autodesktop}\Kreds"; Filename: "{app}\Kreds.exe"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"

[Run]
Filename: "{app}\Kreds.exe"; Description: "Launch Kreds now"; Flags: nowait postinstall skipifsilent
