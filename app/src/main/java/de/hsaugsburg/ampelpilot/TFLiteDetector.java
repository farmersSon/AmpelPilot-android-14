package de.hsaugsburg.ampelpilot;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.RectF;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import org.tensorflow.lite.Interpreter;

/**
 * TensorFlow Lite object detection model wrapper for pedestrian traffic light detection.
 *
 * Wraps a model trained with the TensorFlow Object Detection API (SSD MobileNet format).
 * Model, labels and configuration originate from the AmpelPilot project by strator1:
 * https://github.com/strator1/Ampel-Pilot-for-Android-new-
 */
public class TFLiteDetector implements Classifier {

    // Only return this many results.
    private static final int NUM_DETECTIONS = 10;
    private boolean isModelQuantized;
    // Normalization constants
    private static final float SSD_IMAGE_MEAN = 128.0f;
    private static final float SSD_IMAGE_STD = 128.0f;
    private static final int NUM_THREADS = 4;

    private int inputSize;
    private final Vector<String> labels = new Vector<>();
    private int[] intValues;

    // [FEATURE: YOLOV8_TFLITE_SUPPORT]
    // Model architecture auto-detection
    private boolean isYoloModel = false;
    private boolean isYoloChannelsFirst = true; // [1, 4+C, 8400] vs [1, 8400, 4+C]
    private int yoloNumBoxes = 8400;
    private int yoloNumChannels = 6;
    private float[][][] yoloOutput;
    private static final float YOLO_CONFIDENCE_THRESHOLD = 0.25f;
    private static final float YOLO_IOU_THRESHOLD = 0.45f;

    // SSD MobileNet output containers
    private float[][][] outputLocations;
    private float[][] outputClasses;
    private float[][] outputScores;
    private float[] numDetections;

    private ByteBuffer imgData;
    private Interpreter tfLite;

    private TFLiteDetector() {}

    /** Memory-map the model file in Assets. */
    private static MappedByteBuffer loadModelFile(AssetManager assets, String modelFilename)
            throws IOException {
        AssetFileDescriptor fileDescriptor = assets.openFd(modelFilename);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    /**
     * Initializes a TensorFlow Lite session for detecting traffic lights.
     * Auto-detects whether the model is SSD MobileNet or YOLOv8 based on tensor structure.
     */
    public static Classifier create(
            final AssetManager assetManager,
            final String modelFilename,
            final String labelFilename,
            final int defaultInputSize,
            final boolean isQuantized)
            throws IOException {
        final TFLiteDetector d = new TFLiteDetector();

        InputStream labelsInput = assetManager.open(labelFilename);
        BufferedReader br = new BufferedReader(new InputStreamReader(labelsInput));
        String line;
        while ((line = br.readLine()) != null) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                d.labels.add(trimmed);
            }
        }
        br.close();

        try {
            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(NUM_THREADS);
            d.tfLite = new Interpreter(loadModelFile(assetManager, modelFilename), options);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // [FEATURE: YOLOV8_TFLITE_SUPPORT]
        // Dynamically inspect model tensor input shape
        try {
            int[] inputShape = d.tfLite.getInputTensor(0).shape();
            if (inputShape != null && inputShape.length >= 3 && inputShape[1] > 0) {
                d.inputSize = inputShape[1]; // Typically 300 for SSD, 640 for YOLO
            } else {
                d.inputSize = defaultInputSize;
            }
        } catch (Exception e) {
            d.inputSize = defaultInputSize;
        }

        d.isModelQuantized = isQuantized;
        int numBytesPerChannel = isQuantized ? 1 : 4;
        d.imgData = ByteBuffer.allocateDirect(d.inputSize * d.inputSize * 3 * numBytesPerChannel);
        d.imgData.order(ByteOrder.nativeOrder());
        d.intValues = new int[d.inputSize * d.inputSize];

        // [FEATURE: YOLOV8_TFLITE_SUPPORT]
        // Inspect output tensors to auto-detect architecture (YOLOv8 vs SSD MobileNet)
        int outputCount = d.tfLite.getOutputTensorCount();
        if (outputCount == 1) {
            // YOLOv8 architecture (single output tensor)
            d.isYoloModel = true;
            int[] outShape = d.tfLite.getOutputTensor(0).shape();
            if (outShape.length == 3) {
                if (outShape[1] < outShape[2]) {
                    // Standard Ultralytics shape: [1, 4+classes, 8400]
                    d.isYoloChannelsFirst = true;
                    d.yoloNumChannels = outShape[1];
                    d.yoloNumBoxes = outShape[2];
                    d.yoloOutput = new float[1][d.yoloNumChannels][d.yoloNumBoxes];
                } else {
                    // Transposed shape: [1, 8400, 4+classes]
                    d.isYoloChannelsFirst = false;
                    d.yoloNumBoxes = outShape[1];
                    d.yoloNumChannels = outShape[2];
                    d.yoloOutput = new float[1][d.yoloNumBoxes][d.yoloNumChannels];
                }
            }
        } else {
            // Legacy SSD MobileNet architecture (4 output tensors)
            d.isYoloModel = false;
            d.outputLocations = new float[1][NUM_DETECTIONS][4];
            d.outputClasses = new float[1][NUM_DETECTIONS];
            d.outputScores = new float[1][NUM_DETECTIONS];
            d.numDetections = new float[1];
        }

        return d;
    }

