# deploy.ps1 — Install Hyperborea as a privileged system app on a rooted NordicTrack console.
#
# Place Hyperborea*.apk (and any additional APKs) in apps\, then run:
#   powershell -ExecutionPolicy Bypass -File deploy.ps1
#

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$AppsDir = Join-Path $ScriptDir "apps"

$TotalSteps = 7

function Write-Ok($msg)      { Write-Host "  " -NoNewline; Write-Host "✓" -ForegroundColor Green -NoNewline; Write-Host " $msg" }
function Write-Fail($msg)    { Write-Host "  " -NoNewline; Write-Host "✗" -ForegroundColor Red -NoNewline; Write-Host " $msg" }
function Write-Info($msg)    { Write-Host "  " -NoNewline; Write-Host "→" -ForegroundColor Cyan -NoNewline; Write-Host " $msg" }
function Write-Warn($msg)    { Write-Host "  " -NoNewline; Write-Host "!" -ForegroundColor Yellow -NoNewline; Write-Host " $msg" }
function Write-Step($n, $msg) { Write-Host "`n[$n/$TotalSteps] $msg" -ForegroundColor Cyan }
function Stop-WithError($msg) { Write-Fail $msg; exit 1 }

function Format-Elapsed([int]$seconds) {
    return "{0}:{1:d2}" -f [int][math]::Floor($seconds / 60), [int]($seconds % 60)
}

function Wait-Key {
    while ($true) {
        $key = [Console]::ReadKey($true)
        if ($key.Key -eq 'Escape') { exit 0 }
        if ($key.Key -eq 'Enter') { return }
    }
}

function Read-Input {
    $buf = ""
    while ($true) {
        $key = [Console]::ReadKey($true)
        if ($key.Key -eq 'Escape') { exit 0 }
        if ($key.Key -eq 'Enter') { Write-Host ""; return $buf }
        if ($key.Key -eq 'Backspace') {
            if ($buf.Length -gt 0) {
                $buf = $buf.Substring(0, $buf.Length - 1)
                Write-Host -NoNewline "`b `b"
            }
            continue
        }
        if ($key.KeyChar -ge ' ') {
            $buf += $key.KeyChar
            Write-Host -NoNewline $key.KeyChar
        }
    }
}

$script:AllowRefresh = $false

function Select-Choice {
    param([string[]]$Options)
    $count = $Options.Count
    $cursor = 0

    $hint = "Up/Down navigate  -  Enter select  -  Esc cancel"
    if ($script:AllowRefresh) { $hint = "r refresh  -  $hint" }
    Write-Host "    $hint" -ForegroundColor DarkGray
    Write-Host ""

    $menuTop = [Console]::CursorTop

    function Draw-Choices {
        [Console]::SetCursorPosition(0, $menuTop)
        for ($i = 0; $i -lt $count; $i++) {
            Write-Host "    " -NoNewline
            if ($i -eq $cursor) {
                Write-Host "› " -ForegroundColor Cyan -NoNewline
            } else {
                Write-Host "  " -NoNewline
            }
            Write-Host $Options[$i]
        }
    }

    Draw-Choices

    while ($true) {
        $key = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
        if ($key.VirtualKeyCode -eq 38) { if ($cursor -gt 0) { $cursor-- } }
        elseif ($key.VirtualKeyCode -eq 40) { if ($cursor -lt ($count - 1)) { $cursor++ } }
        elseif ($key.VirtualKeyCode -eq 82) { if ($script:AllowRefresh) { return -2 } }
        elseif ($key.VirtualKeyCode -eq 27) { Write-Host ""; exit 0 }
        elseif ($key.VirtualKeyCode -eq 13) { break }
        Draw-Choices
    }

    return $cursor
}

