package com.procamera.x100;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
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

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_PERMISSIONS = 10;
    private static final String TAG = "ProCamera100x";

    private PreviewView previewView;
    private ZoomLinearLayout zoomContainer;
    private SeekBar zoomSeekBar;
    private TextView tvZoomLevel;
    private Button btnCapture, btnAIEnhance;
    private ImageView ivResult;

    private ImageCapture imageCapture;
    private Camera camera;
    private ExecutorService cameraExecutor;
    private Interpreter tflite;
    private boolean modelLoaded = false;

    private float currentZoomLevel = 1.0f;
    private File lastCapturedFile = null;

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

        cameraExecutor = Executors.newSingleThreadExecutor();

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
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnCapture.setOnClickListener(v -> takePhoto());

        btnAIEnhance.setOnClickListener(v -> {
            if (!modelLoaded) {
                Toast.makeText(this, "نموذج AI لم يُحمّل", Toast.LENGTH_SHORT).show();
                return;
            }
            if (lastCapturedFile == null || !lastCapturedFile.exists()) {
                Toast.makeText(this, "التقط صورة أولاً", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "جارٍ التحسين بالذكاء الاصطناعي...", Toast.LENGTH_LONG).show();
            enhanceWithAI(lastCapturedFile);
        });
    }

    private void loadModel() {
        try {
            AssetFileDescriptor fileDescriptor = getAssets().openFd("esrgan.tflite");
            FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
            FileChannel fileChannel = inputStream.getChannel();
            long startOffset = fileDescriptor.getStartOffset();
            long declaredLength = fileDescriptor.getDeclaredLength();

            if (declaredLength <= 0) {
                modelLoaded = false;
                tflite = null;
                return;
            }

            MappedByteBuffer modelBuffer = fileChannel.map(
                    FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);

            Interpreter.Options options = new Interpreter.Options();
            options.setNumThreads(4);
            tflite = new Interpreter(modelBuffer, options);
            modelLoaded = true;
            Log.i(TAG, "AI model loaded: " + declaredLength + " bytes");
        } catch (Throwable e) {
            Log.e(TAG, "AI model not available: " + e.getMessage());
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

                CameraSelector cameraSelector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(
                        this, cameraSelector, preview, imageCapture);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera start failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
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

    private void enhanceWithAI(File sourceFile) {
        new Thread(() -> {
            Bitmap source = null;
            try {
                if (tflite == null) {
                    runOnUiThread(() -> Toast.makeText(this, "AI model not loaded",
                            Toast.LENGTH_SHORT).show());
                    return;
                }

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 4;
                source = BitmapFactory.decodeFile(sourceFile.getAbsolutePath(), opts);
                if (source == null) {
                    runOnUiThread(() -> Toast.makeText(this, "Cannot read image",
                            Toast.LENGTH_SHORT).show());
                    return;
                }

                int[] shape = tflite.getInputTensor(0).shape();
                int inH = shape[1];
                int inW = shape[2];

                int tilesX = Math.max(1, source.getWidth() / inW);
                int tilesY = Math.max(1, source.getHeight() / inH);
                int scale = 4;
                int outW = inW * scale;
                int outH = inH * scale;

                Bitmap result = Bitmap.createBitmap(
                        tilesX * outW, tilesY * outH, Bitmap.Config.ARGB_8888);

                for (int ty = 0; ty < tilesY; ty++) {
                    for (int tx = 0; tx < tilesX; tx++) {
                        int x = tx * inW;
                        int y = ty * inH;
                        int w = Math.min(inW, source.getWidth() - x);
                        int h = Math.min(inH, source.getHeight() - y);
                        if (w <= 0 || h <= 0) continue;

                        Bitmap tile = Bitmap.createBitmap(source, x, y, w, h);
                        Bitmap inputTile = Bitmap.createScaledBitmap(tile, inW, inH, true);

                        ByteBuffer inputBuffer = ByteBuffer.allocateDirect(
                                4 * inW * inH * 3);
                        inputBuffer.order(ByteOrder.nativeOrder());

                        int[] pixels = new int[inW * inH];
                        inputTile.getPixels(pixels, 0, inW, 0, 0, inW, inH);

                        for (int px : pixels) {
                            float r = ((px >> 16) & 0xFF) / 255.0f;
                            float g = ((px >> 8) & 0xFF) / 255.0f;
                            float b = (px & 0xFF) / 255.0f;
                            inputBuffer.putFloat(r);
                            inputBuffer.putFloat(g);
                            inputBuffer.putFloat(b);
                        }
                        inputBuffer.rewind();

                        ByteBuffer outputBuffer = ByteBuffer.allocateDirect(
                                4 * outW * outH * 3);
                        outputBuffer.order(ByteOrder.nativeOrder());

                        tflite.run(inputBuffer, outputBuffer);

                        outputBuffer.rewind();
                        int[] outPixels = new int[outW * outH];
                        for (int i = 0; i < outW * outH; i++) {
                            float r = outputBuffer.getFloat();
                            float g = outputBuffer.getFloat();
                            float b = outputBuffer.getFloat();
                            int ri = Math.min(255, Math.max(0, (int) (r * 255)));
                            int gi = Math.min(255, Math.max(0, (int) (g * 255)));
                            int bi = Math.min(255, Math.max(0, (int) (b * 255)));
                            outPixels[i] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
                        }

                        Bitmap outputTile = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
                        outputTile.setPixels(outPixels, 0, outW, 0, 0, outW, outH);

                        int destX = tx * outW;
                        int destY = ty * outH;
                        for (int py = 0; py < outH; py++) {
                            for (int px = 0; px < outW; px++) {
                                if (destX + px < result.getWidth() && destY + py < result.getHeight()) {
                                    result.setPixel(destX + px, destY + py, outputTile.getPixel(px, py));
                                }
                            }
                        }

                        inputTile.recycle();
                        outputTile.recycle();
                        tile.recycle();
                    }
                }

                String enhancedName = "enhanced_" + sourceFile.getName();
                File enhancedFile = new File(getExternalFilesDir(null), enhancedName);
                try (FileOutputStream fos = new FileOutputStream(enhancedFile)) {
                    result.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                }

                saveToPublicGallery(enhancedFile, enhancedName);

                Bitmap finalResult = result;
                runOnUiThread(() -> {
                    ivResult.setImageBitmap(finalResult);
                    ivResult.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "تم تحسين الصورة بالذكاء الاصطناعي", Toast.LENGTH_LONG).show();
                });

            } catch (Throwable e) {
                Log.e(TAG, "Enhance failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "فشل التحسين: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (source != null) source.recycle();
            }
        }).start();
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
            if (tflite != null) tflite.close();
        } catch (Throwable ignored) {}
    }
}