    public int getInputSize() {
        return inputSize;
    }

    public boolean isYolo() {
        return isYoloModel;
    }

    @Override
    public List<Recognition> recognizeImage(final Bitmap bitmap) {
        // Preprocess: convert bitmap pixels to normalized float buffer
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        imgData.rewind();
        for (int i = 0; i < inputSize; ++i) {
            for (int j = 0; j < inputSize; ++j) {
                int pixelValue = intValues[i * inputSize + j];
                int r = (pixelValue >> 16) & 0xFF;
                int g = (pixelValue >> 8) & 0xFF;
                int b = pixelValue & 0xFF;

                if (isModelQuantized) {
                    imgData.put((byte) r);
                    imgData.put((byte) g);
                    imgData.put((byte) b);
                } else if (isYoloModel) {
                    // YOLO normalizes RGB directly to [0.0, 1.0]
                    imgData.putFloat(r / 255.0f);
                    imgData.putFloat(g / 255.0f);
                    imgData.putFloat(b / 255.0f);
                } else {
                    // SSD MobileNet normalizes to [-1.0, 1.0]
                    imgData.putFloat((r - SSD_IMAGE_MEAN) / SSD_IMAGE_STD);
                    imgData.putFloat((g - SSD_IMAGE_MEAN) / SSD_IMAGE_STD);
                    imgData.putFloat((b - SSD_IMAGE_MEAN) / SSD_IMAGE_STD);
                }
            }
        }

        if (isYoloModel) {
            return runYoloInference();
        } else {
            return runSsdInference();
        }
    }

    /** Safely resolves class label whether labelmap includes a background token or not */
    private String getLabelName(int classId, boolean isSsd) {
        if (labels.isEmpty()) return "unknown";
        boolean hasBackgroundToken = labels.get(0).equals("???") || labels.get(0).equalsIgnoreCase("background");
        int index = classId;
        if (hasBackgroundToken) {
            index = classId + 1;
        }
        if (index >= 0 && index < labels.size()) {
            return labels.get(index);
        }
        return "class_" + classId;
    }

    /** Run inference and decode outputs for legacy SSD MobileNet models */
    private List<Recognition> runSsdInference() {
        outputLocations = new float[1][NUM_DETECTIONS][4];
        outputClasses = new float[1][NUM_DETECTIONS];
        outputScores = new float[1][NUM_DETECTIONS];
        numDetections = new float[1];

        Object[] inputArray = {imgData};
        Map<Integer, Object> outputMap = new HashMap<>();
        outputMap.put(0, outputLocations);
        outputMap.put(1, outputClasses);
        outputMap.put(2, outputScores);
        outputMap.put(3, numDetections);

        tfLite.runForMultipleInputsOutputs(inputArray, outputMap);

        final ArrayList<Recognition> recognitions = new ArrayList<>(NUM_DETECTIONS);
        for (int i = 0; i < NUM_DETECTIONS; ++i) {
            final RectF detection = new RectF(
                    outputLocations[0][i][1] * inputSize,
                    outputLocations[0][i][0] * inputSize,
                    outputLocations[0][i][3] * inputSize,
                    outputLocations[0][i][2] * inputSize);
            int classId = (int) outputClasses[0][i];
            String title = getLabelName(classId, true);
            recognitions.add(new Recognition(
                    "" + i, title, outputScores[0][i], detection));
        }
        return recognitions;
    }

