@echo off

F:\Shared\Programmi\Java\jdk\9.0.1\bin\javac.exe -cp "%~dp0..\..\..\..\core\target\classes;%~dp0..\..\..\target\classes;" %~dp0jdk\internal\loader\ClassLoaderDelegate.java

pause