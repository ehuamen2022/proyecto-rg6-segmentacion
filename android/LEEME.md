# Apps Android del OE3

Dos apps que usan el modelo **YOLOv11n-seg INT8** del OE3 (`resultados/oe3/ptq_int8/`), con el mismo código de
detección:

| App | Para qué | Qué guarda en el celular |
|---|---|---|
| **RG6 Medir** | Medir la latencia en el celular (OE3): 5 modos, 5 cuadros de calentamiento + 100 medidos por modo, 30 s de pausa entre modos | `Documents/RG6Medir/registro.csv` |
| **RG6 Contador** | Uso en campo: detectar y contar `rg6Bien` / `rg6Mal` en vivo, con una foto o desde la galería, y guardar evidencia | `Pictures/RG6Contador/` (foto original y segmentada) y `Documents/RG6Contador/capturas.csv` |

## Estructura

```
android/
├── compilar.bat               copia a C:\RG6final\android, pone los modelos, compila, prueba e instala
├── referencia_ultralytics.py  genera la referencia de la prueba NucleoTest
├── comun/                     código compartido por las dos apps
│   ├── Nucleo.kt              decodificación de YOLOv11-seg (cajas, NMS por clase, máscaras), sin Android
│   ├── Modelo.kt              carga el ONNX y classes.json; letterbox, tensor, inferencia y tiempos
│   ├── Camara.kt              cámara trasera 4:3 (vista previa, análisis de cuadros y fotos)
│   ├── Dibujo.kt              dibuja máscaras, cajas y etiquetas
│   ├── Archivos.kt            guarda fotos en Pictures/ y CSV en Documents/ (visibles desde el PC)
│   └── src/test/              NucleoTest + referencia de Ultralytics (4 fotos de test)
├── RG6Medir/                  MainActivity.kt (pantalla) y Medidor.kt (protocolo y registro.csv)
└── RG6Contador/               MainActivity.kt (en vivo, Foto, Galería, Cámara, Guardar)
```

Los modelos **no** se guardan aquí (ya están en `resultados/oe3/ptq_int8/`): `compilar.bat` los copia a la
carpeta de compilación.

## Compilar e instalar

1. Activa la **depuración USB** en el celular y conéctalo al PC (acepta el aviso «¿Permitir depuración USB?»).
2. Doble clic en `compilar.bat`. Hace cuatro pasos:
   1. copia `android/` a `C:\RG6final\android` (Gradle no compila bien dentro de Google Drive);
   2. copia `yolo11nseg_int8_conv_only_dyn.onnx`, `yolo11nseg_fp32_dyn.onnx` y `classes.json` a los assets;
   3. compila las dos apps y ejecuta **NucleoTest**;
   4. instala las dos apps en el celular.

Para abrir el proyecto en Android Studio: ejecuta antes `compilar.bat` y abre `C:\RG6final\android`.
Si cambias el código, cámbialo **aquí** (en Drive) y vuelve a ejecutar `compilar.bat`.

## Medir (RG6 Medir)

1. Prepara la escena (p. ej. una foto de test en el monitor del PC) y encuádrala con la vista previa.
   El botón «Modo» solo cambia el modo de la vista previa.
2. Condiciones: modo avión, **sin cable USB** (cargar cambia la temperatura), sin ahorro de batería. Anótalas.
3. Pulsa «Medir» y no muevas el celular. Tarda unos 8 minutos (5 modos y 4 pausas de 30 s).
   El botón muestra «Midiendo 1/5», «Pausa de 30 s», etc., y vuelve a «Medir» al terminar.
4. Conecta el celular, copia `Documents/RG6Medir/registro.csv` (Almacenamiento interno) a
   `resultados/oe3/android/registro.csv` en Drive y ejecuta `notebooks/notebook_objetivo3/resumen_android.ipynb`.

Detalles de la medición:
- Modos: `ONNX INT8 · CPU 4 hilos`, `ONNX INT8 · CPU 2 hilos`, `ONNX FP32 · CPU 4 hilos`, `ONNX FP32 · CPU 2 hilos`
  y `ONNX INT8 · NNAPI`. Si NNAPI no funciona en el teléfono, la app lo avisa y lo salta.
- Entrada fija de 1024×768 (vertical, como la evaluación rectangular del OE3) y umbral F1-óptimo por clase de
  `classes.json`.
- `total_ms` = preproceso (cuadro de cámara → bitmap derecho → letterbox → tensor) + inferencia + post-proceso
  (decodificación, NMS y máscaras). No incluye el dibujo en pantalla.
- Cada medición se **añade** al final de `registro.csv`; el cuaderno toma la última completa.
  Si se desinstala la app, Android crea un archivo nuevo (`registro (1).csv`).

## Prueba del núcleo (NucleoTest)

Comprueba en el PC (sin celular) que `Nucleo.kt` da **lo mismo que Ultralytics** con las salidas reales del
modelo INT8 en 4 fotos de test (2 horizontales y 2 verticales): mismas detecciones, clases, puntuaciones, cajas,
píxeles de máscara y conteo. `compilar.bat` la ejecuta siempre.

Si cambia el modelo (otra semilla o se vuelve a ejecutar la cuantización), regenera la referencia:

```
python referencia_ultralytics.py ..\resultados\oe3\ptq_int8 ..\data_estratificado1\test
```

(con `ultralytics==8.4.113` y `onnxruntime==1.27.0`).

## Versiones

Android Gradle Plugin 9.2.1 · Gradle 9.4.1 · compileSdk 36 · minSdk 29 (Android 10) · onnxruntime-android 1.27.0
(la misma versión que el cuaderno de cuantización) · CameraX 1.6.2.