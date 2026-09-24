#!/usr/bin/env python3
"""
AmpelPilot YOLOv8 TFLite - Direct Desktop OpenCV Webcam Window
Press 'q' or 'ESC' to quit.
"""

import os
import sys
import time
import cv2
import numpy as np
from ai_edge_litert.interpreter import Interpreter

MODEL_PATH = os.path.join(os.path.dirname(__file__), "app/src/main/assets/detect.tflite")
LABELS_PATH = os.path.join(os.path.dirname(__file__), "app/src/main/assets/labelmap.txt")

LABELS = []
if os.path.exists(LABELS_PATH):
    with open(LABELS_PATH, "r", encoding="utf-8") as f:
        LABELS = [l.strip() for l in f if l.strip()]
if not LABELS:
    LABELS = ["red", "green", "pedestrian Traffic Light"]

print(f"[*] Loading model from {MODEL_PATH}...")
interpreter = Interpreter(model_path=MODEL_PATH)
interpreter.allocate_tensors()

input_details = interpreter.get_input_details()[0]
output_details = interpreter.get_output_details()[0]

input_shape = input_details["shape"]
is_nchw = (len(input_shape) == 4 and input_shape[1] == 3)
input_size = input_shape[2] if is_nchw else input_shape[1]

print(f"[+] Loaded successfully! Input shape: {input_shape} ({input_size}x{input_size}), Classes: {LABELS}")

CLASS_COLORS = {
    0: (50, 50, 255),   # Red
    1: (50, 205, 50),   # Green
    2: (0, 165, 255),   # Amber/Orange
}

def main():
    conf_thresh = 0.25
    iou_thresh = 0.45

    print("[*] Opening webcam (index 0)...")
    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        print("[!] Error: Could not open webcam.")
        print("    Tip: Check System Settings -> Privacy & Security -> Camera -> Terminal/Python.")
        print("    Or use the web version: python3 webcam_web.py (runs in Safari/Chrome).")
        return

    print("[+] Webcam opened. Press 'q' to quit.")
    prev_time = time.time()

    while True:
        ret, frame = cap.read()
        if not ret:
            print("[!] Failed to grab frame.")
            break

        orig_h, orig_w = frame.shape[:2]

        # Preprocess
        rgb = cv2.cvtColor(frame, cv2.COLOR_BGR2RGB)
        resized = cv2.resize(rgb, (input_size, input_size))

        if is_nchw:
            inp = np.transpose(resized, (2, 0, 1)).astype(np.float32) / 255.0
        else:
            inp = resized.astype(np.float32) / 255.0
        inp = np.expand_dims(inp, axis=0)

        # Inference
        interpreter.set_tensor(input_details["index"], inp)
        interpreter.invoke()
        output = interpreter.get_tensor(output_details["index"])[0]

        cx = output[0, :]
        cy = output[1, :]
        w = output[2, :]
        h = output[3, :]
        scores = output[4:4 + len(LABELS), :]

        max_classes = np.argmax(scores, axis=0)
        max_scores = np.max(scores, axis=0)

        mask = max_scores >= conf_thresh
        v_cx = cx[mask]
        v_cy = cy[mask]
        v_w = w[mask]
        v_h = h[mask]
        v_cls = max_classes[mask]
        v_sc = max_scores[mask]

        if len(v_cx) > 0 and np.max(v_cx) <= 1.0:
            v_cx *= input_size
            v_cy *= input_size
            v_w *= input_size
            v_h *= input_size

        scale_x = orig_w / float(input_size)
        scale_y = orig_h / float(input_size)

        boxes = []
        for x_c, y_c, bw, bh in zip(v_cx, v_cy, v_w, v_h):
            left = int(max(0, (x_c - bw / 2.0) * scale_x))
            top = int(max(0, (y_c - bh / 2.0) * scale_y))
            boxes.append([left, top, int(bw * scale_x), int(bh * scale_y)])

        if len(boxes) > 0:
            idxs = cv2.dnn.NMSBoxes(boxes, [float(s) for s in v_sc], float(conf_thresh), float(iou_thresh))
            if len(idxs) > 0:
                for i in idxs.flatten():
                    box = boxes[i]
                    cid = int(v_cls[i])
                    sc = float(v_sc[i])
                    label = LABELS[cid] if cid < len(LABELS) else f"class_{cid}"
                    color = CLASS_COLORS.get(cid, (255, 255, 0))

                    # Draw box
                    cv2.rectangle(frame, (box[0], box[1]), (box[0] + box[2], box[1] + box[3]), color, 3)

                    # Draw label badge
                    text = f"{label} {int(sc * 100)}%"
                    (tw, th), _ = cv2.getTextSize(text, cv2.FONT_HERSHEY_SIMPLEX, 0.7, 2)
                    cv2.rectangle(frame, (box[0], max(0, box[1] - th - 10)), (box[0] + tw + 10, max(0, box[1])), color, -1)
                    cv2.putText(frame, text, (box[0] + 5, max(0, box[1] - 5)),
                                cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2, cv2.LINE_AA)

        # FPS calculation
        curr_time = time.time()
        fps = 1.0 / (curr_time - prev_time + 1e-6)
        prev_time = curr_time
        cv2.putText(frame, f"FPS: {int(fps)} | Conf >= {int(conf_thresh * 100)}%", (15, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 255), 2, cv2.LINE_AA)

        cv2.imshow("AmpelPilot YOLOv8 Detection Test (Press 'q' to quit)", frame)
        if cv2.waitKey(1) & 0xFF in (ord('q'), 27):
            break

    cap.release()
    cv2.destroyAllWindows()

if __name__ == "__main__":
    main()
