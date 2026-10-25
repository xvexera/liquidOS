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
# The installer's language: 'en' or 'es', asked first (the default follows Windows' own language).
$lang = 'en'
try { if ((Get-UICulture).TwoLetterISOLanguageName -eq 'es') { $lang = 'es' } } catch { }
function T([string]$en, [string]$es) { if ($script:lang -eq 'es') { return $es } return $en }

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
    Say (T '   The liquidOS launcher + debloater for Android TV and Google TV' '   El launcher liquidOS + debloater para Android TV y Google TV') White
    Write-Host (T '   Created by ' '   Creado por ') -NoNewline -ForegroundColor DarkGray
    Write-Host 'Wade' -NoNewline -ForegroundColor White
    Write-Host '   ·   X: ' -NoNewline -ForegroundColor DarkGray
    Paint '@wade9_' 5
    Write-Host ''
}

# "STEP 3/7 ■■■□□□□ Remove factory apps", the badge in the step's own stripe colour.
function Step([int]$n, [string]$title, [string]$what) {
    $bar = ('■' * $n) + ('□' * ($totalSteps - $n))
    Write-Host ''
    Paint (T "  ► STEP $n of $totalSteps  " "  ► PASO $n de $totalSteps  ") ($n - 1) -NoNewline
    Paint $bar ($n - 1) -NoNewline
    Write-Host "   $title" -ForegroundColor White
    if ($what) { Say "    $what" DarkGray }
    $host.UI.RawUI.WindowTitle = (T "liquidOS installer  -  step $n of $totalSteps  -  by Wade (@wade9_)" "Instalador liquidOS  -  paso $n de $totalSteps  -  por Wade (@wade9_)")
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

function Bye([int]$code) { Write-Host ''; Read-Host (T '   Press Enter to close' '   Pulsa Enter para cerrar') | Out-Null; exit $code }
function Fail([string]$text, [string]$fix = '') {
    Write-Host ''
    $lines = @((T 'Something went wrong.' 'Algo salió mal.'), '') + ($text -split "`n" | Where-Object { $_ })
    if ($fix) { $lines += @('', (T 'What to do:' 'Qué hacer:')) + ($fix -split "`n") }
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
        $a = Ask "$q $(if ($default) { T '[Y/n]' '[S/n]' } else { T '[y/N]' '[s/N]' })"
        if ($a -eq '') { return $default }
        if ($a -match '^(y|yes|s|si|sí)$') { return $true }
        if ($a -match '^(n|no)$') { return $false }
        Warn (T 'Please answer y (yes) or n (no).' 'Responde s (sí) o n (no).')
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
    Info (T 'Downloading Android platform-tools from Google (dl.google.com, a few MB)...' 'Descargando Android platform-tools de Google (dl.google.com, unos MB)...')
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
    Box @((T 'Get the TV ready (one time only):' 'Prepara la TV (solo la primera vez):'),
        '',
        (T '1. On the TV open Settings > System (or Device Preferences) > About.' '1. En la TV abre Ajustes > Sistema (o Preferencias del dispositivo) > Información.'),
        (T '2. Press OK on "Build" 7 times, until it says you are a developer.' '2. Pulsa OK 7 veces sobre "Compilación", hasta que diga que eres desarrollador.'),
        (T '3. Go back to Settings > Developer options and turn on' '3. Vuelve a Ajustes > Opciones de desarrollador y activa'),
        (T '   "USB debugging", and also "Network debugging" or' '   "Depuración USB", y también "Depuración por red" o'),
        (T '   "Wireless debugging" if your TV has it.' '   "Depuración inalámbrica" si tu TV lo tiene.'),
        (T '4. Keep the TV on and on the same Wi-Fi as this PC.' '4. Deja la TV encendida y en la misma red Wi-Fi que esta PC.')) 5
}

function Choose-Tv {
    while ($true) {
        Info (T 'Looking for TVs on your network (a few seconds)...' 'Buscando TVs en tu red (unos segundos)...')
        $tvs = @(Find-Tvs)
        if ($tvs.Count -gt 0) {
            for ($i = 0; $i -lt $tvs.Count; $i++) {
                Paint ("     {0})  " -f ($i + 1)) $i -NoNewline
                Write-Host ("{0,-16} {1}" -f $tvs[$i].ip, (Kind $tvs[$i].kind)) -ForegroundColor White
            }
            Say (T '     M)  Type the IP address myself     R)  Search again' '     M)  Escribir la IP yo mismo     R)  Buscar de nuevo') Gray
            $a = Ask (T 'Which one is your TV? (Enter = 1)' '¿Cuál es tu TV? (Enter = 1)') '1'
            if ($a -match '^\d+$' -and [int]$a -ge 1 -and [int]$a -le $tvs.Count) { return $tvs[[int]$a - 1] }
            if ($a -match '^[rR]') { continue }
            if ($a -notmatch '^[mM]') { Warn (T 'Type the number from the list, M or R.' 'Escribe el número de la lista, M o R.'); continue }
        } else {
            Warn (T 'No TV found yet. Check that debugging is on (see the box above) and the TV is awake.' 'Todavía no encontré ninguna TV. Revisa que la depuración esté activada (ver el recuadro de arriba) y que la TV esté encendida.')
            if (Yes (T 'Search again?' '¿Buscar de nuevo?')) { continue }
        }
        while ($true) {
            $ip = Ask (T 'TV IP address (shown on the TV under Settings > Network)' 'IP de la TV (la muestra la TV en Ajustes > Red)')
            if ($ip -match '^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$' -and
                -not (@($Matches[1..4]) | Where-Object { [int]$_ -gt 255 })) { break }
            Warn (T "'$ip' does not look like an IP address: it is four numbers separated by dots." "'$ip' no parece una IP: son cuatro números separados por puntos.")
        }
        $port = Ask (T 'Port (Enter = 5555; on "Wireless debugging" TVs it is the number after the colon)' 'Puerto (Enter = 5555; en TVs con "Depuración inalámbrica" es el número después de los dos puntos)') '5555'
        return [pscustomobject]@{ ip = $ip; port = $port; kind = 'typed' }
    }
}

# The kind of connection a found TV offers, in the installer's language.
function Kind([string]$kind) {
    switch ($kind) {
        'ADB over network' { return (T 'ADB over network' 'ADB por red') }
        'Wireless debugging' { return (T 'Wireless debugging' 'Depuración inalámbrica') }
        default { return $kind }
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
            Warn (T 'Look at the TV: it asks "Allow USB debugging?". Tick "Always allow" and press OK.' 'Mira la TV: pregunta "¿Permitir la depuración USB?". Marca "Permitir siempre" y pulsa OK.')
            $shown = $true
        }
        Spin $i (T 'Waiting for the TV...' 'Esperando a la TV...')
        if ($i % 6 -eq 5) { Adb connect $script:serial | Out-Null }
        Start-Sleep -Seconds 1
    }
    Write-Host ''
    return $false
}

