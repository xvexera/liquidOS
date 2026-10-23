# One-step installer for a new TV over wireless ADB. It sets up adb (Android platform-tools) if this PC has none,
# asks for the TV's IP (and the port and pairing code when the TV uses Wireless debugging), then disables the
# factory apps from that model's list, installs the launcher, grants its permissions, makes it Home and pushes a
# theme with its wallpapers. Everything goes through the official adb, which can also pair with a code.
#
# Opened by "Install on TV.cmd" at the repo root. Menu option 2 undoes what it did on a TV.
# INSTALL_TV_FUNCTIONS=1 only defines the functions (to try them one by one) and runs nothing.
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }
$repo = Split-Path -Parent $PSScriptRoot
$pkg = 'com.gathin.tvhome'
$knownStock = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx',
    'com.google.android.leanbacklauncher', 'com.mitv.tvhome')
$setup = 'com.google.android.tungsten.setupwraith'
$totalSteps = 7

# ---- look and feel -----------------------------------------------------------------------------------------
function Say([string]$text, [string]$color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Ok([string]$text) { Write-Host '   ✔ ' -ForegroundColor Green -NoNewline; Write-Host $text }
function Warn([string]$text) { Write-Host '   ! ' -ForegroundColor Yellow -NoNewline; Write-Host $text -ForegroundColor Yellow }
function Info([string]$text) { Write-Host '   · ' -ForegroundColor DarkCyan -NoNewline; Write-Host $text -ForegroundColor Gray }

function Banner {
    Clear-Host
    $c = 'Cyan'
    Say ''
    Say '   ████████╗██╗   ██╗    ██╗  ██╗ ██████╗ ███╗   ███╗███████╗' $c
    Say '   ╚══██╔══╝██║   ██║    ██║  ██║██╔═══██╗████╗ ████║██╔════╝' $c
    Say '      ██║   ██║   ██║    ███████║██║   ██║██╔████╔██║█████╗  ' $c
    Say '      ██║   ╚██╗ ██╔╝    ██╔══██║██║   ██║██║╚██╔╝██║██╔══╝  ' $c
    Say '      ██║    ╚████╔╝     ██║  ██║╚██████╔╝██║ ╚═╝ ██║███████╗' $c
    Say '      ╚═╝     ╚═══╝      ╚═╝  ╚═╝ ╚═════╝ ╚═╝     ╚═╝╚══════╝' $c
    Say '              Liquid Glass launcher for Android TV / Google TV' White
    Say ''
    Write-Host '              Created by ' -NoNewline -ForegroundColor DarkGray
    Write-Host 'Wade' -NoNewline -ForegroundColor White
    Write-Host '  ·  X: ' -NoNewline -ForegroundColor DarkGray
    Write-Host '@wade9_' -ForegroundColor Cyan
    Say '   ──────────────────────────────────────────────────────────────' DarkGray
}

# A step header with a bar of done/total steps: "STEP 3/7 ■■■□□□□  Install the launcher".
function Step([int]$n, [string]$title) {
    $bar = ('■' * $n) + ('□' * ($totalSteps - $n))
    Write-Host ''
    Write-Host "  STEP $n/$totalSteps " -NoNewline -ForegroundColor Black -BackgroundColor Cyan
    Write-Host " $bar " -NoNewline -ForegroundColor Cyan
    Write-Host " $title" -ForegroundColor White
    $host.UI.RawUI.WindowTitle = "TV Home installer · step $n/$totalSteps · by Wade (@wade9_)"
}

function Box([string[]]$lines, [string]$color = 'Green') {
    $w = ($lines | Measure-Object -Property Length -Maximum).Maximum + 2
    Say ('   ╭' + ('─' * $w) + '╮') $color
    foreach ($l in $lines) { Say ('   │ ' + $l.PadRight($w - 1) + '│') $color }
    Say ('   ╰' + ('─' * $w) + '╯') $color
}

function Fail([string]$text) {
    Write-Host ''
    Box @('Something went wrong', '', $text) 'Red'
    Read-Host '   Press Enter to close'
    exit 1
}

function Ask([string]$q, [string]$default = '') {
    Write-Host '   ? ' -NoNewline -ForegroundColor Cyan
    $a = Read-Host $q
    if ([string]::IsNullOrWhiteSpace($a)) { return $default }
    return $a.Trim()
}
function Yes([string]$q, [bool]$default = $true) {
    $a = Ask "$q $(if ($default) { '[Y/n]' } else { '[y/N]' })"
    if ($a -eq '') { return $default }
    return $a -match '^[yYsS]'
}

# ---- adb ---------------------------------------------------------------------------------------------------
$platformTools = Join-Path $repo 'tools\platform-tools'

# Downloads Google's platform-tools (adb) for Windows into $dest\platform-tools.
function Install-PlatformTools([string]$dest) {
    $zip = Join-Path $env:TEMP 'platform-tools-latest-windows.zip'
    Info 'Downloading Android platform-tools from dl.google.com (a few MB)...'
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
        Expand-Archive -Force $zip $dest
    } catch {
        return $false
    } finally {
        Remove-Item $zip -ErrorAction SilentlyContinue
    }
    return Test-Path (Join-Path $dest 'platform-tools\adb.exe')
}

