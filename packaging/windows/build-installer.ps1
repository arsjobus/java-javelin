param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')]
    [string]$AppVersion
)

$ErrorActionPreference = "Stop"

if ($env:OS -ne "Windows_NT") {
    throw "build-installer.ps1 builds the Windows installer and must be run on Windows."
}

foreach ($tool in @("java", "jpackage", "mvn")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Missing '$tool'. Install JDK 25+ and Apache Maven first."
    }
}

$jdkVersion = (& java -version 2>&1 | Select-String -Pattern '"([0-9]+)' | Select-Object -First 1).Matches.Groups[1].Value
if ([int]$jdkVersion -lt 25) {
    throw "Javelin IDE needs JDK 25 or newer to build; found JDK $jdkVersion."
}

$root = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
Set-Location $root

& mvn -q clean package
if ($LASTEXITCODE -ne 0) {
    throw "Maven package failed."
}

$jarFile = Get-ChildItem (Join-Path $root "target") -Filter "Javelin-IDE-*.jar" -File | Select-Object -First 1
if (-not $jarFile) {
    throw "Maven package succeeded but did not produce the application JAR."
}
$jar = $jarFile.Name

$inputDir = Join-Path $root "target/dist"
$fxDir = Join-Path $root "target/fx"
Remove-Item $inputDir, $fxDir -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $inputDir, $fxDir | Out-Null
& mvn -q dependency:copy-dependencies "-DoutputDirectory=$inputDir" -DincludeScope=runtime
if ($LASTEXITCODE -ne 0) {
    throw "Could not copy runtime dependencies."
}

Copy-Item $jarFile.FullName $inputDir
$platformJars = @(Get-ChildItem (Join-Path $inputDir "javafx-*-win*.jar") -ErrorAction SilentlyContinue)
if ($platformJars.Count -eq 0) {
    throw "Could not find the Windows JavaFX runtime jars."
}
Move-Item $platformJars.FullName $fxDir
Remove-Item (Join-Path $inputDir "javafx-*.jar")

& java "-Djava.awt.headless=true" -cp (Join-Path $root "target/classes") com.javelin.icon.IconExporter (Join-Path $root "packaging/icons")
if ($LASTEXITCODE -ne 0) {
    throw "Could not export application icons."
}

$dist = Join-Path $root "dist"
Remove-Item $dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$modules = "javafx.controls,java.se,jdk.unsupported,jdk.charsets,jdk.localedata,jdk.zipfs,jdk.compiler"
& jpackage `
    --type msi `
    --name "Javelin IDE" `
    --app-version $AppVersion `
    --input $inputDir `
    --main-jar $jar `
    --main-class com.javelin.Launcher `
    --icon (Join-Path $root "packaging/icons/icon.ico") `
    --module-path $fxDir `
    --add-modules $modules `
    --java-options "-Dfile.encoding=UTF-8" `
    --java-options "--enable-native-access=javafx.graphics" `
    --win-menu `
    --win-menu-group "Javelin IDE" `
    --win-shortcut `
    --dest $dist
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed to build the Windows installer."
}

$installer = Get-ChildItem $dist -Filter "*.msi" | Select-Object -First 1
if (-not $installer) {
    throw "jpackage succeeded but did not produce an MSI installer."
}
Move-Item $installer.FullName (Join-Path $dist "Javelin-IDE-$AppVersion-windows-x64.msi")
