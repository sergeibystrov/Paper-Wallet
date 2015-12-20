/*
 * Basic no frills app which integrates the ZBar barcode scanner with
 * the camera.
 * 
 * Created by lisah0 on 2012-02-24
 */
package ru.valle.btc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.Camera.AutoFocusCallback;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Camera.Size;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import net.sourceforge.zbar.Config;
import net.sourceforge.zbar.Image;
import net.sourceforge.zbar.ImageScanner;
import net.sourceforge.zbar.Symbol;
import net.sourceforge.zbar.SymbolSet;

import java.security.MessageDigest;

@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements ActivityCompat.OnRequestPermissionsResultCallback {
    private static final String TAG = "CameraTestActivity";
    private static final int REQUEST_CAMERA_PERMISSION = 1;
    @Nullable
    private Camera camera;
    @Nullable
    private ImageScanner scanner;

    static {
        System.loadLibrary("iconv");
    }

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }
        if (Build.VERSION.SDK_INT < 23 || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            createCameraSource();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
        }
    }

    private void createCameraSource() {
        if (camera == null) {
            try {
                camera = Camera.open();
                if (camera == null) {
                    throw new RuntimeException(getString(R.string.unable_open_camera));
                }
                scanner = new ImageScanner();
                scanner.setConfig(0, Config.X_DENSITY, 3);
                scanner.setConfig(0, Config.Y_DENSITY, 3);
                setContentView(new CameraPreview(this, camera, previewCallback, autoFocusCallback));
            } catch (Exception e) {
                Log.e(TAG, getString(R.string.unable_open_camera), e);
                setResult(RESULT_CANCELED);
                finish();
            }
        }
    }

    @SuppressLint("Override")
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            createCameraSource();
        } else {
            new AlertDialog.Builder(this).setMessage(R.string.no_camera_permission_granted)
                    .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            finish();
                        }
                    }).show();
        }
    }

    private final PreviewCallback previewCallback = new PreviewCallback() {
        @SuppressWarnings("deprecation")
        public void onPreviewFrame(byte[] data, Camera camera) {
            Size size = camera.getParameters().getPreviewSize();
            if (size != null && scanner != null) {
                Image barcode = new Image(size.width, size.height, "Y800");
                barcode.setData(data);
                int result = scanner.scanImage(barcode);
                if (result != 0) {
                    SymbolSet syms = scanner.getResults();
                    for (Symbol sym : syms) {
                        String scannedData = sym.getData();
                        boolean validInput = !TextUtils.isEmpty(scannedData) && scannedData.startsWith("bitcoin:");
                        if (!validInput) {
                            byte[] decodedEntity = BTCUtils.decodeBase58(scannedData);
                            validInput = decodedEntity != null && BTCUtils.verifyChecksum(decodedEntity);
                            if (!validInput && decodedEntity != null && scannedData.startsWith("S")) {
                                try {
                                    validInput = MessageDigest.getInstance("SHA-256").digest((scannedData + '?').getBytes("UTF-8"))[0] == 0;
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        if (validInput) {
                            camera.setPreviewCallback(null);
                            camera.stopPreview();
                            releaseCamera();
                            setResult(RESULT_OK, new Intent().putExtra("data", scannedData));
                            finish();
                            return;
                        }
                    }
                }
            }
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
    }

    private void releaseCamera() {
        if (camera != null) {
            camera.setPreviewCallback(null);
            camera.release();
            if (scanner != null) {
                scanner.destroy();
            }
            camera = null;
        }
    }

    private final Handler handler = new Handler();

    private final AutoFocusCallback autoFocusCallback = new AutoFocusCallback() {
        public void onAutoFocus(boolean success, Camera camera) {
            handler.postDelayed(doAutoFocus, 1000);
        }
    };

    private final Runnable doAutoFocus = new Runnable() {
        public void run() {
            if (camera != null) {
                try {
                    camera.autoFocus(autoFocusCallback);
                } catch (Exception e) {
                    Log.w(TAG, "autofocus", e);
                    handler.postDelayed(this, 1000);
                }
            }
        }
    };

}
