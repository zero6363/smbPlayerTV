# 一键创建 TV 版测试账号 + SMB 共享（需管理员运行）
$ErrorActionPreference = 'Continue'
$log = 'D:\code\smbPlayerTV\testdata\setup_result.txt'
Set-Content -Path $log -Value "start $(Get-Date -Format s)" -Encoding UTF8

# 1. 专用测试账号（复杂度：大写+小写+数字，8位以上）
net user SmbTest SmbTest2026 /add 2>&1 | Out-File $log -Append -Encoding UTF8
net localgroup Users SmbTest /add 2>&1 | Out-File $log -Append -Encoding UTF8

# 2. 共享 testdata -> \\本机\TEST（仅授权 SmbTest 完全控制）
net share TEST=D:\code\smbPlayerTV\testdata /grant:SmbTest,FULL 2>&1 | Out-File $log -Append -Encoding UTF8

# 3. 放行 SMB 入站（模拟器走回环通常不需要，兜底）
try {
    Get-NetFirewallRule -DisplayGroup "文件和打印机共享" -ErrorAction SilentlyContinue |
        Enable-NetFirewallRule -ErrorAction SilentlyContinue
    Get-NetFirewallRule -DisplayGroup "File and Printer Sharing" -ErrorAction SilentlyContinue |
        Enable-NetFirewallRule -ErrorAction SilentlyContinue
    "firewall ok" | Out-File $log -Append -Encoding UTF8
} catch {
    "firewall skip: $_" | Out-File $log -Append -Encoding UTF8
}

Add-Content $log "done" -Encoding UTF8
