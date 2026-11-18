# ============================================================
# T1.3 – Comprehensive Workspace & Invite Endpoint Tests
# Run from repo root: powershell -ExecutionPolicy Bypass -File .\scripts\test-workspaces.ps1
# ============================================================

$baseUrl = "http://localhost:8080"

# ---------- Setup: Authenticate Charan, Bob, and Dave ----------
Write-Host "=== SETUP: Authenticating Charan, Bob, and Dave ===" -ForegroundColor Cyan

# 1. Login / Ensure User 1: Charan
$loginCharan = Invoke-WebRequest -Uri "$baseUrl/api/auth/login" `
    -Method POST `
    -ContentType "application/json" `
    -Body '{"email":"charan@example.com","password":"password123"}' `
    -UseBasicParsing `
    -ErrorAction Stop
$charanToken = ($loginCharan.Content | ConvertFrom-Json).token
Write-Host "Charan authenticated."

# 2. Signup / Login User 2: Bob
try {
    Invoke-WebRequest -Uri "$baseUrl/api/auth/signup" `
        -Method POST `
        -ContentType "application/json" `
        -Body '{"email":"bob@example.com","password":"password123","displayName":"Bob"}' `
        -UseBasicParsing `
        -ErrorAction Stop | Out-Null
} catch {}
$loginBob = Invoke-WebRequest -Uri "$baseUrl/api/auth/login" `
    -Method POST `
    -ContentType "application/json" `
    -Body '{"email":"bob@example.com","password":"password123"}' `
    -UseBasicParsing `
    -ErrorAction Stop
$bobToken = ($loginBob.Content | ConvertFrom-Json).token
Write-Host "Bob authenticated."

# 3. Signup / Login User 3: Dave (to be promoted to ADMIN)
try {
    Invoke-WebRequest -Uri "$baseUrl/api/auth/signup" `
        -Method POST `
        -ContentType "application/json" `
        -Body '{"email":"dave@example.com","password":"password123","displayName":"Dave"}' `
        -UseBasicParsing `
        -ErrorAction Stop | Out-Null
} catch {}
$loginDave = Invoke-WebRequest -Uri "$baseUrl/api/auth/login" `
    -Method POST `
    -ContentType "application/json" `
    -Body '{"email":"dave@example.com","password":"password123"}' `
    -UseBasicParsing `
    -ErrorAction Stop
$daveToken = ($loginDave.Content | ConvertFrom-Json).token
Write-Host "Dave authenticated."

# ---------- Test 1: Create Workspace (Charan) ----------
Write-Host ""
Write-Host "=== TEST 1: Create workspace (expect 201 and role OWNER) ===" -ForegroundColor Cyan
$wsName = "Workspace $(Get-Random)"
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
    Write-Host "Status: $($r1.StatusCode), Workspace ID: $workspaceId, Role: $($ws1.role)"
    if ($r1.StatusCode -eq 201 -and $ws1.role -eq "OWNER") {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL" -ForegroundColor Red; exit 1
    }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red; exit 1
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
    Write-Host "UNEXPECTED SUCCESS - Status: $($r2.StatusCode)" -ForegroundColor Red; exit 1
} catch {
    $code2 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code2 (expected 404)"
    if ($code2 -eq 404) { Write-Host "PASS" -ForegroundColor Green } else { Write-Host "FAIL - got $code2" -ForegroundColor Red; exit 1 }
}

# ---------- Test 3: Invite with role OWNER is rejected (4xx) ----------
Write-Host ""
Write-Host "=== TEST 3: Invite with role OWNER is rejected (expect 403) ===" -ForegroundColor Cyan
try {
    $r3 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces/$workspaceId/invites" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $charanToken" } `
        -ContentType "application/json" `
        -Body '{"email":"someone@example.com","role":"OWNER"}' `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "UNEXPECTED SUCCESS - Status: $($r3.StatusCode)" -ForegroundColor Red; exit 1
} catch {
    $code3 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code3 (expected 403)"
    if ($code3 -ge 400 -and $code3 -lt 500) { Write-Host "PASS" -ForegroundColor Green } else { Write-Host "FAIL - got $code3" -ForegroundColor Red; exit 1 }
}

