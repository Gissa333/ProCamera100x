package com.procamera.x100;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
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
                if (camera != null) {
                    float zoomRatio = Math.max(1f, progress);
                    try {
                        camera.getCameraControl().setZoomRatio(zoomRatio);
                    } catch (Exception e) {
                        Log.e(TAG, "لا يمكن ضبط التقريب إلى " + zoomRatio, e);
                    }
                    tvZoomLevel.setText(progress + "x");
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnCapture.setOnClickListener(v -> takePhoto());

        btnAIEnhance.setOnClickListener(v ->
                Toast.makeText(this, "تحسين AI قيد التطوير", Toast.LENGTH_SHORT).show());
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
                Log.e(TAG, "فشل تشغيل الكاميرا", e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) return;

        // اسم ملف فريد
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "ProCam_" + timeStamp + ".jpg";

        // مجلد مؤقت داخل التطبيق أولاً
        File tempFile = new File(getExternalFilesDir(null), fileName);

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(tempFile).build();

        imageCapture.takePicture(outputOptions, cameraExecutor,
                new ImageCapture.OnImageSavedCallback() {
            @Override
            public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                // حفظ في المعرض العام
                Uri galleryUri = saveToPublicGallery(tempFile, fileName);

                runOnUiThread(() -> {
                    Bitmap bitmap = BitmapFactory.decodeFile(tempFile.getAbsolutePath());
                    if (bitmap != null) {
                        ivResult.setImageBitmap(bitmap);
                        ivResult.setVisibility(View.VISIBLE);
                    }
                    if (galleryUri != null) {
                        Toast.makeText(MainActivity.this,
                                "تم الحفظ في Pictures/ProCamera100x",
                                Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(MainActivity.this,
                                "فشل الحفظ في المعرض",
                                Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override
            public void onError(@NonNull ImageCaptureException exception) {
                Log.e(TAG, "فشل الالتقاط", exception);
            }
        });
    }

    /**
     * حفظ الصورة في مجلد Pictures/ProCamera100x العام
     */
    private Uri saveToPublicGallery(File sourceFile, String fileName) {
        OutputStream outputStream = null;
        FileInputStream inputStream = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // أندرويد 10 وما فوق - استخدام RELATIVE_PATH
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/ProCamera100x");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            } else {
                // أندرويد 9 وما دون - استخدام DATA (المسار المباشر)
                File picturesDir = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES),
                        "ProCamera100x");
                if (!picturesDir.exists()) {
                    picturesDir.mkdirs();
                }
                File destFile = new File(picturesDir, fileName);
                values.put(MediaStore.Images.Media.DATA, destFile.getAbsolutePath());
            }

            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (uri == null) {
                Log.e(TAG, "insert returned null URI");
                return null;
            }

            outputStream = getContentResolver().openOutputStream(uri);
            inputStream = new FileInputStream(sourceFile);

            if (outputStream == null) return null;

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();

            // إزالة IS_PENDING ليصبح الملف مرئياً
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);
            }

            return uri;

        } catch (IOException e) {
            Log.e(TAG, "فشل حفظ الصورة", e);
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
                Toast.makeText(this, "الصلاحيات مطلوبة",
                        Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }
}
