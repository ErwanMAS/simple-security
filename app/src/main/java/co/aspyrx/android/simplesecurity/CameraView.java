package co.aspyrx.android.simplesecurity;

import android.app.Activity;
import android.hardware.Camera;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraView implements SurfaceHolder.Callback {
    private static final String LOG_TAG = MainActivity.class.getSimpleName();
    private static final int PIXEL_VALUE_DIFFERENCE_THRESHOLD = 32;
    private static final int PERCENT_DIFFERENT_PIXELS_THRESHOLD = 20;
    private static final long MS_MIN_TIME_BETWEEN_MOTION_TRIGGERS = 2000;
    private Activity mActivity;
    private SurfaceView mSurfaceView;
    private SurfaceHolder mSurfaceHolder;
    private Camera mCamera;
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private byte[] lastPreviewFrame;
    private Date lastMotionFrameDate = new Date(0);

    public CameraView(Activity activity, SurfaceView surfaceView) {
        mActivity = activity;
        mSurfaceView = surfaceView;
        mSurfaceHolder = surfaceView.getHolder();
        mSurfaceHolder.addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        initCamera();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Camera.Parameters parameters = mCamera.getParameters();

        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_EDOF);

        // Set preview FPS to lowest possible FPS to improve performance and reduce strain
        List<int[]> fpsRanges = parameters.getSupportedPreviewFpsRange();
        int[] fpsRange = fpsRanges.get(fpsRanges.size() - 1);
        parameters.setPreviewFpsRange(fpsRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX], fpsRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX]);

        Camera.Size size = getOptimalPreviewSize(parameters.getSupportedPreviewSizes(), width, height);
        parameters.setPreviewSize(size.width, size.height);

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

        mCamera.setPreviewCallback(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(final byte[] data, Camera camera) {
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
                                    Log.v(LOG_TAG, "motion detected");
                                    lastMotionFrameDate = now;
                                }
                            }
                        }

                        lastPreviewFrame = data;
                    }
                });
            }
        });

        return true;
    }

    private void initCamera() {
        mCamera = Camera.open(0);

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

        try {
            mCamera.setPreviewDisplay(mSurfaceHolder);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error setting camera preview display", e);
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