    // [FEATURE: YOLOV8_TFLITE_SUPPORT]
    /** Run inference and decode candidate boxes with NMS for YOLOv8 models */
    private List<Recognition> runYoloInference() {
        Object[] inputArray = {imgData};
        Map<Integer, Object> outputMap = new HashMap<>();
        outputMap.put(0, yoloOutput);

        tfLite.runForMultipleInputsOutputs(inputArray, outputMap);

        int numClasses = yoloNumChannels - 4;
        List<Recognition> candidates = new ArrayList<>();

        for (int i = 0; i < yoloNumBoxes; ++i) {
            float cx, cy, w, h;
            float maxScore = -1f;
            int maxClassId = -1;

            if (isYoloChannelsFirst) {
                cx = yoloOutput[0][0][i];
                cy = yoloOutput[0][1][i];
                w = yoloOutput[0][2][i];
                h = yoloOutput[0][3][i];
                for (int c = 0; c < numClasses; ++c) {
                    float score = yoloOutput[0][4 + c][i];
                    if (score > maxScore) {
                        maxScore = score;
                        maxClassId = c;
                    }
                }
            } else {
                cx = yoloOutput[0][i][0];
                cy = yoloOutput[0][i][1];
                w = yoloOutput[0][i][2];
                h = yoloOutput[0][i][3];
                for (int c = 0; c < numClasses; ++c) {
                    float score = yoloOutput[0][i][4 + c];
                    if (score > maxScore) {
                        maxScore = score;
                        maxClassId = c;
                    }
                }
            }

            if (maxScore >= YOLO_CONFIDENCE_THRESHOLD && maxClassId >= 0) {
                // If coordinates are normalized [0, 1], scale to inputSize
                if (cx <= 1.0f && w <= 1.0f) {
                    cx *= inputSize;
                    cy *= inputSize;
                    w *= inputSize;
                    h *= inputSize;
                }
                float left = Math.max(0, cx - w / 2.0f);
                float top = Math.max(0, cy - h / 2.0f);
                float right = Math.min(inputSize, cx + w / 2.0f);
                float bottom = Math.min(inputSize, cy + h / 2.0f);

                String title = getLabelName(maxClassId, false);
                candidates.add(new Recognition(
                        "" + i, title, maxScore, new RectF(left, top, right, bottom)));
            }
        }

        // Apply Non-Maximum Suppression (NMS) to eliminate overlapping boxes
        return applyNms(candidates, YOLO_IOU_THRESHOLD, NUM_DETECTIONS);
    }

    // [FEATURE: YOLOV8_TFLITE_SUPPORT]
    /** NMS (Non-Maximum Suppression) implementation */
    private List<Recognition> applyNms(List<Recognition> boxes, float iouThreshold, int maxDetections) {
        // Sort descending by confidence score
        boxes.sort((a, b) -> Float.compare(b.getConfidence(), a.getConfidence()));

        List<Recognition> selected = new ArrayList<>();
        boolean[] suppressed = new boolean[boxes.size()];

        for (int i = 0; i < boxes.size(); i++) {
            if (suppressed[i]) continue;
            Recognition a = boxes.get(i);
            selected.add(a);
            if (selected.size() >= maxDetections) break;

            RectF locA = a.getLocation();
            for (int j = i + 1; j < boxes.size(); j++) {
                if (suppressed[j]) continue;
                Recognition b = boxes.get(j);
                if (calculateIoU(locA, b.getLocation()) > iouThreshold) {
                    suppressed[j] = true;
                }
            }
        }
        return selected;
    }

    /** Calculate Intersection over Union (IoU) between two bounding boxes */
    private float calculateIoU(RectF a, RectF b) {
        float interLeft = Math.max(a.left, b.left);
        float interTop = Math.max(a.top, b.top);
        float interRight = Math.min(a.right, b.right);
        float interBottom = Math.min(a.bottom, b.bottom);

        float interArea = Math.max(0, interRight - interLeft) * Math.max(0, interBottom - interTop);
        float areaA = (a.right - a.left) * (a.bottom - a.top);
        float areaB = (b.right - b.left) * (b.bottom - b.top);

        float unionArea = areaA + areaB - interArea;
        return unionArea <= 0 ? 0 : interArea / unionArea;
    }

    @Override
    public void close() {
        if (tfLite != null) {
            tfLite.close();
            tfLite = null;
        }
    }
}