function Pair([string]$ip) {
    Box @((T 'This TV needs a one-time pairing code:' 'Esta TV necesita un código de vinculación (solo una vez):'),
        '',
        (T 'On the TV open Developer options > Wireless debugging' 'En la TV abre Opciones de desarrollador > Depuración inalámbrica'),
        (T '> "Pair device with pairing code". Leave that screen open.' '> "Vincular dispositivo con código". Deja esa pantalla abierta.')) 1
    for ($try = 1; $try -le 3; $try++) {
        $port = $null
        for ($i = 0; $i -lt 20 -and -not $port; $i++) { Spin $i (T 'Looking for the pairing screen...' 'Buscando la pantalla de vinculación...'); $port = Pairing-Port $ip; if (-not $port) { Start-Sleep 1 } }
        Write-Host "`r                                                            `r" -NoNewline
        if (-not $port) { $port = Ask (T 'Pairing PORT shown on the TV (the number after the colon)' 'PUERTO de vinculación que muestra la TV (el número después de los dos puntos)') }
        $code = Ask (T 'The 6-digit code shown on the TV' 'El código de 6 dígitos que muestra la TV')
        $out = Adb pair "${ip}:$port" ($code -replace '\s', '')
        if ($out -match 'Successfully paired') { Ok (T 'Paired' 'Vinculada'); return $true }
        Warn (T "That did not work ($((Last $out))). The code changes each time: check it on the TV and try again." "No funcionó ($((Last $out))). El código cambia cada vez: revísalo en la TV y prueba de nuevo.")
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
        if ($out -match 'connected to' -and (Wait-Authorized)) { Ok (T "Connected to $script:serial" "Conectada a $script:serial"); return }
        # Wireless debugging TVs refuse the connection until this PC is paired.
        if ($tv.port -ne '5555' -or $tv.kind -eq 'Wireless debugging') {
            if (Pair $tv.ip) {
                Adb connect $script:serial | Out-Null
                if (Wait-Authorized 30) { Ok (T "Connected to $script:serial" "Conectada a $script:serial"); return }
            }
        }
        Warn (T "Could not connect to $script:serial." "No se pudo conectar a $script:serial.")
        Info (T 'Check: the TV is on, debugging is on, the TV and this PC are on the same Wi-Fi.' 'Revisa: la TV está encendida, la depuración está activada y la TV y esta PC están en la misma red Wi-Fi.')
        if (-not (Yes (T 'Try again?' '¿Probar de nuevo?'))) { Fail (T 'The TV did not accept the connection.' 'La TV no aceptó la conexión.') (T 'Turn on debugging on the TV (see the first box), then open the installer again.' 'Activa la depuración en la TV (ver el primer recuadro) y vuelve a abrir el instalador.') }
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
        Write-Progress -Activity (T 'Backing up the TV' 'Respaldando la TV') -Status $name -PercentComplete (100 * $i / $files.Count)
        Set-Content -Encoding UTF8 (Join-Path $dir $name) (Sh $files[$name])
    }
    Write-Progress -Activity (T 'Backing up the TV' 'Respaldando la TV') -Completed
    Ok (T 'App list, Home, system settings and TV details saved' 'Guardados: lista de apps, Home, ajustes del sistema y datos de la TV')
    # The apps the owner installed (Play Store or sideloaded): their APKs, if they want them.
    $apps = @((Sh 'pm list packages -3 -f') -split "`n" | ForEach-Object {
            if ($_.Trim() -match '^package:(.+\.apk)=([A-Za-z0-9_.]+)$' -and $Matches[2] -ne $pkg) {
                [pscustomobject]@{ path = $Matches[1]; name = $Matches[2] } } })
    if ($apps.Count) {
        $bytes = 0
        $sizes = (Sh ('stat -c %s ' + (($apps | ForEach-Object { $_.path }) -join ' ') + ' 2>/dev/null')) -split "`n"
        foreach ($n in $sizes) { if ($n.Trim() -match '^\d+$') { $bytes += [long]$n.Trim() } }
        if (Yes ((T "Also save the {0} apps you installed yourself ({1:N0} MB)?" "¿Guardar también las {0} apps que instalaste tú ({1:N0} MB)?") -f $apps.Count, ($bytes / 1MB)) $false) {
            $apkDir = Join-Path $dir 'apps'
            New-Item -ItemType Directory -Force $apkDir | Out-Null
            $i = 0
            foreach ($a in $apps) {
                $i++
                Write-Progress -Activity (T 'Saving your apps' 'Guardando tus apps') -Status $a.name -PercentComplete (100 * $i / $apps.Count)
                Adb -s $script:serial pull $a.path (Join-Path $apkDir "$($a.name).apk") | Out-Null
            }
            Write-Progress -Activity (T 'Saving your apps' 'Guardando tus apps') -Completed
            Ok (T "$($apps.Count) apps saved" "$($apps.Count) apps guardadas")
        }
    }
    Set-Content -Encoding UTF8 (Join-Path $dir 'README.txt') @(
        (T 'liquidOS installer - backup of the TV before any change' 'Instalador liquidOS - respaldo de la TV antes de cualquier cambio'),
        '',
        (T 'apps-on.txt / apps-off.txt   which apps were turned on and off' 'apps-on.txt / apps-off.txt   qué apps estaban activadas y desactivadas'),
        (T 'home.txt                     the app the Home button opened' 'home.txt                     la app que abría el botón Home'),
        (T 'settings-*.txt               the system settings (global, secure, system)' 'settings-*.txt               los ajustes del sistema (global, secure, system)'),
        (T 'tv.txt                       model, Android version and build (getprop)' 'tv.txt                       modelo, versión de Android y compilación (getprop)'),
        (T 'apps-all.txt                 every app with its version' 'apps-all.txt                 todas las apps con su versión'),
        (T 'apps\                        the APKs of the apps you installed (if you chose to save them)' 'apps\                        los APK de las apps que instalaste (si elegiste guardarlos)'),
        '',
        (T 'To put the TV back: open "liquidOS Installer.cmd" and choose 2 (Undo).' 'Para dejar la TV como estaba: abre "liquidOS Installer.cmd" y elige 2 (Deshacer).'))
    Ok (T "Backup saved in backup\$(Split-Path -Leaf $dir)" "Respaldo guardado en backup\$(Split-Path -Leaf $dir)")
}

