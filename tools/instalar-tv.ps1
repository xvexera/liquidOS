# Instalador en un paso para una TV nueva: pide la IP (y el puerto y el código si la TV usa "Depuración
# inalámbrica"), y después quita las apps de fábrica de la lista de ese modelo, instala el launcher, le da sus
# permisos, lo pone como Home y copia el tema con sus fondos. Todo con el adb oficial (platform-tools), que
# también sabe emparejar con código, cosa que tools/tv.py no hace.
#
# Lo abre "Instalar en TV.cmd" en la raíz del repo. Opción 2 del menú: deshacer lo que hizo en una TV.
$ErrorActionPreference = 'Continue'
$repo = Split-Path -Parent $PSScriptRoot
$pkg = 'com.gathin.tvhome'
$knownStock = @('com.google.android.tvlauncher', 'com.google.android.apps.tv.launcherx',
    'com.google.android.leanbacklauncher', 'com.mitv.tvhome')
$setup = 'com.google.android.tungsten.setupwraith'

function Say([string]$text, [string]$color = 'Gray') { Write-Host $text -ForegroundColor $color }
function Step([string]$text) { Write-Host ''; Write-Host "== $text" -ForegroundColor Cyan }
function Fail([string]$text) { Say "ERROR: $text" Red; Read-Host 'Pulsa Enter para salir'; exit 1 }
function Ask([string]$q, [string]$default = '') {
    $a = Read-Host $q
    if ([string]::IsNullOrWhiteSpace($a)) { return $default }
    return $a.Trim()
}
function Yes([string]$q, [bool]$default = $true) {
    $a = Ask "$q $(if ($default) { '[S/n]' } else { '[s/N]' })"
    if ($a -eq '') { return $default }
    return $a -match '^[sSyY]'
}

# ---- adb ---------------------------------------------------------------------------------------------------
function Find-Adb {
    $here = Join-Path $repo 'tools\platform-tools\adb.exe'
    $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    $onPath = Get-Command adb.exe -ErrorAction SilentlyContinue
    foreach ($c in @($here, $sdk)) { if (Test-Path $c) { return $c } }
    if ($onPath) { return $onPath.Source }
    Say 'No encontré adb (Android platform-tools) en esta PC.' Yellow
    if (-not (Yes 'Descargarlo ahora de Google (dl.google.com, unos 7 MB) a tools\platform-tools?')) { Fail 'sin adb no se puede seguir' }
    $zip = Join-Path $env:TEMP 'platform-tools-windows.zip'
    Invoke-WebRequest -UseBasicParsing 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' -OutFile $zip
    Expand-Archive -Force $zip (Join-Path $repo 'tools')
    Remove-Item $zip
    if (-not (Test-Path $here)) { Fail 'la descarga no trajo adb.exe' }
    return $here
}
$adb = Find-Adb
# Every call returns its output as one string (stderr too); never throws.
function Adb { ((& $adb @args 2>&1) | ForEach-Object { "$_" }) -join "`n" }
function Sh([string]$cmd) { ((& $adb -s $script:serial shell $cmd 2>&1) | ForEach-Object { "$_" }) -join "`n" }
function Last([string]$text) { ("$text" -split "`n" | Where-Object { $_ -match '\S' } | Select-Object -Last 1).Trim() }

