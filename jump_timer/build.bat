@echo off
REM ============================================================
REM  跳转计时器 —— 一键构建 APK
REM
REM  依赖（按顺序检查）：
REM    1. JDK 8+          javac / jar / keytool
REM    2. Android SDK      android.jar (platform)
REM    3. build-tools      aapt2 / d8 / zipalign / apksigner
REM
REM  用法：
REM    双击本文件，或在命令行执行 build.bat
REM ============================================================
setlocal enabledelayedexpansion
chcp 65001 >nul

set "ROOT=%~dp0"
set "SRC=%ROOT%src"
set "OUT=%ROOT%build"
set "DIST=%ROOT%dist"

echo ============================================================
echo   跳转计时器 构建脚本
echo ============================================================
echo.

REM ---------- 1. 检查 Java ----------
where javac >nul 2>nul
if errorlevel 1 (
    echo [错误] 找不到 javac，请安装 JDK 8 或更高版本
    goto :fail
)
for /f "tokens=3" %%v in ('javac -version 2^>^&1') do set "JAVAVER=%%v"
echo [OK] Java: %JAVAVER%

REM ---------- 2. 定位 Android SDK ----------
set "SDK="
if defined ANDROID_HOME set "SDK=%ANDROID_HOME%"
if defined ANDROID_SDK_ROOT set "SDK=%ANDROID_SDK_ROOT%"
if not defined SDK (
    for %%d in (
        "%LOCALAPPDATA%\Android\Sdk"
        "D:\Software\Android\Sdk"
        "D:\Android\Sdk"
        "C:\Android\Sdk"
    ) do (
        if exist "%%~d\platforms" if not defined SDK set "SDK=%%~d"
    )
)

if not defined SDK (
    echo [错误] 找不到 Android SDK
    echo        请设置环境变量 ANDROID_HOME 指向 SDK 目录
    echo        或把 SDK 放到 %LOCALAPPDATA%\Android\Sdk
    goto :fail
)
echo [OK] SDK: %SDK%

REM ---------- 3. 找 android.jar ----------
set "ANDROID_JAR="
for /f "delims=" %%p in ('dir /b /o-n "%SDK%\platforms" 2^>nul') do (
    if not defined ANDROID_JAR (
        if exist "%SDK%\platforms\%%p\android.jar" set "ANDROID_JAR=%SDK%\platforms\%%p\android.jar"
    )
)
if not defined ANDROID_JAR (
    echo [错误] 找不到 android.jar，请在 SDK Manager 里装一个 Platform
    goto :fail
)
echo [OK] android.jar: %ANDROID_JAR%

REM ---------- 4. 找 build-tools ----------
set "BT="
for /f "delims=" %%p in ('dir /b /o-n "%SDK%\build-tools" 2^>nul') do (
    if not defined BT (
        if exist "%SDK%\build-tools\%%p\aapt2.exe" set "BT=%SDK%\build-tools\%%p"
    )
)
if not defined BT (
    echo [错误] 找不到 build-tools（需要 aapt2 / d8 / apksigner）
    goto :fail
)
echo [OK] build-tools: %BT%

REM 检查需要的工具
for %%t in (aapt2.exe d8.bat zipalign.exe apksigner.bat) do (
    if not exist "%BT%\%%t" (
        echo [错误] build-tools 里缺少 %%t
        goto :fail
    )
)

echo.
echo ============================================================
echo   开始构建
echo ============================================================
echo.

if exist "%OUT%" rmdir /s /q "%OUT%"
if exist "%DIST%" rmdir /s /q "%DIST%"
mkdir "%OUT%\gen" 2>nul
mkdir "%OUT%\obj" 2>nul
mkdir "%OUT%\dex" 2>nul
mkdir "%DIST%" 2>nul

REM ---------- 5. 编译资源（生成 R.java）----------
echo [1/6] 编译资源...
"%BT%\aapt2.exe" compile --dir "%SRC%\res" -o "%OUT%\res.zip"
if errorlevel 1 goto :fail
echo       完成

