# Builds and runs the firmware host tests with any available C++ compiler (zig, g++ or clang++).
$ErrorActionPreference = "Stop"
$here = $PSScriptRoot
$out = Join-Path $env:TEMP "solar_pi30_tests.exe"
$src = Join-Path $here "test_pi30.cpp"
$inc = Join-Path $here "..\solar_monitor_v3"
if (Get-Command zig -ErrorAction SilentlyContinue) { & zig c++ -std=c++17 -Wall -Wextra -I $inc $src -o $out }
elseif (Get-Command g++ -ErrorAction SilentlyContinue) { & g++ -std=c++17 -Wall -Wextra -I $inc $src -o $out }
else { & clang++ -std=c++17 -Wall -Wextra -I $inc $src -o $out }
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $out
exit $LASTEXITCODE
