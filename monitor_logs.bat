@echo off
echo 开始监控StreamService和WatchdogManager日志...
echo 请在应用中开始推流以触发重连逻辑
echo 按Ctrl+C停止监控
echo.
adb logcat -s StreamService:D WatchdogManager:D -v time