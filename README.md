# Segmentación de instancias de conectores coaxiales RG6

Código de la tesis sobre **segmentación de instancias de conectores RG6** con dos clases:
`rg6Bien` (conector bien colocado) y `rg6Mal` (mal colocado). Incluye los cuadernos de entrenamiento y
evaluación (Google Colab) y dos apps Android que ejecutan el modelo en el celular.

Autor: **Edison** · 2026

## Objetivos y cuadernos

Cada cuaderno se abre en Colab con el botón y se ejecuta con *Ejecutar todo*. Todos tienen sus salidas
guardadas, así que los resultados se pueden ver sin ejecutar nada.

| Objetivo | Cuaderno | Entorno | Qué hace |
|---|---|---|---|
| OE1 | [`estratificar_rg6.ipynb`](notebooks/notebook_objetivo1/estratificar_rg6.ipynb) [![Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/ehuamen2022/proyecto-rg6-segmentacion/blob/main/notebooks/notebook_objetivo1/estratificar_rg6.ipynb) | CPU | Divide el dataset 70/20/10 por clase, con un test fijo común |
| OE1 | [`comparar_datasets_yolo.ipynb`](notebooks/notebook_objetivo1/comparar_datasets_yolo.ipynb) [![Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/ehuamen2022/proyecto-rg6-segmentacion/blob/main/notebooks/notebook_objetivo1/comparar_datasets_yolo.ipynb) | A100 | Compara tres datasets: sin curar, curado y curado + aumento de datos |
| OE2 | [`yolov11seg.ipynb`](notebooks/notebook_objetivo2/yolov11seg.ipynb) [![Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/ehuamen2022/proyecto-rg6-segmentacion/blob/main/notebooks/notebook_objetivo2/yolov11seg.ipynb) | A100 | YOLOv11n-seg, 3 semillas |
| OE2 | [`maskrcnn.ipynb`](notebooks/notebook_objetivo2/maskrcnn.ipynb) [![Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/ehuamen2022/proyecto-rg6-segmentacion/blob/main/notebooks/notebook_objetivo2/maskrcnn.ipynb) | A100 | Mask R-CNN R50-FPN, 3 semillas |
| OE2 | [`cascade_maskrcnn.ipynb`](notebooks/notebook_objetivo2/cascade_maskrcnn.ipynb) [![Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/ehuamen2022/proyecto-rg6-segmentacion/blob/main/notebooks/notebook_objetivo2/cascade_maskrcnn.ipynb) | A100 | Cascade Mask R-CNN R50-FPN, 3 semillas |
| OE2 | [`eficiencia.ipynb`](notebooks/notebook_objetivo2/eficiencia.ipynb) [![Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/ehuamen2022/proyecto-rg6-segmentacion/blob/main/notebooks/notebook_objetivo2/eficiencia.ipynb) | A100 | Latencia, FPS, VRAM y frente de Pareto de las 9 corridas |
| OE3 | [`yolo11nseg_faseB.ipynb`](notebooks/notebook_objetivo3/yolo11nseg_faseB.ipynb) [![Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/ehuamen2022/proyecto-rg6-segmentacion/blob/main/notebooks/notebook_objetivo3/yolo11nseg_faseB.ipynb) | A100 | Receta optimizada de YOLO (200 épocas, mosaic, copy-paste), 3 semillas |
| OE3 | [`yolo11nseg_ptq_int8.ipynb`](notebooks/notebook_objetivo3/yolo11nseg_ptq_int8.ipynb) [![Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/ehuamen2022/proyecto-rg6-segmentacion/blob/main/notebooks/notebook_objetivo3/yolo11nseg_ptq_int8.ipynb) | CPU | Elige la semilla, exporta a ONNX y cuantiza a INT8 |
| OE3 | [`resumen_android.ipynb`](notebooks/notebook_objetivo3/resumen_android.ipynb) [![Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/ehuamen2022/proyecto-rg6-segmentacion/blob/main/notebooks/notebook_objetivo3/resumen_android.ipynb) | CPU | Resume la medición de latencia en el celular |

Orden de ejecución: OE1 → OE2 → OE3, en el orden de la tabla. `estratificar_rg6.ipynb` se ejecuta dos veces
(dataset curado y dataset sin curar, este último con el mismo test fijo).

## Resultados principales

Precisión en el test (141 fotos), mAP50-95 de máscara con pycocotools, escala 0–100:

- **OE1** (YOLOv11n-seg, semilla 42): sin curar 52,5 → curado 55,7 (+3,3) → curado + aumento 61,8 (+6,1).
- **OE2** (media de 3 semillas, A100): Cascade Mask R-CNN 64,1 · Mask R-CNN 63,2 · YOLOv11n-seg 61,2.
  YOLOv11n-seg es unas 3 veces más rápido (19 ms frente a 57–66 ms por foto, umbral 0,25).
- **OE3**: la receta optimizada (FASE B) sube YOLOv11n-seg a 65,0. El modelo INT8 conserva el 99,5 % del mAP
  del FP32 y pesa 3,1 veces menos (3,7 MB). En el celular (Redmi Note 14 Pro 4G, MediaTek MT6789) el INT8 con
  2 hilos procesa **2,07 FPS**, un 40 % menos de latencia que el FP32.

## Apps Android (`android/`)

| App | Para qué |
|---|---|
| **RG6 Medir** | Mide la latencia del modelo en 5 modos (INT8/FP32 con 4 y 2 hilos, e INT8 con NNAPI) y la guarda en un CSV |
| **RG6 Contador** | Detecta y cuenta conectores en vivo, en una foto o desde la galería, y guarda la evidencia |

Las dos usan el mismo decodificador (`comun/.../Nucleo.kt`), comprobado contra Ultralytics con `NucleoTest`.
Los modelos ONNX y los datos de prueba de `NucleoTest` están en la
[Release v1.0](https://github.com/ehuamen2022/proyecto-rg6-segmentacion/releases/tag/v1.0).
Cómo compilar e instalar: [`android/LEEME.md`](android/LEEME.md).

## Entorno y versiones

- Google Colab: GPU A100 para entrenar y CPU para estratificar, cuantizar y resumir.
- `ultralytics==8.4.113`, detectron2 commit `02b5c4e295e990042a714712c21dc79b731e8833`,
  `onnx==1.22.0`, `onnxruntime==1.27.0`, pycocotools.
- Android: onnxruntime-android 1.27.0, CameraX 1.6.2, minSdk 29.
- Semillas 42, 123 y 2026.

## Datos

El dataset (fotos de conectores anotadas en Roboflow, formato COCO de segmentación) **no se incluye** en este
repositorio. Los cuadernos esperan las carpetas `data_roboflow/` y `data_curado/` dentro de
`MyDrive/tesis-rg6final/` en Google Drive.

## Licencia

[AGPL-3.0](LICENSE), la misma licencia de Ultralytics YOLO, en la que se basan el entrenamiento y el modelo.