function Find-Adb {
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    $onPath = Get-Command adb.exe -ErrorAction SilentlyContinue
    foreach ($c in @((Join-Path $platformTools 'adb.exe'), $sdk)) { if (Test-Path $c) { return $c } }
    if ($onPath) { return $onPath.Source }
    return $null
}

# Every call returns its output as one string (stderr too); never throws.
function Adb { ((& $script:adb @args 2>&1) | ForEach-Object { "$_" }) -join "`n" }
function Sh([string]$cmd) { ((& $script:adb -s $script:serial shell $cmd 2>&1) | ForEach-Object { "$_" }) -join "`n" }
function Last([string]$text) { ("$text" -split "`n" | Where-Object { $_ -match '\S' } | Select-Object -Last 1).Trim() }

# ---- connect -----------------------------------------------------------------------------------------------
function Connect-Tv {
    Box @('On the TV, turn on debugging:',
        '1. Settings > System (or Device Preferences) > About',
        '2. Press OK on "Build" 7 times until you are a developer',
        '3. Developer options > turn on "USB debugging", and also',
        '   "Network/ADB debugging" or "Wireless debugging" if you see it',
        '4. The IP address is under Settings > Network') 'DarkCyan'
    $ip = Ask 'TV IP address (e.g. 192.168.0.50)'
    if ($ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { Fail "'$ip' is not an IP address" }
    if (Yes 'Does the TV show a PAIRING CODE (Wireless debugging > Pair device with pairing code)?' $false) {
        $pairPort = Ask 'PAIRING port (shown next to the code)'
        $code = Ask '6-digit pairing code'
        $out = Adb pair "${ip}:$pairPort" $code
        if ($out -notmatch 'Successfully paired') { Fail "Pairing failed; check the port and the code (they change every time).`n$out" }
        Ok 'Paired'
        $port = Ask 'CONNECTION port (from "IP address & Port" on the Wireless debugging screen)'
    } else {
        $port = Ask 'Port' '5555'
    }
    $script:serial = "${ip}:$port"
    Adb connect $script:serial | Out-Null
    Warn 'If the TV asks "Allow USB debugging?", tick "Always allow" and press OK.'
    $spin = '|/-\'
    for ($i = 0; $i -lt 40; $i++) {
        $state = Last (Adb -s $script:serial get-state)
        if ($state -eq 'device') { Write-Host "`r" -NoNewline; Ok "Connected to $script:serial"; return }
        Write-Host ("`r   {0} Waiting for the TV...  " -f $spin[$i % 4]) -NoNewline -ForegroundColor DarkGray
        if ($i % 5 -eq 4) { Adb connect $script:serial | Out-Null }
        Start-Sleep -Seconds 2
    }
    Write-Host ''
    Fail "The TV did not accept the connection ($state). Did you accept the prompt on the TV? Is debugging on?"
}

# ---- undo --------------------------------------------------------------------------------------------------
function Undo {
    $ip = ($script:serial -split ':')[0]
    $records = @(Get-ChildItem (Join-Path $repo 'backup') -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^(install|instalar)-tv-' -and $_.Name -like "*$ip*" } | Sort-Object LastWriteTime)
    if ($records.Count -eq 0) { Fail 'There is no install record for this TV in the backup folder.' }
    $rec = $records[-1].FullName
    Info "Record: $rec"
    $lines = Get-Content $rec
    $stock = ($lines | Where-Object { $_ -like 'stock=*' } | Select-Object -First 1) -replace '^stock=', ''
    $disabled = @($lines | Where-Object { $_ -match '^(disabled)=' } | ForEach-Object { $_ -replace '^disabled=', '' })
    if (-not (Yes "Re-enable $($disabled.Count) apps and give Home back to the factory launcher ($stock)?")) { return }
    foreach ($p in $disabled) { Sh "pm enable --user 0 $p" | Out-Null; Ok "re-enabled $p" }
    if ($stock -and $stock -ne 'none') {
        Sh "pm enable --user 0 $stock" | Out-Null
        Sh ('cmd package set-home-activity --user 0 $(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME ' + $stock + ' | tail -n 1)') | Out-Null
    }
    Ok "Home is now: $(Last (Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'))"
    Info "The launcher is still installed; to remove it: adb -s $script:serial uninstall $pkg"
}

# ---- factory apps ------------------------------------------------------------------------------------------
function Find-List([string[]]$ids) {
    foreach ($f in Get-ChildItem (Join-Path $repo 'examples') -Recurse -Filter 'debloat.txt' -ErrorAction SilentlyContinue) {
        $head = (Get-Content $f.FullName -TotalCount 3) -join ' '
        foreach ($id in $ids) {
            if ($id -and $head -match "(^|[^A-Za-z0-9_])$([regex]::Escape($id))([^A-Za-z0-9_]|$)") { return $f.FullName }
        }
    }
    return $null
}

function Debloat([string]$list, [string]$homePkg, [string]$rec) {
    # The same reading as tools/disable.ps1: BOM, CRLF and comments away, then bare package names only.
    $pkgs = @(Get-Content $list | ForEach-Object { ($_ -replace '^﻿', '' -replace '#.*$', '').Trim() } |
        Where-Object { $_ } | ForEach-Object { ($_ -split '\s+')[0] })
    $bad = @($pkgs | Where-Object { $_ -notmatch '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' })
    if ($bad.Count) { Warn "The list has odd entries, skipping it: $($bad -join ', ')"; return }
    # Home and the stock launchers are handed over later, with a way back if the new Home does not take.
    $pkgs = @($pkgs | Where-Object { $_ -ne $homePkg -and $knownStock -notcontains $_ -and $_ -ne $pkg })
    # The Wi-Fi network saved in the first-run wizard belongs to setupwraith, and Android deletes it when
    # setupwraith is disabled: the TV would drop off Wi-Fi (and ADB with it).
    if ($pkgs -contains $setup) {
        $wifi = Sh 'dumpsys wifi'
        if ($wifi -notmatch 'cname=' -or $wifi -match 'cname=[^ ]*setupwraith') {
            Warn "Keeping ${setup}: it owns the saved Wi-Fi network, and the TV would lose it."
            Info 'To allow it: on the TV, forget the Wi-Fi network and join it again from Settings, then run this again.'
            $pkgs = @($pkgs | Where-Object { $_ -ne $setup })
        }
    }
    if ($pkgs.Count -eq 0) { Ok 'Nothing to disable.'; return }
    # A package with TV_INPUT_HARDWARE drives the HDMI/AV inputs: disabled, they show "No Signal" for good.
    $loop = 'for p in ' + ($pkgs -join ' ') + '; do dumpsys package $p | grep -q TV_INPUT_HARDWARE && echo $p; done; true'
    foreach ($p in ((Sh $loop) -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
        Warn "Keeping ${p}: it drives the HDMI/AV inputs."
        $pkgs = @($pkgs | Where-Object { $_ -ne $p })
    }
    $installed = (Sh 'pm list packages -e') -split "`n" | ForEach-Object { ($_ -replace '^package:', '').Trim() }
    $pkgs = @($pkgs | Where-Object { $installed -contains $_ })
    if ($pkgs.Count -eq 0) { Ok 'Nothing to disable: this TV is already clean.'; return }
    Info "Disabling $($pkgs.Count) apps (not deleted: the Undo option brings them back)."
    $i = 0
    foreach ($p in $pkgs) {
        $i++
        Write-Progress -Activity 'Disabling factory apps' -Status $p -PercentComplete (100 * $i / $pkgs.Count)
        if ($script:dryRun) { Info "(dry run) $p"; continue }
        $out = Sh "pm disable-user --user 0 $p"
        if ($out -match 'disabled') {
            Add-Content $rec "disabled=$p"
            Ok $p
        } else { Warn "$p : $out" }
    }
    Write-Progress -Activity 'Disabling factory apps' -Completed
}

# ---- Home --------------------------------------------------------------------------------------------------
function Find-Stock {
    $homePkg = (Last (Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')) -split '/' | Select-Object -First 1
    if ($knownStock -contains $homePkg) { return $homePkg }
    if ($homePkg -eq $pkg) { return 'none' }
    $claims = (Sh 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -split "`n" |
        ForEach-Object { ($_.Trim() -split '/')[0] } | Sort-Object -Unique
    $found = @($knownStock | Where-Object { $claims -contains $_ })
    if ($found.Count -eq 1) { return $found[0] }
    return 'none'
}

function Hand-Over([string]$stock) {
    $start = Sh "am start -W -n $pkg/.MainActivity"
    if ($start -notmatch 'Status: ok' -or $start -match '(?m)^Error') { Fail "The new launcher did not start; the factory one was left alone.`n$start" }
    Ok 'The new launcher starts'
    if ($stock -ne 'none') { Sh "pm disable-user --user 0 $stock" | Out-Null; Ok "Factory launcher turned off ($stock)" }
    Sh "cmd package set-home-activity --user 0 $pkg/.MainActivity" | Out-Null
    $now = Last (Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
    if ($now -like "$pkg/*") { Ok 'Home is now the new launcher'; return }
    # Not ours: put the stock launcher back so the TV is never left without a Home.
    if ($stock -ne 'none') {
        Sh "pm enable --user 0 $stock" | Out-Null
        Sh ('cmd package set-home-activity --user 0 $(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME ' + $stock + ' | tail -n 1)') | Out-Null
    }
    Fail "Home stayed on '$now', not on the new launcher; the factory launcher is back on. On Google TV this is usually ${setup}: it has to be disabled (see the Wi-Fi note above), then run this again."
}

# ---- theme -------------------------------------------------------------------------------------------------
$themeDir = "/sdcard/Android/data/$pkg/files"
$themeUri = "content://$pkg.theme"
$themeTmp = '/data/local/tmp/cyt-theme'

# Some firmware locks the shell out of Android/data: stage in /data/local/tmp and copy, or write through the
# launcher's ThemeProvider when the copy is refused (as tools/theme.sh does).
function Put-File([string]$local, [string]$rel) {
    $stage = "$themeTmp-$($rel -replace '/', '-')"
    Adb -s $script:serial push $local $stage | Out-Null
    Sh "cp $stage $themeDir/$rel 2>/dev/null || content write --uri $themeUri/$rel < $stage; rm -f $stage" | Out-Null
}

function Push-Theme([string]$theme) {
    Sh "mkdir -p $themeDir/wallpapers 2>/dev/null" | Out-Null
    Put-File (Join-Path $theme 'config.json') 'config.json'
    Ok 'Theme settings'
    $walls = @(Get-ChildItem (Join-Path $theme 'wallpapers') -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -match '^\.(jpe?g|png|webp)$' -and $_.Name -match '^[A-Za-z0-9_.-]+$' })
    $i = 0
    foreach ($w in $walls) {
        $i++
        Write-Progress -Activity 'Copying wallpapers' -Status $w.Name -PercentComplete (100 * $i / $walls.Count)
        Put-File $w.FullName "wallpapers/$($w.Name)"
    }
    Write-Progress -Activity 'Copying wallpapers' -Completed
    if ($walls.Count) { Ok "$($walls.Count) wallpapers" }
    $rev = [guid]::NewGuid().ToString('N')
    Sh "printf '%s' $rev > $themeTmp-theme_revision; cp $themeTmp-theme_revision $themeDir/theme_revision 2>/dev/null || content write --uri $themeUri/theme_revision < $themeTmp-theme_revision; rm -f $themeTmp-theme_revision" | Out-Null
    Sh "am force-stop $pkg; am start -n $pkg/.MainActivity" | Out-Null
    Ok "Theme $(Split-Path -Leaf $theme) applied"
}

if ($env:INSTALL_TV_FUNCTIONS) { return }

# ---- main --------------------------------------------------------------------------------------------------
Banner
Say ''
Say '   1  Install everything on a TV' White
Say '   2  Undo the install on a TV' White
$mode = Ask 'Choose (Enter = 1)' '1'

Step 1 'Get this PC ready (ADB)'
$script:adb = Find-Adb
if ($script:adb) {
    Ok "ADB found: $script:adb"
} else {
    Warn 'This PC has no ADB (Android platform-tools), which talks to the TV over Wi-Fi.'
    if (-not (Yes 'Download and set it up now (from Google, into tools\platform-tools)?')) { Fail 'ADB is needed to reach the TV.' }
    if (-not (Install-PlatformTools (Join-Path $repo 'tools'))) { Fail 'The ADB download did not work. Check the internet connection and try again.' }
    $script:adb = Join-Path $platformTools 'adb.exe'
    Ok "ADB ready: $script:adb"
}
Adb start-server | Out-Null
$apk = @((Join-Path $PSScriptRoot 'TVHome.apk'), (Join-Path $repo 'launcher\app\build\outputs\apk\release\app-release.apk')) |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if ($mode -ne '2') {
    if (-not $apk) { Fail 'The launcher APK is missing: build it with launcher\build-and-install.ps1, or put TVHome.apk in tools\.' }
    Ok ('Launcher APK: {0} ({1:N0} KB)' -f (Split-Path -Leaf $apk), ((Get-Item $apk).Length / 1KB))
}

Step 2 'Connect to the TV'
Connect-Tv
$model = Last (Sh 'getprop ro.product.model')
$device = Last (Sh 'getprop ro.product.device')
$product = Last (Sh 'getprop ro.product.name')
$android = Last (Sh 'getprop ro.build.version.release')
Ok "TV: $model ($device, Android $android)"
if ($mode -eq '2') { Step 3 'Undo'; Undo; Read-Host '   Done. Press Enter to close'; exit 0 }

$stock = Find-Stock
New-Item -ItemType Directory -Force (Join-Path $repo 'backup') | Out-Null
$rec = Join-Path $repo ("backup\install-tv-{0}-{1}.txt" -f ($script:serial -split ':')[0], (Get-Date -Format 'yyyyMMdd-HHmmss'))
Set-Content $rec @("tv=$model ($device)", "stock=$stock")

Step 3 'Remove factory apps'
$list = Find-List @($product, $device, $model)
$debloated = 'skipped'
if ($list) {
    Info "List for this model: $(Split-Path -Leaf (Split-Path -Parent $list))"
    if (Yes 'Disable the apps on that list?') { Debloat $list $stock $rec; $debloated = 'done' }
} else {
    Warn 'There is no app list for this model in examples\; skipping this step.'
}

Step 4 'Install the launcher'
Adb -s $script:serial push $apk /data/local/tmp/tvhome.apk | Out-Null
$out = Sh 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
if ($out -notmatch 'Success') { Fail "Could not install the APK; the TV is as it was.`n$out" }
Ok 'Installed'

Step 5 'Permissions'
$perms = [ordered]@{
    'Wi-Fi name'           = "pm grant $pkg android.permission.ACCESS_FINE_LOCATION"
    'Calendar'             = "pm grant $pkg android.permission.READ_CALENDAR"
    'Night mode'           = "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
    'Night mode overlay'   = "appops set $pkg SYSTEM_ALERT_WINDOW allow"
    'Real TV brightness'   = "appops set $pkg WRITE_SETTINGS allow"
    'Now playing widget'   = "cmd notification allow_listener $pkg/$pkg.MediaListener"
}
foreach ($k in $perms.Keys) {
    $o = Sh "$($perms[$k]) 2>&1"
    if ($o -match 'Exception|Error|Unknown') { Warn "$k (not on this TV)" } else { Ok $k }
}
Info 'Optimizing the launcher so it opens fast (can take a minute)...'
Sh "cmd package compile -m speed -f $pkg" | Out-Null
Ok 'Optimized'

Step 6 "Make it the Home screen"
Info "Factory launcher: $stock"
Hand-Over $stock

Step 7 'Theme and wallpapers'
$themes = @(Get-ChildItem (Join-Path $repo 'themes') -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'config.json') })
$default = if ($themes.Name -contains 'mi-onn') { 'mi-onn' } else { '' }
Info ('Themes: ' + ($themes.Name -join ', '))
$name = Ask "Theme to apply (Enter = $(if ($default) { $default } else { 'none' }); type 'no' to skip)" $default
$themed = 'skipped'
if ($name -and $name -ne 'no') {
    $theme = Join-Path $repo "themes\$name"
    if (Test-Path (Join-Path $theme 'config.json')) { Push-Theme $theme; $themed = $name } else { Warn "There is no theme called $name; skipping it." }
}

Write-Host ''
Box @('All done!  The TV now starts on the new launcher.',
    '',
    "TV            $model",
    "Factory apps  $debloated",
    "Theme         $themed",
    "Undo record   backup\$(Split-Path -Leaf $rec)",
    '',
    'On the TV: pick a wallpaper and your settings in the launcher menu.',
    "The calendar shows the Google accounts of that TV.",
    '',
    'Created by Wade  ·  X: @wade9_') 'Green'
if (Yes 'Disconnect this PC from the TV now?') { Adb disconnect $script:serial | Out-Null; Ok 'Disconnected' }
Warn 'Tip: when you are done, turn USB / network debugging off in the TV''s Developer options.'
Read-Host '   Press Enter to close'
