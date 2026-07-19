# Session env for the Android spike toolchain. The JDK/SDK were installed
# with User-level env vars (2026-07-19); a shell opened before that -- or
# a Claude tool session -- does not inherit them. Dot-source this first:
#   . .\android_tor_spike\tools\env.ps1
$env:JAVA_HOME = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'User')
$env:ANDROID_HOME = [Environment]::GetEnvironmentVariable('ANDROID_HOME', 'User')
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;" +
            "$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
