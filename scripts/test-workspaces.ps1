# ============================================================
# T1.3 – Workspace & Invite Endpoint Tests
# Run from repo root: powershell -ExecutionPolicy Bypass -File .\scripts\test-workspaces.ps1
# ============================================================

$baseUrl = "http://localhost:8080"

# ---------- Setup: Authenticate User 1 (Charan) and User 2 (Bob) ----------
Write-Host "=== SETUP: Authenticating Charan and Bob ===" -ForegroundColor Cyan

# 1. Login Charan
$loginCharan = Invoke-WebRequest -Uri "$baseUrl/api/auth/login" `
    -Method POST `
    -ContentType "application/json" `
    -Body '{"email":"charan@example.com","password":"password123"}' `
    -UseBasicParsing `
    -ErrorAction Stop
$charanToken = ($loginCharan.Content | ConvertFrom-Json).token
Write-Host "Charan logged in."

# 2. Signup / Login Bob
try {
    Invoke-WebRequest -Uri "$baseUrl/api/auth/signup" `
        -Method POST `
        -ContentType "application/json" `
        -Body '{"email":"bob@example.com","password":"password123","displayName":"Bob"}' `
        -UseBasicParsing `
        -ErrorAction Stop | Out-Null
    Write-Host "Bob signed up."
} catch {
    # 409 Conflict is expected if Bob already exists in database
}

$loginBob = Invoke-WebRequest -Uri "$baseUrl/api/auth/login" `
    -Method POST `
    -ContentType "application/json" `
    -Body '{"email":"bob@example.com","password":"password123"}' `
    -UseBasicParsing `
    -ErrorAction Stop
$bobToken = ($loginBob.Content | ConvertFrom-Json).token
Write-Host "Bob logged in."

# ---------- Test 1: Create Workspace (Charan) ----------
Write-Host ""
Write-Host "=== TEST 1: Create workspace (expect 201 and role OWNER) ===" -ForegroundColor Cyan
$wsName = "Engineering Team $(Get-Random)"
try {
    $r1 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $charanToken" } `
        -ContentType "application/json" `
        -Body ("{`"name`":`"$wsName`"}") `
        -UseBasicParsing `
        -ErrorAction Stop
    $ws1 = $r1.Content | ConvertFrom-Json
    $workspaceId = $ws1.id
    Write-Host "Status: $($r1.StatusCode)"
    Write-Host "Workspace ID: $workspaceId, Role: $($ws1.role)"

    if ($r1.StatusCode -eq 201 -and $ws1.role -eq "OWNER") {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - unexpected response" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# ---------- Test 2: Non-member gets 404 on members list ----------
Write-Host ""
Write-Host "=== TEST 2: Non-member gets 404 on members list ===" -ForegroundColor Cyan
try {
    $r2 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces/$workspaceId/members" `
        -Method GET `
        -Headers @{ Authorization = "Bearer $bobToken" } `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "UNEXPECTED SUCCESS - Status: $($r2.StatusCode)" -ForegroundColor Red
    exit 1
} catch {
    $code2 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code2 (expected 404)"
    if ($code2 -eq 404) {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - got $code2" -ForegroundColor Red
        exit 1
    }
}

# ---------- Test 3: OWNER invites a MEMBER ----------
Write-Host ""
Write-Host "=== TEST 3: OWNER invites a MEMBER (expect 201 and token) ===" -ForegroundColor Cyan
try {
    $r3 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces/$workspaceId/invites" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $charanToken" } `
        -ContentType "application/json" `
        -Body '{"email":"bob@example.com","role":"MEMBER"}' `
        -UseBasicParsing `
        -ErrorAction Stop
    $inv3 = $r3.Content | ConvertFrom-Json
    $inviteToken = $inv3.token
    Write-Host "Status: $($r3.StatusCode)"
    Write-Host "Invite Token: $inviteToken"

    if ($r3.StatusCode -eq 201 -and $inviteToken) {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - invite token missing" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# ---------- Test 4: Accept with WRONG email returns 403 ----------
Write-Host ""
Write-Host "=== TEST 4: Accept with WRONG email (Charan accepts Bob's invite - expect 403) ===" -ForegroundColor Cyan
try {
    $r4 = Invoke-WebRequest -Uri "$baseUrl/api/invites/$inviteToken/accept" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $charanToken" } `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "UNEXPECTED SUCCESS - Status: $($r4.StatusCode)" -ForegroundColor Red
    exit 1
} catch {
    $code4 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code4 (expected 403)"
    if ($code4 -eq 403) {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - got $code4" -ForegroundColor Red
        exit 1
    }
}

# ---------- Test 5: Accept with RIGHT email succeeds ----------
Write-Host ""
Write-Host "=== TEST 5: Accept with RIGHT email (Bob accepts Bob's invite - expect 200) ===" -ForegroundColor Cyan
try {
    $r5 = Invoke-WebRequest -Uri "$baseUrl/api/invites/$inviteToken/accept" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $bobToken" } `
        -UseBasicParsing `
        -ErrorAction Stop
    $acceptJson = $r5.Content | ConvertFrom-Json
    Write-Host "Status: $($r5.StatusCode)"
    Write-Host "Body: $($r5.Content)"

    if ($r5.StatusCode -eq 200 -and $acceptJson.role -eq "MEMBER") {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - unexpected response" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# ---------- Test 6: MEMBER gets 403 on invite ----------
Write-Host ""
Write-Host "=== TEST 6: MEMBER gets 403 on invite (Bob tries to invite - expect 403) ===" -ForegroundColor Cyan
try {
    $r6 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces/$workspaceId/invites" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $bobToken" } `
        -ContentType "application/json" `
        -Body '{"email":"alice@example.com","role":"MEMBER"}' `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "UNEXPECTED SUCCESS - Status: $($r6.StatusCode)" -ForegroundColor Red
    exit 1
} catch {
    $code6 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code6 (expected 403)"
    if ($code6 -eq 403) {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - got $code6" -ForegroundColor Red
        exit 1
    }
}

# ---------- Test 7: Members list shows both with correct roles ----------
Write-Host ""
Write-Host "=== TEST 7: Members list shows both with correct roles ===" -ForegroundColor Cyan
try {
    $r7 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces/$workspaceId/members" `
        -Method GET `
        -Headers @{ Authorization = "Bearer $charanToken" } `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "Status: $($r7.StatusCode)"
    Write-Host "Body: $($r7.Content)"

    $members = $r7.Content | ConvertFrom-Json
    $charanMember = $members | Where-Object { $_.email -eq "charan@example.com" }
    $bobMember    = $members | Where-Object { $_.email -eq "bob@example.com" }

    if ($members.Count -ge 2 -and $charanMember.role -eq "OWNER" -and $bobMember.role -eq "MEMBER") {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - member list or roles do not match" -ForegroundColor Red
        exit 1
    }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "=== ALL 7 WORKSPACE TESTS PASSED ===" -ForegroundColor Green
