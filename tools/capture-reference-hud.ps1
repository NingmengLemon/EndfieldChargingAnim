# Captures the reference HUD (references/zmd-charge) frame by frame for A/B comparison.
#
# Desktop region capture with gdigrab produced a static video, so this finds the HUD window by
# handle and grabs its rectangle directly. The process must be DPI aware: GetWindowRect virtualises
# coordinates for a DPI unaware process while CopyFromScreen works on the physical desktop, so on a
# scaled display the capture comes out offset and undersized.
#
#   pwsh -File tools/capture-reference-hud.ps1
#   pwsh -File tools/capture-reference-hud.ps1 -Exe <path> -OutDir <dir> -Seconds 8
#
# Output: <OutDir>/fNNN.png (560x90 physical on a 125% display) and timestamps_ms.txt.
# At 100% display scaling the window is 448x72, which is 1 design unit per pixel.
param(
    [string]$Exe = "$PSScriptRoot\..\references\zmd-charge\publish\EndfieldCharge.exe",
    [string]$OutDir = "refcapture",
    [int]$Seconds = 8
)

Add-Type -AssemblyName System.Drawing

if (-not ("HudCaptureWin" -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class HudCaptureWin {
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    public static List<IntPtr> WindowsOf(uint target) {
        var list = new List<IntPtr>();
        EnumWindows((h, l) => {
            uint pid; GetWindowThreadProcessId(h, out pid);
            if (pid == target && IsWindowVisible(h)) list.Add(h);
            return true;
        }, IntPtr.Zero);
        return list;
    }
}
"@
}

[void][HudCaptureWin]::SetProcessDPIAware()

Get-Process EndfieldCharge -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 800

$OutDir = Join-Path (Get-Location) $OutDir
New-Item -ItemType Directory -Force $OutDir | Out-Null
Get-ChildItem $OutDir -Filter *.png -ErrorAction SilentlyContinue | Remove-Item -Force

$proc = Start-Process -FilePath $Exe -ArgumentList '--demo' -PassThru
Write-Host "started pid=$($proc.Id)"

# The HUD is a small wide window; the tray icon and any dialog do not match this shape.
$hud = [IntPtr]::Zero
$rect = New-Object -TypeName 'HudCaptureWin+RECT'
$deadline = (Get-Date).AddSeconds(6)
while ((Get-Date) -lt $deadline -and $hud -eq [IntPtr]::Zero) {
    Start-Sleep -Milliseconds 40
    foreach ($h in [HudCaptureWin]::WindowsOf([uint32]$proc.Id)) {
        $r = New-Object -TypeName 'HudCaptureWin+RECT'
        [void][HudCaptureWin]::GetWindowRect($h, [ref]$r)
        $w = $r.Right - $r.Left
        $ht = $r.Bottom - $r.Top
        if ($w -gt 200 -and $w -lt 900 -and $ht -gt 40 -and $ht -lt 200) {
            $hud = $h
            $rect = $r
            break
        }
    }
}

if ($hud -eq [IntPtr]::Zero) {
    Write-Host "HUD window not found"
    Get-Process EndfieldCharge -ErrorAction SilentlyContinue | Stop-Process -Force
    exit 1
}

$w = $rect.Right - $rect.Left
$ht = $rect.Bottom - $rect.Top
Write-Host "HUD window $hud at $($rect.Left),$($rect.Top) size ${w}x${ht}"

$end = (Get-Date).AddSeconds($Seconds)
$index = 0
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$stamps = @()
while ((Get-Date) -lt $end) {
    $bmp = New-Object System.Drawing.Bitmap $w, $ht
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size $w, $ht))
    $g.Dispose()
    $bmp.Save((Join-Path $OutDir ("f{0:d3}.png" -f $index)), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $stamps += [math]::Round($sw.Elapsed.TotalMilliseconds)
    $index++
}

$stamps | Set-Content (Join-Path $OutDir "timestamps_ms.txt")
Write-Host "captured $index frames in $([math]::Round($sw.Elapsed.TotalSeconds,2))s"

Get-Process EndfieldCharge -ErrorAction SilentlyContinue | Stop-Process -Force
Write-Host "reference app stopped"