# ---- undo --------------------------------------------------------------------------------------------------
function Undo {
    $ip = ($script:serial -split ':')[0]
    $backups = @(Get-ChildItem (Join-Path $repo 'backup') -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "liquidos-$ip-*" -and (Test-Path (Join-Path $_.FullName 'apps-on.txt')) } | Sort-Object Name)
    if ($backups.Count -eq 0) { Fail (T 'There is no backup of this TV on this PC.' 'No hay ningún respaldo de esta TV en esta PC.') (T 'Undo works on a TV this installer set up from this PC (folder backup).' 'Deshacer funciona en una TV que este instalador configuró desde esta PC (carpeta backup).') }
    $dir = $backups[0].FullName
    if ($backups.Count -gt 1) {
        for ($i = 0; $i -lt $backups.Count; $i++) { Paint ("     {0})  " -f ($i + 1)) $i -NoNewline; Write-Host $backups[$i].Name -ForegroundColor White }
        $a = Ask (T 'Which backup? (Enter = 1, from before the first install)' '¿Qué respaldo? (Enter = 1, el de antes de la primera instalación)') '1'
        if ($a -match '^\d+$' -and [int]$a -ge 1 -and [int]$a -le $backups.Count) { $dir = $backups[[int]$a - 1].FullName }
    }
    Info (T "Backup: $(Split-Path -Leaf $dir)" "Respaldo: $(Split-Path -Leaf $dir)")
    $wasOn = @(Get-Content (Join-Path $dir 'apps-on.txt') | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ })
    $nowOff = @((Sh 'pm list packages -d') -split "`n" | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ })
    $back = @($nowOff | Where-Object { $wasOn -contains $_ })
    $homeWas = Last (Get-Content (Join-Path $dir 'home.txt') -Raw)
    if (-not (Yes (T "Turn $($back.Count) apps back on and make Home open $homeWas again?" "¿Reactivar $($back.Count) apps y que Home vuelva a abrir $homeWas?"))) { return }
    foreach ($p in $back) { Sh "pm enable --user 0 $p" | Out-Null; Ok (T "back on: $p" "reactivada: $p") }
    if ($homeWas -match '^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$') { Sh "cmd package set-home-activity --user 0 $homeWas" | Out-Null }
    Ok ((T 'Home is now: ' 'Home ahora: ') + (Last (Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')))
    if ($homeWas -notlike "$pkg/*" -and (Yes (T 'Also remove the liquidOS app?' '¿Desinstalar también la app liquidOS?') $false)) { Info (Adb -s $script:serial uninstall $pkg) }
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
        Warn (T 'The app list for this TV has odd entries, so it is not used.' 'La lista de apps de esta TV tiene entradas raras, así que no se usa.')
        return @()
    }
    # Home and the stock launchers are handed over later, with a way back if the new Home does not take.
    $pkgs = @($pkgs | Where-Object { $_ -ne $homePkg -and $knownStock -notcontains $_ -and $_ -ne $pkg })
    # The Wi-Fi network saved in the first-run wizard belongs to setupwraith, and Android deletes it when
    # setupwraith is disabled: the TV would drop off Wi-Fi (and the installer with it).
    if ($pkgs -contains $setup -and (Setup-On)) {
        if (Wifi-Held) {
            Info (T 'Keeping the Google setup app: the saved Wi-Fi belongs to it, and the TV would lose the network.' 'Dejo la app de configuración de Google: la red Wi-Fi guardada le pertenece y la TV se quedaría sin red.')
            $pkgs = @($pkgs | Where-Object { $_ -ne $setup })
        }
    }
    if ($pkgs.Count -eq 0) { return @() }
    # A package with TV_INPUT_HARDWARE drives the HDMI/AV inputs: disabled, they show "No Signal" for good.
    $loop = 'for p in ' + ($pkgs -join ' ') + '; do dumpsys package $p | grep -q TV_INPUT_HARDWARE && echo $p; done; true'
    $inputs = @((Sh $loop) -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })
    if ($inputs.Count) { Info (T "Keeping $($inputs.Count) apps that run the HDMI inputs." "Dejo $($inputs.Count) apps que manejan las entradas HDMI.") }
    $installed = (Sh 'pm list packages -e') -split "`n" | ForEach-Object { ($_ -replace '^package:', '').Trim() }
    return @($pkgs | Where-Object { $inputs -notcontains $_ -and $installed -contains $_ })
}

