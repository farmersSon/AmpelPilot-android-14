#!/usr/bin/env python3
"""
AmpelPilot YOLOv8 TFLite - Real-Time Webcam Detection Server
Supports both Server-Side Webcam (OpenCV) and Browser-Side Webcam (HTML5 Canvas/MediaDevices)
"""

import os
import sys
import time
import base64
import json
import cv2
import numpy as np
from flask import Flask, Response, render_template_string, request, jsonify
from ai_edge_litert.interpreter import Interpreter

app = Flask(__name__)

MODEL_PATH = os.path.join(os.path.dirname(__file__), "app/src/main/assets/detect.tflite")
LABELS_PATH = os.path.join(os.path.dirname(__file__), "app/src/main/assets/labelmap.txt")

# Load labels
LABELS = []
if os.path.exists(LABELS_PATH):
    with open(LABELS_PATH, "r", encoding="utf-8") as f:
        LABELS = [line.strip() for line in f.readlines() if line.strip()]
if not LABELS:
    LABELS = ["red", "green", "pedestrian Traffic Light"]

# Initialize LiteRT / TFLite Interpreter
print(f"[*] Loading TFLite model from {MODEL_PATH}...")
interpreter = Interpreter(model_path=MODEL_PATH)
interpreter.allocate_tensors()

input_details = interpreter.get_input_details()[0]
output_details = interpreter.get_output_details()[0]

input_shape = input_details["shape"] # [1, 3, 640, 640] or [1, 640, 640, 3]
is_nchw = (len(input_shape) == 4 and input_shape[1] == 3)
input_size = input_shape[2] if is_nchw else input_shape[1]

print(f"[+] Model loaded successfully!")
print(f"    - Input shape: {input_shape} (NCHW: {is_nchw}, Size: {input_size}x{input_size})")
print(f"    - Output shape: {output_details['shape']}")
print(f"    - Classes ({len(LABELS)}): {LABELS}")

# Class colors & emojis (BGR format for OpenCV)
CLASS_INFO = {
    0: {"name": "🔴 Red", "color": (50, 50, 255), "label": "Rot"},
    1: {"name": "🟢 Green", "color": (50, 205, 50), "label": "Grün"},
    2: {"name": "🟡 Ampel", "color": (0, 165, 255), "label": "Ampel"},
}

