@echo off
echo Downloading Maven dependencies to speed up future builds and startup...
call mvnw.cmd dependency:go-offline
echo.
echo Dependencies downloaded successfully!
pause
