@if "%DEBUG%"=="" @echo off
@rem Sociax Gradle launcher for Windows.
@rem Needs gradle\wrapper\gradle-wrapper.jar. Create it once with:  gradle wrapper --gradle-version 8.9
@rem (that command also replaces this file with the official Gradle script).
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi

set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto checkWrapper

echo.
echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
echo Please install Java 17 and set JAVA_HOME.
goto fail

:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe
if exist "%JAVA_EXE%" goto checkWrapper

echo.
echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
goto fail

:checkWrapper
if exist "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" goto execute

echo.
echo ERROR: gradle\wrapper\gradle-wrapper.jar is missing.
echo Run once:  gradle wrapper --gradle-version 8.9   (then commit the jar)
goto fail

:execute
set CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

:end
if %ERRORLEVEL% equ 0 goto mainEnd

:fail
set EXIT_CODE=%ERRORLEVEL%
if %EXIT_CODE% equ 0 set EXIT_CODE=1
if not "" == "%GRADLE_EXIT_CONSOLE%" exit %EXIT_CODE%
exit /b %EXIT_CODE%

:mainEnd
if "%OS%"=="Windows_NT" endlocal