# =========================================================================
# Wizard-style multi-step configuration
# =========================================================================
function Select-WizardConfig {
    $numSections = $script:WizSections.Count
    $currentStep = 0
    $width = [Console]::WindowWidth

    function Clear-Line {
        $row = [Console]::CursorTop
        [Console]::SetCursorPosition(0, $row)
        [Console]::Write(" " * [Math]::Max(0, $width - 1))
        [Console]::SetCursorPosition(0, $row)
    }

    while ($currentStep -lt $numSections) {
        $secStart = $script:WizSecStart[$currentStep]
        $secCount = $script:WizSecCount[$currentStep]
        $isDone = $script:WizSections[$currentStep] -eq "Done"
        $cursor = 0

        # Build summary for Done step
        $summaryItems = @()
        if ($isDone) {
            for ($s = 0; $s -lt $numSections - 1; $s++) {
                $summaryItems += [PSCustomObject]@{ Type = 'header'; Text = $script:WizSections[$s]; State = 0 }
                for ($j = 0; $j -lt $script:WizSecCount[$s]; $j++) {
                    $idx = $script:WizSecStart[$s] + $j
                    $summaryItems += [PSCustomObject]@{ Type = 'item'; Text = $script:WizLabels[$idx]; State = $script:WizStates[$idx] }
                }
            }
        }

        $contentLines = if ($isDone) { $summaryItems.Count + 2 } else { $secCount + 2 }
        $totalLines = $contentLines + 4
        $wizTop = [Console]::CursorTop

        function Draw-TabBar {
            [Console]::SetCursorPosition(0, $wizTop)
            Clear-Line
            Write-Host "  ←" -NoNewline
            for ($s = 0; $s -lt $numSections; $s++) {
                Write-Host "  " -NoNewline
                if ($s -lt $currentStep) {
                    Write-Host "✓" -ForegroundColor Green -NoNewline
                    Write-Host " $($script:WizSections[$s])" -NoNewline
                } elseif ($s -eq $currentStep) {
                    Write-Host "●" -ForegroundColor Cyan -NoNewline
                    Write-Host " $($script:WizSections[$s])" -NoNewline
                } else {
                    Write-Host "○ $($script:WizSections[$s])" -ForegroundColor DarkGray -NoNewline
                }
            }
            Write-Host "  →"
        }

        function Draw-Content {
            [Console]::SetCursorPosition(0, $wizTop + 4)
            if ($isDone) {
                foreach ($item in $summaryItems) {
                    Clear-Line
                    if ($item.Type -eq 'header') {
                        Write-Host "    $($item.Text)"
                    } elseif ($item.State -eq 1) {
                        Write-Host "      " -NoNewline
                        Write-Host "✓" -ForegroundColor Green -NoNewline
                        Write-Host " $($item.Text)"
                    } else {
                        Write-Host "      ✗ $($item.Text)" -ForegroundColor DarkGray
                    }
                }
            } else {
                for ($i = 0; $i -lt $secCount; $i++) {
                    Clear-Line
                    $idx = $secStart + $i
                    Write-Host "    " -NoNewline
                    if ($i -eq $cursor) {
                        Write-Host "› " -ForegroundColor Cyan -NoNewline
                    } else {
                        Write-Host "  " -NoNewline
                    }
                    if ($script:WizStates[$idx] -eq 1) {
                        Write-Host "✓" -ForegroundColor Green -NoNewline
                        Write-Host " $($script:WizLabels[$idx])"
                    } else {
                        Write-Host "✗ $($script:WizLabels[$idx])" -ForegroundColor DarkGray
                    }
                }
            }
            # Blank line
            Clear-Line
            Write-Host ""
            # Action button
            Clear-Line
            $btnLabel = if ($isDone) { "Confirm" } else { "Continue →" }
            Write-Host "    " -NoNewline
            if ($cursor -eq $secCount) {
                Write-Host "› " -ForegroundColor Cyan -NoNewline
            } else {
                Write-Host "  " -NoNewline
            }
            Write-Host "$btnLabel"
        }

        function Draw-Wizard {
            Draw-TabBar
            [Console]::SetCursorPosition(0, $wizTop + 1)
            Clear-Line
            Write-Host ""
            Clear-Line
            if ($isDone) {
                Write-Host "    Enter confirm  -  Esc cancel" -ForegroundColor DarkGray
            } else {
                Write-Host "    Up/Down navigate  -  Space toggle  -  Enter continue  -  Esc cancel" -ForegroundColor DarkGray
            }
            Clear-Line
            Write-Host ""
            Draw-Content
        }

        Draw-Wizard

        while ($true) {
            $key = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
            if ($key.VirtualKeyCode -eq 27) { exit 0 }            # Escape
            if ($key.VirtualKeyCode -eq 13) { break }             # Enter
            if ($key.VirtualKeyCode -eq 38) {                      # Up
                if ($cursor -gt 0) { $cursor-- }
            }
            elseif ($key.VirtualKeyCode -eq 40) {                  # Down
                if ($cursor -lt $secCount) { $cursor++ }
            }
            elseif ($key.VirtualKeyCode -eq 32) {                  # Space
                if ($cursor -eq $secCount) {
                    break                                           # Continue/Confirm
                } elseif ($secCount -gt 0) {
                    $idx = $secStart + $cursor
                    $script:WizStates[$idx] = if ($script:WizStates[$idx] -eq 1) { 0 } else { 1 }
                }
            }
            if ($contentLines -gt 0) {
                Draw-Content
            }
        }

        # Clear wizard area for next step
        [Console]::SetCursorPosition(0, $wizTop)
        for ($l = 0; $l -lt $totalLines; $l++) {
            Clear-Line
            Write-Host ""
        }
        [Console]::SetCursorPosition(0, $wizTop)

        $currentStep++
    }
}

