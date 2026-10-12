@echo off
rem RRBridge 検証用受信サーバー（Telemetry API試作）: 127.0.0.1:18766 で受信し data\telemetry.db (SQLite) に保存
set "RR=C:\Program Files (x86)\RomRaider"
cd /d "%~dp0"
if not exist data mkdir data
title RRBridge Receiver
echo RRBridge Receiver running. Log: %~dp0data\receiver.log  (close this window to stop)
"%RR%\runtime\bin\java.exe" -cp "%~dp0rrreceiver.jar;%~dp0sqlite-jdbc.jar" net.neristal.rrbridge.Receiver "%~dp0data\telemetry.db" 127.0.0.1 18766 >> "%~dp0data\receiver.log" 2>&1
echo Receiver exited. See data\receiver.log
pause