REM ---------- 6. 链接资源 + 生成 R.java + 打包基础 APK ----------
echo [2/6] 链接资源...
"%BT%\aapt2.exe" link ^
    -o "%OUT%\base.apk" ^
    -I "%ANDROID_JAR%" ^
    --manifest "%SRC%\AndroidManifest.xml" ^
    --java "%OUT%\gen" ^
    --min-sdk-version 21 ^
    --target-sdk-version 34 ^
    "%OUT%\res.zip"
if errorlevel 1 goto :fail
echo       完成

REM ---------- 7. 编译 Java ----------
echo [3/6] 编译 Java 源码...
dir /s /b "%SRC%\*.java" > "%OUT%\sources.txt"
dir /s /b "%OUT%\gen\*.java" >> "%OUT%\sources.txt"

javac -source 8 -target 8 -encoding UTF-8 ^
    -bootclasspath "%ANDROID_JAR%" ^
    -classpath "%ANDROID_JAR%" ^
    -d "%OUT%\obj" ^
    @"%OUT%\sources.txt" 2> "%OUT%\javac.log"
if errorlevel 1 (
    echo [错误] Java 编译失败，详情：
    type "%OUT%\javac.log"
    goto :fail
)
echo       完成

REM ---------- 8. dex ----------
echo [4/6] 转换 dex...
"%BT%\d8.bat" --lib "%ANDROID_JAR%" --min-api 21 ^
    --output "%OUT%\dex" ^
    "%OUT%\obj\com\timer\jump\*.class" 2> "%OUT%\d8.log"
if errorlevel 1 (
    echo [错误] d8 失败，详情：
    type "%OUT%\d8.log"
    goto :fail
)
echo       完成

REM ---------- 9. 把 dex 加进 APK ----------
echo [5/6] 打包 APK...
REM 用 jar 更新（APK 本质是 zip）
cd /d "%OUT%\dex"
"%JAVA_HOME%\bin\jar" 2>nul
jar uf "%OUT%\base.apk" classes.dex
if errorlevel 1 (
    echo [错误] 加入 classes.dex 失败
    cd /d "%ROOT%"
    goto :fail
)
cd /d "%ROOT%"

REM 对齐
"%BT%\zipalign.exe" -f -p 4 "%OUT%\base.apk" "%OUT%\aligned.apk"
if errorlevel 1 goto :fail
echo       完成

REM ---------- 10. 签名 ----------
echo [6/6] 签名...
if not exist "%OUT%\debug.keystore" (
    keytool -genkeypair -v ^
        -keystore "%OUT%\debug.keystore" ^
        -alias jump_timer ^
        -keyalg RSA -keysize 2048 -validity 10000 ^
        -storepass android -keypass android ^
        -dname "CN=Jump Timer, OU=Dev, O=Local, L=NA, ST=NA, C=CN" 2>nul
)

"%BT%\apksigner.bat" sign ^
    --ks "%OUT%\debug.keystore" ^
    --ks-key-alias jump_timer ^
    --ks-pass pass:android ^
    --key-pass pass:android ^
    --out "%DIST%\JumpTimer.apk" ^
    "%OUT%\aligned.apk"
if errorlevel 1 goto :fail

"%BT%\apksigner.bat" verify "%DIST%\JumpTimer.apk"
if errorlevel 1 (
    echo [警告] 签名校验未通过
) else (
    echo       签名有效
)

echo.
echo ============================================================
echo   构建成功
echo ============================================================
echo.
echo   APK: %DIST%\JumpTimer.apk
for %%f in ("%DIST%\JumpTimer.apk") do echo   大小: %%~zf 字节
echo.
echo   安装：
echo     adb install -r "%DIST%\JumpTimer.apk"
echo.
goto :eof

:fail
echo.
echo ============================================================
echo   构建失败
echo ============================================================
exit /b 1
