param(
    [Parameter(Mandatory = $true)][string]$Pacticipant,
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Environment = "staging",
    [string]$BrokerUrl = "http://localhost:9292",
    [switch]$CreateEnvironment,
    [switch]$Production
)

# Records the fact "version X of pacticipant Y is now running in environment Z".
#
# can-i-deploy needs this: it compares the version you want to deploy with what is
# ALREADY in the target environment. Without recorded deployments it answers
# "no version is currently recorded as deployed/released in this environment".
#
# In a real pipeline this is the last step of a deploy job:
#   pact-broker record-deployment --pacticipant payment-service --version $GIT_SHA \
#     --environment production --broker-base-url $PACT_BROKER_URL
#
# Here we call the Broker HTTP API directly, because pact-broker-client 1.78 inside
# the official pact-cli image ignored --broker-base-url for record-deployment and
# kept connecting to http://localhost:9292 (see docs/07-can-i-deploy.md, section 8).

$ErrorActionPreference = "Stop"

function Get-BrokerEnvironments {
    $response = Invoke-WebRequest -Uri "$BrokerUrl/environments" `
        -Headers @{ Accept = "application/hal+json" } -UseBasicParsing -TimeoutSec 30
    $text = if ($response.Content -is [byte[]]) {
        [System.Text.Encoding]::UTF8.GetString($response.Content)
    } else {
        $response.Content
    }
    return ($text | ConvertFrom-Json)._embedded.environments
}

function New-BrokerEnvironment {
    $body = @{
        name        = $Environment
        displayName = $Environment
        production  = [bool]$Production
    } | ConvertTo-Json -Compress

    Invoke-WebRequest -Uri "$BrokerUrl/environments" -Method POST `
        -ContentType "application/json" -Body $body -UseBasicParsing -TimeoutSec 30 | Out-Null
}

$environments = Get-BrokerEnvironments
$target = $environments | Where-Object { $_.name -eq $Environment }

if (-not $target) {
    if (-not $CreateEnvironment) {
        $known = ($environments | ForEach-Object { $_.name }) -join ", "
        Write-Host "Environment '$Environment' not found. Known environments: $known"
        Write-Host "Re-run with -CreateEnvironment to create it."
        exit 1
    }

    try {
        New-BrokerEnvironment
        Write-Host "Created environment '$Environment' (production=$([bool]$Production))"
    } catch {
        # 400 usually means "already exists" - we re-read the list below anyway
        Write-Host "Create environment returned: $($_.Exception.Message)"
    }

    $target = (Get-BrokerEnvironments) | Where-Object { $_.name -eq $Environment }
    if (-not $target) {
        Write-Host "Still cannot find environment '$Environment'."
        exit 1
    }
}

$uri = "$BrokerUrl/pacticipants/$Pacticipant/versions/$Version/deployed-versions/environment/$($target.uuid)"

try {
    Invoke-WebRequest -Uri $uri -Method POST -ContentType "application/json" -Body "{}" `
        -UseBasicParsing -TimeoutSec 30 | Out-Null
    Write-Host "Recorded deployment: $Pacticipant $Version -> $Environment"
} catch {
    Write-Host "Failed to record deployment: $($_.Exception.Message)"
    if ($_.Exception.Response) {
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        Write-Host $reader.ReadToEnd()
    }
    exit 1
}
