param([int]$W = 1600, [int]$H = 940)
Add-Type @'
using System;
using System.Runtime.InteropServices;
public class Win32 {
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hWnd, IntPtr after, int X, int Y, int cx, int cy, uint flags);
}
'@
$p = Get-Process | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1
if (-not $p) { Write-Output 'NO_WINDOW'; exit 1 }
[Win32]::SetWindowPos($p.MainWindowHandle, [IntPtr]::Zero, 40, 30, $W, $H, 0) | Out-Null
Start-Sleep -Milliseconds 800
Write-Output "resized pid=$($p.Id) to ${W}x${H}"