# ---------- Test 4: OWNER invites an ADMIN (Dave) ----------
Write-Host ""
Write-Host "=== TEST 4: OWNER invites an ADMIN (Dave - expect 201) ===" -ForegroundColor Cyan
try {
    $r4 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces/$workspaceId/invites" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $charanToken" } `
        -ContentType "application/json" `
        -Body '{"email":"dave@example.com","role":"ADMIN"}' `
        -UseBasicParsing `
        -ErrorAction Stop
    $inv4 = $r4.Content | ConvertFrom-Json
    $daveInviteToken = $inv4.token
    Write-Host "Status: $($r4.StatusCode), Dave Invite Token: $daveInviteToken"
    if ($r4.StatusCode -eq 201 -and $daveInviteToken) { Write-Host "PASS" -ForegroundColor Green } else { Write-Host "FAIL" -ForegroundColor Red; exit 1 }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red; exit 1
}

# ---------- Test 5: Accept with WRONG email returns 403 ----------
Write-Host ""
Write-Host "=== TEST 5: Accept with WRONG email (Bob accepts Dave's invite - expect 403) ===" -ForegroundColor Cyan
try {
    $r5 = Invoke-WebRequest -Uri "$baseUrl/api/invites/$daveInviteToken/accept" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $bobToken" } `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "UNEXPECTED SUCCESS - Status: $($r5.StatusCode)" -ForegroundColor Red; exit 1
} catch {
    $code5 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code5 (expected 403)"
    if ($code5 -eq 403) { Write-Host "PASS" -ForegroundColor Green } else { Write-Host "FAIL - got $code5" -ForegroundColor Red; exit 1 }
}

# ---------- Test 6: Dave accepts invite with RIGHT email (joins as ADMIN) ----------
Write-Host ""
Write-Host "=== TEST 6: Dave accepts invite with RIGHT email (expect 200, role ADMIN) ===" -ForegroundColor Cyan
try {
    $r6 = Invoke-WebRequest -Uri "$baseUrl/api/invites/$daveInviteToken/accept" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $daveToken" } `
        -UseBasicParsing `
        -ErrorAction Stop
    $accept6 = $r6.Content | ConvertFrom-Json
    Write-Host "Status: $($r6.StatusCode), Role: $($accept6.role)"
    if ($r6.StatusCode -eq 200 -and $accept6.role -eq "ADMIN") { Write-Host "PASS" -ForegroundColor Green } else { Write-Host "FAIL" -ForegroundColor Red; exit 1 }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red; exit 1
}

# ---------- Test 7: Accepting the same invite twice fails ----------
Write-Host ""
Write-Host "=== TEST 7: Accepting the same invite twice fails (expect 400) ===" -ForegroundColor Cyan
try {
    $r7 = Invoke-WebRequest -Uri "$baseUrl/api/invites/$daveInviteToken/accept" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $daveToken" } `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "UNEXPECTED SUCCESS - Status: $($r7.StatusCode)" -ForegroundColor Red; exit 1
} catch {
    $code7 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code7 (expected 400)"
    if ($code7 -ge 400 -and $code7 -lt 500) { Write-Host "PASS" -ForegroundColor Green } else { Write-Host "FAIL - got $code7" -ForegroundColor Red; exit 1 }
}

# ---------- Test 8: OWNER invites a MEMBER (Bob) ----------
Write-Host ""
Write-Host "=== TEST 8: OWNER invites a MEMBER (Bob - expect 201) ===" -ForegroundColor Cyan
try {
    $r8 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces/$workspaceId/invites" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $charanToken" } `
        -ContentType "application/json" `
        -Body '{"email":"bob@example.com","role":"MEMBER"}' `
        -UseBasicParsing `
        -ErrorAction Stop
    $inv8 = $r8.Content | ConvertFrom-Json
    $bobInviteToken = $inv8.token
    Write-Host "Status: $($r8.StatusCode), Bob Invite Token: $bobInviteToken"
    if ($r8.StatusCode -eq 201 -and $bobInviteToken) { Write-Host "PASS" -ForegroundColor Green } else { Write-Host "FAIL" -ForegroundColor Red; exit 1 }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red; exit 1
}

