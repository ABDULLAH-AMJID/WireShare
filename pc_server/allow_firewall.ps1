# WireShare: Windows Defender Firewall Configuration Script
# Must be run as Administrator

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "   WireShare: Configuring Windows Defender Firewall" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

if (-NOT ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole] "Administrator")) {
    Write-Host "[ERROR] Please run PowerShell as Administrator!" -ForegroundColor Red
    Write-Host "Right-click allow_firewall.ps1 and select 'Run with PowerShell as administrator'." -ForegroundColor Yellow
    Exit
}

Write-Host "Adding TCP Port 8765 inbound rule..." -ForegroundColor Green
New-NetFirewallRule -DisplayName "WireShare KVM Server (TCP)" -Direction Inbound -LocalPort 8765 -Protocol TCP -Action Allow -Force

Write-Host "Adding UDP Ports 8766, 8767 inbound rule..." -ForegroundColor Green
New-NetFirewallRule -DisplayName "WireShare KVM Server (UDP)" -Direction Inbound -LocalPort 8766,8767 -Protocol UDP -Action Allow -Force

Write-Host "`nSUCCESS! Firewall rules added. Your Android device can now connect!" -ForegroundColor Green
