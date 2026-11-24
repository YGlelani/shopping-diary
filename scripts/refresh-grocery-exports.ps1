param(
    [switch]$Catalogue,
    [switch]$InitialCatalogue,
    [switch]$Branches,
    [switch]$OpenPrices
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$output = Join-Path $repo 'backend/target/grocery-exports'
New-Item -ItemType Directory -Force -Path $output | Out-Null
$null = Get-Command curl.exe -ErrorAction Stop

function Save-Export([string]$Url, [string]$Name) {
    $destination = Join-Path $output $Name
    $partial = "$destination.partial"
    & curl.exe --fail --silent --show-error --location --retry 2 --max-time 1800 --output $partial $Url
    if ($LASTEXITCODE -ne 0) { throw "Could not download $Name. The previous export is still available." }
    Move-Item -LiteralPath $partial -Destination $destination -Force
}

if ($InitialCatalogue) {
    Save-Export 'https://static.openfoodfacts.org/data/openfoodfacts-products.jsonl.gz' 'catalogue-initial.jsonl.gz'
}
if ($Catalogue) {
    Save-Export 'https://static.openfoodfacts.org/data/delta/index.txt' 'catalogue-deltas.txt'
    $latest = Get-Content -LiteralPath (Join-Path $output 'catalogue-deltas.txt') |
        ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^openfoodfacts_products_[0-9]+_[0-9]+\.json\.gz$' } | Select-Object -First 1
    if (-not $latest) { throw 'The Open Food Facts index did not contain a recognized JSONL delta filename.' }
    $state = Join-Path $output 'catalogue-latest-delta.txt'
    if (-not (Test-Path -LiteralPath $state) -or (Get-Content -LiteralPath $state -Raw).Trim() -ne $latest) {
        Save-Export "https://static.openfoodfacts.org/data/delta/$latest" 'catalogue-delta.jsonl.gz'
        Set-Content -LiteralPath $state -Value $latest
    }
}
if ($Branches) {
    $null = Get-Command osmium -ErrorAction Stop
    Save-Export 'https://download.geofabrik.de/africa/south-africa-latest.osm.pbf' 'south-africa.osm.pbf'
    $inputPbf = Join-Path $output 'south-africa.osm.pbf'
    $filtered = Join-Path $output 'supermarkets.osm.pbf'
    $partial = Join-Path $output 'branches-partial.geojsonseq'
    & osmium tags-filter $inputPbf 'nwr/shop=supermarket' -o $filtered --overwrite
    if ($LASTEXITCODE -ne 0) { throw 'Osmium could not filter the supermarket extract.' }
    & osmium export $filtered --attributes=type,id,timestamp -f geojsonseq -o $partial --overwrite
    if ($LASTEXITCODE -ne 0) { throw 'Osmium could not export the supermarket geometries.' }
    Move-Item -LiteralPath $partial -Destination (Join-Path $output 'branches.geojsonseq') -Force
}
if ($OpenPrices) {
    Save-Export 'https://prices.openfoodfacts.org/data/locations.jsonl.gz' 'open-prices-locations.jsonl.gz'
    Save-Export 'https://prices.openfoodfacts.org/data/prices.jsonl.gz' 'open-prices.jsonl.gz'
}
Write-Output $output
