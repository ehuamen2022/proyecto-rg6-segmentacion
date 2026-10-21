# -*- coding: utf-8 -*-
"""Genera la referencia de NucleoTest: salidas crudas del ONNX INT8 en 4 fotos de test (2 horizontales y
2 verticales) y su decodificación OFICIAL con Ultralytics (NMS + máscaras).

Uso (desde la carpeta android/, con ultralytics==8.4.113 y onnxruntime==1.27.0 instalados):
    python referencia_ultralytics.py ../resultados/oe3/ptq_int8 ../data_estratificado1/test
Escribe en comun/src/test/resources/referencia/.
"""
import glob, json, os, sys
import cv2, numpy as np, onnxruntime as ort, torch
from ultralytics.utils.nms import non_max_suppression
from ultralytics.utils.ops import crop_mask

MODELOS, FOTOS = sys.argv[1], sys.argv[2]
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "comun", "src", "test", "resources", "referencia")
os.makedirs(OUT, exist_ok=True)
cfg = json.load(open(os.path.join(MODELOS, "classes.json"), encoding="utf-8"))
nombres = cfg["names"]
post = cfg["postprocess"]
umb = [post["conf_campo_por_clase_f1_valid"][n]["conf"] for n in nombres]   # el mismo umbral que usan las apps
iou = post["iou_nms"]
ses = ort.InferenceSession(os.path.join(MODELOS, "yolo11nseg_int8_conv_only_dyn.onnx"), providers=["CPUExecutionProvider"])


def letterbox(img, alto, ancho):
    h, w = img.shape[:2]
    r = min(alto / h, ancho / w)
    nw, nh = int(round(w * r)), int(round(h * r))
    img = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_LINEAR)
    dw, dh = (ancho - nw) / 2, (alto - nh) / 2
    t, b = int(round(dh - 0.1)), int(round(dh + 0.1))
    l, rr = int(round(dw - 0.1)), int(round(dw + 0.1))
    return cv2.copyMakeBorder(img, t, b, l, rr, cv2.BORDER_CONSTANT, value=(114, 114, 114))


fotos = sorted(glob.glob(os.path.join(FOTOS, "*.jpg")))
formas = {f: cv2.imread(f).shape[:2] for f in fotos}
horiz = [f for f in fotos if formas[f][1] >= formas[f][0]][:2]
vert = [f for f in fotos if formas[f][1] < formas[f][0]][:2]
casos = horiz + vert
for k, f in enumerate(casos):
    img = cv2.imread(f)
    h, w = img.shape[:2]
    alto, ancho = (768, 1024) if w >= h else (1024, 768)
    x = cv2.cvtColor(letterbox(img, alto, ancho), cv2.COLOR_BGR2RGB).astype(np.float32).transpose(2, 0, 1)[None] / 255.0
    det, proto = sorted(ses.run(None, {ses.get_inputs()[0].name: np.ascontiguousarray(x)}), key=lambda a: a.size)
    det.astype("<f4").tofile(os.path.join(OUT, f"caso{k}_det.bin"))
    proto.astype("<f4").tofile(os.path.join(OUT, f"caso{k}_proto.bin"))
    # NMS oficial por clase con el menor umbral; luego cada detección debe superar el umbral de SU clase
    p = non_max_suppression(torch.from_numpy(det), conf_thres=min(umb), iou_thres=iou, nc=len(nombres), max_det=300)[0]
    p = p[[float(r[4]) > umb[int(r[5])] for r in p]] if len(p) else p
    lineas = [f"{alto} {ancho}"]
    if len(p):
        pr = torch.from_numpy(proto[0])                                    # 32 x H/4 x W/4
        m = (p[:, 6:] @ pr.view(32, -1)).view(-1, *pr.shape[1:])           # logits de máscara
        m = crop_mask(m, p[:, :4] / 4) > 0                                 # recorte a la caja (resolución /4)
        for r, mk in zip(p, m):
            lineas.append(" ".join(map(str, [int(r[5]), float(r[4]), *[float(v) for v in r[:4]], int(mk.sum())])))
    open(os.path.join(OUT, f"caso{k}_ref.txt"), "w").write("\n".join(lineas) + "\n")
    print(f"caso{k}: {os.path.basename(f)[:45]} {w}x{h} -> entrada {ancho}x{alto} | {len(lineas) - 1} detecciones")
open(os.path.join(OUT, "config.txt"), "w").write(
    f"{' '.join(nombres)}\n{' '.join(str(u) for u in umb)}\n{iou}\n{len(casos)}\n")
print("referencia en", OUT)
