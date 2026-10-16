@echo off
setlocal
cd /d "%~dp0"

rem Starts RuneLite in developer mode with the OSRS Armory plugin loaded.
rem Needs JDK 11+. Uses JAVA_HOME if set, otherwise looks for an Eclipse Temurin install.

if not defined JAVA_HOME (
  for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-*") do set "JAVA_HOME=%%D"
)
if not defined JAVA_HOME (
  echo No JDK found. Install one with:  winget install EclipseAdoptium.Temurin.17.JDK
  pause
  exit /b 1
)

echo Using Java from %JAVA_HOME%
echo Starting RuneLite with OSRS Armory (the first start takes a minute)...
rem Full paths so this works even when cmd can't switch into a very long folder path.
call "%~dp0gradlew.bat" -p "%~dp0." run --console=plain
if errorlevel 1 pause