def detect_objects(image_bgr, conf_threshold=0.25, iou_threshold=0.45):
    """Run YOLOv8 inference on a single BGR frame and return detections + annotated image."""
    orig_h, orig_w = image_bgr.shape[:2]

    # Preprocess
    rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
    resized = cv2.resize(rgb, (input_size, input_size))

    if is_nchw:
        # [1, 3, 640, 640]
        input_tensor = np.transpose(resized, (2, 0, 1)).astype(np.float32) / 255.0
    else:
        # [1, 640, 640, 3]
        input_tensor = resized.astype(np.float32) / 255.0
    input_tensor = np.expand_dims(input_tensor, axis=0)

    # Invoke TFLite
    interpreter.set_tensor(input_details["index"], input_tensor)
    interpreter.invoke()
    output = interpreter.get_tensor(output_details["index"])[0] # (7, 8400)

    # Decode YOLOv8 output
    # Channels: 0:cx, 1:cy, 2:w, 3:h, 4..: classes
    cx = output[0, :]
    cy = output[1, :]
    w = output[2, :]
    h = output[3, :]
    class_scores = output[4:4 + len(LABELS), :] # shape: (num_classes, 8400)

    max_class_ids = np.argmax(class_scores, axis=0)
    max_scores = np.max(class_scores, axis=0)

    # Filter by confidence
    mask = max_scores >= conf_threshold
    valid_cx = cx[mask]
    valid_cy = cy[mask]
    valid_w = w[mask]
    valid_h = h[mask]
    valid_class_ids = max_class_ids[mask]
    valid_scores = max_scores[mask]

    # If coordinates are normalized [0, 1], scale to input_size
    if len(valid_cx) > 0 and np.max(valid_cx) <= 1.0:
        valid_cx *= input_size
        valid_cy *= input_size
        valid_w *= input_size
        valid_h *= input_size

    # Scale to original image size
    scale_x = orig_w / float(input_size)
    scale_y = orig_h / float(input_size)

    boxes_for_nms = []
    for x_c, y_c, box_w, box_h in zip(valid_cx, valid_cy, valid_w, valid_h):
        left = int(max(0, (x_c - box_w / 2.0) * scale_x))
        top = int(max(0, (y_c - box_h / 2.0) * scale_y))
        box_width = int(box_w * scale_x)
        box_height = int(box_h * scale_y)
        boxes_for_nms.append([left, top, box_width, box_height])

    # Apply NMS
    detections = []
    if len(boxes_for_nms) > 0:
        indices = cv2.dnn.NMSBoxes(
            boxes_for_nms,
            [float(s) for s in valid_scores],
            float(conf_threshold),
            float(iou_threshold)
        )
        if len(indices) > 0:
            for idx in indices.flatten():
                box = boxes_for_nms[idx]
                class_id = int(valid_class_ids[idx])
                score = float(valid_scores[idx])
                detections.append({
                    "class_id": class_id,
                    "label": LABELS[class_id] if class_id < len(LABELS) else f"class_{class_id}",
                    "score": score,
                    "box": box, # [x, y, w, h]
                })

    # Draw detections on copy
    annotated = image_bgr.copy()
    for d in detections:
        x, y, bw, bh = d["box"]
        cid = d["class_id"]
        score = d["score"]
        info = CLASS_INFO.get(cid, {"name": d["label"], "color": (255, 255, 0), "label": d["label"]})
        color = info["color"]

        # Bounding box with rounded look or thick stroke
        cv2.rectangle(annotated, (x, y), (x + bw, y + bh), color, 3)

        # Badge label
        badge_text = f"{info['name']} {int(score * 100)}%"
        (tw, th), baseline = cv2.getTextSize(badge_text, cv2.FONT_HERSHEY_DUPLEX, 0.7, 2)
        cv2.rectangle(annotated, (x, max(0, y - th - 12)), (x + tw + 10, max(0, y)), color, -1)
        cv2.putText(annotated, badge_text, (x + 5, max(0, y - 6)),
                    cv2.FONT_HERSHEY_DUPLEX, 0.7, (255, 255, 255), 2, cv2.LINE_AA)

    return detections, annotated

