param(
    [string]$BrokerUrl = "http://localhost:9292",
    [string]$Version = "1.0.0",
    [string]$PactFile = "pacts/order-service-payment-service.json"
)

# Публикация контракта в Pact Broker "руками", без CLI.
# В реальном проекте это делает `pact-broker publish` в пайплайне consumer-а.
#
#   docker compose up -d pact-broker
#   ./scripts/publish-pact.ps1
#   mvn test -Dtest=PaymentProviderBrokerVerificationTest -Dpact.broker.url=http://localhost:9292
#
# ВАЖНО: один и тот же version нельзя публиковать дважды с РАЗНЫМ содержимым.
# Брокер ответит "Cannot change the content of the pact ...".
# Каждая публикация = новый уникальный номер версии (обычно git sha).

if (-not (Test-Path $PactFile)) {
    Write-Error "Pact file not found: $PactFile (run PaymentConsumerContractTest first)"
    exit 1
}

$uri = "$BrokerUrl/pacts/provider/payment-service/consumer/order-service/version/$Version"

Write-Host "Publishing $PactFile -> $uri"

try {
    $response = Invoke-WebRequest -Uri $uri -Method PUT -ContentType "application/json" -InFile $PactFile -UseBasicParsing -TimeoutSec 60
    Write-Host "OK: HTTP $($response.StatusCode)"
    Write-Host "Open: $BrokerUrl/pacts/provider/payment-service/consumer/order-service/version/$Version"
} catch {
    Write-Error "Publish failed: $($_.Exception.Message)"
    if ($_.Exception.Response) {
        $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
        Write-Host $reader.ReadToEnd()
    }
    exit 1
}