function Debloat([string[]]$pkgs, [string]$rec) {
    $i = 0
    foreach ($p in $pkgs) {
        $i++
        Write-Progress -Activity (T 'liquidOS debloater' 'Debloater liquidOS') -Status $p -PercentComplete (100 * $i / $pkgs.Count)
        if ($script:dryRun) { Info (T "(dry run) $p" "(simulación) $p"); continue }
        $out = Sh "pm disable-user --user 0 $p"
        if ($out -match 'disabled') { Add-Content $rec "disabled=$p"; Ok $p } else { Warn "$p : $(Last $out)" }
    }
    Write-Progress -Activity (T 'liquidOS debloater' 'Debloater liquidOS') -Completed
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
    return (T 'your Wi-Fi network' 'tu red Wi-Fi')
}

# Walks the owner through forgetting and re-joining the Wi-Fi on the TV, reconnecting by itself while the TV
# is off the network. True once the network no longer belongs to setupwraith.
function Rejoin-Wifi {
    $ssid = Wifi-Name
    Box @((T 'One quick thing on the TV, then liquidOS can be the Home screen.' 'Un paso rápido en la TV y liquidOS podrá ser la pantalla de inicio.'),
        '',
        (T "Google's setup app still owns the Wi-Fi network ""$ssid"". To hand it over:" "La app de configuración de Google todavía es dueña de la red Wi-Fi ""$ssid"". Para pasársela a la TV:"),
        '',
        (T "1. On the TV: Settings > Network & Internet > ""$ssid""." "1. En la TV: Ajustes > Red e Internet > ""$ssid""."),
        (T '2. Choose "Forget network" and confirm.' '2. Elige "Olvidar red" y confirma.'),
        (T "3. Pick ""$ssid"" again from the list and type the Wi-Fi password." "3. Elige ""$ssid"" otra vez en la lista y escribe la contraseña del Wi-Fi."),
        (T '4. IMPORTANT: changing the Wi-Fi turns debugging off. Go to Settings >' '4. IMPORTANTE: al cambiar el Wi-Fi se apaga la depuración. Ve a Ajustes >'),
        (T '   Developer options and turn "Wireless debugging" (or "Network' '   Opciones de desarrollador y vuelve a activar "Depuración inalámbrica" (o'),
        (T '   debugging" / "USB debugging") back on.' '   "Depuración por red" / "Depuración USB").'),
        '',
        (T 'This PC loses the TV while you do it. That is normal.' 'Mientras lo haces, esta PC pierde la TV. Es normal.')) 1
    while ($true) {
        Paint '   ? ' 5 -NoNewline
        $ans = Read-Host (T 'Press Enter when the TV is back on Wi-Fi and debugging is on again (or type q to stop)' 'Pulsa Enter cuando la TV vuelva al Wi-Fi y la depuración esté activada otra vez (o escribe q para salir)')
        if ($ans -match '^[qQ]') { return $false }
        # The old connection is dead even when adb still lists it: drop it and find the TV again.
        Adb disconnect $script:serial | Out-Null
        Info (T 'Looking for the TV again (its address or port may have changed)...' 'Buscando la TV otra vez (puede haber cambiado su dirección o puerto)...')
        Reconnect-Tv
        if (-not (Wifi-Held)) {
            Ok (T "The TV is back on ""$(Wifi-Name)"", and the network is the TV's own now" "La TV volvió a ""$(Wifi-Name)"" y ahora la red es de la propia TV")
            return $true
        }
        Warn (T "The TV is still on the network saved by Google's setup app. Forget ""$(Wifi-Name)"" on the TV and join it again (steps 1 to 4)." "La TV sigue en la red guardada por la app de configuración de Google. Olvida ""$(Wifi-Name)"" en la TV y vuelve a conectarte (pasos 1 a 4).")
    }
}

