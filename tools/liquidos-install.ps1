# liquidOS installer: puts the liquidOS launcher on an Android TV / Google TV over Wi-Fi in one go.
# It sets up adb (Android platform-tools) when this PC has none, finds the TV on the network by itself (or takes
# its IP), pairs with a code when the TV uses Wireless debugging, then removes the factory bloat from that
# model's list, installs the launcher, grants its permissions, makes it Home and copies a theme with wallpapers.
#
# Opened by "liquidOS Installer.cmd" at the repo root. Menu option 2 undoes what it did on a TV.
# LIQUIDOS_FUNCTIONS=1 only defines the functions (to try them one by one) and runs nothing.
#
# Only characters every Windows console font has are used here (the WGL4 set: block and box-drawing glyphs,
# no emoji, no rounded corners), so the art never turns into question marks.
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }
$repo = Split-Path -Parent $PSScriptRoot
$pkg = 'com.gathin.tvhome'
$knownStock = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx',
    'com.google.android.leanbacklauncher', 'com.mitv.tvhome')
$setup = 'com.google.android.tungsten.setupwraith'
$totalSteps = 8

# ---- colour: the six stripes of the retro Apple logo -------------------------------------------------------
# 24-bit colour where the console understands it (Windows 10 and later), else the closest of the 16 classic ones.
$esc = [char]27
$vt = $false
try { $vt = [bool]$Host.UI.SupportsVirtualTerminal } catch { }
if ($env:LIQUIDOS_PLAIN) { $vt = $false }
$rainbow = @(
    @{ rgb = '97;187;70'; old = 'Green' },       # green
    @{ rgb = '253;184;39'; old = 'Yellow' },     # yellow
    @{ rgb = '245;130;31'; old = 'DarkYellow' }, # orange
    @{ rgb = '224;58;62'; old = 'Red' },         # red
    @{ rgb = '150;61;151'; old = 'Magenta' },    # purple
    @{ rgb = '0;157;220'; old = 'Cyan' }         # blue
)
function Paint([string]$text, [int]$i, [switch]$NoNewline) {
    $c = $rainbow[$i % 6]
    if ($vt) { Write-Host "$esc[38;2;$($c.rgb)m$text$esc[0m" -NoNewline:$NoNewline }
    else { Write-Host $text -ForegroundColor $c.old -NoNewline:$NoNewline }
}
function Say([string]$text, [string]$color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Ok([string]$text) { Paint '   √ ' 0 -NoNewline; Write-Host $text }
function Warn([string]$text) { Paint '   ! ' 1 -NoNewline; Paint $text 1 }
function Info([string]$text) { Paint '   · ' 5 -NoNewline; Write-Host $text -ForegroundColor Gray }

# The stripes, as on the old logo: one block of each colour.
function Stripes([int]$width = 62) {
    $seg = [math]::Floor($width / 6)
    Write-Host '   ' -NoNewline
    for ($i = 0; $i -lt 6; $i++) { Paint ('█' * $seg) $i -NoNewline }
    Write-Host ''
}

$logo = @(
    '██╗     ██╗ ██████╗ ██╗   ██╗██╗██████╗  ██████╗ ███████╗',
    '██║     ██║██╔═══██╗██║   ██║██║██╔══██╗██╔═══██╗██╔════╝',
    '██║     ██║██║   ██║██║   ██║██║██║  ██║██║   ██║███████╗',
    '██║     ██║██║▄▄ ██║██║   ██║██║██║  ██║██║   ██║╚════██║',
    '███████╗██║╚██████╔╝╚██████╔╝██║██████╔╝╚██████╔╝███████║',
    '╚══════╝╚═╝ ╚══▀▀═╝  ╚═════╝ ╚═╝╚═════╝  ╚═════╝ ╚══════╝'
)
$debloaterArt = @(
    '██████  ███████ ██████  ██       ██████   █████  ████████ ███████ ██████ ',
    '██   ██ ██      ██   ██ ██      ██    ██ ██   ██    ██    ██      ██   ██',
    '██   ██ █████   ██████  ██      ██    ██ ███████    ██    █████   ██████ ',
    '██   ██ ██      ██   ██ ██      ██    ██ ██   ██    ██    ██      ██   ██',
    '██████  ███████ ██████  ███████  ██████  ██   ██    ██    ███████ ██   ██'
)
$doneArt = @(
    '██████╗  ██████╗ ███╗   ██╗███████╗',
    '██╔══██╗██╔═══██╗████╗  ██║██╔════╝',
    '██║  ██║██║   ██║██╔██╗ ██║█████╗  ',
    '██║  ██║██║   ██║██║╚██╗██║██╔══╝  ',
    '██████╔╝╚██████╔╝██║ ╚████║███████╗',
    '╚═════╝  ╚═════╝ ╚═╝  ╚═══╝╚══════╝'
)
function Art([string[]]$lines, [int]$indent = 3) {
    for ($i = 0; $i -lt $lines.Count; $i++) { Paint ((' ' * $indent) + $lines[$i]) $i }
}

function Banner {
    Clear-Host
    Write-Host ''
    Art $logo
    Write-Host ''
    Stripes
    Write-Host ''
    Say '   The liquidOS launcher + debloater for Android TV and Google TV' White
    Write-Host '   Created by ' -NoNewline -ForegroundColor DarkGray
    Write-Host 'Wade' -NoNewline -ForegroundColor White
    Write-Host '   ·   X: ' -NoNewline -ForegroundColor DarkGray
    Paint '@wade9_' 5
    Write-Host ''
}

# "STEP 3/7 ■■■□□□□ Remove factory apps", the badge in the step's own stripe colour.
function Step([int]$n, [string]$title, [string]$what) {
    $bar = ('■' * $n) + ('□' * ($totalSteps - $n))
    Write-Host ''
    Paint "  ► STEP $n of $totalSteps  " ($n - 1) -NoNewline
    Paint $bar ($n - 1) -NoNewline
    Write-Host "   $title" -ForegroundColor White
    if ($what) { Say "    $what" DarkGray }
    $host.UI.RawUI.WindowTitle = "liquidOS installer  -  step $n of $totalSteps  -  by Wade (@wade9_)"
}

# Splits text into lines of at most $max characters, at spaces, keeping a line's leading indent on its
# continuation lines.
function Wrap([string[]]$lines, [int]$max = 72) {
    foreach ($line in $lines) {
        if ($line.Length -le $max) { $line; continue }
        $indent = ' ' * ($line.Length - $line.TrimStart().Length)
        $cur = ''
        foreach ($word in ($line.Trim() -split ' ')) {
            $next = if ($cur) { "$cur $word" } else { "$indent$word" }
            if ($next.Length -gt $max -and $cur) { $cur; $cur = "$indent$word" } else { $cur = $next }
        }
        if ($cur) { $cur }
    }
}

# A double-line frame (box-drawing characters every console font has), never wider than the window.
function Box([string[]]$lines, [int]$color = 5) {
    $max = 72
    try { $max = [math]::Max(40, [math]::Min(90, $host.UI.RawUI.WindowSize.Width - 10)) } catch { }
    $lines = @(Wrap $lines $max)
    $w = ($lines | Measure-Object -Property Length -Maximum).Maximum + 2
    Paint ('   ╔' + ('═' * $w) + '╗') $color
    foreach ($l in $lines) {
        Paint '   ║ ' $color -NoNewline
        Write-Host $l.PadRight($w - 1) -NoNewline -ForegroundColor White
        Paint '║' $color
    }
    Paint ('   ╚' + ('═' * $w) + '╝') $color
}

function Bye([int]$code) { Write-Host ''; Read-Host '   Press Enter to close' | Out-Null; exit $code }
function Fail([string]$text, [string]$fix = '') {
    Write-Host ''
    $lines = @('Something went wrong.', '') + ($text -split "`n" | Where-Object { $_ })
    if ($fix) { $lines += @('', 'What to do:') + ($fix -split "`n") }
    Box $lines 3
    Bye 1
}

function Ask([string]$q, [string]$default = '') {
    Paint '   ? ' 5 -NoNewline
    $a = Read-Host $q
    if ([string]::IsNullOrWhiteSpace($a)) { return $default }
    return $a.Trim()
}
function Yes([string]$q, [bool]$default = $true) {
    while ($true) {
        $a = Ask "$q $(if ($default) { '[Y/n]' } else { '[y/N]' })"
        if ($a -eq '') { return $default }
        if ($a -match '^(y|yes|s|si|sí)$') { return $true }
        if ($a -match '^(n|no)$') { return $false }
        Warn 'Please answer y (yes) or n (no).'
    }
}

function Spin([int]$i, [string]$text) {
    Write-Host ("`r   {0} {1}   " -f '|/-\'[$i % 4], $text) -NoNewline -ForegroundColor DarkGray
}

# ---- adb ---------------------------------------------------------------------------------------------------
$platformTools = Join-Path $repo 'tools\platform-tools'

# Downloads Google's platform-tools (adb) for Windows into $dest\platform-tools.
function Install-PlatformTools([string]$dest) {
    $zip = Join-Path $env:TEMP 'platform-tools-latest-windows.zip'
    Info 'Downloading Android platform-tools from Google (dl.google.com, a few MB)...'
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

# ---- finding the TV ----------------------------------------------------------------------------------------
# This PC's own IPv4 networks (Wi-Fi or cable), as "a.b.c" prefixes to scan.
function Local-Prefixes {
    $ips = @()
    try {
        $ips = @(Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' -and $_.PrefixLength -ge 16 -and
                # virtual adapters (WSL, Hyper-V, VirtualBox, VMware, VPNs) have no TV on them
                $_.InterfaceAlias -notmatch 'vEthernet|WSL|Hyper-V|VirtualBox|VMware|Loopback|Tailscale|ZeroTier|VPN' } |
            ForEach-Object { $_.IPAddress })
    } catch {
        $ips = @([Net.Dns]::GetHostAddresses([Net.Dns]::GetHostName()) |
            Where-Object { $_.AddressFamily -eq 'InterNetwork' -and "$_" -notmatch '^(127\.|169\.254\.)' } | ForEach-Object { "$_" })
    }
    return @($ips | ForEach-Object { ($_ -split '\.')[0..2] -join '.' } | Sort-Object -Unique)
}

# TVs that answer ADB: port 5555 open on this PC's networks, plus TVs announcing Wireless debugging.
function Find-Tvs {
    $found = [ordered]@{}
    foreach ($prefix in Local-Prefixes) {
        $probes = @()
        for ($h = 1; $h -le 254; $h++) {
            $c = New-Object Net.Sockets.TcpClient
            $probes += [pscustomobject]@{ ip = "$prefix.$h"; client = $c; task = $c.ConnectAsync("$prefix.$h", 5555) }
        }
        try { [void][Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]@($probes.task), 1500) } catch { }
        foreach ($p in $probes) {
            if ($p.task.Status -eq 'RanToCompletion' -and $p.client.Connected) {
                $found[$p.ip] = [pscustomobject]@{ ip = $p.ip; port = '5555'; kind = 'ADB over network' }
            }
            $p.client.Close()
        }
    }
    foreach ($line in (Adb mdns services) -split "`n") {
        if ($line -match '_adb-tls-connect\._tcp\.?\s+(\d{1,3}(\.\d{1,3}){3}):(\d+)') {
            if (-not $found.Contains($Matches[1])) {
                $found[$Matches[1]] = [pscustomobject]@{ ip = $Matches[1]; port = $Matches[3]; kind = 'Wireless debugging' }
            }
        }
    }
    return @($found.Values)
}

# The pairing port a TV announces while its "Pair device with pairing code" screen is open.
function Pairing-Port([string]$ip) {
    foreach ($line in (Adb mdns services) -split "`n") {
        if ($line -match "_adb-tls-pairing\._tcp\.?\s+$([regex]::Escape($ip)):(\d+)") { return $Matches[1] }
    }
    return $null
}

function Show-Howto {
    Box @('Get the TV ready (one time only):',
        '',
        '1. On the TV open Settings > System (or Device Preferences) > About.',
        '2. Press OK on "Build" 7 times, until it says you are a developer.',
        '3. Go back to Settings > Developer options and turn on',
        '   "USB debugging", and also "Network debugging" or',
        '   "Wireless debugging" if your TV has it.',
        '4. Keep the TV on and on the same Wi-Fi as this PC.') 5
}

function Choose-Tv {
    while ($true) {
        Info 'Looking for TVs on your network (a few seconds)...'
        $tvs = @(Find-Tvs)
        if ($tvs.Count -gt 0) {
            for ($i = 0; $i -lt $tvs.Count; $i++) {
                Paint ("     {0})  " -f ($i + 1)) $i -NoNewline
                Write-Host ("{0,-16} {1}" -f $tvs[$i].ip, $tvs[$i].kind) -ForegroundColor White
            }
            Say '     M)  Type the IP address myself     R)  Search again' Gray
            $a = Ask 'Which one is your TV? (Enter = 1)' '1'
            if ($a -match '^\d+$' -and [int]$a -ge 1 -and [int]$a -le $tvs.Count) { return $tvs[[int]$a - 1] }
            if ($a -match '^[rR]') { continue }
            if ($a -notmatch '^[mM]') { Warn 'Type the number from the list, M or R.'; continue }
        } else {
            Warn 'No TV found yet. Check that debugging is on (see the box above) and the TV is awake.'
            if (Yes 'Search again?') { continue }
        }
        while ($true) {
            $ip = Ask 'TV IP address (Settings > Network on the TV, e.g. 192.168.0.50)'
            if ($ip -match '^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$' -and
                -not (@($Matches[1..4]) | Where-Object { [int]$_ -gt 255 })) { break }
            Warn "'$ip' does not look like an IP address. It has four numbers with dots, like 192.168.0.50."
        }
        $port = Ask 'Port (Enter = 5555; on "Wireless debugging" TVs it is the number after the colon)' '5555'
        return [pscustomobject]@{ ip = $ip; port = $port; kind = 'typed' }
    }
}

# Waits for the TV to accept this PC; true once adb sees it as a device.
function Wait-Authorized([int]$seconds = 90) {
    $shown = $false
    for ($i = 0; $i -lt $seconds; $i++) {
        $state = Last (Adb -s $script:serial get-state)
        if ($state -eq 'device') { Write-Host "`r                                                            `r" -NoNewline; return $true }
        if ($state -match 'unauthorized' -and -not $shown) {
            Write-Host ''
            Warn 'Look at the TV: it asks "Allow USB debugging?". Tick "Always allow" and press OK.'
            $shown = $true
        }
        Spin $i 'Waiting for the TV...'
        if ($i % 6 -eq 5) { Adb connect $script:serial | Out-Null }
        Start-Sleep -Seconds 1
    }
    Write-Host ''
    return $false
}

function Pair([string]$ip) {
    Box @('This TV needs a one-time pairing code:',
        '',
        'On the TV open Developer options > Wireless debugging',
        '> "Pair device with pairing code". Leave that screen open.') 1
    for ($try = 1; $try -le 3; $try++) {
        $port = $null
        for ($i = 0; $i -lt 20 -and -not $port; $i++) { Spin $i 'Looking for the pairing screen...'; $port = Pairing-Port $ip; if (-not $port) { Start-Sleep 1 } }
        Write-Host "`r                                                            `r" -NoNewline
        if (-not $port) { $port = Ask 'Pairing PORT shown on the TV (the number after the colon)' }
        $code = Ask 'The 6-digit code shown on the TV'
        $out = Adb pair "${ip}:$port" ($code -replace '\s', '')
        if ($out -match 'Successfully paired') { Ok 'Paired'; return $true }
        Warn "That did not work ($((Last $out))). The code changes each time: check it on the TV and try again."
    }
    return $false
}

function Connect-Tv {
    Show-Howto
    Reconnect-Tv
}

# Lets the owner pick the TV (network scan, or its IP) and connects, pairing when needed; loops until it works.
function Reconnect-Tv {
    while ($true) {
        $tv = Choose-Tv
        $script:serial = "$($tv.ip):$($tv.port)"
        $out = Adb connect $script:serial
        if ($out -match 'connected to' -and (Wait-Authorized)) { Ok "Connected to $script:serial"; return }
        # Wireless debugging TVs refuse the connection until this PC is paired.
        if ($tv.port -ne '5555' -or $tv.kind -eq 'Wireless debugging') {
            if (Pair $tv.ip) {
                Adb connect $script:serial | Out-Null
                if (Wait-Authorized 30) { Ok "Connected to $script:serial"; return }
            }
        }
        Warn "Could not connect to $script:serial."
        Info 'Check: the TV is on, debugging is on, the TV and this PC are on the same Wi-Fi.'
        if (-not (Yes 'Try again?')) { Fail 'The TV did not accept the connection.' 'Turn on debugging on the TV (see the first box), then open the installer again.' }
    }
}

# ---- backup ------------------------------------------------------------------------------------------------
# Saves the TV's state before anything changes, as plain text files in backup\liquidos-<ip>-<time>\: which apps
# are on and off, which app is Home, the system settings, the TV's identity, and optionally the apps the owner
# installed (their APK files). Undo reads it back.
function Backup-Tv([string]$dir) {
    New-Item -ItemType Directory -Force $dir | Out-Null
    $files = [ordered]@{
        'tv.txt'              = 'getprop'
        'home.txt'            = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
        'apps-on.txt'         = 'pm list packages -e'
        'apps-off.txt'        = 'pm list packages -d'
        'apps-all.txt'        = 'pm list packages -f --show-versioncode'
        'settings-global.txt' = 'settings list global'
        'settings-secure.txt' = 'settings list secure'
        'settings-system.txt' = 'settings list system'
    }
    $i = 0
    foreach ($name in $files.Keys) {
        $i++
        Write-Progress -Activity 'Backing up the TV' -Status $name -PercentComplete (100 * $i / $files.Count)
        Set-Content -Encoding UTF8 (Join-Path $dir $name) (Sh $files[$name])
    }
    Write-Progress -Activity 'Backing up the TV' -Completed
    Ok 'App list, Home, system settings and TV details saved'
    # The apps the owner installed (Play Store or sideloaded): their APKs, if they want them.
    $apps = @((Sh 'pm list packages -3 -f') -split "`n" | ForEach-Object {
            if ($_.Trim() -match '^package:(.+\.apk)=([A-Za-z0-9_.]+)$' -and $Matches[2] -ne $pkg) {
                [pscustomobject]@{ path = $Matches[1]; name = $Matches[2] } } })
    if ($apps.Count) {
        $bytes = 0
        $sizes = (Sh ('stat -c %s ' + (($apps | ForEach-Object { $_.path }) -join ' ') + ' 2>/dev/null')) -split "`n"
        foreach ($n in $sizes) { if ($n.Trim() -match '^\d+$') { $bytes += [long]$n.Trim() } }
        if (Yes ("Also save the {0} apps you installed yourself ({1:N0} MB)?" -f $apps.Count, ($bytes / 1MB)) $false) {
            $apkDir = Join-Path $dir 'apps'
            New-Item -ItemType Directory -Force $apkDir | Out-Null
            $i = 0
            foreach ($a in $apps) {
                $i++
                Write-Progress -Activity 'Saving your apps' -Status $a.name -PercentComplete (100 * $i / $apps.Count)
                Adb -s $script:serial pull $a.path (Join-Path $apkDir "$($a.name).apk") | Out-Null
            }
            Write-Progress -Activity 'Saving your apps' -Completed
            Ok "$($apps.Count) apps saved"
        }
    }
    Set-Content -Encoding UTF8 (Join-Path $dir 'README.txt') @(
        'liquidOS installer - backup of the TV before any change',
        '',
        'apps-on.txt / apps-off.txt   which apps were turned on and off',
        'home.txt                     the app the Home button opened',
        'settings-*.txt               the system settings (global, secure, system)',
        'tv.txt                       model, Android version and build (getprop)',
        'apps-all.txt                 every app with its version',
        'apps\                        the APKs of the apps you installed (if you chose to save them)',
        '',
        'To put the TV back: open "liquidOS Installer.cmd" and choose 2 (Undo).')
    Ok "Backup saved in backup\$(Split-Path -Leaf $dir)"
}

# ---- undo --------------------------------------------------------------------------------------------------
function Undo {
    $ip = ($script:serial -split ':')[0]
    $backups = @(Get-ChildItem (Join-Path $repo 'backup') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "liquidos-$ip-*" -and (Test-Path (Join-Path $_.FullName 'apps-on.txt')) } | Sort-Object Name)
    if ($backups.Count -eq 0) { Fail 'There is no backup of this TV on this PC.' 'Undo works on a TV this installer set up from this PC (folder backup).' }
    $dir = $backups[0].FullName
    if ($backups.Count -gt 1) {
        for ($i = 0; $i -lt $backups.Count; $i++) { Paint ("     {0})  " -f ($i + 1)) $i -NoNewline; Write-Host $backups[$i].Name -ForegroundColor White }
        $a = Ask 'Which backup? (Enter = 1, from before the first install)' '1'
        if ($a -match '^\d+$' -and [int]$a -ge 1 -and [int]$a -le $backups.Count) { $dir = $backups[[int]$a - 1].FullName }
    }
    Info "Backup: $(Split-Path -Leaf $dir)"
    $wasOn = @(Get-Content (Join-Path $dir 'apps-on.txt') | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ })
    $nowOff = @((Sh 'pm list packages -d') -split "`n" | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ })
    $back = @($nowOff | Where-Object { $wasOn -contains $_ })
    $homeWas = Last (Get-Content (Join-Path $dir 'home.txt') -Raw)
    if (-not (Yes "Turn $($back.Count) apps back on and make Home open $homeWas again?")) { return }
    foreach ($p in $back) { Sh "pm enable --user 0 $p" | Out-Null; Ok "back on: $p" }
    if ($homeWas -match '^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$') { Sh "cmd package set-home-activity --user 0 $homeWas" | Out-Null }
    Ok "Home is now: $(Last (Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'))"
    if ($homeWas -notlike "$pkg/*" -and (Yes 'Also remove the liquidOS app?' $false)) { Info (Adb -s $script:serial uninstall $pkg) }
}

