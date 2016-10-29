package com.example.ab.camera;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.speech.tts.TextToSpeech;
import android.support.annotation.IntDef;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static java.lang.annotation.RetentionPolicy.SOURCE;

abstract public class CameraService extends Service {
    public static final String SCHEME = "camera";
    public static final int IMAGE_FORMAT = ImageFormat.JPEG;
    // TODO:
    public static final int REQUIRED_FEATURE = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE;
    public static final String EXTRA_MESSAGE = CameraService.class.getSimpleName() + ".MESSAGE";
    public static final String EXTRA_ID = CameraService.class.getSimpleName() + ".ID";
    public static final int CAMERA_ACCESS_EXCEPTION = 0;
    public static final int NO_CAMERA_PERMISSION = 1;
    public static final int CAMERA_STATE_ERROR = 2;
    public static final int CAPTURE_SESSION_CONFIGURE_FAILED = 3;
    public static final int CAPTURE_SESSION_CONFIGURE_EXCEPTION = 4;
    public static final int SERVICE_START_ERROR = 5;
    public final String CAMERA_ID;
    public final Uri BASE;
    private final String TAG;
    private final HandlerThread SERVICE_MAIN_THREAD;
    private Integer FACE_DETECTION_MODE = -1;
    private Uri mStartRequest;
    private boolean mDoFaceDetect = true;
    private int SERVICE_START_MODE = START_NOT_STICKY;
    private Size mPreviewSize;
    private CameraAvailabilityManager mCameraAvailabilityManager;
    private MediaRecorder mMediaRecorder;
    private String mNextVideoAbsolutePath;
    private Size mVideoSize;

    public CameraService() {
        TAG = getClass().getName();
        int p = TAG.indexOf('_');
        if (p < CameraService.class.getSimpleName().length()) {
            throw new RuntimeException("Invalid class name");
        }
        CAMERA_ID = TAG.substring(p + 1);
        BASE = new Uri.Builder().scheme(SCHEME).authority(CAMERA_ID).build();
        SERVICE_MAIN_THREAD = new HandlerThread(TAG + " main thread");
        SERVICE_MAIN_THREAD.start();
        l(TAG, "constructor... " + this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getData() != null) {
            if (!SCHEME.equals(intent.getData().getScheme())) {
                return SERVICE_START_MODE;
            }
            if ("start".equals(intent.getData().getHost()) && mCameraAvailabilityManager != null) {
                e(TAG, "ignoring repeating start " + intent.getData());
                return SERVICE_START_MODE;
            }
        }

        try {
            l(TAG, "onStartCommand()... " + this);
            final CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (!Arrays.asList(cm.getCameraIdList()).contains(CAMERA_ID)) {
                throw new CameraAccessException(CameraAccessException.CAMERA_DISCONNECTED, "camera " + CAMERA_ID + " is not on the CameraManager list");
            }

            if (intent != null) {
                mStartRequest = intent.getData();
                mDoFaceDetect = mStartRequest.getBooleanQueryParameter("fd", true);
            }

            CameraCharacteristics chars = cm.getCameraCharacteristics(CAMERA_ID);

            int[] caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            if (caps != null) {
                Arrays.sort(caps);
                if (Arrays.binarySearch(caps, REQUIRED_FEATURE) < 0) {
                    e(TAG, "camera " + CAMERA_ID + " does not support required feature " + REQUIRED_FEATURE);
                }
            }

            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = map.getOutputSizes(IMAGE_FORMAT);
            if (sizes == null) {
                throw new RuntimeException("camera " + CAMERA_ID + " does not have sizes for image format " + IMAGE_FORMAT);
            }

            mPreviewSize = Collections.max(Arrays.asList(sizes),
                    (l, r) -> Integer.signum(l.getWidth() * l.getHeight() - r.getWidth() * r.getHeight()));

            if (mDoFaceDetect) {
                int[] fd = chars.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES);
                int maxFd = chars.get(CameraCharacteristics.STATISTICS_INFO_MAX_FACE_COUNT);
                if (maxFd > 0) {
                    Arrays.sort(fd);
                    FACE_DETECTION_MODE = fd[fd.length - 1];
                }
                l(TAG, "fase detection modes " + fd.length + ", max = " + maxFd);
            }

            boolean isFrontCam = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT;
            l(TAG, "Camera " + CAMERA_ID + " is " + (isFrontCam ? "front" : "back") + " cam");

            mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));
            l(TAG, "Video size = " + mVideoSize);

            mCameraAvailabilityManager = new CameraAvailabilityManager();
            SERVICE_START_MODE = (mStartRequest != null && mStartRequest.getBooleanQueryParameter("sticky", false)) ? START_STICKY : START_NOT_STICKY;
            cm.registerAvailabilityCallback(mCameraAvailabilityManager, new Handler(SERVICE_MAIN_THREAD.getLooper()));
            broadcast("service started");
            return SERVICE_START_MODE;
        } catch (CameraAccessException e) {
            e.printStackTrace();
            broadcast("failed to start service");
            { // onError
                reportError(SERVICE_START_ERROR, e);
            }
            e(TAG, "onStartCommand() failed " + intent);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        l(TAG, "onDestroy() " + this);
        ((CameraManager) getSystemService(Context.CAMERA_SERVICE)).unregisterAvailabilityCallback(mCameraAvailabilityManager);
        //SERVICE_MAIN_THREAD.quit();
        mCameraAvailabilityManager.destroy();
    }

    protected void reportError(@CameraError int error, Exception ex) {
        ex.printStackTrace();
        String msg = ex.getMessage();
        switch (error) {
            case CAMERA_ACCESS_EXCEPTION:
                e(TAG, "CAMERA_ACCESS_EXCEPTION: " + msg);
                break;
            case NO_CAMERA_PERMISSION:
                e(TAG, "NO_CAMERA_PERMISSION: " + msg);
                break;
            case CAMERA_STATE_ERROR:
                e(TAG, "CAMERA_STATE_ERROR: " + msg);
                break;
            case CAPTURE_SESSION_CONFIGURE_FAILED:
                e(TAG, "CAPTURE_SESSION_CONFIGURE_FAILED: " + msg);
                break;
            default:
                e(TAG, "UNKNOWN ERROR " + error + ": " + msg);
                break;
        }
    }

    protected void broadcast(String msg) {
        sendBroadcast(new Intent(TAG).putExtra(EXTRA_MESSAGE, msg));
    }

    protected void broadcast(String id, String msg) {
        sendBroadcast(new Intent(TAG).putExtra(EXTRA_ID, id).putExtra(EXTRA_MESSAGE, msg));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
    }

    @Override
    public void onLowMemory() {
    }

    @Override
    public void onTrimMemory(int level) {
    }

    // TODO:
    // TODO: unhandled exception handler

    private void setUpMediaRecorder() throws IOException {
//        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
            mNextVideoAbsolutePath = getVideoFilePath();
        }
        mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
        mMediaRecorder.setVideoEncodingBitRate(8 * 1000 * 1000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
//        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mMediaRecorder.prepare();
        e(TAG, "Prepared video recorder writing to " + mNextVideoAbsolutePath);
    }

    private String getVideoFilePath() {
        return getExternalFilesDir(null).getAbsolutePath() + "/" + CAMERA_ID + "_" + System.currentTimeMillis() / 1000 + ".mp4";
    }

    private Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