# ---------- Test 9: A second pending invite for the same email returns 409 ----------
Write-Host ""
Write-Host "=== TEST 9: Second pending invite for same email returns 409 ===" -ForegroundColor Cyan
try {
    $r9 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces/$workspaceId/invites" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $charanToken" } `
        -ContentType "application/json" `
        -Body '{"email":"bob@example.com","role":"MEMBER"}' `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "UNEXPECTED SUCCESS - Status: $($r9.StatusCode)" -ForegroundColor Red; exit 1
} catch {
    $code9 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code9 (expected 409)"
    if ($code9 -eq 409) { Write-Host "PASS" -ForegroundColor Green } else { Write-Host "FAIL - got $code9" -ForegroundColor Red; exit 1 }
}

# ---------- Test 10: Bob accepts invite (joins as MEMBER) ----------
Write-Host ""
Write-Host "=== TEST 10: Bob accepts invite (expect 200, role MEMBER) ===" -ForegroundColor Cyan
try {
    $r10 = Invoke-WebRequest -Uri "$baseUrl/api/invites/$bobInviteToken/accept" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $bobToken" } `
        -UseBasicParsing `
        -ErrorAction Stop
    $accept10 = $r10.Content | ConvertFrom-Json
    Write-Host "Status: $($r10.StatusCode), Role: $($accept10.role)"
    if ($r10.StatusCode -eq 200 -and $accept10.role -eq "MEMBER") { Write-Host "PASS" -ForegroundColor Green } else { Write-Host "FAIL" -ForegroundColor Red; exit 1 }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red; exit 1
}

# ---------- Test 11: Inviting someone who is already a member returns 409 ----------
Write-Host ""
Write-Host "=== TEST 11: Inviting someone who is already a member returns 409 ===" -ForegroundColor Cyan
try {
    $r11 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces/$workspaceId/invites" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $charanToken" } `
        -ContentType "application/json" `
        -Body '{"email":"bob@example.com","role":"MEMBER"}' `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "UNEXPECTED SUCCESS - Status: $($r11.StatusCode)" -ForegroundColor Red; exit 1
} catch {
    $code11 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code11 (expected 409)"
    if ($code11 -eq 409) { Write-Host "PASS" -ForegroundColor Green } else { Write-Host "FAIL - got $code11" -ForegroundColor Red; exit 1 }
}

