# Pre-commit checks: secret scan of staged changes + fast unit tests for the parts that changed.
$ErrorActionPreference = "Stop"
$env:Path = [Environment]::GetEnvironmentVariable("Path", "Machine") + ";" + [Environment]::GetEnvironmentVariable("Path", "User")
$root = (git rev-parse --show-toplevel).Trim()
Set-Location $root

if (-not (Get-Command gitleaks -ErrorAction SilentlyContinue)) { Write-Host "pre-commit: gitleaks is not installed (winget install Gitleaks.Gitleaks)"; exit 1 }
gitleaks git --staged --redact --no-banner --config .gitleaks.toml
if ($LASTEXITCODE -ne 0) { Write-Host "pre-commit: possible secret in staged changes - commit blocked"; exit 1 }

$staged = git diff --cached --name-only
if ($staged -match '^firmware/') {
  & "$root\firmware\test\run.ps1"
  if ($LASTEXITCODE -ne 0) { Write-Host "pre-commit: firmware tests failed"; exit 1 }
}
foreach ($pkg in @("logger", "web")) {
  if (($staged -match "^$pkg/") -and (Test-Path "$root\$pkg\package.json")) {
    Push-Location "$root\$pkg"
    npm test --silent
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { Write-Host "pre-commit: $pkg tests failed"; exit 1 }
  }
}
# Android unit tests take ~1 min, so they run in CI and in scripts/test-all.ps1 rather than on every commit.
exit 0
