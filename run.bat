@echo off
setlocal
cd /d "%~dp0"
if not exist "lib\mysql-connector-j.jar" (
  echo ERROR: MySQL Connector/J jar was not found in the lib folder.
  pause
  exit /b 1
)
if not exist "public\index.html" (
  echo ERROR: public\index.html was not found. Run this file from the project folder.
  pause
  exit /b 1
)
set /p SMARTPAYROLL_DB_PASSWORD=Enter your local MySQL root password: 
if not exist out mkdir out
echo Compiling SmartPayroll backend...
javac -cp ".;lib\mysql-connector-j.jar" -d out src\SmartPayrollServer.java
if errorlevel 1 (echo Compilation failed. Check Java installation and try again.& pause & exit /b 1)
echo Starting SmartPayroll...
java -cp ".;out;lib\mysql-connector-j.jar" SmartPayrollServer
pause
