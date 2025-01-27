@echo off
SETLOCAL
:: Auto Updater: 0: Disabled; 1 = Enabled
set auto_update=1
:: Update channel: 0 = Preview; 1 = Stable
set preview=0

:: Move CWD to script folder
pushd "%~0\.."

:: Auto-Updater
if (%auto_update% NEQ 1) goto :launch
set channel=
if (%preview% EQU 1) (set channel=-preview)
for /f "tokens=2" %%i in ('curl -s https://api.github.com/repos/Suwayomi/Suwayomi-Server%channel%/releases/latest^|findstr "https://.*jar"') do (
	pushd bin
	if NOT EXIST "%%~nxi" (
    echo Updating to %%~ni
		del /q "*.jar"
		curl -sL %%i -o "%%~nxi"
		mklink /h "Suwayomi-Server.jar" "%%~nxi"
	)
	popd
)

:launch
start "" jre\bin\javaw %* -jar Suwayomi-Launcher.jar --launch
popd
ENDLOCAL
