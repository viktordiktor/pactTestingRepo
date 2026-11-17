param(
    [Parameter(Mandatory = $true)][string]$Pacticipant,
    [Parameter(Mandatory = $true)][string]$Version,
    [string]$Environment = "staging",
    [string]$ContainerBrokerUrl = "http://host.docker.internal:9292",
    [string]$PactCliImage = "pactfoundation/pact-cli:latest"
)

# "Can I deploy version X to environment Y?"
#
# Exit code 0 = yes, 1 = no. That is the whole point: this is a CI gate,
# not a report you read with your eyes.
#
# In a real pipeline the CLI runs directly on the agent:
#   pact-broker can-i-deploy -a order-service -e $GIT_SHA --to-environment staging \
#     --broker-base-url $PACT_BROKER_URL
#
# Here it runs in a container, so it reaches the Broker through
# host.docker.internal (Docker Desktop / Linux with host-gateway).
# The Broker must be up:  docker compose up -d pact-broker

if ($PactCliImage -eq "") {
    Write-Host "PactCliImage is empty"
    exit 2
}

Write-Host "can-i-deploy: $Pacticipant $Version -> $Environment"

docker run --rm --add-host host.docker.internal:host-gateway $PactCliImage `
    broker can-i-deploy `
    -a $Pacticipant `
    -e $Version `
    --to-environment $Environment `
    -b $ContainerBrokerUrl

$result = $LASTEXITCODE
Write-Host "can-i-deploy exit code: $result"
exit $result
