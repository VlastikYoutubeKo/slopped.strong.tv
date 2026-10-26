$adb = if ($env:ADB) { $env:ADB } else { 'adb' }
function Shot($n){ & $adb shell screencap -p /sdcard/s.png; & $adb pull /sdcard/s.png "shots\$n.png" 2>&1 | Out-Null }
function Key($k,$ms=700){ & $adb shell input keyevent $k; Start-Sleep -Milliseconds $ms }
function Foc(){ & $adb shell uiautomator dump /sdcard/u.xml | Out-Null; & $adb pull /sdcard/u.xml "$env:TEMP\u.xml" | Out-Null; $x=Get-Content "$env:TEMP\u.xml" -Raw -Encoding UTF8; $m=[regex]::Matches($x,'<node[^>]*focused="true"[^>]*>'); ($m | % { [regex]::Match($_.Value,'content-desc="([^"]*)"').Groups[1].Value + '|' + [regex]::Match($_.Value,'text="([^"]*)"').Groups[1].Value }) -join ' ; ' }
function GoTo($desc,$dir='KEYCODE_DPAD_RIGHT'){ for($i=0;$i -lt 14;$i++){ if((Foc) -like "*$desc*"){ return $true }; Key $dir 900 }; return $false }
function SS($n){ & $adb shell screencap -p /sdcard/s.png; & $adb pull /sdcard/s.png "docs\screenshots\$n.png" 2>&1 | Out-Null }
function Launch(){ & $adb shell am force-stop cz.plain.tvstream; & $adb shell am start -n cz.plain.tvstream/.MainActivity | Out-Null; Start-Sleep 20 }
function Tab($n){ Launch; for($i=0;$i -lt $n;$i++){ Key KEYCODE_DPAD_RIGHT 800 }; Start-Sleep 3 }

