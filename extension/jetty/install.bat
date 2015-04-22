@echo off¡¡

call cd /d %~dp0

call mvn clean

call mvn install

pause