# ---- conectar ----------------------------------------------------------------------------------------------
function Connect-Tv {
    Say ''
    Say 'En la TV: Ajustes > Sistema (o Preferencias del dispositivo) > Información > pulsa 7 veces "Compilación".'
    Say 'Después, en Opciones de desarrollador, activa "Depuración USB" y, si existe, "Depuración por red/ADB"'
    Say 'o "Depuración inalámbrica". La IP está en Ajustes > Red.'
    $ip = Ask 'IP de la TV (ej. 192.168.0.50)'
    if ($ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { Fail "'$ip' no es una IP" }
    if (Yes 'La TV te muestra un CÓDIGO de vinculación (Depuración inalámbrica > Vincular con código)?' $false) {
        $pairPort = Ask 'Puerto de VINCULACIÓN (el que aparece junto al código)'
        $code = Ask 'Código de 6 dígitos'
        $out = Adb pair "${ip}:$pairPort" $code
        Say $out
        if ($out -notmatch 'Successfully paired') { Fail 'no se pudo vincular; revisa el puerto y el código (cambian cada vez)' }
        $port = Ask 'Puerto de CONEXIÓN (el de "Dirección IP y puerto" en la pantalla de Depuración inalámbrica)'
    } else {
        $port = Ask 'Puerto' '5555'
    }
    $script:serial = "${ip}:$port"
    Say (Adb connect $script:serial)
    Say 'Si la TV pregunta "¿Permitir la depuración?", marca "Permitir siempre" y acepta.' Yellow
    for ($i = 0; $i -lt 40; $i++) {
        $state = Last (Adb -s $script:serial get-state)
        if ($state -eq 'device') { return }
        if ($i % 5 -eq 4) { Adb connect $script:serial | Out-Null }
        Start-Sleep -Seconds 2
    }
    Fail "la TV no aceptó la conexión ($state). ¿Aceptaste el aviso en la TV? ¿Está activada la depuración?"
}

# ---- deshacer ----------------------------------------------------------------------------------------------
function Undo {
    $records = @(Get-ChildItem (Join-Path $repo 'backup') -Filter 'instalar-tv-*.txt' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -like "*$(($script:serial -split ':')[0])*" } | Sort-Object LastWriteTime)
    if ($records.Count -eq 0) { Fail 'no hay registro de una instalación en esta TV (carpeta backup)' }
    $rec = $records[-1].FullName
    Say "Registro: $rec"
    $lines = Get-Content $rec
    $stock = ($lines | Where-Object { $_ -like 'stock=*' } | Select-Object -First 1) -replace '^stock=', ''
    $disabled = @($lines | Where-Object { $_ -like 'disabled=*' } | ForEach-Object { $_ -replace '^disabled=', '' })
    if (-not (Yes "Reactivar $($disabled.Count) apps y volver al launcher de fábrica ($stock)?")) { return }
    foreach ($p in $disabled) { Sh "pm enable --user 0 $p" | Out-Null }
    if ($stock -and $stock -ne 'none') {
        Sh "pm enable --user 0 $stock" | Out-Null
        Sh ('cmd package set-home-activity --user 0 $(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME ' + $stock + ' | tail -n 1)') | Out-Null
    }
    Say "Home ahora: $(Last (Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'))" Green
    Say "El launcher sigue instalado; para quitarlo: adb -s $script:serial uninstall $pkg"
}

# ---- quitar apps de fábrica --------------------------------------------------------------------------------
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
    if ($bad.Count) { Say "La lista tiene entradas raras, la salto: $($bad -join ', ')" Yellow; return }
    # Home and the stock launchers are handed over later, with a way back if the new Home does not take.
    $pkgs = @($pkgs | Where-Object { $_ -ne $homePkg -and $knownStock -notcontains $_ -and $_ -ne $pkg })
    # The Wi-Fi network saved in the first-run wizard belongs to setupwraith, and Android deletes it when
    # setupwraith is disabled: the TV would drop off Wi-Fi (and ADB with it).
    if ($pkgs -contains $setup) {
        $wifi = Sh 'dumpsys wifi'
        if ($wifi -notmatch 'cname=' -or $wifi -match 'cname=[^ ]*setupwraith') {
            Say "No desactivo ${setup}: la red Wi-Fi guardada le pertenece y la TV se quedaría sin red." Yellow
            Say '  Para poder hacerlo: en la TV, olvida la red Wi-Fi y vuelve a conectarte desde Ajustes; luego corre esto otra vez.' Yellow
            $pkgs = @($pkgs | Where-Object { $_ -ne $setup })
        }
    }
    if ($pkgs.Count -eq 0) { Say 'Nada que desactivar.'; return }
    # A package with TV_INPUT_HARDWARE drives the HDMI/AV inputs: disabled, they show "No Signal" for good.
    $loop = 'for p in ' + ($pkgs -join ' ') + '; do dumpsys package $p | grep -q TV_INPUT_HARDWARE && echo $p; done; true'
    foreach ($p in ((Sh $loop) -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
        Say "No desactivo ${p}: maneja las entradas HDMI/AV." Yellow
        $pkgs = @($pkgs | Where-Object { $_ -ne $p })
    }
    $installed = (Sh 'pm list packages -e') -split "`n" | ForEach-Object { ($_ -replace '^package:', '').Trim() }
    $pkgs = @($pkgs | Where-Object { $installed -contains $_ })
    Say "Voy a desactivar $($pkgs.Count) apps (no se borran; se pueden reactivar con la opción Deshacer)."
    foreach ($p in $pkgs) {
        if ($script:dryRun) { Say "  (simulación) $p"; continue }
        $out = Sh "pm disable-user --user 0 $p"
        if ($out -match 'disabled') {
            Add-Content $rec "disabled=$p"
            Say "  - $p"
        } else { Say "  ? $p : $out" DarkYellow }
    }
}