# Before the factory Home goes off: turn setupwraith off where it would take Home, safely for the Wi-Fi.
function Free-Home([string]$rec) {
    if (-not (Setup-On) -or -not (Setup-Claims-Home)) { return }
    if (Wifi-Held) {
        if (-not (Rejoin-Wifi)) {
            Fail (T 'liquidOS is installed, but the TV still opens its own Home: the Wi-Fi was not re-joined.' 'liquidOS está instalado, pero la TV sigue abriendo su propio Home: no se volvió a conectar el Wi-Fi.') (T 'Run the installer again and follow the Wi-Fi steps; nothing on the TV was changed in this step.' 'Vuelve a abrir el instalador y sigue los pasos del Wi-Fi; en este paso no se cambió nada en la TV.')
        }
    }
    $out = Sh "pm disable-user --user 0 $setup"
    if ($out -match 'disabled') { Add-Content $rec "disabled=$setup"; Ok (T "Google's setup app turned off (it would take Home)" "App de configuración de Google desactivada (si no, se quedaría con Home)") }
    else { Warn (T "Could not turn off Google's setup app: $(Last $out)" "No se pudo desactivar la app de configuración de Google: $(Last $out)") }
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
        Fail (T 'liquidOS did not start on the TV, so the factory Home was left as it was.' 'liquidOS no arrancó en la TV, así que el Home de fábrica quedó como estaba.') (T 'Wake the TV with its remote and open the installer again.' 'Enciende la TV con su control remoto y vuelve a abrir el instalador.')
    }
    Ok (T 'liquidOS starts' 'liquidOS arranca')
    if ($stock -ne 'none') { Sh "pm disable-user --user 0 $stock" | Out-Null; Ok (T 'Factory Home turned off' 'Home de fábrica desactivado') }
    Sh "cmd package set-home-activity --user 0 $pkg/.MainActivity" | Out-Null
    $now = Last (Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
    if ($now -like "$pkg/*") { Ok (T 'The Home button now opens liquidOS' 'El botón Home ahora abre liquidOS'); return }
    # Not ours: put the stock launcher back so the TV is never left without a Home.
    if ($stock -ne 'none') {
        Sh "pm enable --user 0 $stock" | Out-Null
        Sh ('cmd package set-home-activity --user 0 $(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME ' + $stock + ' | tail -n 1)') | Out-Null
    }
    Fail (T "The TV kept its own Home ($now). The factory Home is back on: nothing is broken." "La TV mantuvo su propio Home ($now). El Home de fábrica volvió a activarse: no se rompió nada.") (T 'Run the installer again. If it happens again, tell the person who gave you the installer which TV model this is.' 'Vuelve a abrir el instalador. Si pasa otra vez, dile a quien te dio el instalador qué modelo de TV es.')
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
    Ok (T 'Theme settings' 'Ajustes del tema')
    $walls = Theme-Walls $theme
    $i = 0
    foreach ($w in $walls) {
        $i++
        Write-Progress -Activity (T 'Copying wallpapers' 'Copiando fondos') -Status $w.Name -PercentComplete (100 * $i / $walls.Count)
        Put-File $w.FullName "wallpapers/$($w.Name)"
    }
    Write-Progress -Activity (T 'Copying wallpapers' 'Copiando fondos') -Completed
    if ($walls.Count) { Ok (T "$($walls.Count) wallpapers" "$($walls.Count) fondos") }
    $rev = [guid]::NewGuid().ToString('N')
    Sh "printf '%s' $rev > $themeTmp-theme_revision; cp $themeTmp-theme_revision $themeDir/theme_revision 2>/dev/null || content write --uri $themeUri/theme_revision < $themeTmp-theme_revision; rm -f $themeTmp-theme_revision" | Out-Null
    Sh "am force-stop $pkg; am start -n $pkg/.MainActivity" | Out-Null
    Ok (T "Theme $(Split-Path -Leaf $theme) applied" "Tema $(Split-Path -Leaf $theme) aplicado")
}

