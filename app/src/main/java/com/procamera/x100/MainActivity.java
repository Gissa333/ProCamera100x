package com.procamera.x100;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String TAG = "ProCamera100x";
    private static final int MODEL_INPUT_SIZE = 50;
    private static final int MODEL_OUTPUT_SIZE = 200;
    private static final long LIVE_FRAME_INTERVAL_MS = 100; // 10 FPS

    private PreviewView previewView;
    private ZoomLinearLayout zoomContainer;
    private SeekBar zoomSeekBar;
    private TextView tvZoomLevel;
    private Button btnCapture, btnAIEnhance;
    private ImageView ivResult;
    private ImageView ivAiOverlay;

    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private Camera camera;
    private ExecutorService cameraExecutor;
    private ExecutorService analysisExecutor;

    private Interpreter tflite;
    private boolean modelLoaded = false;

    private float currentZoomLevel = 1.0f;
    private File lastCapturedFile = null;

    private final AtomicBoolean isProcessingFrame = new AtomicBoolean(false);
    private volatile long lastFrameTime = 0L;
    private volatile boolean liveAiEnabled = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.previewView);
        zoomContainer = findViewById(R.id.zoomContainer);
        zoomSeekBar = findViewById(R.id.zoomSeekBar);
        tvZoomLevel = findViewById(R.id.tvZoomLevel);
        btnCapture = findViewById(R.id.btnCapture);
        btnAIEnhance = findViewById(R.id.btnAIEnhance);
        ivResult = findViewById(R.id.ivResult);
        ivAiOverlay = findViewById(R.id.ivAiOverlay);

        cameraExecutor = Executors.newSingleThreadExecutor();
        analysisExecutor = Executors.newSingleThreadExecutor();

        loadModel();

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.CAMERA,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                    },
                    REQUEST_CODE_PERMISSIONS);
        }

        zoomSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentZoomLevel = Math.max(1f, progress);
                if (camera != null) {
                    try {
                        camera.getCameraControl().setZoomRatio(currentZoomLevel);
                    } catch (Exception e) {
                        Log.e(TAG, "Cannot set zoom", e);
                    }
                    tvZoomLevel.setText(progress + "x");
                    updateOverlayAlpha();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnCapture.setOnClickListener(v -> takePhoto());

        btnAIEnhance.setOnClickListener(v -> {
            liveAiEnabled = !liveAiEnabled;
            updateOverlayAlpha();
            Toast.makeText(this,
                    liveAiEnabled ? "AI مباشر: مفعل" : "AI مباشر: معطل",
                    Toast.LENGTH_SHORT).show();
        });

        updateOverlayAlpha();
    }

    private void updateOverlayAlpha() {
        if (ivAiOverlay == null) return;
        if (!liveAiEnabled || !modelLoaded) {
            ivAiOverlay.setAlpha(0f);
            return;
        }
        // كل ما زاد التقريب، زادت شفافية طبقة AI
        float alpha;
        if (currentZoomLevel < 5f) {
            alpha = 0f;
        } else if (currentZoomLevel < 20f) {
            alpha = 0.4f;
        } else if (currentZoomLevel < 50f) {
            alpha = 0.7f;
        } else {
            alpha = 1.0f;
        }
        ivAiOverlay.setAlpha(alpha);
    }

    private void loadModel() {
        try {
            AssetFileDescriptor fd = getAssets().openFd("esrgan.tflite");
            FileInputStream is = new FileInputStream(fd.getFileDescriptor());
            FileChannel fc = is.getChannel();
            long startOffset = fd.getStartOffset();
            long declaredLength = fd.getDeclaredLength();

            if (declaredLength <= 0) {
                modelLoaded = false;
                return;
            }

            MappedByteBuffer buffer = fc.map(FileChannel.MapMode.READ_ONLY,
                    startOffset, declaredLength);

            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            tflite = new Interpreter(buffer, options);
            modelLoaded = true;
            Log.i(TAG, "Model loaded: " + declaredLength + " bytes");
        } catch (Throwable t) {
            Log.e(TAG, "Model load failed", t);
            modelLoaded = false;
            tflite = null;
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .build();

                if (modelLoaded) {
                    imageAnalysis = new ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build();
                    imageAnalysis.setAnalyzer(analysisExecutor, this::analyzeFrame);
                }

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                cameraProvider.unbindAll();

                if (imageAnalysis != null) {
                    camera = cameraProvider.bindToLifecycle(
                            this, cameraSelector, preview, imageCapture, imageAnalysis);
                } else {
                    camera = cameraProvider.bindToLifecycle(
                            this, cameraSelector, preview, imageCapture);
                }

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera start failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        long now = System.currentTimeMillis();
        if (now - lastFrameTime < LIVE_FRAME_INTERVAL_MS) {
            imageProxy.close();
            return;
        }
        lastFrameTime = now;

        if (!modelLoaded || tflite == null || !liveAiEnabled) {
            imageProxy.close();
            return;
        }

        if (!isProcessingFrame.compareAndSet(false, true)) {
            imageProxy.close();
            return;
        }

        Bitmap input = null;
        Bitmap rotated = null;
        try {
            input = imageProxyToBitmap(imageProxy);
            if (input == null) return;

            // تصغير الإطار ليكون مقاسه مناسباً للنموذج
            int tileSize = MODEL_INPUT_SIZE;
            Bitmap tile = Bitmap.createScaledBitmap(input, tileSize, tileSize, true);

            ByteBuffer inputBuffer = ByteBuffer.allocateDirect(
                    4 * tileSize * tileSize * 3);
            inputBuffer.order(ByteOrder.nativeOrder());

            int[] pixels = new int[tileSize * tileSize];
            tile.getPixels(pixels, 0, tileSize, 0, 0, tileSize, tileSize);

            for (int px : pixels) {
                inputBuffer.putFloat(((px >> 16) & 0xFF) / 255.0f);
                inputBuffer.putFloat(((px >> 8) & 0xFF) / 255.0f);
                inputBuffer.putFloat((px & 0xFF) / 255.0f);
            }
            inputBuffer.rewind();

            int outSize = MODEL_OUTPUT_SIZE;
            ByteBuffer outputBuffer = ByteBuffer.allocateDirect(
                    4 * outSize * outSize * 3);
            outputBuffer.order(ByteOrder.nativeOrder());

            tflite.run(inputBuffer, outputBuffer);

            outputBuffer.rewind();
            int[] outPixels = new int[outSize * outSize];
            for (int i = 0; i < outSize * outSize; i++) {
                float r = outputBuffer.getFloat();
                float g = outputBuffer.getFloat();
                float b = outputBuffer.getFloat();
                int ri = Math.min(255, Math.max(0, (int) (r * 255)));
                int gi = Math.min(255, Math.max(0, (int) (g * 255)));
                int bi = Math.min(255, Math.max(0, (int) (b * 255)));
                outPixels[i] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
            }

            Bitmap outputBitmap = Bitmap.createBitmap(
                    outSize, outSize, Bitmap.Config.ARGB_8888);
            outputBitmap.setPixels(outPixels, 0, outSize, 0, 0, outSize, outSize);

            runOnUiThread(() -> {
                ivAiOverlay.setImageBitmap(outputBitmap);
                ivAiOverlay.setVisibility(View.VISIBLE);
            });

            tile.recycle();
        } catch (Throwable t) {
            Log.e(TAG, "Frame analysis failed", t);
        } finally {
            if (input != null) input.recycle();
            if (rotated != null) rotated.recycle();
            isProcessingFrame.set(false);
            imageProxy.close();
        }
    }

    private Bitmap imageProxyToBitmap(ImageProxy image) {
        try {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);

            if (bitmap != null && image.getImageInfo().getRotationDegrees() != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(image.getImageInfo().getRotationDegrees());
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) bitmap.recycle();
                return rotated;
            }
            return bitmap;
        } catch (Throwable t) {
            Log.e(TAG, "ImageProxy decode failed", t);
            return null;
        }
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "ProCam_" + timeStamp + ".jpg";

        File tempFile = new File(getExternalFilesDir(null), fileName);
        lastCapturedFile = tempFile;

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(tempFile).build();

        imageCapture.takePicture(outputOptions, cameraExecutor,
                new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                File finalFile = tempFile;
                try {
                    if (currentZoomLevel > 1.0f) {
                        File zoomed = applyDigitalZoom(tempFile, currentZoomLevel, fileName);
                        if (zoomed != null) {
                            finalFile = zoomed;
                            lastCapturedFile = zoomed;
                        }
                    }

                    Uri galleryUri = saveToPublicGallery(finalFile, fileName);
                    final File displayFile = finalFile;

                    runOnUiThread(() -> {
                        Bitmap bitmap = BitmapFactory.decodeFile(displayFile.getAbsolutePath());
                        if (bitmap != null) {
                            ivResult.setImageBitmap(bitmap);
                            ivResult.setVisibility(View.VISIBLE);
                        }
                        if (galleryUri != null) {
                            Toast.makeText(MainActivity.this,
                                    "تم الحفظ في Pictures/ProCamera100x",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "Save failed", t);
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "Capture failed", exception);
            }
        });
    }

    private File applyDigitalZoom(File sourceFile, float zoomLevel, String fileName) {
        Bitmap source = null;
        Bitmap cropped = null;
        Bitmap scaled = null;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            source = BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), opts);
            if (source == null) return sourceFile;

            int srcW = source.getWidth();
            int srcH = source.getHeight();

            float cropFactor;
            if (zoomLevel <= 10f) {
                cropFactor = 1.0f / (1.0f + (zoomLevel - 1.0f) * 0.1f);
            } else {
                cropFactor = 10f / zoomLevel;
            }

            int newW = (int) (srcW * cropFactor);
            int newH = (int) (srcH * cropFactor);
            if (newW < 100) newW = 100;
            if (newH < 100) newH = 100;
            if (newW > srcW) newW = srcW;
            if (newH > srcH) newH = srcH;

            int startX = Math.max(0, (srcW - newW) / 2);
            int startY = Math.max(0, (srcH - newH) / 2);

            cropped = Bitmap.createBitmap(source, startX, startY, newW, newH);
            scaled = Bitmap.createScaledBitmap(cropped, newW * 2, newH * 2, true);

            File zoomedFile = new File(getExternalFilesDir(null), "zoomed_" + fileName);
            try (FileOutputStream fos = new FileOutputStream(zoomedFile)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 95, fos);
            }
            return zoomedFile;
        } catch (Throwable e) {
            Log.e(TAG, "Digital zoom failed", e);
            return sourceFile;
        } finally {
            try {
                if (cropped != null && cropped != source) cropped.recycle();
                if (scaled != null) scaled.recycle();
                if (source != null) source.recycle();
            } catch (Throwable ignored) {}
        }
    }

    private Uri saveToPublicGallery(File sourceFile, String fileName) {
        java.io.OutputStream outputStream = null;
        FileInputStream inputStream = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/ProCamera100x");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            } else {
                File picturesDir = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES),
                        "ProCamera100x");
                if (!picturesDir.exists()) picturesDir.mkdirs();
                File destFile = new File(picturesDir, fileName);
                values.put(MediaStore.Images.Media.DATA, destFile.getAbsolutePath());
            }

            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (uri == null) return null;

            outputStream = getContentResolver().openOutputStream(uri);
            inputStream = new FileInputStream(sourceFile);

            if (outputStream == null) return null;

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
            }

            return uri;

        } catch (Throwable e) {
            Log.e(TAG, "Gallery save failed", e);
            return null;
        } finally {
            try {
                if (outputStream != null) outputStream.close();
                if (inputStream != null) inputStream.close();
            } catch (IOException ignored) {}
        }
    }

    private boolean allPermissionsGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "الصلاحيات مطلوبة", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            cameraExecutor.shutdown();
            analysisExecutor.shutdown();
            if (tflite != null) tflite.close();
        } catch (Throwable ignored) {}
    }
}
