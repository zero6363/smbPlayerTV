@echo off
REM ============================================================
REM  Remove SMB test share + test user (run as Administrator)
REM  Right-click this file -> "Run as administrator"
REM ============================================================
echo Removing share TEST ...
net share TEST /delete >nul 2>&1
if %errorlevel%==0 (
    echo [OK] share TEST removed
) else (
    echo [WARN] share TEST not removed - code %errorlevel%
)

echo Removing user SmbTest ...
net user SmbTest /delete >nul 2>&1
if %errorlevel%==0 (
    echo [OK] user SmbTest deleted
) else (
    echo [WARN] user SmbTest not removed - code %errorlevel%
)

echo.
echo === verify ===
net share TEST >nul 2>&1
if %errorlevel%==0 (echo share TEST : STILL EXISTS) else (echo share TEST : removed)
net user SmbTest >nul 2>&1
if %errorlevel%==0 (echo user SmbTest: STILL EXISTS) else (echo user SmbTest: removed)

echo.
echo DONE.
pause
