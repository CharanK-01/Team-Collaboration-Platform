$ErrorActionPreference = 'Stop'
# 1. Run Maven tests in backend and capture output
Write-Host "Running Maven tests..."
$testCmd = "& .\backend\mvnw test"
$testOutput = Invoke-Expression $testCmd 2>&1
# Filter relevant lines
$patterns = @('Tests run:', 'FAIL', 'ERROR\]', 'BUILD')
foreach ($p in $patterns) {
    $testOutput | Select-String -Pattern $p
}
# Also print names of failing tests (lines containing "\[ERROR\]" followed by test name)
$testOutput | Select-String -Pattern "\[ERROR\]\s+.*Tests run" -NotMatch | ForEach-Object { $_ }
# 2. Show git status short
Write-Host "\nGit status --short:"
git -C "C:\Users\ASUS\Team-Collaboration-Platform" status --short
# 3. Fail if any .java under src contains DEBUGTMP or "if (true)"
Write-Host "\nChecking for forbidden patterns..."
$forbidden = Get-ChildItem -Recurse -Path "C:\Users\ASUS\Team-Collaboration-Platform\backend\src" -Include "*.java" | Select-String -Pattern 'DEBUGTMP|if\s*\(\s*true\s*\)'
if ($forbidden) {
    Write-Error "Forbidden pattern found in source files."
    $forbidden | ForEach-Object { Write-Host $_.Path }
    exit 1
}
# 4. List any two files with the same name under src/main (duplicate class check)
Write-Host "\nChecking for duplicate class names under src/main..."
$files = Get-ChildItem -Recurse -Path "C:\Users\ASUS\Team-Collaboration-Platform\backend\src\main\java" -Include "*.java"
$grouped = $files | Group-Object -Property Name | Where-Object { $_.Count -gt 1 }
if ($grouped) {
    $grouped | Select-Object -First 2 | ForEach-Object { $_.Group[0].FullName; $_.Group[1].FullName }
} else {
    Write-Host "No duplicate class names found."
}
