param(
    [Parameter(Mandatory=$true, HelpMessage="Enter your Xelta JWT token")]
    [string]$Token,
    
    [Parameter(Mandatory=$false, HelpMessage="Enter a prompt for the image")]
    [string]$Prompt = "A glowing neon Minecraft diamond in a dark cave"
)

$headers = @{
    "Authorization" = "Bearer $Token"
    "Content-Type" = "application/json"
}

$body = @{
    "prompt" = $Prompt
} | ConvertTo-Json

Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "     Testing Xelta Image Generation       " -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "Prompt: $Prompt"

try {
    $response = Invoke-RestMethod -Uri "https://bgremover.xelta.ai/generate" -Headers $headers -Method POST -Body $body -ErrorAction Stop
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