if ($env:LIQUIDOS_FUNCTIONS) { return }

# ---- main --------------------------------------------------------------------------------------------------
Banner
Say '     Language / Idioma:   1)  English     2)  Español' White
$pick = Ask "Choose / Elige (Enter = $(if ($lang -eq 'es') { '2' } else { '1' }))" $(if ($lang -eq 'es') { '2' } else { '1' })
if ($pick -eq '2' -or $pick -match '^(es|esp)') { $lang = 'es' } elseif ($pick -eq '1' -or $pick -match '^en') { $lang = 'en' }
Banner
Say (T '     1)  Install liquidOS on a TV' '     1)  Instalar liquidOS en una TV') White
Say (T '     2)  Undo: put a TV back the way it was' '     2)  Deshacer: dejar una TV como estaba') White
$mode = ''
while ($mode -notin @('1', '2')) {
    $mode = Ask (T 'Choose 1 or 2 (Enter = 1)' 'Elige 1 o 2 (Enter = 1)') '1'
    if ($mode -notin @('1', '2')) { Warn (T 'Type 1 or 2.' 'Escribe 1 o 2.') }
}

Step 1 (T 'Get this PC ready' 'Preparar esta PC') (T 'liquidOS talks to the TV with ADB, a small free tool from Google.' 'liquidOS se comunica con la TV mediante ADB, una pequeña herramienta gratuita de Google.')
$script:adb = Find-Adb
if ($script:adb) {
    Ok (T 'ADB is ready' 'ADB está listo')
} else {
    Warn (T 'This PC does not have ADB yet.' 'Esta PC todavía no tiene ADB.')
    if (-not (Yes (T 'Download it now from Google? (recommended)' '¿Descargarlo ahora de Google? (recomendado)'))) { Fail (T 'ADB is needed to reach the TV.' 'Se necesita ADB para comunicarse con la TV.') (T 'Open the installer again and answer y to the download.' 'Vuelve a abrir el instalador y responde s a la descarga.') }
    if (-not (Install-PlatformTools (Join-Path $repo 'tools'))) { Fail (T 'The ADB download did not work.' 'La descarga de ADB no funcionó.') (T 'Check the internet connection of this PC and open the installer again.' 'Revisa la conexión a internet de esta PC y vuelve a abrir el instalador.') }
    $script:adb = Join-Path $platformTools 'adb.exe'
    Ok (T 'ADB installed' 'ADB instalado')
}
Adb start-server | Out-Null
$apk = @((Join-Path $PSScriptRoot 'liquidOS.apk'), (Join-Path $PSScriptRoot 'TVHome.apk'),
    (Join-Path $repo 'launcher\app\build\outputs\apk\release\app-release.apk')) | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($mode -eq '1') {
    if (-not $apk) { Fail (T 'The liquidOS app (APK) is missing.' 'Falta la app liquidOS (APK).') (T 'Put liquidOS.apk in the tools folder, or build it with launcher\build-and-install.ps1.' 'Pon liquidOS.apk en la carpeta tools, o compílala con launcher\build-and-install.ps1.') }
    Ok ((T 'liquidOS app found ({0:N0} KB)' 'App liquidOS encontrada ({0:N0} KB)') -f ((Get-Item $apk).Length / 1KB))
}

Step 2 (T 'Connect to the TV' 'Conectar con la TV') (T 'Over Wi-Fi: no cables needed.' 'Por Wi-Fi: no hacen falta cables.')
Connect-Tv
$model = Last (Sh 'getprop ro.product.model')
$device = Last (Sh 'getprop ro.product.device')
$product = Last (Sh 'getprop ro.product.name')
$android = Last (Sh 'getprop ro.build.version.release')
Ok (T "Found: $model (Android $android)" "Encontrada: $model (Android $android)")
if ($mode -eq '2') { Step 3 (T 'Undo' 'Deshacer') (T 'Brings back the factory apps and the factory Home.' 'Reactiva las apps de fábrica y el Home de fábrica.'); Undo; Bye 0 }