# ---- debloater ---------------------------------------------------------------------------------------------
function Find-List([string[]]$ids) {
    foreach ($f in Get-ChildItem (Join-Path $repo 'examples') -Recurse -Filter 'debloat.txt' -ErrorAction SilentlyContinue) {
        $head = (Get-Content $f.FullName -TotalCount 3) -join ' '
        foreach ($id in $ids) {
            if ($id -and $head -match "(^|[^A-Za-z0-9_])$([regex]::Escape($id))([^A-Za-z0-9_]|$)") { return $f.FullName }
        }
    }
    return $null
}

# The packages that will really be disabled: the list minus Home, the launchers, the Wi-Fi owner, the HDMI
# drivers and whatever is not installed or already off.
function Plan-Debloat([string]$list, [string]$homePkg) {
    # The same reading as tools/disable.ps1: BOM, CRLF and comments away, then bare package names only.
    $pkgs = @(Get-Content $list | ForEach-Object { ($_ -replace '^\uFEFF', '' -replace '#.*$', '').Trim() } |
        Where-Object { $_ } | ForEach-Object { ($_ -split '\s+')[0] })
    if (@($pkgs | Where-Object { $_ -notmatch '^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$' }).Count) {
        Warn 'The app list for this TV has odd entries, so it is not used.'
        return @()
    }
    # Home and the stock launchers are handed over later, with a way back if the new Home does not take.
    $pkgs = @($pkgs | Where-Object { $_ -ne $homePkg -and $knownStock -notcontains $_ -and $_ -ne $pkg })
    # The Wi-Fi network saved in the first-run wizard belongs to setupwraith, and Android deletes it when
    # setupwraith is disabled: the TV would drop off Wi-Fi (and the installer with it).
    if ($pkgs -contains $setup -and (Setup-On)) {
        if (Wifi-Held) {
            Info 'Keeping the Google setup app: the saved Wi-Fi belongs to it, and the TV would lose the network.'
            $pkgs = @($pkgs | Where-Object { $_ -ne $setup })
        }
    }
    if ($pkgs.Count -eq 0) { return @() }
    # A package with TV_INPUT_HARDWARE drives the HDMI/AV inputs: disabled, they show "No Signal" for good.
    $loop = 'for p in ' + ($pkgs -join ' ') + '; do dumpsys package $p | grep -q TV_INPUT_HARDWARE && echo $p; done; true'
    $inputs = @((Sh $loop) -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($inputs.Count) { Info "Keeping $($inputs.Count) apps that run the HDMI inputs." }
    $installed = (Sh 'pm list packages -e') -split "`n" | ForEach-Object { ($_ -replace '^package:', '').Trim() }
    return @($pkgs | Where-Object { $inputs -notcontains $_ -and $installed -contains $_ })
}

function Debloat([string[]]$pkgs, [string]$rec) {
    $i = 0
    foreach ($p in $pkgs) {
        $i++
        Write-Progress -Activity 'liquidOS debloater' -Status $p -PercentComplete (100 * $i / $pkgs.Count)
        if ($script:dryRun) { Info "(dry run) $p"; continue }
        $out = Sh "pm disable-user --user 0 $p"
        if ($out -match 'disabled') { Add-Content $rec "disabled=$p"; Ok $p } else { Warn "$p : $(Last $out)" }
    }
    Write-Progress -Activity 'liquidOS debloater' -Completed
}

# ---- the Google setup app ----------------------------------------------------------------------------------
# On Google TV, com.google.android.tungsten.setupwraith answers Home too (its RecoveryActivity): once the
# factory launcher is off it takes Home instead of liquidOS. It has to be off first. But the Wi-Fi network
# saved in the TV's first setup belongs to it, and turning it off deletes that network: the TV would drop off
# Wi-Fi. Joining the network again from Settings makes the system its owner, and then it is safe.
function Setup-On { return ((Sh "pm list packages -e $setup") -match [regex]::Escape("package:$setup")) }
function Setup-Claims-Home {
    return ((Sh 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -match [regex]::Escape("$setup/"))
}
function On-Wifi { return ((Sh 'ip route get 1.1.1.1') -match 'dev wlan') }
# True when the TV is on Wi-Fi and the saved network belongs to setupwraith (or that cannot be read).
# True when the TV is on Wi-Fi and the network it is on now belongs to setupwraith. Other saved networks do
# not matter (losing them costs nothing now); a network whose owner cannot be read counts as held, to be safe.
function Wifi-Held {
    if (-not (On-Wifi)) { return $false }
    $wifi = Sh 'dumpsys wifi'
    $at = $wifi.IndexOf('Currently Connected: true')
    if ($at -lt 0) { return $true }
    if ($wifi.Substring($at) -match 'cname=(\S+)') { return $Matches[1] -match 'setupwraith' }
    return $true
}
function Wifi-Name {
    if ((Sh 'cmd wifi status') -match 'Wifi is connected to "([^"]+)"') { return $Matches[1] }
    return 'your Wi-Fi network'
}

# Walks the owner through forgetting and re-joining the Wi-Fi on the TV, reconnecting by itself while the TV
# is off the network. True once the network no longer belongs to setupwraith.
function Rejoin-Wifi {
    $ssid = Wifi-Name
    Box @('One quick thing on the TV, then liquidOS can be the Home screen.',
        '',
        "Google's setup app still owns the Wi-Fi network ""$ssid"". To hand it over:",
        '',
        "1. On the TV: Settings > Network & Internet > ""$ssid"".",
        '2. Choose "Forget network" and confirm.',
        "3. Pick ""$ssid"" again from the list and type the Wi-Fi password.",
        '4. IMPORTANT: changing the Wi-Fi turns debugging off. Go to Settings >',
        '   Developer options and turn "Wireless debugging" (or "Network',
        '   debugging" / "USB debugging") back on.',
        '',
        'This PC loses the TV while you do it. That is normal.') 1
    while ($true) {
        Paint '   ? ' 5 -NoNewline
        $ans = Read-Host 'Press Enter when the TV is back on Wi-Fi and debugging is on again (or type q to stop)'
        if ($ans -match '^[qQ]') { return $false }
        # The old connection is dead even when adb still lists it: drop it and find the TV again.
        Adb disconnect $script:serial | Out-Null
        Info 'Looking for the TV again (its address or port may have changed)...'
        Reconnect-Tv
        if (-not (Wifi-Held)) {
            Ok "The TV is back on ""$(Wifi-Name)"", and the network is the TV's own now"
            return $true
        }
        Warn "The TV is still on the network saved by Google's setup app. Forget ""$(Wifi-Name)"" on the TV and join it again (steps 1 to 4)."
    }
}

# Before the factory Home goes off: turn setupwraith off where it would take Home, safely for the Wi-Fi.
function Free-Home([string]$rec) {
    if (-not (Setup-On) -or -not (Setup-Claims-Home)) { return }
    if (Wifi-Held) {
        if (-not (Rejoin-Wifi)) {
            Fail 'liquidOS is installed, but the TV still opens its own Home: the Wi-Fi was not re-joined.' 'Run the installer again and follow the Wi-Fi steps; nothing on the TV was changed in this step.'
        }
    }
    $out = Sh "pm disable-user --user 0 $setup"
    if ($out -match 'disabled') { Add-Content $rec "disabled=$setup"; Ok "Google's setup app turned off (it would take Home)" }
    else { Warn "Could not turn off Google's setup app: $(Last $out)" }
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
    if ($start -notmatch 'Status: ok' -or $start -match '(?m)^Error') {
        Fail 'liquidOS did not start on the TV, so the factory Home was left as it was.' 'Wake the TV with its remote and open the installer again.'
    }
    Ok 'liquidOS starts'
    if ($stock -ne 'none') { Sh "pm disable-user --user 0 $stock" | Out-Null; Ok 'Factory Home turned off' }
    Sh "cmd package set-home-activity --user 0 $pkg/.MainActivity" | Out-Null
    $now = Last (Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
    if ($now -like "$pkg/*") { Ok 'The Home button now opens liquidOS'; return }
    # Not ours: put the stock launcher back so the TV is never left without a Home.
    if ($stock -ne 'none') {
        Sh "pm enable --user 0 $stock" | Out-Null
        Sh ('cmd package set-home-activity --user 0 $(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME ' + $stock + ' | tail -n 1)') | Out-Null
    }
    Fail "The TV kept its own Home ($now). The factory Home is back on: nothing is broken." 'Run the installer again. If it happens again, tell the person who gave you the installer which TV model this is.'
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

function Theme-Walls([string]$theme) {
    return @(Get-ChildItem (Join-Path $theme 'wallpapers') -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -match '^\.(jpe?g|png|webp)$' -and $_.Name -match '^[A-Za-z0-9_.-]+$' })
}

function Push-Theme([string]$theme) {
    Sh "mkdir -p $themeDir/wallpapers 2>/dev/null" | Out-Null
    Put-File (Join-Path $theme 'config.json') 'config.json'
    Ok 'Theme settings'
    $walls = Theme-Walls $theme
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

if ($env:LIQUIDOS_FUNCTIONS) { return }

# ---- main --------------------------------------------------------------------------------------------------
Banner
Say '     1)  Install liquidOS on a TV' White
Say '     2)  Undo: put a TV back the way it was' White
$mode = ''
while ($mode -notin @('1', '2')) {
    $mode = Ask 'Choose 1 or 2 (Enter = 1)' '1'
    if ($mode -notin @('1', '2')) { Warn 'Type 1 or 2.' }
}

Step 1 'Get this PC ready' 'liquidOS talks to the TV with ADB, a small free tool from Google.'
$script:adb = Find-Adb
if ($script:adb) {
    Ok 'ADB is ready'
} else {
    Warn 'This PC does not have ADB yet.'
    if (-not (Yes 'Download it now from Google? (recommended)')) { Fail 'ADB is needed to reach the TV.' 'Open the installer again and answer y to the download.' }
    if (-not (Install-PlatformTools (Join-Path $repo 'tools'))) { Fail 'The ADB download did not work.' 'Check the internet connection of this PC and open the installer again.' }
    $script:adb = Join-Path $platformTools 'adb.exe'
    Ok 'ADB installed'
}
Adb start-server | Out-Null
$apk = @((Join-Path $PSScriptRoot 'liquidOS.apk'), (Join-Path $PSScriptRoot 'TVHome.apk'),
    (Join-Path $repo 'launcher\app\build\outputs\apk\release\app-release.apk')) | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($mode -eq '1') {
    if (-not $apk) { Fail 'The liquidOS app (APK) is missing.' 'Put liquidOS.apk in the tools folder, or build it with launcher\build-and-install.ps1.' }
    Ok ('liquidOS app found ({0:N0} KB)' -f ((Get-Item $apk).Length / 1KB))
}

Step 2 'Connect to the TV' 'Over Wi-Fi: no cables needed.'
Connect-Tv
$model = Last (Sh 'getprop ro.product.model')
$device = Last (Sh 'getprop ro.product.device')
$product = Last (Sh 'getprop ro.product.name')
$android = Last (Sh 'getprop ro.build.version.release')
Ok "Found: $model (Android $android)"
if ($mode -eq '2') { Step 3 'Undo' 'Brings back the factory apps and the factory Home.'; Undo; Bye 0 }

$stock = Find-Stock
$list = Find-List @($product, $device, $model)
$plan = if ($list) { @(Plan-Debloat $list $stock) } else { @() }
$themes = @(Get-ChildItem (Join-Path $repo 'themes') -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'config.json') })
$theme = if ($themes.Name -contains 'mi-onn') { Join-Path $repo 'themes\mi-onn' } else { $null }
$walls = if ($theme) { @(Theme-Walls $theme) } else { @() }

# Say what is about to happen, in plain words, before touching anything.
Write-Host ''
$debloatLine = if (-not $list) { 'no list for this model, nothing removed' } elseif ($plan.Count) { "turn off $($plan.Count) factory apps (they can come back)" } else { 'nothing to remove, already clean' }
Box (@("TV:            $model",
    '',
    'liquidOS will now:',
    '  ► Back up the TV first (apps, Home, settings) on this PC',
    "  ► Debloater:  $debloatLine",
    '  ► Install the liquidOS launcher and give it its permissions',
    '  ► Make the Home button open liquidOS',
    $(if ($stock -ne 'none' -and (Setup-On) -and (Setup-Claims-Home) -and (Wifi-Held)) { '  ► Ask you to re-join the Wi-Fi on the TV once (it guides you)' } else { $null }),
    $(if ($theme) { "  ► Copy the theme $(Split-Path -Leaf $theme) with $($walls.Count) wallpapers" } else { '  ► (no theme to copy)' }),
    '',
    'Nothing is deleted: option 2 of this installer puts it all back.') | Where-Object { $null -ne $_ }) 0
if (-not (Yes 'Start?')) { Say '   Nothing was changed.' Gray; Bye 0 }

$backupDir = Join-Path $repo ("backup\liquidos-{0}-{1}" -f ($script:serial -split ':')[0], (Get-Date -Format 'yyyyMMdd-HHmmss'))
Step 3 'Backup of the TV' 'Saves how the TV is now, so it can always go back (option 2).'
Backup-Tv $backupDir
$rec = Join-Path $backupDir 'install.txt'
Set-Content $rec @("tv=$model ($device)", "stock=$stock")

Step 4 'Debloater' 'Turns off the ads, trackers and extras the TV came with.'
Write-Host ''
Art $debloaterArt 4
Write-Host ''
if ($plan.Count) { Debloat $plan $rec; Ok "$($plan.Count) factory apps turned off" }
elseif ($list) { Ok 'Nothing to remove: this TV is already clean' }
else { Info 'There is no app list for this TV model yet, so nothing was removed.' }

Step 5 'Install liquidOS' 'Copies the launcher app to the TV.'
Adb -s $script:serial push $apk /data/local/tmp/liquidos.apk | Out-Null
$out = Sh 'pm install -r /data/local/tmp/liquidos.apk; rm -f /data/local/tmp/liquidos.apk'
if ($out -notmatch 'Success') { Fail "The TV did not accept the app: $(Last $out)" 'Free some space on the TV (uninstall an app you do not use) and run the installer again.' }
Ok 'liquidOS installed'

Step 6 'Permissions' 'So the clock, calendar, night mode and music widget can work.'
$perms = [ordered]@{
    'Wi-Fi name'         = "pm grant $pkg android.permission.ACCESS_FINE_LOCATION"
    'Calendar'           = "pm grant $pkg android.permission.READ_CALENDAR"
    'Night mode'         = "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
    'Night mode overlay' = "appops set $pkg SYSTEM_ALERT_WINDOW allow"
    'Real TV brightness' = "appops set $pkg WRITE_SETTINGS allow"
    'Now playing widget' = "cmd notification allow_listener $pkg/$pkg.MediaListener"
}
foreach ($k in $perms.Keys) {
    $o = Sh "$($perms[$k]) 2>&1"
    if ($o -match 'Exception|Error|Unknown') { Info "$k (this TV does not have it)" } else { Ok $k }
}
$job = Start-Job -ScriptBlock { param($a, $s, $p) & $a -s $s shell "cmd package compile -m speed -f $p" } -ArgumentList $script:adb, $script:serial, $pkg
$i = 0
while ($job.State -eq 'Running') { Spin $i 'Making liquidOS open fast (up to a minute)...'; $i++; Start-Sleep -Milliseconds 250 }
Remove-Job $job -Force
Write-Host "`r                                                            `r" -NoNewline
Ok 'Optimized for speed'

Step 7 'Make liquidOS the Home screen' 'The Home button on the remote will open liquidOS.'
if ($stock -ne 'none') { Free-Home $rec }
Hand-Over $stock

Step 8 'Theme and wallpapers' 'Your look: dock apps, colours and wallpapers.'
$themed = 'skipped'
if ($theme) {
    if (Yes "Copy the theme $(Split-Path -Leaf $theme) with $($walls.Count) wallpapers?") { Push-Theme $theme; $themed = Split-Path -Leaf $theme }
    else { Info 'Theme skipped.' }
} else {
    Info 'No theme found in the themes folder; liquidOS starts with its own look.'
}

Write-Host ''
Art $doneArt 4
Write-Host ''
Stripes
Write-Host ''
Box @('liquidOS is on your TV. Press Home on the remote to see it.',
    '',
    "TV           $model",
    "Debloater    $(if ($plan.Count) { "$($plan.Count) apps turned off" } else { 'nothing to remove' })",
    "Theme        $themed",
    "Backup       backup\$(Split-Path -Leaf $backupDir)",
    '',
    'On the TV: Settings (top right) to pick a wallpaper and effects.',
    'The calendar shows the Google accounts signed in on that TV.',
    'Changed your mind? Open this installer and choose 2 (Undo).',
    '',
    'Created by Wade   ·   X: @wade9_') 0
Write-Host ''
if (Yes 'Disconnect this PC from the TV now?') { Adb disconnect $script:serial | Out-Null; Ok 'Disconnected' }
Info 'Tip: for safety, turn "USB debugging" off on the TV when you are done.'
Bye 0
