@if "%DEBUG%"=="" @echo off
if "%OS%"=="Windows_NT" setlocal
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set JAVA_EXE=java.exe
if defined JAVA_HOME set JAVA_EXE=%JAVA_HOME%\bin\java.exe
"%JAVA_EXE%" -jar "%DIRNAME%gradle\wrapper\gradle-wrapper.jar" %*
exit /b %ERRORLEVEL%
