@echo off
REM ============================================================================================
REM Compila las dos apps (RG6Medir y RG6Contador), prueba el nucleo de deteccion y, si hay un
REM celular conectado por USB (depuracion USB activada), las instala.
REM
REM Gradle / Android Studio no compilan bien dentro de Google Drive (R:), por eso este script:
REM   1) copia esta carpeta a C:\RG6final\android (sin carpetas de compilacion),
REM   2) copia los modelos ONNX y classes.json desde resultados\oe3\ptq_int8 a los assets de cada app,
REM   3) compila alli con Gradle y ejecuta NucleoTest,
REM   4) instala los APK con adb.
REM Uso: doble clic, o  compilar.bat [carpeta_de_modelos]
REM ============================================================================================
setlocal
set FUENTE=%~dp0
set DESTINO=C:\RG6final\android
set MODELOS=%~1
if "%MODELOS%"=="" set MODELOS=%~dp0..\resultados\oe3\ptq_int8
set SDK=%LOCALAPPDATA%\Android\Sdk
if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"

for %%F in (yolo11nseg_int8_conv_only_dyn.onnx yolo11nseg_fp32_dyn.onnx classes.json) do (
  if not exist "%MODELOS%\%%F" (
    echo Falta "%MODELOS%\%%F". Ejecuta antes el cuaderno yolo11nseg_ptq_int8.ipynb.
    exit /b 1
  )
)

echo [1/4] Copiando el proyecto a %DESTINO% ...
robocopy "%FUENTE%." "%DESTINO%" /MIR /XD .gradle .kotlin .idea build assets /NFL /NDL /NJH /NJS /NP >nul
if errorlevel 8 ( echo Error al copiar el proyecto. & exit /b 1 )

echo [2/4] Copiando los modelos desde %MODELOS% ...
set A_MEDIR=%DESTINO%\RG6Medir\src\main\assets
set A_CONTADOR=%DESTINO%\RG6Contador\src\main\assets
if not exist "%A_MEDIR%" mkdir "%A_MEDIR%"
if not exist "%A_CONTADOR%" mkdir "%A_CONTADOR%"
copy /Y "%MODELOS%\yolo11nseg_int8_conv_only_dyn.onnx" "%A_MEDIR%\" >nul
copy /Y "%MODELOS%\yolo11nseg_fp32_dyn.onnx" "%A_MEDIR%\" >nul
copy /Y "%MODELOS%\classes.json" "%A_MEDIR%\" >nul
copy /Y "%MODELOS%\yolo11nseg_int8_conv_only_dyn.onnx" "%A_CONTADOR%\" >nul
copy /Y "%MODELOS%\classes.json" "%A_CONTADOR%\" >nul
echo sdk.dir=%SDK:\=/%> "%DESTINO%\local.properties"

echo [3/4] Compilando y ejecutando NucleoTest ...
pushd "%DESTINO%"
call "%DESTINO%\gradlew.bat" --console=plain :comun:testDebugUnitTest :RG6Medir:assembleDebug :RG6Contador:assembleDebug
if errorlevel 1 ( popd & echo La compilacion o NucleoTest fallo: revisa el mensaje de arriba. & exit /b 1 )
popd

echo [4/4] Instalando en el celular ...
set ADB=%SDK%\platform-tools\adb.exe
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (
  echo No hay celular conectado: los APK quedaron en
  echo   %DESTINO%\RG6Medir\build\outputs\apk\debug\RG6Medir-debug.apk
  echo   %DESTINO%\RG6Contador\build\outputs\apk\debug\RG6Contador-debug.apk
  exit /b 0
)
"%ADB%" install -r "%DESTINO%\RG6Medir\build\outputs\apk\debug\RG6Medir-debug.apk"
"%ADB%" install -r "%DESTINO%\RG6Contador\build\outputs\apk\debug\RG6Contador-debug.apk"
echo Listo: RG6 Medir y RG6 Contador instaladas.
