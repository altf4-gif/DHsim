@echo off
setlocal
cd /d "%~dp0"

if not exist "out\com\example\engine\Main.class" (
    echo [ERROR] Project not built. Run build.bat first.
    pause
    exit /b 1
)

if not exist "out\com\example\engine\Sprite.class" (
    echo [ERROR] out\ does not contain Sprite.class.
    echo         You are running an OLD build. Run clean.bat and build.bat.
    pause
    exit /b 1
)

echo Starting LWJGL 3D Engine demo...
java ^
  --enable-native-access=ALL-UNNAMED ^
  --sun-misc-unsafe-memory-access=allow ^
  -cp "out;lib/*" com.example.engine.Main

if errorlevel 1 (
    echo.
    echo [ERROR] Java process exited with error code %errorlevel%.
    pause
)
