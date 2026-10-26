$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
Set-Location $PSScriptRoot
$Sdk = Join-Path $env:LOCALAPPDATA 'SimpleTVLauncher\AndroidSdk'
$ToolsZip = Join-Path $env:TEMP 'simpletv-commandlinetools-win-15859902.zip'
$ToolsUrl = 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
$ExpectedSha256 = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
$SdkManager = Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
try {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw 'Java no esta disponible. Debes tener Java 25 instalado como antes.'
    }
    Write-Host "SDK: $Sdk"
    if (-not (Test-Path $SdkManager)) {
        Write-Host '1/4 Descargando herramientas oficiales de Android (aprox. 156 MB)...'
        New-Item -ItemType Directory -Force -Path $Sdk | Out-Null
        if (Test-Path $ToolsZip) {
            if ((Get-FileHash $ToolsZip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedSha256) { Remove-Item $ToolsZip -Force }
        }
        if (-not (Test-Path $ToolsZip)) { Invoke-WebRequest -UseBasicParsing -Uri $ToolsUrl -OutFile $ToolsZip }
        if ((Get-FileHash $ToolsZip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedSha256) {
            throw 'El archivo descargado no coincide con la firma SHA-256 publicada por Google.'
        }
        Write-Host '2/4 Extrayendo herramientas Android...'
        $Staging = Join-Path $Sdk '_temp-tools'
        if (Test-Path $Staging) { Remove-Item $Staging -Recurse -Force }
        Expand-Archive -Path $ToolsZip -DestinationPath $Staging -Force
        $Target = Join-Path $Sdk 'cmdline-tools\latest'
        New-Item -ItemType Directory -Force -Path (Split-Path $Target -Parent) | Out-Null
        if (Test-Path $Target) { Remove-Item $Target -Recurse -Force }
        Move-Item (Join-Path $Staging 'cmdline-tools') $Target
        Remove-Item $Staging -Recurse -Force
    } else { Write-Host '1/4 Herramientas Android ya disponibles.' }

    $env:ANDROID_HOME = $Sdk
    $env:ANDROID_SDK_ROOT = $Sdk
    $NormalizedSdk = $Sdk.Replace('\','/')
    Set-Content -Path (Join-Path $PSScriptRoot 'local.properties') -Value "sdk.dir=$NormalizedSdk" -Encoding ASCII

    Write-Host '3/4 Instalando SDK Android API 35 y Build Tools 36...'
    # The first invocation accepts Google's SDK licenses non-interactively.
    1..80 | ForEach-Object { 'y' } | & $SdkManager "--sdk_root=$Sdk" '--licenses'
    if ($LASTEXITCODE -ne 0) { throw 'No fue posible aceptar las licencias del SDK.' }
    & $SdkManager "--sdk_root=$Sdk" 'platforms;android-35' 'build-tools;36.0.0' 'platform-tools'
    if ($LASTEXITCODE -ne 0) { throw 'Error descargando componentes SDK. Comprueba tu conexion.' }

    Write-Host '4/4 Compilando APK (Gradle 9.8.2 / AGP 9.4.0)...'
    & (Join-Path $PSScriptRoot 'gradlew.bat') ':app:assembleDebug' '--no-daemon'
    if ($LASTEXITCODE -ne 0) { throw 'Gradle ha fallado. Revisa el error mostrado arriba.' }
    $Apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
    if (-not (Test-Path $Apk)) { throw 'Gradle termino pero no se encontro el APK.' }
    Write-Host ''
    Write-Host "APK CREADO: $Apk" -ForegroundColor Green
    Start-Process explorer.exe -ArgumentList ('/select,"' + $Apk + '"')
    exit 0
} catch {
    Write-Host ''
    Write-Host ('ERROR: ' + $_.Exception.Message) -ForegroundColor Red
    exit 1
}
