param(
  [Parameter(Mandatory = $true)]
  [string]$AppUrl,

  [string]$AdminToken = "",

  [string]$PoolCode = "had,hhad",

  [string]$Channel = "c"
)

$ErrorActionPreference = "Stop"

$headers = @{
  "Accept" = "application/json, text/plain, */*"
  "Referer" = "https://m.sporttery.cn/mjc/jsq/zqspf/"
  "Origin" = "https://m.sporttery.cn"
  "User-Agent" = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
}

function Get-Utf8String($Url) {
  $client = New-Object System.Net.WebClient
  $client.Encoding = [System.Text.Encoding]::UTF8
  foreach ($key in $headers.Keys) {
    $client.Headers.Add($key, $headers[$key])
  }
  return $client.DownloadString($Url)
}

$encodedPool = [uri]::EscapeDataString($PoolCode)
$matchUrl = "https://webapi.sporttery.cn/gateway/uniform/football/getMatchCalculatorV1.qry?channel=$Channel&poolCode=$encodedPool"

Write-Host "Fetching Sporttery matches..."
$matchJson = Get-Utf8String $matchUrl
$matchData = $matchJson | ConvertFrom-Json

$ids = @()
foreach ($group in $matchData.value.matchInfoList) {
  foreach ($match in $group.subMatchList) {
    if ($match.matchId) {
      $ids += [string]$match.matchId
    }
  }
}

$supportJson = ""
if ($ids.Count -gt 0) {
  $matchIds = [uri]::EscapeDataString(($ids -join ","))
  $supportUrl = "https://webapi.sporttery.cn/gateway/jc/common/getSupportRateV1.qry?matchIds=$matchIds&poolCode=hhad,had&sportType=1"
  Write-Host "Fetching Sporttery support rates..."
  $supportJson = Get-Utf8String $supportUrl
}

$payload = @{
  matchJson = $matchJson
  supportJson = $supportJson
} | ConvertTo-Json -Depth 8

$importHeaders = @{
  "Content-Type" = "application/json; charset=utf-8"
}
if ($AdminToken -ne "") {
  $importHeaders["X-Admin-Token"] = $AdminToken
}

$target = $AppUrl.TrimEnd("/") + "/api/admin/import-sporttery"
Write-Host "Importing to $target ..."
$bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($payload)
$result = Invoke-RestMethod -Uri $target -Method Post -Headers $importHeaders -Body $bodyBytes
$result | ConvertTo-Json -Depth 4
