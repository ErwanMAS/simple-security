package co.aspyrx.android.simplesecurity;

import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Environment;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.squareup.okhttp.OkHttpClient;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import retrofit.RestAdapter;
import retrofit.client.OkClient;
import retrofit.client.Response;
import retrofit.mime.TypedFile;

public class CameraView implements SurfaceHolder.Callback {
    private static final String LOG_TAG = MainActivity.class.getSimpleName();
    private static final int PIXEL_VALUE_DIFFERENCE_THRESHOLD = 32;
    private static final int PERCENT_DIFFERENT_PIXELS_THRESHOLD = 10;
    private static final long MS_MIN_TIME_BETWEEN_MOTION_TRIGGERS = 2000;
    private static final int JPEG_IMAGE_QUALITY = 85;
    private static UploadService uploadService = new RestAdapter.Builder()
            .setEndpoint("http://security.aspyrx.co")
            .setClient(new OkClient(new OkHttpClient()))
            .build()
            .create(UploadService.class);
    private Activity mActivity;
    private SurfaceHolder mSurfaceHolder;
    private Camera mCamera;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private byte[] lastPreviewFrame;
    private Date lastMotionFrameDate = new Date(0);

    private Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(final byte[] data, final Camera camera) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    if (lastPreviewFrame != null) {
                        int diff_count = 0;
                        for (int i = 0; i < data.length; i++) {
                            int diff = Math.abs(data[i] - lastPreviewFrame[i]);
                            if (diff > PIXEL_VALUE_DIFFERENCE_THRESHOLD) {
                                diff_count++;
                            }
                        }

                        if (diff_count > data.length / 100 * PERCENT_DIFFERENT_PIXELS_THRESHOLD) {
                            Date now = new Date();
                            if (now.getTime() - lastMotionFrameDate.getTime() > MS_MIN_TIME_BETWEEN_MOTION_TRIGGERS) {
                                savePreviewAsJpeg(lastPreviewFrame, camera);
                                lastMotionFrameDate = now;
                            }
                        }
                    }

                    lastPreviewFrame = data;
                }
            });
        }
    };

    public CameraView(Activity activity, SurfaceView surfaceView) {
        mActivity = activity;
        mSurfaceHolder = surfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
        TelephonyManager tm = (TelephonyManager) activity.getSystemService(Context.TELEPHONY_SERVICE);
        final String tmDevice, tmSerial, androidId;
        tmDevice = "" + tm.getDeviceId();
        tmSerial = "" + tm.getSimSerialNumber();
        androidId = "" + android.provider.Settings.Secure.getString(activity.getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
    }

    /**
     * Create a File for saving an image
     */
    private static File getOutputImageFile() {
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES), "Simple Security");

            // Create the storage directory if it does not exist
            if (!mediaStorageDir.exists()) {
                if (!mediaStorageDir.mkdirs()) {
                    Log.d(LOG_TAG, "failed to create directory");
                    return null;
                }
            }

            // Create an image file name
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            return new File(mediaStorageDir.getPath() + File.separator +
                    "IMG_" + timeStamp + ".jpg");
        }

        return null;
    }

    public void savePreviewAsJpeg(byte[] data, Camera camera) {
        Camera.Size size = camera.getParameters().getPreviewSize();
        YuvImage image = new YuvImage(data, ImageFormat.NV21, size.width, size.height, null);

        File pictureFile = getOutputImageFile();
        if (pictureFile == null) {
            Log.d(LOG_TAG, "failed to create media file");
        } else {
            try {
                FileOutputStream fos = new FileOutputStream(pictureFile);
                image.compressToJpeg(new Rect(0, 0, size.width, size.height), JPEG_IMAGE_QUALITY, fos);
                fos.close();

                TypedFile photo = new TypedFile("multipart/form-data", pictureFile);
                Response response = uploadService.uploadPhoto(photo);
                Log.d(LOG_TAG, "upload: " + response.getStatus());
            } catch (FileNotFoundException e) {
                Log.d(LOG_TAG, "File not found: ", e);
            } catch (IOException e) {
                Log.d(LOG_TAG, "Error accessing file: ", e);
            }
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        initCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(0, info);
        int rotation = mActivity.getWindowManager().getDefaultDisplay()
                .getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }

        mCamera.setDisplayOrientation(result);

        Camera.Parameters parameters = mCamera.getParameters();

        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_EDOF);
        parameters.setPreviewFormat(ImageFormat.NV21);

        // Set preview FPS to lowest possible FPS to improve performance and reduce strain
        List<int[]> fpsRanges = parameters.getSupportedPreviewFpsRange();
        int[] fpsRange = fpsRanges.get(fpsRanges.size() - 1);
        parameters.setPreviewFpsRange(fpsRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX], fpsRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX]);

        Camera.Size previewSize = getOptimalPreviewSize(parameters.getSupportedPreviewSizes(), width, height);
        parameters.setPreviewSize(previewSize.width, previewSize.height);

        mCamera.setParameters(parameters);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        releaseCamera();
    }

    public void releaseCamera() {
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    public void stopRecording() {
        if (mCamera != null) {
            mCamera.setPreviewCallback(null);
        }
    }

    public boolean startRecording() {
        if (mCamera == null) {
            return false;
        }

        mCamera.setPreviewCallback(previewCallback);

        return true;
    }

    private void initCamera() {
        mCamera = Camera.open(0);

        try {
            mCamera.setPreviewDisplay(mSurfaceHolder);
        } catch (IOException e) {
            Log.d(LOG_TAG, "Error setting camera preview display", e);
        }

        mCamera.startPreview();
    }

    private Camera.Size getOptimalPreviewSize(List<Camera.Size> sizes, int w, int h) {
        final double ASPECT_TOLERANCE = 0.1;
        double targetRatio = (double) h / w;

        if (sizes == null) return null;

        Camera.Size optimalSize = null;
        double minDiff = Double.MAX_VALUE;

        for (Camera.Size size : sizes) {
            double ratio = (double) size.width / size.height;
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) continue;
            if (Math.abs(size.height - h) < minDiff) {
                optimalSize = size;
                minDiff = Math.abs(size.height - h);
            }
        }

        if (optimalSize == null) {
            minDiff = Double.MAX_VALUE;
            for (Camera.Size size : sizes) {
                if (Math.abs(size.height - h) < minDiff) {
                    optimalSize = size;
                    minDiff = Math.abs(size.height - h);
                }
            }
        }
        return optimalSize;
    }
}
