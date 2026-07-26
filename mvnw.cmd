@ECHO OFF
SETLOCAL
SET "MAVEN_PROJECTBASEDIR=%~dp0"
SET "WRAPPER_PROPERTIES=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.properties"
SET "WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar"

IF NOT EXIST "%WRAPPER_PROPERTIES%" (
  ECHO Missing Maven Wrapper properties: "%WRAPPER_PROPERTIES%" 1>&2
  EXIT /B 1
)

IF NOT EXIST "%WRAPPER_JAR%" (
  FOR /F "usebackq tokens=1,* delims==" %%A IN ("%WRAPPER_PROPERTIES%") DO (
    IF "%%A"=="wrapperUrl" SET "WRAPPER_URL=%%B"
  )
  IF NOT DEFINED WRAPPER_URL (
    ECHO wrapperUrl is required in "%WRAPPER_PROPERTIES%" 1>&2
    EXIT /B 1
  )
  WHERE powershell >NUL 2>NUL
  IF ERRORLEVEL 1 (
    ECHO Maven Wrapper needs PowerShell to download %WRAPPER_URL% 1>&2
    EXIT /B 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "(New-Object Net.WebClient).DownloadFile('%WRAPPER_URL%', '%WRAPPER_JAR%')"
  IF ERRORLEVEL 1 EXIT /B 1
)

IF DEFINED JAVA_HOME (
  SET "JAVACMD=%JAVA_HOME%\bin\java.exe"
) ELSE (
  SET "JAVACMD=java.exe"
)
"%JAVACMD%" -classpath "%WRAPPER_JAR%" -Dmaven.multiModuleProjectDirectory="%MAVEN_PROJECTBASEDIR%" org.apache.maven.wrapper.MavenWrapperMain %*
EXIT /B %ERRORLEVEL%