# =========================================================================
# Timer (background runspace with elapsed time)
# =========================================================================
$script:TimerRunspace = $null
$script:TimerPowerShell = $null

function Start-Timer($label) {
    $startTicks = (Get-Date).Ticks
    $script:TimerRunspace = [runspacefactory]::CreateRunspace()
    $script:TimerRunspace.Open()
    $script:TimerPowerShell = [powershell]::Create()
    $script:TimerPowerShell.Runspace = $script:TimerRunspace
    [void]$script:TimerPowerShell.AddScript({
        param($label, $startTicks)
        $start = [datetime]::new($startTicks)
        while ($true) {
            $elapsed = [int]((Get-Date) - $start).TotalSeconds
            $time = "{0}:{1:d2}" -f [math]::Floor($elapsed / 60), ($elapsed % 60)
            [Console]::Write("`r  > {0} {1}  " -f $label, $time)
            Start-Sleep -Seconds 1
        }
    })
    [void]$script:TimerPowerShell.AddArgument($label)
    [void]$script:TimerPowerShell.AddArgument($startTicks)
    $script:TimerPowerShell.BeginInvoke() | Out-Null
}

function Stop-Timer {
    if ($script:TimerPowerShell) {
        $script:TimerPowerShell.Stop()
        $script:TimerPowerShell.Dispose()
    }
    if ($script:TimerRunspace) {
        $script:TimerRunspace.Close()
        $script:TimerRunspace.Dispose()
    }
    $script:TimerPowerShell = $null
    $script:TimerRunspace = $null
    Write-Host "`r$(' ' * 60)" -NoNewline
    Write-Host "`r" -NoNewline
}

# =========================================================================
# Helpers
# =========================================================================
function Test-IpConnection {
    return $env:ANDROID_SERIAL -and $env:ANDROID_SERIAL -match ':'
}

function Invoke-AdbRootWait {
    & adb root 2>$null | Out-Null
    if (Test-IpConnection) {
        Start-Sleep -Seconds 2
        & adb connect $env:ANDROID_SERIAL 2>$null | Out-Null
        Start-Sleep -Seconds 1
    }
    & adb wait-for-device 2>$null | Out-Null
}