# ---- Home ---------------------------------------------------------------------------------------------------
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
    if ($start -notmatch 'Status: ok' -or $start -match '(?m)^Error') { Fail "el launcher nuevo no arrancó; no toqué el de fábrica.`n$start" }
    if ($stock -ne 'none') { Say (Sh "pm disable-user --user 0 $stock") }
    Sh "cmd package set-home-activity --user 0 $pkg/.MainActivity" | Out-Null
    $now = Last (Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME')
    if ($now -like "$pkg/*") { Say "Home ahora es el launcher nuevo." Green; return }
    # Not ours: put the stock launcher back so the TV is never left without a Home.
    if ($stock -ne 'none') {
        Sh "pm enable --user 0 $stock" | Out-Null
        Sh ('cmd package set-home-activity --user 0 $(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME ' + $stock + ' | tail -n 1)') | Out-Null
    }
    Fail "Home quedó en '$now' y no en el launcher nuevo; volví a activar el de fábrica. En Google TV suele ser ${setup}: hay que desactivarlo (ver el aviso de la red Wi-Fi más arriba) y correr esto otra vez."
}

# ---- tema ---------------------------------------------------------------------------------------------------
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
    $dir = $themeDir; $uri = $themeUri; $tmp = $themeTmp
    Sh "mkdir -p $dir/wallpapers 2>/dev/null" | Out-Null
    Put-File (Join-Path $theme 'config.json') 'config.json'
    $walls = @(Get-ChildItem (Join-Path $theme 'wallpapers') -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Extension -match '^\.(jpe?g|png|webp)$' -and $_.Name -match '^[A-Za-z0-9_.-]+$' })
    foreach ($w in $walls) { Put-File $w.FullName "wallpapers/$($w.Name)"; Say "  + $($w.Name)" }
    $rev = [guid]::NewGuid().ToString('N')
    Sh "printf '%s' $rev > $tmp-theme_revision; cp $tmp-theme_revision $dir/theme_revision 2>/dev/null || content write --uri $uri/theme_revision < $tmp-theme_revision; rm -f $tmp-theme_revision" | Out-Null
    Sh "am force-stop $pkg; am start -n $pkg/.MainActivity" | Out-Null
    Say "Tema $(Split-Path -Leaf $theme) aplicado con $($walls.Count) fondos." Green
}

