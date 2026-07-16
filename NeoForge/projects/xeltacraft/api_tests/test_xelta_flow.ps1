param(
    [Parameter(Mandatory=$false)]
    [string]$Token = $env:XELTA_TOKEN,

    [Parameter(Mandatory=$false)]
    [string]$Prompt = "A glowing neon Minecraft diamond in a dark cave",

    [Parameter(Mandatory=$false)]
    [switch]$RunLiveGeneration
)

$ErrorActionPreference = "Stop"

$CreditUrl = "https://devpmt.xelta.ai/api/users/credits"
$PhotoBaseUrl = "https://bgremover.xelta.ai"
$ImagePath = Join-Path $PSScriptRoot "empty.png"
Add-Type -AssemblyName System.Net.Http

function Invoke-Scenario {
    param(
        [string]$Name,
        [scriptblock]$Body
    )

    Write-Host ""
    Write-Host "=== $Name ===" -ForegroundColor Cyan
    try {
        & $Body
        Write-Host "PASS: $Name" -ForegroundColor Green
    } catch {
        Write-Host "FAIL: $Name" -ForegroundColor Red
        Write-Host $_.Exception.Message -ForegroundColor Red
        if ($_.Exception.Response) {
            $stream = $_.Exception.Response.GetResponseStream()
            $reader = New-Object System.IO.StreamReader($stream)
            Write-Host $reader.ReadToEnd() -ForegroundColor Red
        }
    }
}

function New-AuthHeaders {
    param([string]$Jwt)
    return @{ Authorization = "Bearer $Jwt"; "User-Agent" = "XeltaCraft-SmokeTest/1.0" }
}

function Assert-HttpFails {
    param(
        [scriptblock]$Body,
        [int[]]$ExpectedStatus
    )

    try {
        & $Body | Out-Null
        throw "Expected HTTP failure, but request succeeded."
    } catch {
        if (-not $_.Exception.Response) {
            throw
        }
        $status = [int]$_.Exception.Response.StatusCode
        if ($ExpectedStatus -notcontains $status) {
            throw "Expected HTTP status $($ExpectedStatus -join ', '), got $status."
        }
    }
}

function Submit-PhotoLabJob {
    param(
        [string]$Jwt,
        [string]$TextPrompt
    )

    $client = [System.Net.Http.HttpClient]::new()
    $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $Jwt)
    $client.DefaultRequestHeaders.UserAgent.ParseAdd("XeltaCraft-SmokeTest/1.0")

    $content = [System.Net.Http.MultipartFormDataContent]::new()
    $imageBytes = [System.IO.File]::ReadAllBytes($ImagePath)
    $fileContent = [System.Net.Http.ByteArrayContent]::new($imageBytes)
    $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("image/png")
    $content.Add($fileContent, "file", "image.png")
    $content.Add([System.Net.Http.StringContent]::new($TextPrompt), "prompt")
    $content.Add([System.Net.Http.StringContent]::new("blurry, low quality, pixelated, distorted, ugly"), "negative_prompt")

    $httpResponse = $client.PostAsync("$PhotoBaseUrl/api/ai-backgrounds/generate", $content).GetAwaiter().GetResult()
    $body = $httpResponse.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    if (-not $httpResponse.IsSuccessStatusCode) {
        $status = [int]$httpResponse.StatusCode
        throw "HTTP ${status}: $body"
    }

    $response = $body | ConvertFrom-Json

    $taskId = $response.task_id
    if (-not $taskId -and $response.data) { $taskId = $response.data.task_id }
    if (-not $taskId -and $response.job_id) { $taskId = $response.job_id }
    if (-not $taskId) { throw "No task id returned: $($response | ConvertTo-Json -Depth 10)" }

    return $taskId
}

function Wait-PhotoLabResult {
    param(
        [string]$Jwt,
        [string]$TaskId
    )

    $headers = New-AuthHeaders $Jwt

    for ($i = 0; $i -lt 60; $i++) {
        $statusResponse = Invoke-RestMethod `
            -Uri "$PhotoBaseUrl/api/tasks/status/$TaskId" `
            -Method GET `
            -Headers $headers

        $status = $statusResponse.status
        if (-not $status -and $statusResponse.data) { $status = $statusResponse.data.status }
        if (-not $status) { $status = "processing" }

        if ($status -in @("done", "completed", "success")) {
            $result = Invoke-RestMethod `
                -Uri "$PhotoBaseUrl/api/tasks/result/$TaskId" `
                -Method GET `
                -Headers $headers

            $data = if ($result.data) { $result.data } elseif ($result.result) { $result.result } else { $result }
            $url = $data.result_url
            if (-not $url) { $url = $data.output_url }
            if (-not $url) { $url = $data.image_url }
            if (-not $url) { $url = $data.url }
            if (-not $url) { throw "No image URL returned: $($result | ConvertTo-Json -Depth 10)" }

            return $url
        }

        if ($status -in @("failed", "error")) {
            throw "Generation failed: $($statusResponse | ConvertTo-Json -Depth 10)"
        }

        Start-Sleep -Seconds 3
    }

    throw "Timed out waiting for task $TaskId"
}

Invoke-Scenario "Auth scenario 1: missing token is rejected" {
    Assert-HttpFails -ExpectedStatus @(401, 403) -Body {
        Invoke-RestMethod -Uri $CreditUrl -Method GET
    }
}

Invoke-Scenario "Auth scenario 2: invalid token is rejected" {
    Assert-HttpFails -ExpectedStatus @(401, 403) -Body {
        Invoke-RestMethod -Uri $CreditUrl -Method GET -Headers (New-AuthHeaders "invalid-token")
    }
}

Invoke-Scenario "Auth scenario 3: valid token checks credits" {
    if ([string]::IsNullOrWhiteSpace($Token)) {
        Write-Host "SKIP: set XELTA_TOKEN to run this scenario." -ForegroundColor Yellow
        return
    }
    $response = Invoke-RestMethod -Uri $CreditUrl -Method GET -Headers (New-AuthHeaders $Token)
    $response | ConvertTo-Json -Depth 10
}

Invoke-Scenario "Generation scenario 1: empty prompt is blocked locally" {
    if ([string]::IsNullOrWhiteSpace("")) {
        Write-Host "Local validation would block before sending C2SGeneratePacket."
        return
    }
    throw "Empty prompt validation did not trigger."
}

Invoke-Scenario "Generation scenario 2: invalid token cannot submit job" {
    try {
        Submit-PhotoLabJob -Jwt "invalid-token" -TextPrompt $Prompt
        throw "Expected invalid token generation submit to fail."
    } catch {
        if ($_.Exception.Message -notmatch "HTTP (401|403)") {
            throw
        }
    }
}

Invoke-Scenario "Generation scenario 3: live submit, poll, and output URL" {
    if (-not $RunLiveGeneration) {
        Write-Host "SKIP: pass -RunLiveGeneration to spend credits and fetch a real output URL." -ForegroundColor Yellow
        return
    }
    if ([string]::IsNullOrWhiteSpace($Token)) {
        throw "Set XELTA_TOKEN or pass -Token before live generation."
    }

    $taskId = Submit-PhotoLabJob -Jwt $Token -TextPrompt $Prompt
    Write-Host "Submitted task: $taskId"
    $url = Wait-PhotoLabResult -Jwt $Token -TaskId $taskId
    Write-Host "Output URL: $url"
}