HTML_PAGE = """
<!DOCTYPE html>
<html lang="ko">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>🚦 AmpelPilot YOLOv8 웹캠 실시간 감지 테스트</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; }
        body { background: #121214; color: #eee; padding: 20px; display: flex; flex-direction: column; align-items: center; }
        header { text-align: center; margin-bottom: 20px; }
        h1 { font-size: 26px; color: #fff; margin-bottom: 8px; }
        p.subtitle { color: #888; font-size: 14px; }
        .controls { background: #1c1c1f; border-radius: 12px; padding: 15px 25px; margin-bottom: 20px; display: flex; gap: 30px; align-items: center; box-shadow: 0 4px 20px rgba(0,0,0,0.5); }
        .control-group { display: flex; align-items: center; gap: 10px; }
        label { font-weight: 600; font-size: 14px; color: #aaa; }
        input[type="range"] { width: 140px; }
        .val-badge { background: #2a2a30; padding: 4px 10px; border-radius: 6px; font-weight: bold; color: #00d26a; }
        .container { position: relative; width: 640px; max-width: 95vw; background: #000; border-radius: 16px; overflow: hidden; box-shadow: 0 10px 40px rgba(0,0,0,0.8); border: 2px solid #282830; }
        video { display: none; }
        canvas { width: 100%; display: block; }
        .stats-bar { display: flex; justify-content: space-between; padding: 10px 15px; background: #18181c; font-size: 13px; color: #aaa; border-top: 1px solid #282830; }
        .status-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #00d26a; margin-right: 6px; }
        .detection-list { margin-top: 20px; width: 640px; max-width: 95vw; background: #1c1c1f; border-radius: 12px; padding: 15px; }
        .det-item { display: inline-flex; align-items: center; background: #25252b; padding: 6px 14px; border-radius: 20px; margin: 4px; font-size: 14px; font-weight: 600; }
        .tag-red { color: #ff453a; border-left: 4px solid #ff453a; }
        .tag-green { color: #32d74b; border-left: 4px solid #32d74b; }
        .tag-amber { color: #ff9f0a; border-left: 4px solid #ff9f0a; }
    </style>
</head>
<body>
    <header>
        <h1>🚦 AmpelPilot YOLOv8 웹캠 실시간 테스트</h1>
        <p class="subtitle">Mac의 웹캠을 통해 빨간불/초록불/신호등 하우징을 실시간으로 감지합니다.</p>
    </header>

    <div class="controls">
        <div class="control-group">
            <label for="conf">인식 기준 확률 (Confidence):</label>
            <input type="range" id="conf" min="10" max="90" value="25" step="5" oninput="updateConf(this.value)">
            <span class="val-badge" id="conf-val">25%</span>
        </div>
        <div class="control-group">
            <button id="camera-btn" onclick="toggleCamera()" style="padding: 8px 18px; border-radius: 8px; border: none; background: #0071e3; color: white; font-weight: bold; cursor: pointer;">
                카메라 시작
            </button>
        </div>
    </div>

    <div class="container">
        <video id="webcam" playsinline autoplay></video>
        <canvas id="output-canvas" width="640" height="480"></canvas>
        <div class="stats-bar">
            <div><span class="status-dot" id="status-dot"></span><span id="status-text">준비됨 (카메라 시작을 눌러주세요)</span></div>
            <div>FPS: <span id="fps-val" style="color: #fff; font-weight: bold;">0</span></div>
        </div>
    </div>

    <div class="detection-list" id="detection-list">
        <div style="font-size: 13px; color: #777; margin-bottom: 8px;">실시간 감지 객체:</div>
        <div id="tags-container" style="color: #555; font-size: 14px;">감지된 신호등이 없습니다.</div>
    </div>

    <script>
        const video = document.getElementById('webcam');
        const canvas = document.getElementById('output-canvas');
        const ctx = canvas.getContext('2d');
        const confSlider = document.getElementById('conf');
        const confVal = document.getElementById('conf-val');
        const statusDot = document.getElementById('status-dot');
        const statusText = document.getElementById('status-text');
        const fpsVal = document.getElementById('fps-val');
        const tagsContainer = document.getElementById('tags-container');

        let isRunning = false;
        let isProcessing = false;
        let currentConf = 0.25;
        let frameCount = 0;
        let lastFpsTime = performance.now();

        function updateConf(val) {
            currentConf = val / 100.0;
            confVal.textContent = val + '%';
        }

        async function toggleCamera() {
            if (isRunning) {
                stopCamera();
            } else {
                startCamera();
            }
        }

        async function startCamera() {
            try {
                statusText.textContent = "웹캠 연결 중...";
                const stream = await navigator.mediaDevices.getUserMedia({
                    video: { width: { ideal: 640 }, height: { ideal: 480 }, facingMode: "user" }
                });
                video.srcObject = stream;
                await video.play();

                canvas.width = video.videoWidth || 640;
                canvas.height = video.videoHeight || 480;

                isRunning = true;
                document.getElementById('camera-btn').textContent = "카메라 중지";
                document.getElementById('camera-btn').style.background = "#ff453a";
                statusText.textContent = "실시간 감지 중";
                statusDot.style.background = "#00d26a";

                requestAnimationFrame(processFrame);
            } catch (err) {
                alert("웹캠 접근 실패: " + err.message + "\\nSafari/Chrome 권한 설정에서 카메라를 허용해주세요.");
                statusText.textContent = "카메라 권한 오류";
                statusDot.style.background = "#ff453a";
            }
        }

        function stopCamera() {
            isRunning = false;
            if (video.srcObject) {
                video.srcObject.getTracks().forEach(track => track.stop());
            }
            document.getElementById('camera-btn').textContent = "카메라 시작";
            document.getElementById('camera-btn').style.background = "#0071e3";
            statusText.textContent = "정지됨";
            statusDot.style.background = "#888";
            ctx.clearRect(0, 0, canvas.width, canvas.height);
        }

        // Temporary canvas to grab frame as JPEG
        const tempCanvas = document.createElement('canvas');
        const tempCtx = tempCanvas.getContext('2d');

        async function processFrame() {
            if (!isRunning) return;

            // Draw webcam frame directly to canvas first
            ctx.drawImage(video, 0, 0, canvas.width, canvas.height);

            if (!isProcessing) {
                isProcessing = true;
                tempCanvas.width = canvas.width;
                tempCanvas.height = canvas.height;
                tempCtx.drawImage(video, 0, 0, canvas.width, canvas.height);

                const dataUrl = tempCanvas.toDataURL('image/jpeg', 0.7);

                fetch('/detect_frame', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ image: dataUrl, conf: currentConf })
                })
                .then(res => res.json())
                .then(data => {
                    renderDetections(data.detections);
                    isProcessing = false;
                })
                .catch(err => {
                    console.error(err);
                    isProcessing = false;
                });
            }

            // Update FPS
            frameCount++;
            const now = performance.now();
            if (now - lastFpsTime >= 1000) {
                fpsVal.textContent = Math.round(frameCount * 1000 / (now - lastFpsTime));
                frameCount = 0;
                lastFpsTime = now;
            }

            requestAnimationFrame(processFrame);
        }

        let latestDetections = [];
        function renderDetections(detections) {
            latestDetections = detections || [];

            // Update tag list
            if (latestDetections.length === 0) {
                tagsContainer.innerHTML = '<span style="color: #666;">감지된 신호등이 없습니다.</span>';
            } else {
                tagsContainer.innerHTML = latestDetections.map(d => {
                    let tagClass = "tag-amber";
                    let emoji = "🟡";
                    if (d.class_id === 0) { tagClass = "tag-red"; emoji = "🔴"; }
                    else if (d.class_id === 1) { tagClass = "tag-green"; emoji = "🟢"; }
                    return `<span class="det-item ${tagClass}">${emoji} ${d.label} ${(d.score * 100).toFixed(1)}%</span>`;
                }).join(' ');
            }
        }

        // Overlay boxes loop
        function drawOverlay() {
            if (isRunning && latestDetections.length > 0) {
                latestDetections.forEach(d => {
                    const [x, y, w, h] = d.box;
                    let strokeColor = "#ff9f0a";
                    let badgeBg = "#ff9f0a";
                    let emoji = "🟡";
                    if (d.class_id === 0) { strokeColor = "#ff453a"; badgeBg = "#ff453a"; emoji = "🔴"; }
                    else if (d.class_id === 1) { strokeColor = "#32d74b"; badgeBg = "#32d74b"; emoji = "🟢"; }

                    // Box
                    ctx.lineWidth = 4;
                    ctx.strokeStyle = strokeColor;
                    ctx.strokeRect(x, y, w, h);

                    // Badge
                    const labelText = `${emoji} ${d.label} ${(d.score * 100).toFixed(0)}%`;
                    ctx.font = 'bold 16px -apple-system, sans-serif';
                    const textWidth = ctx.measureText(labelText).width;

                    ctx.fillStyle = badgeBg;
                    ctx.fillRect(x, Math.max(0, y - 28), textWidth + 14, 28);

                    ctx.fillStyle = "#ffffff";
                    ctx.fillText(labelText, x + 7, Math.max(20, y - 8));
                });
            }
            requestAnimationFrame(drawOverlay);
        }
        drawOverlay();

        // Auto-start camera when page loads
        window.addEventListener('load', () => {
            setTimeout(startCamera, 500);
        });
    </script>
</body>
</html>
"""

