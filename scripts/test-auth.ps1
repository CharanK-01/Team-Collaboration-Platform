# ============================================================
# T1.2 Part B – Auth endpoint tests
# Run from repo root: powershell -ExecutionPolicy Bypass -File .\scripts\test-auth.ps1
# ============================================================

# ---------- Test 1: Login with correct credentials ----------
Write-Host "=== TEST 1: Login with correct credentials ===" -ForegroundColor Cyan
try {
    $r1 = Invoke-WebRequest -Uri http://localhost:8080/api/auth/login `
        -Method POST `
        -ContentType "application/json" `
        -Body '{"email":"charan@example.com","password":"password123"}' `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "Status: $($r1.StatusCode)"
    Write-Host "Body: $($r1.Content)"

    $json1 = $r1.Content | ConvertFrom-Json
    $token  = $json1.token

    if ($r1.StatusCode -eq 200 -and $token) {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - status=$($r1.StatusCode), token missing" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# ---------- Test 2: GET /api/users/me WITH valid token ----------
Write-Host ""
Write-Host "=== TEST 2: GET /api/users/me with valid token ===" -ForegroundColor Cyan
try {
    $r2 = Invoke-WebRequest -Uri http://localhost:8080/api/users/me `
        -Method GET `
        -Headers @{ Authorization = "Bearer $token" } `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "Status: $($r2.StatusCode)"
    Write-Host "Body: $($r2.Content)"

    $body2 = $r2.Content | ConvertFrom-Json
    if ($r2.StatusCode -eq 200 -and $body2.email -eq "charan@example.com") {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - status=$($r2.StatusCode), email=$($body2.email)" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# ---------- Test 3: GET /api/users/me with NO token → expect 401 ----------
Write-Host ""
Write-Host "=== TEST 3: GET /api/users/me with NO token (expect 401) ===" -ForegroundColor Cyan
try {
    $r3 = Invoke-WebRequest -Uri http://localhost:8080/api/users/me `
        -Method GET `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "UNEXPECTED SUCCESS - Status: $($r3.StatusCode)" -ForegroundColor Red
    exit 1
} catch {
    $code3 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code3 (expected 401)"
    if ($code3 -eq 401) {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - got $code3" -ForegroundColor Red
        exit 1
    }
}

# ---------- Test 4: Login with WRONG password → expect 401 ----------
Write-Host ""
Write-Host "=== TEST 4: Login with wrong password (expect 401) ===" -ForegroundColor Cyan
try {
    $r4 = Invoke-WebRequest -Uri http://localhost:8080/api/auth/login `
        -Method POST `
        -ContentType "application/json" `
        -Body '{"email":"charan@example.com","password":"wrongpassword"}' `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "UNEXPECTED SUCCESS - Status: $($r4.StatusCode)" -ForegroundColor Red
    exit 1
} catch {
    $code4 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code4 (expected 401)"
    if ($code4 -eq 401) {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - got $code4" -ForegroundColor Red
        exit 1
    }
}

# ---------- Test 5: GET /api/users/me with TAMPERED token → expect 401 ----------
# Tamper by changing one character in the middle of the payload (second JWT segment).
# The signature still belongs to the original payload, so the server must reject it.
Write-Host ""
Write-Host "=== TEST 5: GET /api/users/me with tampered token (expect 401) ===" -ForegroundColor Cyan

$parts        = $token.Split(".")
$payload      = $parts[1]                        # base64url-encoded payload
$mid          = [int]($payload.Length / 2)       # pick the middle character
$chars        = $payload.ToCharArray()
# Flip to a different valid base64url character so the string stays well-formed
if ($chars[$mid] -eq [char]'A') { $chars[$mid] = [char]'B' } else { $chars[$mid] = [char]'A' }
$tamperedPayload = -join $chars
$tamperedToken   = $parts[0] + "." + $tamperedPayload + "." + $parts[2]

Write-Host "Original  payload[mid]: $($payload[$mid])"
Write-Host "Tampered  payload[mid]: $($tamperedPayload[$mid])"

try {
    $r5 = Invoke-WebRequest -Uri http://localhost:8080/api/users/me `
        -Method GET `
        -Headers @{ Authorization = "Bearer $tamperedToken" } `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "UNEXPECTED SUCCESS - Status: $($r5.StatusCode)" -ForegroundColor Red
    exit 1
} catch {
    $code5 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code5 (expected 401)"
    if ($code5 -eq 401) {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - got $code5" -ForegroundColor Red
        exit 1
    }
}

Write-Host ""
Write-Host "=== ALL 5 TESTS PASSED ===" -ForegroundColor Green