$stock = Find-Stock
$list = Find-List @($product, $device, $model)
$plan = if ($list) { @(Plan-Debloat $list $stock) } else { @() }
$themes = @(Get-ChildItem (Join-Path $repo 'themes') -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'config.json') })
$theme = if ($themes.Name -contains 'mi-onn') { Join-Path $repo 'themes\mi-onn' } else { $null }
$walls = if ($theme) { @(Theme-Walls $theme) } else { @() }

# Say what is about to happen, in plain words, before touching anything.
Write-Host ''
$debloatLine = if (-not $list) { T 'no list for this model, nothing removed' 'no hay lista para este modelo, no se quita nada' } elseif ($plan.Count) { T "turn off $($plan.Count) factory apps (they can come back)" "desactivar $($plan.Count) apps de fábrica (se pueden reactivar)" } else { T 'nothing to remove, already clean' 'nada que quitar, ya está limpia' }
Box (@("TV:            $model",
    '',
    (T 'liquidOS will now:' 'liquidOS va a:'),
    (T '  ► Back up the TV first (apps, Home, settings) on this PC' '  ► Primero respaldar la TV (apps, Home, ajustes) en esta PC'),
    (T "  ► Debloater:  $debloatLine" "  ► Debloater:  $debloatLine"),
    (T '  ► Install the liquidOS launcher and give it its permissions' '  ► Instalar el launcher liquidOS y darle sus permisos'),
    (T '  ► Make the Home button open liquidOS' '  ► Hacer que el botón Home abra liquidOS'),
    $(if ($stock -ne 'none' -and (Setup-On) -and (Setup-Claims-Home) -and (Wifi-Held)) { (T '  ► Ask you to re-join the Wi-Fi on the TV once (it guides you)' '  ► Pedirte una vez que reconectes el Wi-Fi en la TV (te guía paso a paso)') } else { $null }),
    $(if ($theme) { (T "  ► Copy the theme $(Split-Path -Leaf $theme) with $($walls.Count) wallpapers" "  ► Copiar el tema $(Split-Path -Leaf $theme) con $($walls.Count) fondos") } else { (T '  ► (no theme to copy)' '  ► (no hay tema para copiar)') }),
    '',
    (T 'Nothing is deleted: option 2 of this installer puts it all back.' 'No se borra nada: la opción 2 de este instalador deja todo como estaba.')) | Where-Object { $null -ne $_ }) 0
if (-not (Yes (T 'Start?' '¿Empezar?'))) { Say (T '   Nothing was changed.' '   No se cambió nada.') Gray; Bye 0 }

$backupDir = Join-Path $repo ("backup\liquidos-{0}-{1}" -f ($script:serial -split ':')[0], (Get-Date -Format 'yyyyMMdd-HHmmss'))
Step 3 (T 'Backup of the TV' 'Respaldo de la TV') (T 'Saves how the TV is now, so it can always go back (option 2).' 'Guarda cómo está la TV ahora, para poder volver atrás siempre (opción 2).')
Backup-Tv $backupDir
$rec = Join-Path $backupDir 'install.txt'
Set-Content $rec @("tv=$model ($device)", "stock=$stock")

Step 4 'Debloater' (T 'Turns off the ads, trackers and extras the TV came with.' 'Desactiva la publicidad, los rastreadores y los extras que trae la TV.')
Write-Host ''
Art $debloaterArt 4
Write-Host ''
if ($plan.Count) { Debloat $plan $rec; Ok (T "$($plan.Count) factory apps turned off" "$($plan.Count) apps de fábrica desactivadas") }
elseif ($list) { Ok (T 'Nothing to remove: this TV is already clean' 'Nada que quitar: esta TV ya está limpia') }
else { Info (T 'There is no app list for this TV model yet, so nothing was removed.' 'Todavía no hay lista de apps para este modelo de TV, así que no se quitó nada.') }

Step 5 (T 'Install liquidOS' 'Instalar liquidOS') (T 'Copies the launcher app to the TV.' 'Copia la app del launcher a la TV.')
Adb -s $script:serial push $apk /data/local/tmp/liquidos.apk | Out-Null
$out = Sh 'pm install -r /data/local/tmp/liquidos.apk; rm -f /data/local/tmp/liquidos.apk'
if ($out -notmatch 'Success') { Fail (T "The TV did not accept the app: $(Last $out)" "La TV no aceptó la app: $(Last $out)") (T 'Free some space on the TV (uninstall an app you do not use) and run the installer again.' 'Libera espacio en la TV (desinstala una app que no uses) y vuelve a abrir el instalador.') }
Ok (T 'liquidOS installed' 'liquidOS instalado')

