param(
    [Parameter(Mandatory=$true, HelpMessage="Enter your Xelta JWT token")]
    [string]$Token
)

$headers = @{
    "Authorization" = "Bearer $Token"
    "Content-Type" = "application/json"
}

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "     Testing Xelta Credit API             " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

try {
    $response = Invoke-RestMethod -Uri "https://devpmt.xelta.ai/api/users/credits" -Headers $headers -Method GET -ErrorAction Stop
    Write-Host "✅ SUCCESS!" -ForegroundColor Green
    $response | ConvertTo-Json -Depth 5 | Write-Host
} catch {
    Write-Host "❌ FAILED!" -ForegroundColor Red
    if ($_.Exception.Response) {
        $stream = $_.Exception.Response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        Write-Host "Server Response: $($reader.ReadToEnd())" -ForegroundColor Red
    }
}