# =========================================================================
# Device Discovery
# =========================================================================
function Find-Device {
    while ($true) {
        Write-Step 1 "Connect to device"

        $raw = & adb devices 2>$null
        $devices = @($raw | Where-Object { $_ -match '\s+device$' })
        $serials = @()
        foreach ($d in $devices) { $serials += ($d -split '\s+')[0] }

        $options = @() + $serials
        $options += "Enter IP address..."

        Write-Host ""
        $script:AllowRefresh = $true
        $idx = Select-Choice $options
        $script:AllowRefresh = $false

        if ($idx -eq -2) { continue }

        $deviceCount = $serials.Count

        if ($idx -lt $deviceCount) {
            $env:ANDROID_SERIAL = $serials[$idx]
            Write-Host ""
            Write-Info "Connecting..."
            Invoke-AdbRootWait
            Write-Ok "Connected to $($serials[$idx])"
            return
        }

        # Enter IP address
        Write-Host ""
        Write-Host -NoNewline "  Enter device IP (e.g. 192.168.1.100): "
        $ip = Read-Input
        if (-not $ip) { continue }
        if ($ip -notmatch ':') { $ip = "${ip}:5555" }

        Write-Info "Connecting to $ip..."
        & adb connect $ip 2>$null | Out-Null
        Start-Sleep -Seconds 2

        & adb -s $ip shell true 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $env:ANDROID_SERIAL = $ip
            Invoke-AdbRootWait
            Write-Ok "Connected to $ip"
            return
        }
        Write-Warn "Couldn't connect. Check the IP and that ADB is enabled."
        Write-Host ""
        Write-Host "  Press Enter to try again, or Esc to exit." -ForegroundColor DarkGray
        Wait-Key
    }
}

# =========================================================================
# Wait for device to come back after reboot
# =========================================================================
function Wait-ForReboot {
    param([int]$MaxWait = 300)

    $waitStart = Get-Date

    # Wait for device to actually go offline
    while ($true) {
        $null = & adb shell true 2>$null
        if ($LASTEXITCODE -ne 0) { break }
        Start-Sleep -Seconds 1
    }

    # Poll for boot completion (reconnect if IP)
    while ($true) {
        $elapsed = [int]((Get-Date) - $waitStart).TotalSeconds
        if ($elapsed -gt $MaxWait) {
            Stop-Timer
            Stop-WithError "Timed out after ${MaxWait}s. Try reconnecting manually."
        }

        if (Test-IpConnection) {
            & adb connect $env:ANDROID_SERIAL 2>$null | Out-Null
            Start-Sleep -Seconds 2
        }

        $bootComplete = & adb shell "getprop sys.boot_completed" 2>$null
        if ($bootComplete -match "1") {
            Invoke-AdbRootWait
            break
        }

        Start-Sleep -Seconds 3
    }
}

# =========================================================================
# Configuration
# =========================================================================
$IfitPackages = @(
    "com.ifit.eru"
    "com.ifit.standalone"
    "com.ifit.launcher"
    "com.ifit.arda"
    "com.ifit.glassos_service"
    "com.ifit.gandalf"
    "com.ifit.rivendell"
    "com.ifit.mithlond"
)

function Apply-Config {
    $applied = 0

    if ($script:CfgImmersive -eq 1) {
        & adb shell "settings put global policy_control null" 2>$null | Out-Null
        Write-Ok "Immersive mode disabled"
        $applied++
    }

    if ($applied -eq 0) { Write-Info "No configuration changes to apply" }
}

# =========================================================================
# Step 1: Pre-flight
# =========================================================================
if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Stop-WithError "ADB not found. Install Android platform-tools and add to PATH."
}
if (-not (Test-Path $AppsDir)) {
    Stop-WithError "apps\ folder not found. Place APKs in apps\ next to this script."
}

# Find Hyperborea APK
$hyperboreaApk = Get-ChildItem -Path $AppsDir -Filter "Hyperborea*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $hyperboreaApk) {
    Stop-WithError "No Hyperborea*.apk found in apps\. Place the APK there and try again."
}