Step 6 (T 'Permissions' 'Permisos') (T 'So the clock, calendar, night mode and music widget can work.' 'Para que funcionen el reloj, el calendario, el modo nocturno y el widget de música.')
$perms = [ordered]@{
    (T 'Wi-Fi name' 'Nombre del Wi-Fi')                 = "pm grant $pkg android.permission.ACCESS_FINE_LOCATION"
    (T 'Calendar' 'Calendario')                         = "pm grant $pkg android.permission.READ_CALENDAR"
    (T 'Night mode' 'Modo nocturno')                    = "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
    (T 'Night mode overlay' 'Capa del modo nocturno')   = "appops set $pkg SYSTEM_ALERT_WINDOW allow"
    (T 'Real TV brightness' 'Brillo real de la TV')     = "appops set $pkg WRITE_SETTINGS allow"
    (T 'Now playing widget' 'Widget de música')         = "cmd notification allow_listener $pkg/$pkg.MediaListener"
}
foreach ($k in $perms.Keys) {
    $o = Sh "$($perms[$k]) 2>&1"
    if ($o -match 'Exception|Error|Unknown') { Info (T "$k (this TV does not have it)" "$k (esta TV no lo tiene)") } else { Ok $k }
}
$job = Start-Job -ScriptBlock { param($a, $s, $p) & $a -s $s shell "cmd package compile -m speed -f $p" } -ArgumentList $script:adb, $script:serial, $pkg
$i = 0
while ($job.State -eq 'Running') { Spin $i (T 'Making liquidOS open fast (up to a minute)...' 'Optimizando liquidOS para que abra rápido (hasta un minuto)...'); $i++; Start-Sleep -Milliseconds 250 }
Remove-Job $job -Force
Write-Host "`r                                                            `r" -NoNewline
Ok (T 'Optimized for speed' 'Optimizado para velocidad')

Step 7 (T 'Make liquidOS the Home screen' 'Poner liquidOS como pantalla de inicio') (T 'The Home button on the remote will open liquidOS.' 'El botón Home del control remoto abrirá liquidOS.')
if ($stock -ne 'none') { Free-Home $rec }
Hand-Over $stock

Step 8 (T 'Theme and wallpapers' 'Tema y fondos') (T 'Your look: dock apps, colours and wallpapers.' 'Tu estilo: apps del dock, colores y fondos.')
$themed = T 'skipped' 'omitido'
if ($theme) {
    if (Yes (T "Copy the theme $(Split-Path -Leaf $theme) with $($walls.Count) wallpapers?" "¿Copiar el tema $(Split-Path -Leaf $theme) con $($walls.Count) fondos?")) { Push-Theme $theme; $themed = Split-Path -Leaf $theme }
    else { Info (T 'Theme skipped.' 'Tema omitido.') }
} else {
    Info (T 'No theme found in the themes folder; liquidOS starts with its own look.' 'No hay tema en la carpeta themes; liquidOS arranca con su propio estilo.')
}

Write-Host ''
Art $doneArt 4
Write-Host ''
Stripes
Write-Host ''
Box @((T 'liquidOS is on your TV. Press Home on the remote to see it.' 'liquidOS ya está en tu TV. Pulsa Home en el control remoto para verlo.'),
    '',
    (T "TV           $model" "TV           $model"),
    "Debloater    $(if ($plan.Count) { T "$($plan.Count) apps turned off" "$($plan.Count) apps desactivadas" } else { T 'nothing to remove' 'nada que quitar' })",
    (T "Theme        $themed" "Tema         $themed"),
    (T "Backup       backup\$(Split-Path -Leaf $backupDir)" "Respaldo     backup\$(Split-Path -Leaf $backupDir)"),
    '',
    (T 'On the TV: Settings (top right) to pick a wallpaper and effects.' 'En la TV: Ajustes (arriba a la derecha) para elegir fondo y efectos.'),
    (T 'The calendar shows the Google accounts signed in on that TV.' 'El calendario muestra las cuentas de Google de esa TV.'),
    (T 'Changed your mind? Open this installer and choose 2 (Undo).' '¿Cambiaste de idea? Abre este instalador y elige 2 (Deshacer).'),
    '',
    (T 'Created by Wade   ·   X: @wade9_' 'Creado por Wade   ·   X: @wade9_')) 0
Write-Host ''
if (Yes (T 'Disconnect this PC from the TV now?' '¿Desconectar esta PC de la TV ahora?')) { Adb disconnect $script:serial | Out-Null; Ok (T 'Disconnected' 'Desconectada') }
Info (T 'Tip: for safety, turn "USB debugging" off on the TV when you are done.' 'Consejo: por seguridad, apaga la "Depuración USB" en la TV cuando termines.')
Bye 0
