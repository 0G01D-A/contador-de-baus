param(
    [string]$WixDirectory,
    [switch]$Portable
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
if ($WixDirectory) {
    $env:Path = (Resolve-Path -LiteralPath $WixDirectory).Path + ';' + $env:Path
}

$buildDir = Join-Path $PSScriptRoot ('out/build-' + [Guid]::NewGuid().ToString('N'))
$classes = Join-Path $buildDir 'classes'
$inputDir = Join-Path $buildDir 'input'
$packageDir = Join-Path $buildDir 'package'
$dist = Join-Path $PSScriptRoot 'dist'
New-Item -ItemType Directory -Force $classes, $inputDir, $packageDir, $dist | Out-Null
$sources = Get-ChildItem -Path src -Filter '*.java' | Select-Object -ExpandProperty FullName

& javac -encoding UTF-8 -d $classes $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed.' }
& jar --create --file (Join-Path $inputDir 'contador.jar') --main-class Main -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'jar failed.' }

$packageOptions = @(
    '--name', 'Contador de Baus',
    '--app-version', '1.2.0',
    '--vendor', 'Contador de Baus',
    '--input', $inputDir,
    '--main-jar', 'contador.jar',
    '--main-class', 'Main',
    '--add-modules', 'java.desktop',
    '--java-options', '--enable-native-access=ALL-UNNAMED',
    '--dest', $packageDir
)
if ($Portable) {
    & jpackage @packageOptions --type app-image
} else {
    $installerOptions = @(
        '--type', 'exe', '--win-per-user-install', '--win-menu',
        '--win-menu-group', 'Contador de Baus',
        '--win-upgrade-uuid', '8a877019-c3d5-4ef3-9878-ef276dc758ba'
    )
    & jpackage @packageOptions @installerOptions
}
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }
if ($Portable) {
    Write-Output (Join-Path $packageDir 'Contador de Baus')
} else {
    $installer = Get-ChildItem -LiteralPath $packageDir -Filter '*.exe' | Select-Object -First 1
    $target = Join-Path $dist $installer.Name
    Move-Item -LiteralPath $installer.FullName -Destination $target -Force
    Copy-Item -LiteralPath (Join-Path $inputDir 'contador.jar') -Destination $dist -Force
    Write-Output $target
}