# BLE overlay is pushed to /vendor/overlay/ separately
$overlayApk = Join-Path $AppsDir "BluetoothPeripheralOverlay.apk"

# Collect other APKs (excluding Hyperborea and overlay)
$otherApks = @(Get-ChildItem -Path $AppsDir -Filter "*.apk" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -ne $hyperboreaApk.FullName -and $_.FullName -ne $overlayApk })

Write-Ok "Found $($hyperboreaApk.Name)"

# Build wizard sections
$script:WizSections = @("Device settings")
$script:WizLabels = @("Disable immersive mode")
$script:WizStates = @(1)
$script:WizSecStart = @(0)
$script:WizSecCount = @(1)

if (Test-Path $overlayApk) {
    $script:WizLabels += "Enable Bluetooth advertising"
    $script:WizStates += 1
    $script:WizSecCount[0]++
}

if ($otherApks.Count -gt 0) {
    $script:WizSections += "Additional apps"
    $script:WizSecStart += $script:WizLabels.Count
    $appCount = 0
    foreach ($apk in $otherApks) {
        $script:WizLabels += [System.IO.Path]::GetFileNameWithoutExtension($apk.Name)
        $script:WizStates += 1
        $appCount++
    }
    $script:WizSecCount += $appCount
}

$script:WizSections += "Done"
$script:WizSecStart += $script:WizLabels.Count
$script:WizSecCount += 0

# =========================================================================
# Step 1: Connect to device
# =========================================================================
Find-Device

$whoami = (& adb shell "whoami" 2>$null) -replace "`r",""
if ($whoami -ne "root") { Stop-WithError "Failed to get root (got: $whoami)" }
Write-Ok "Root access confirmed"

# =========================================================================
# Step 2: Configure
# =========================================================================
Write-Step 2 "Configure"

Write-Host ""
Select-WizardConfig
[Console]::Write("$([char]27)[J")
Write-Ok "Configuration saved"

# Extract results
$script:CfgImmersive = $script:WizStates[0]
$next = 1
$script:CfgOverlay = 0
if (Test-Path $overlayApk) {
    $script:CfgOverlay = $script:WizStates[$next]
    $next++
}
$appsStateStart = $next
$appInstallStates = @()
for ($i = 0; $i -lt $otherApks.Count; $i++) {
    $appInstallStates += $script:WizStates[$appsStateStart + $i]
}

# =========================================================================
# Step 3: Install Hyperborea as priv-app
# =========================================================================
Write-Step 3 "Install Hyperborea"

Write-Info "Preparing device..."
& adb shell "mount -o rw,remount /system" 2>$null | Out-Null
Write-Ok "Device ready"

Write-Info "Installing..."
& adb shell "mkdir -p /system/priv-app/Hyperborea" 2>$null | Out-Null
& adb push $hyperboreaApk.FullName /system/priv-app/Hyperborea/Hyperborea.apk 2>$null | Out-Null
& adb shell "chmod 755 /system/priv-app/Hyperborea && chmod 644 /system/priv-app/Hyperborea/Hyperborea.apk" 2>$null | Out-Null
Write-Ok "Installed"

if ((Test-Path $overlayApk) -and $script:CfgOverlay -eq 1) {
    Write-Info "Applying Bluetooth configuration..."
    & adb shell "mkdir -p /vendor/overlay" 2>$null | Out-Null
    & adb push $overlayApk /vendor/overlay/BluetoothPeripheralOverlay.apk 2>$null | Out-Null
    & adb shell "chmod 644 /vendor/overlay/BluetoothPeripheralOverlay.apk" 2>$null | Out-Null
    Write-Ok "Bluetooth advertising enabled"
}

Write-Info "Finalizing..."
& adb shell "mount -o ro,remount /system" 2>$null | Out-Null
$null = & adb shell "[ -f /data/update.zip ] || touch /data/update.zip; toybox chattr +i /data/update.zip" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Warn "Some protections could not be applied"
}
& adb shell "touch /sdcard/.wolfDev" 2>$null | Out-Null
Write-Ok "Done"