//            if (size.getWidth() == size.getHeight() * 9 / 6 && size.getWidth() <= 1080) {
//                return size;
//            }
            if (size.getWidth() == 1920) {
                return size;
            }
        }
        e(TAG, "Couldn't find any suitable video size");
        return choices[choices.length - 1];
    }

    private boolean isReceiverRegistred(Intent intent) {
        final PackageManager packageManager = getPackageManager();
        List<ResolveInfo> resolveInfo = packageManager.queryBroadcastReceivers(intent, PackageManager.MATCH_ALL);
        return resolveInfo.size() > 0;
    }

    @Retention(SOURCE)
    @IntDef({CAMERA_ACCESS_EXCEPTION, NO_CAMERA_PERMISSION, CAMERA_STATE_ERROR,
            CAPTURE_SESSION_CONFIGURE_FAILED, CAPTURE_SESSION_CONFIGURE_EXCEPTION, SERVICE_START_ERROR})
    public @interface CameraError {
    }

    private class CameraAvailabilityManager extends CameraManager.AvailabilityCallback {
        final String TAG = CameraAvailabilityManager.class.getName();
        private CameraStateManager mCameraStateManager;

        @Override
        public void onCameraAvailable(String cameraId) {
            if (!CAMERA_ID.equals(cameraId)) {
                return;
            }
            l(TAG, "onCameraAvailable()... " + cameraId);
            try {
                if (ActivityCompat.checkSelfPermission(getApplication(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    throw new IllegalAccessException("No camera permission granted");
                }
                mCameraStateManager = new CameraStateManager();
                ((CameraManager) getSystemService(Context.CAMERA_SERVICE)).openCamera(cameraId, mCameraStateManager, null);
                l(TAG, "onCameraAvailable() submited request to open camera " + cameraId);
                return;
            } catch (CameraAccessException e) {
                reportError(CAMERA_ACCESS_EXCEPTION, e);
            } catch (IllegalAccessException e) {
                reportError(NO_CAMERA_PERMISSION, e);
            }
            broadcast("Failed to open camera " + CAMERA_ID);
            destroy();
        }

        @Override
        public void onCameraUnavailable(String cameraId) {
            if (!CAMERA_ID.equals(cameraId)) {
                return;
            }
            broadcast("Camera " + CAMERA_ID + " unavailable");
            // ignore because we get it when we open camera
//            destroy();
        }

        public void destroy() {
            if (mCameraStateManager != null) {
                mCameraStateManager.destroy(null);
            }
        }
    }

    /*
     * Takes care of camera life cycle events: connected, disconnected, errors.
     */
    private class CameraStateManager extends CameraDevice.StateCallback {
        final String TAG = CameraStateManager.class.getName();
        private CaptureSessionManager mSessionManager;

        @Override
        public void onOpened(CameraDevice camera) {
            l(TAG, "onOpened() " + camera.getId());
            try {
                mSessionManager = new CaptureSessionManager(camera);
            } catch (CameraAccessException | IllegalAccessException | IOException e) {
                e.printStackTrace();
                reportError(CAMERA_ACCESS_EXCEPTION, e);
                destroy(camera);
                stopSelf();
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            l(TAG, "disconnected " + camera.getId());
            destroy(camera);
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            // TODO: verbose error
            l(TAG, "error in " + camera.getId() + ": " + error);
            reportError(CAMERA_STATE_ERROR, new Exception("camera device " + camera.getId() + " error " + error));
        }

        private void destroy(CameraDevice camera) {
            if (mSessionManager != null) {
                mSessionManager.destroy();
                mSessionManager = null;
            }
            if (camera != null) {
                camera.close();
            }
        }
    }

    /**
     * Camera session creation and life cycle management: configured, failed, closed
     */
    private class CaptureSessionManager extends CameraCaptureSession.StateCallback {
        final String TAG = CaptureSessionManager.class.getName();
        final private HandlerThread imageProducer = new HandlerThread("Image producer callback thread",
                Process.THREAD_PRIORITY_URGENT_DISPLAY);
        final private HandlerThread captureRequestCallbackThread = new HandlerThread("Capture request callback thread",
                Process.THREAD_PRIORITY_URGENT_DISPLAY);
        final private HandlerThread sessionCallbackThread = new HandlerThread("Capture session callback thread",
                Process.THREAD_PRIORITY_DEFAULT);

        final private CameraDevice mCamera;
        private CameraCaptureSession mSession;

        public CaptureSessionManager(CameraDevice camera) throws IllegalAccessException, CameraAccessException, IOException {
            mMediaRecorder = new MediaRecorder();
            mCamera = camera;
            sessionCallbackThread.start();
            // get media recorder params from intent
            setUpMediaRecorder();
            camera.createCaptureSession(Arrays.asList(mMediaRecorder.getSurface()), this, new Handler(sessionCallbackThread.getLooper()));
            captureRequestCallbackThread.start();
        }

        @Override
        public void onConfigured(CameraCaptureSession session) {
            try {
                mSession = session;
                final CaptureRequest.Builder rb = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                rb.addTarget(mMediaRecorder.getSurface());
                if (FACE_DETECTION_MODE >= 0) {
                    rb.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, FACE_DETECTION_MODE);
                }
                rb.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                session.setRepeatingRequest(rb.build(), new CaptureCallbackManager(), new Handler(captureRequestCallbackThread.getLooper()));
                e(TAG, "session configured: " + session);
                // TODO: it may throw exception
                mMediaRecorder.start();
                broadcast("recording on" + CAMERA_ID, mNextVideoAbsolutePath);
                e(TAG, "media recorder started");
            } catch (CameraAccessException | IllegalStateException e) {
                e.printStackTrace();
                reportError(CAPTURE_SESSION_CONFIGURE_EXCEPTION, new Exception("onConfigureFailed(), session " + session));
                destroy();
                stopSelf();
            }
        }

        @Override
        public void onClosed(CameraCaptureSession session) {
            e(TAG, "session callback onClosed() " + session);
            destroy();
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            e(TAG, "session callback onConfigureFailed() " + session);
            reportError(CAPTURE_SESSION_CONFIGURE_FAILED, new Exception("onConfigureFailed(), session " + session));
            destroy();
        }

        public void destroy() {
            try {
                broadcast("recording off", mNextVideoAbsolutePath);
                mMediaRecorder.stop();
                mMediaRecorder.reset();
                mMediaRecorder.release();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            if (mSession != null) {
                try {
                    mSession.close();
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
            try {
                mCamera.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
//            imageProducer.quitSafely();
//            captureRequestCallbackThread.quitSafely();
//            sessionCallbackThread.quitSafely();
        }
    }

    /**
     * Frame metadata handler: exposure, faces, much more ...
     */
    private class CaptureCallbackManager extends CameraCaptureSession.CaptureCallback {
        final String TAG = CaptureCallbackManager.class.getName();

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            // TODO: grab metada data
            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
            long counter = result.getFrameNumber();
            Long ns = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            long ms = TimeUnit.MILLISECONDS.convert(ns, TimeUnit.NANOSECONDS);
//            l(TAG, counter + ": t: " + ms);
            if (counter % 100 == 0)
                broadcast("counter" + CAMERA_ID, " " + counter + " / " + ms + "ms");
            Integer mode = result.get(CaptureResult.STATISTICS_FACE_DETECT_MODE);
            Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
            Byte n = result.get(CaptureResult.REQUEST_PIPELINE_DEPTH);
            e(TAG, "faces : " + faces + " , mode : " + mode);
            // ColorSpaceTransform ccm = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
            // l(TAG, "" + ccm);
            if (faces != null) {
                for (Face face : faces) {
                    Rect bounds = face.getBounds();
                    Point leftEye = face.getLeftEyePosition();
                    Point rightEye = face.getRightEyePosition();
                    Point mouth = face.getMouthPosition();
                    String s = String.format("%dx%d, l:%s, r:%s, m:%s", bounds.width(), bounds.height(), leftEye, rightEye, mouth);
                    //broadcast("face", s);
                    l(TAG, s);
                }
            }
        }

        @Override
        public void onCaptureBufferLost(CameraCaptureSession session, CaptureRequest request, Surface target, long frameNumber) {
            l(TAG, "capture buffer lost " + frameNumber);
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            l(TAG, "onCaptureFailed " + failure.getFrameNumber());
        }

        @Override
        public void onCaptureSequenceAborted(CameraCaptureSession session, int sequenceId) {
            l(TAG, "onCaptureSequenceAborted " + sequenceId);
        }

        @Override
        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
//            l(TAG, "onCaptureStarted " + frameNumber);
        }

        @Override
        public void onCaptureSequenceCompleted(CameraCaptureSession session, int sequenceId, long frameNumber) {
            l(TAG, "onCaptureSequenceCompleted " + frameNumber);
        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {
            l(TAG, "onCaptureProgressed " + partialResult.getFrameNumber());
        }
    }

    /**
     * Frame data handler
     */
    private class ImageAvailabeManager implements ImageReader.OnImageAvailableListener {
        final String TAG = ImageAvailabeManager.class.getName();
        private int mFrameCount = 0;
        private long t = System.currentTimeMillis();

        @Override
        public void onImageAvailable(ImageReader reader) {
            try {
                Image frame = reader.acquireNextImage();
                if (frame.getFormat() == IMAGE_FORMAT) {
                    try {
                        processRawImage(frame);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        frame.close();
                    }
                } else {
                    e(TAG, "Unexpected image format");
                }
            } catch (IllegalStateException e) {
                e(TAG, "Too many images queued for saving, dropping image for request: ");
            }
        }

        private void processRawImage(Image img) throws IOException {
            // l(TAG, "processRawImage()");
            int format = img.getFormat();
            if (format != IMAGE_FORMAT) {
                throw new IllegalArgumentException("Supports only RAW format" + format);
            }
            Image.Plane[] planes = img.getPlanes();
            ByteBuffer buf = planes[0].getBuffer();
            int w = img.getWidth();
            int h = img.getHeight();
            int ps = planes[0].getPixelStride();
            int rs = planes[0].getRowStride();
            int off = 0;
            long capacity = buf.capacity();
            long totalSize = ((long) rs) * h + off;
            int minRowStride = ps * w;
            mFrameCount++;
            if (mFrameCount >= 100) {
                long dt = (System.currentTimeMillis() - t);
                l(TAG, String.format("%d frames %dx%d pixels %d bytes in %d ms", mFrameCount, w, h, capacity, dt));
                mFrameCount = 0;
                t = System.currentTimeMillis();
            }
            // TODO:
            buf.clear(); // Reset mark and limit
        }
    }

    public void l(String TAG, String msg) {
        Log.d(TAG, msg);
    }

    public void e(String TAG, String msg) {
        Log.e(TAG, msg);

    }

    public void s(String TAG, String msg) {
        MainActivity.levitan.speak(msg, TextToSpeech.QUEUE_FLUSH, null, null);
    }

}
