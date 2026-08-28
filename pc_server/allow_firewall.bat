@echo off
echo ==========================================================
echo   WireShare: Allowing Ports 8765, 8766, 8767 in Windows Firewall
echo ==========================================================
echo.
echo Checking administrative privileges...
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [ERROR] Please run this script as Administrator!
    echo Right-click allow_firewall.bat and select "Run as administrator".
    pause
    exit /b 1
)

echo Adding Inbound Rule for WireShare TCP (Port 8765)...
netsh advfirewall firewall add rule name="WireShare KVM Server (TCP)" dir=in action=allow protocol=TCP localport=8765

echo Adding Inbound Rule for WireShare UDP (Ports 8766-8767)...
netsh advfirewall firewall add rule name="WireShare KVM Server (UDP)" dir=in action=allow protocol=UDP localport=8766,8767

echo.
echo ==========================================================
echo   SUCCESS! Windows Firewall rules added.
echo   Your Android phone can now connect to your PC!
echo ==========================================================
pause
