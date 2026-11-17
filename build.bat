@echo off
rem Builds the mod. Output: build\libs\vitruvius-<version>+mc<minecraft>.jar
rem Needs JDK 25: uses a portable jdk-25* folder in tools\ (or ..\Manhattan\tools) if there is one, otherwise JAVA_HOME.
set "ROOT=%~dp0"
cd /d "%ROOT%"
for /d %%D in ("%ROOT%..\Manhattan\tools\jdk-25*") do set "JAVA_HOME=%%~fD"
for /d %%D in ("%ROOT%tools\jdk-25*") do set "JAVA_HOME=%%~fD"
if not exist "%ROOT%tmp" mkdir "%ROOT%tmp"
set "TMP=%ROOT%tmp"
set "TEMP=%ROOT%tmp"
set "JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=%ROOT%tmp"
call "%~dp0gradlew.bat" build %*