# ---- principal ---------------------------------------------------------------------------------------------
# INSTALAR_TV_FUNCIONES=1: only define the functions (for trying them one by one), run nothing.
if ($env:INSTALAR_TV_FUNCIONES) { return }
Clear-Host
Say 'Instalador del launcher Liquid Glass para Android TV / Google TV' White
Say '1) Instalar todo en una TV   2) Deshacer en una TV   (Enter = 1)'
$mode = Ask 'Opción' '1'
Connect-Tv
$model = Last (Sh 'getprop ro.product.model')
$device = Last (Sh 'getprop ro.product.device')
$product = Last (Sh 'getprop ro.product.name')
$android = Last (Sh 'getprop ro.build.version.release')
Say "Conectado: $model ($device, Android $android)" Green
if ($mode -eq '2') { Undo; Read-Host 'Listo. Pulsa Enter para salir'; exit 0 }

$apk = @((Join-Path $PSScriptRoot 'TVHome.apk'), (Join-Path $repo 'launcher\app\build\outputs\apk\release\app-release.apk')) |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $apk) { Fail 'no encuentro la APK: compílala con launcher\build-and-install.ps1 o pon TVHome.apk junto a este script' }
$stock = Find-Stock
New-Item -ItemType Directory -Force (Join-Path $repo 'backup') | Out-Null
$rec = Join-Path $repo ("backup\instalar-tv-{0}-{1}.txt" -f ($script:serial -split ':')[0], (Get-Date -Format 'yyyyMMdd-HHmmss'))
Set-Content $rec @("tv=$model ($device)", "stock=$stock")

Step '1/5 Apps de fábrica'
$list = Find-List @($product, $device, $model)
if ($list) {
    Say "Lista para este modelo: $list"
    if (Yes 'Desactivar las apps de esa lista?') { Debloat $list $stock $rec }
} else {
    Say 'No hay lista de apps para este modelo en examples\; salto este paso.' Yellow
}

Step '2/5 Instalar el launcher'
Adb -s $script:serial push $apk /data/local/tmp/tvhome.apk | Out-Null
$out = Sh 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
Say $out
if ($out -notmatch 'Success') { Fail 'no se pudo instalar la APK; la TV quedó como estaba' }

Step '3/5 Permisos (calendario, modo nocturno, brillo, música, red)'
foreach ($c in @(
        "pm grant $pkg android.permission.ACCESS_FINE_LOCATION",
        "pm grant $pkg android.permission.READ_CALENDAR",
        "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS",
        "appops set $pkg SYSTEM_ALERT_WINDOW allow",
        "appops set $pkg WRITE_SETTINGS allow",
        "cmd notification allow_listener $pkg/$pkg.MediaListener")) {
    $o = Sh "$c 2>&1"
    Say ("  {0} {1}" -f $(if ($o -match 'Exception|Error|Unknown') { '?' } else { 'ok' }), $c)
}
Say '  Optimizando el launcher para que abra rápido (puede tardar un minuto)...'
Sh "cmd package compile -m speed -f $pkg" | Out-Null

Step "4/5 Poner el launcher como Home (el de fábrica: $stock)"
Hand-Over $stock

Step '5/5 Tema y fondos'
$themes = @(Get-ChildItem (Join-Path $repo 'themes') -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'config.json') })
$default = if ($themes.Name -contains 'mi-onn') { 'mi-onn' } else { '' }
Say ('Temas: ' + ($themes.Name -join ', '))
$name = Ask "Tema a aplicar (Enter = $(if ($default) { $default } else { 'ninguno' }); escribe 'no' para saltar)" $default
if ($name -and $name -ne 'no') {
    $theme = Join-Path $repo "themes\$name"
    if (Test-Path (Join-Path $theme 'config.json')) { Push-Theme $theme } else { Say "No existe el tema $name; lo salto." Yellow }
}

Step 'Listo'
Say "Registro para deshacer: $rec"
Say 'En la TV: elige fondo y ajustes en el menú del launcher. El calendario muestra las cuentas de Google de esa TV.'
if (Yes 'Desconectar la PC de la TV ahora?') { Adb disconnect $script:serial | Out-Null }
Say 'Consejo: cuando termines, apaga la Depuración USB / por red en Opciones de desarrollador de la TV.' Yellow
Read-Host 'Pulsa Enter para salir'