# =========================================================================
# Step 4: Reboot and wait
# =========================================================================
Write-Step 4 "Reboot device"

Write-Info "Rebooting..."
$rebootStart = Get-Date
Start-Timer "Waiting for device..."
& adb reboot 2>$null | Out-Null
Wait-ForReboot -MaxWait 300
Stop-Timer
Write-Ok "Device ready ($(Format-Elapsed ([int]((Get-Date) - $rebootStart).TotalSeconds)))"

# =========================================================================
# Step 5: Disable iFit
# =========================================================================
Write-Step 5 "Disable iFit"

$disabled = 0
$installedPkgs = & adb shell "pm list packages" 2>$null
foreach ($pkg in $IfitPackages) {
    if ($installedPkgs -match [regex]::Escape($pkg)) {
        $null = & adb shell "pm disable-user --user 0 $pkg" 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Ok "Disabled $pkg"
            $disabled++
        } else {
            Write-Warn "Could not disable $pkg"
        }
    }
}

if ($disabled -eq 0) {
    Write-Info "No iFit packages found to disable"
} else {
    Write-Ok "$disabled iFit package(s) disabled"
}

# =========================================================================
# Step 6: Install additional apps
# =========================================================================
Write-Step 6 "Install additional apps"

$installed = 0; $failed = 0; $skipped = 0
for ($i = 0; $i -lt $otherApks.Count; $i++) {
    if ($appInstallStates[$i] -ne 1) {
        $skipped++
        continue
    }
    $apk = $otherApks[$i]
    $name = [System.IO.Path]::GetFileNameWithoutExtension($apk.Name)
    Write-Info "Installing $name..."
    $result = & adb install -r $apk.FullName 2>&1
    if ($result -match "Success") {
        Write-Ok $name
        $installed++
    } else {
        Write-Fail $name
        $failed++
    }
}

if ($installed -eq 0 -and $failed -eq 0) {
    Write-Info "No additional apps to install"
} elseif ($failed -eq 0) {
    Write-Host ""
    Write-Ok "All $installed app(s) installed"
} else {
    Write-Host ""
    Write-Warn "$installed installed, $failed failed"
}

# =========================================================================
# Step 7: Configure and verify
# =========================================================================
Write-Step 7 "Configure and verify"

Apply-Config
Write-Host ""

$verify = (& adb shell @"
    echo PATH=`$(pm path com.nettarion.hyperborea 2>/dev/null)
    echo FLAGS=`$(dumpsys package com.nettarion.hyperborea 2>/dev/null | grep 'pkgFlags=' | head -1)
    echo PRIVFLAGS=`$(dumpsys package com.nettarion.hyperborea 2>/dev/null | grep 'privateFlags=' | head -1)
"@ 2>$null) -replace "`r",""

function Get-Val($key) {
    $line = ($verify -split "`n") | Where-Object { $_ -match "^$key=" } | Select-Object -First 1
    if ($line) { return ($line -replace "^$key=","") }
    return ""
}

$pass = 0; $total = 0

# Check priv-app path
$pkgPath = Get-Val "PATH"
$total++
if ($pkgPath -match "/system/priv-app/") {
    Write-Ok "Install location OK"
    $pass++
} else {
    Write-Fail "Install location incorrect"
}

# Check PRIVILEGED flag
$pkgPrivFlags = Get-Val "PRIVFLAGS"
$total++
if ($pkgPrivFlags -match "PRIVILEGED") {
    Write-Ok "Permissions OK"
    $pass++
} else {
    Write-Fail "Permissions not granted"
}

Write-Host ""
if ($pass -eq $total) {
    Write-Host "  $pass/$total checks passed" -ForegroundColor Green
} else {
    Write-Host "  $pass/$total checks passed" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "  Deployment complete!" -ForegroundColor Green
Write-Host ""