@app.route("/")
def index():
    return render_template_string(HTML_PAGE)

@app.route("/detect_frame", methods=["POST"])
def detect_frame():
    try:
        data = request.get_json(force=True)
        image_data = data.get("image", "")
        conf = float(data.get("conf", 0.25))

        if not image_data or not image_data.startswith("data:image"):
            return jsonify({"error": "invalid image"}), 400

        # Decode base64 JPEG
        header, encoded = image_data.split(",", 1)
        image_bytes = base64.b64decode(encoded)
        np_arr = np.frombuffer(image_bytes, np.uint8)
        img_bgr = cv2.imdecode(np_arr, cv2.IMREAD_COLOR)

        if img_bgr is None:
            return jsonify({"error": "could not decode image"}), 400

        detections, _ = detect_objects(img_bgr, conf_threshold=conf)
        return jsonify({"detections": detections})
    except Exception as e:
        return jsonify({"error": str(e)}), 500

if __name__ == "__main__":
    port = 5001
    print("=" * 60)
    print(f"🚦 AmpelPilot 실시간 웹캠 테스트 서버 실행 중!")
    print(f"👉 브라우저를 열고 다음 주소로 접속하세요:")
    print(f"   http://localhost:{port}")
    print("=" * 60)
    app.run(host="0.0.0.0", port=port, debug=False)
