@echo off
setlocal
set VERSION=8.13
set EXPECTED=20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set BASE=%GRADLE_USER_HOME%\manual-wrapper
set DIST=%BASE%\gradle-%VERSION%
set ZIP=%BASE%\gradle-%VERSION%-bin.zip
if not exist "%DIST%\bin\gradle.bat" (
  if not exist "%BASE%" mkdir "%BASE%"
  if not exist "%ZIP%" powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%VERSION%-bin.zip' -OutFile '%ZIP%'"
  for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%ZIP%').Hash.ToLower()"') do set ACTUAL=%%H
  if /I not "%ACTUAL%"=="%EXPECTED%" (
    echo SHA-256 de Gradle no coincide.
    exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%ZIP%' -DestinationPath '%BASE%' -Force"
)
call "%DIST%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
