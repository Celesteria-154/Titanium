param(
    [Parameter(Mandatory = $true)]
    [string]$NgxRoot,

    [Parameter(Mandatory = $true)]
    [string]$VcRuntimeRoot,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [Parameter(Mandatory = $true)]
    [string]$NoticeFile
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Utility/Microsoft.PowerShell.Utility.psd1') -Force
Import-Module (Join-Path $PSHOME 'Modules/Microsoft.PowerShell.Security/Microsoft.PowerShell.Security.psd1') -Force

$ngxVersion = '310.9.1'
$vcVersion = '14.51.36247.0'
$bundleId = "dlss-$ngxVersion-vc-$vcVersion"
$assets = @(
    @{
        Source = Join-Path $VcRuntimeRoot 'msvcp140.dll'
        Resource = 'natives/windows-x86_64/vc-runtime/msvcp140.dll'
        Scope = 'native'
        Target = 'msvcp140.dll'
        Size = 643512
        Sha256 = '7c26614e1d733892c2deac7e245ce115504b1d80592dd0a01b08e3e5a55f89ca'
        Signer = 'Microsoft Corporation'
        Version = $vcVersion
    },
    @{
        Source = Join-Path $VcRuntimeRoot 'vcruntime140.dll'
        Resource = 'natives/windows-x86_64/vc-runtime/vcruntime140.dll'
        Scope = 'native'
        Target = 'vcruntime140.dll'
        Size = 178616
        Sha256 = 'd1f4225df2cd877dbf130d5668a021dce3f94118455ff5ec952061c30afc9ce7'
        Signer = 'Microsoft Corporation'
        Version = $vcVersion
    },
    @{
        Source = Join-Path $VcRuntimeRoot 'vcruntime140_1.dll'
        Resource = 'natives/windows-x86_64/vc-runtime/vcruntime140_1.dll'
        Scope = 'native'
        Target = 'vcruntime140_1.dll'
        Size = 50112
        Sha256 = 'a7146c08f89fe5b04541ab507cdb59ff7b44534d4ba3c668a426c6450a03434e'
        Signer = 'Microsoft Corporation'
        Version = $vcVersion
    },
    @{
        Source = Join-Path $NgxRoot 'lib/Windows_x86_64/rel/nvngx_dlss.dll'
        Resource = 'vendor/nvidia/dlss/310.9.1/nvngx_dlss.dll'
        Scope = 'game'
        Target = 'titanium/dlss/310.9.1/nvngx_dlss.dll'
        Size = 58956912
        Sha256 = '3975567b8943c53acce397f2b72380092f84f162d00b0d2c7d08a1025c563983'
        Signer = 'NVIDIA Corporation'
        Version = ''
    }
)

foreach ($asset in $assets) {
    if (-not (Test-Path -LiteralPath $asset.Source -PathType Leaf)) {
        throw "Missing DLSS release input: $($asset.Source)"
    }
    $item = Get-Item -LiteralPath $asset.Source
    if ($item.Length -ne $asset.Size) {
        throw "Unexpected size for $($item.Name): $($item.Length)"
    }
    $actualHash = (Get-FileHash -LiteralPath $asset.Source -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualHash -ne $asset.Sha256) {
        throw "SHA-256 mismatch for $($item.Name): $actualHash"
    }
    if ($asset.Version -and $item.VersionInfo.FileVersion -ne $asset.Version) {
        throw "Version mismatch for $($item.Name): $($item.VersionInfo.FileVersion)"
    }
    $signature = Get-AuthenticodeSignature -LiteralPath $asset.Source
    if ($signature.Status -ne 'Valid' -or -not $signature.SignerCertificate.Subject.Contains($asset.Signer)) {
        throw "Authenticode verification failed for $($item.Name): $($signature.Status) $($signature.SignerCertificate.Subject)"
    }
}

$license = Join-Path $NgxRoot 'LICENSE.txt'
if (-not (Test-Path -LiteralPath $license -PathType Leaf)) {
    throw "Missing NVIDIA license: $license"
}
$licenseHash = (Get-FileHash -LiteralPath $license -Algorithm SHA256).Hash.ToLowerInvariant()
if ($licenseHash -ne 'd4216e39ebef5f9b50a6712ebb37beeb5379862a67733a9999c651f21592aaf0') {
    throw "NVIDIA license SHA-256 mismatch: $licenseHash"
}
if (-not (Test-Path -LiteralPath $NoticeFile -PathType Leaf)) {
    throw "Missing DLSS third-party notice: $NoticeFile"
}

foreach ($asset in $assets) {
    $target = Join-Path $OutputDirectory $asset.Resource
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
    Copy-Item -LiteralPath $asset.Source -Destination $target -Force
}

$licensesDirectory = Join-Path $OutputDirectory 'META-INF/licenses'
New-Item -ItemType Directory -Force -Path $licensesDirectory | Out-Null
Copy-Item -LiteralPath $license -Destination (Join-Path $licensesDirectory 'NVIDIA-RTX-SDK-LICENSE.txt') -Force
Copy-Item -LiteralPath $NoticeFile -Destination (Join-Path $licensesDirectory 'TITANIUM-DLSS-THIRD-PARTY-NOTICES.txt') -Force

$manifestDirectory = Join-Path $OutputDirectory 'META-INF/titanium'
New-Item -ItemType Directory -Force -Path $manifestDirectory | Out-Null
$manifest = @(
    "bundle.id=$bundleId"
    "asset.count=$($assets.Count)"
)
for ($index = 0; $index -lt $assets.Count; $index++) {
    $asset = $assets[$index]
    $manifest += "asset.$index.resource=/$($asset.Resource)"
    $manifest += "asset.$index.scope=$($asset.Scope)"
    $manifest += "asset.$index.target=$($asset.Target)"
    $manifest += "asset.$index.size=$($asset.Size)"
    $manifest += "asset.$index.sha256=$($asset.Sha256)"
}
$manifest += ''
Set-Content -LiteralPath (Join-Path $manifestDirectory 'dlss-assets.properties') `
        -Value $manifest -Encoding Ascii

Write-Output "Prepared Titanium DLSS release bundle $bundleId in $OutputDirectory"
