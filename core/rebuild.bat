@echo off¡¡

call cd /d %~dp0

call mvn eclipse:clean

call mvn eclipse:eclipse

pause