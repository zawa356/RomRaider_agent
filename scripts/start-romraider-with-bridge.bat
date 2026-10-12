@echo off
rem RRBridge PoC: RomRaider Logger を -javaagent 付きで起動する（RomRaider本体は改造しない）
set "RR=C:\Program Files (x86)\RomRaider"
set "BRIDGE=%~dp0"
if "%BRIDGE:~-1%"=="\" set "BRIDGE=%BRIDGE:~0,-1%"
cd /d "%RR%"
start "RomRaider Logger (RRBridge)" "%RR%\runtime\bin\javaw.exe" -javaagent:"%BRIDGE%\rrbridge.jar" -Drrbridge.dir="%BRIDGE%" -Djava.library.path=lib/windows/32 -Dawt.useSystemAAFontSettings=lcd -Dswing.aatext=true -Xms64M -Xmx512M -jar RomRaider.jar -logger
