@if "%DEBUG%" == "" @echo off

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if "%ERRORLEVEL%" == "0" goto init
echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
goto fail

:init
set CMD_LINE_ARGS=
set _SKIP=2

:check_args
if not "%OS%" == "Windows_NT" goto execute
set CMD_LINE_ARGS=%*

:execute
%JAVA_EXE% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %CMD_LINE_ARGS%

:end
if "%ERRORLEVEL%"=="0" goto mainEnd
:fail
exit /b 1
:mainEnd
