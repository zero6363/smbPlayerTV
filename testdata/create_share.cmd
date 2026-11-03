@echo off
rem === SMB TV test share setup (run as Administrator) ===

net user SmbTest SmbTest2026 /add
if %errorlevel%==0 (echo [OK] user SmbTest created) else (echo [WARN] net user exit code %errorlevel%, maybe already exists)

net localgroup Users SmbTest /add
if %errorlevel%==0 (echo [OK] joined Users group) else (echo [WARN] group exit code %errorlevel%)

net share TEST=D:\code\smbPlayerTV\testdata /grant:SmbTest,FULL
if %errorlevel%==0 (echo [OK] share TEST created) else (echo [WARN] net share exit code %errorlevel%, maybe already exists)

echo.
echo === share info ===
net share TEST

echo.
echo === DONE. Connect from emulator: 10.0.2.2  port 445  share TEST  user SmbTest  pass SmbTest2026 ===
pause