# ---------- Test 12: MEMBER gets 403 on invite ----------
Write-Host ""
Write-Host "=== TEST 12: MEMBER gets 403 on invite (Bob tries to invite) ===" -ForegroundColor Cyan
try {
    $r12 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces/$workspaceId/invites" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $bobToken" } `
        -ContentType "application/json" `
        -Body '{"email":"frank@example.com","role":"MEMBER"}' `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "UNEXPECTED SUCCESS - Status: $($r12.StatusCode)" -ForegroundColor Red; exit 1
} catch {
    $code12 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code12 (expected 403)"
    if ($code12 -eq 403) { Write-Host "PASS" -ForegroundColor Green } else { Write-Host "FAIL - got $code12" -ForegroundColor Red; exit 1 }
}

# ---------- Test 13: ADMIN invites ADMIN returns 403 ----------
Write-Host ""
Write-Host "=== TEST 13: ADMIN invites ADMIN returns 403 (Dave tries to invite ADMIN) ===" -ForegroundColor Cyan
try {
    $r13 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces/$workspaceId/invites" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $daveToken" } `
        -ContentType "application/json" `
        -Body '{"email":"grace@example.com","role":"ADMIN"}' `
        -UseBasicParsing `
        -ErrorAction Stop
    Write-Host "UNEXPECTED SUCCESS - Status: $($r13.StatusCode)" -ForegroundColor Red; exit 1
} catch {
    $code13 = $_.Exception.Response.StatusCode.value__
    Write-Host "Status: $code13 (expected 403)"
    if ($code13 -eq 403) { Write-Host "PASS" -ForegroundColor Green } else { Write-Host "FAIL - got $code13" -ForegroundColor Red; exit 1 }
}

# ---------- Test 14: ADMIN invites MEMBER succeeds ----------
Write-Host ""
Write-Host "=== TEST 14: ADMIN invites MEMBER succeeds (Dave invites MEMBER) ===" -ForegroundColor Cyan
try {
    $r14 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces/$workspaceId/invites" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $daveToken" } `
        -ContentType "application/json" `
        -Body '{"email":"heidi@example.com","role":"MEMBER"}' `
        -UseBasicParsing `
        -ErrorAction Stop
    $inv14 = $r14.Content | ConvertFrom-Json
    Write-Host "Status: $($r14.StatusCode), Heidi Invite Token: $($inv14.token)"
    if ($r14.StatusCode -eq 201 -and $inv14.token) { Write-Host "PASS" -ForegroundColor Green } else { Write-Host "FAIL" -ForegroundColor Red; exit 1 }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red; exit 1
}

# ---------- Test 15: Members list shows all 3 with correct roles ----------
Write-Host ""
Write-Host "=== TEST 15: Members list shows correct roles (OWNER, ADMIN, MEMBER) ===" -ForegroundColor Cyan
try {
    $r15 = Invoke-WebRequest -Uri "$baseUrl/api/workspaces/$workspaceId/members" `
        -Method GET `
        -Headers @{ Authorization = "Bearer $charanToken" } `
        -UseBasicParsing `
        -ErrorAction Stop
    $members = $r15.Content | ConvertFrom-Json
    $c = $members | Where-Object { $_.email -eq "charan@example.com" }
    $d = $members | Where-Object { $_.email -eq "dave@example.com" }
    $b = $members | Where-Object { $_.email -eq "bob@example.com" }

    Write-Host "Charan: $($c.role), Dave: $($d.role), Bob: $($b.role)"
    if ($c.role -eq "OWNER" -and $d.role -eq "ADMIN" -and $b.role -eq "MEMBER") {
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - role mismatch" -ForegroundColor Red; exit 1
    }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red; exit 1
}

# ---------- Test 16: GET /api/workspaces returns only caller's workspaces ----------
Write-Host ""
Write-Host "=== TEST 16: GET /api/workspaces returns only caller's workspaces ===" -ForegroundColor Cyan
try {
    # Dave creates an independent workspace: "Dave Solo Space"
    $rSolo = Invoke-WebRequest -Uri "$baseUrl/api/workspaces" `
        -Method POST `
        -Headers @{ Authorization = "Bearer $daveToken" } `
        -ContentType "application/json" `
        -Body '{"name":"Dave Solo Space"}' `
        -UseBasicParsing `
        -ErrorAction Stop
    $daveSoloWsId = ($rSolo.Content | ConvertFrom-Json).id

    # Charan lists workspaces: MUST NOT contain Dave's solo workspace
    $rCharanWs = Invoke-WebRequest -Uri "$baseUrl/api/workspaces" `
        -Method GET `
        -Headers @{ Authorization = "Bearer $charanToken" } `
        -UseBasicParsing `
        -ErrorAction Stop
    $charanWorkspaces = $rCharanWs.Content | ConvertFrom-Json
    $charanHasSolo = $charanWorkspaces | Where-Object { $_.id -eq $daveSoloWsId }

    # Dave lists workspaces: MUST contain Dave's solo workspace
    $rDaveWs = Invoke-WebRequest -Uri "$baseUrl/api/workspaces" `
        -Method GET `
        -Headers @{ Authorization = "Bearer $daveToken" } `
        -UseBasicParsing `
        -ErrorAction Stop
    $daveWorkspaces = $rDaveWs.Content | ConvertFrom-Json
    $daveHasSolo = $daveWorkspaces | Where-Object { $_.id -eq $daveSoloWsId }

    if (-not $charanHasSolo -and $daveHasSolo) {
        Write-Host "Charan does NOT see Dave's solo workspace."
        Write-Host "Dave DOES see Dave's solo workspace."
        Write-Host "PASS" -ForegroundColor Green
    } else {
        Write-Host "FAIL - workspace isolation leak" -ForegroundColor Red; exit 1
    }
} catch {
    Write-Host "FAIL: $($_.Exception.Message)" -ForegroundColor Red; exit 1
}

Write-Host ""
Write-Host "=== ALL 16 WORKSPACE & INVITE TESTS PASSED ===" -ForegroundColor Green
