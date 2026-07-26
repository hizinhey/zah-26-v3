@ECHO OFF
WHERE mvn >NUL 2>NUL
IF %ERRORLEVEL% EQU 0 (
  mvn %*
  EXIT /B %ERRORLEVEL%
)
ECHO Maven is not installed. Install Maven 3.9.11 or add .mvn\wrapper\maven-wrapper.jar. 1>&2
EXIT /B 1
