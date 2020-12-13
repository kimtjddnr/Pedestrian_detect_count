/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.toure.objectdetection;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Build;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.toure.objectdetection.customview.OverlayView;
import com.toure.objectdetection.env.BorderedText;
import com.toure.objectdetection.env.ImageUtils;
import com.toure.objectdetection.env.Logger;
import com.toure.objectdetection.tflite.Classifier;
import com.toure.objectdetection.tflite.TFLiteObjectDetectionAPIModel;
import com.toure.objectdetection.tracking.MultiBoxTracker;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends MainActivity implements OnImageAvailableListener {
  private static final Logger LOGGER = new Logger();

  // Configuration values for the prepackaged SSD model.
  private static final int TF_OD_API_INPUT_SIZE = 300;
  private static final boolean TF_OD_API_IS_QUANTIZED = true;
  private static final String TF_OD_API_MODEL_FILE = "detectm.tflite";
  private static final String TF_OD_API_LABELS_FILE = "labelmap1.txt";
  private static final DetectorMode MODE = DetectorMode.TF_OD_API;
  // Minimum detection confidence to track a detection.
  private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.2f;
  private static final boolean MAINTAIN_ASPECT = false;
  private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480);
  private static final boolean SAVE_PREVIEW_BITMAP = false;
  private static final float TEXT_SIZE_DIP = 10;
  OverlayView trackingOverlay;
  private Integer sensorOrientation;

  private Classifier detector;

  private long lastProcessingTimeMs;
  private Bitmap rgbFrameBitmap = null;
  private Bitmap croppedBitmap = null;
  private Bitmap cropCopyBitmap = null;

  private boolean computingDetection = false;

  private long timestamp = 0;

  private Matrix frameToCropTransform;
  private Matrix cropToFrameTransform;

  private MultiBoxTracker tracker;

  private byte[] luminanceCopy;

  private BorderedText borderedText;


  @Override
  public void onPreviewSizeChosen(final Size size, final int rotation) {
    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);

    tracker = new MultiBoxTracker(this);

    int cropSize = TF_OD_API_INPUT_SIZE;

    try {
      detector =
          TFLiteObjectDetectionAPIModel.create(
              getAssets(),
              TF_OD_API_MODEL_FILE,
              TF_OD_API_LABELS_FILE,
              TF_OD_API_INPUT_SIZE,
              TF_OD_API_IS_QUANTIZED);
      cropSize = TF_OD_API_INPUT_SIZE;
    } catch (final IOException e) {
      e.printStackTrace();
      LOGGER.e("Exception initializing classifier!", e);
      Toast toast =
          Toast.makeText(
              getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
      toast.show();
      finish();
    }

    previewWidth = size.getWidth();
    previewHeight = size.getHeight();

    sensorOrientation = rotation - getScreenOrientation();
    LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

    LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
    croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

    frameToCropTransform =
        ImageUtils.getTransformationMatrix(
            previewWidth, previewHeight,
            cropSize, cropSize,
            sensorOrientation, MAINTAIN_ASPECT);

    cropToFrameTransform = new Matrix();
    frameToCropTransform.invert(cropToFrameTransform);

    trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
    trackingOverlay.addCallback(
        new OverlayView.DrawCallback() {
          @Override
          public void drawCallback(final Canvas canvas) {
            tracker.draw(canvas);
            if (isDebug()) {
              tracker.drawDebug(canvas);
            }
          }
        });
  }

  @Override
  protected void processImage() {
    ++timestamp;
    final long currTimestamp = timestamp;
    byte[] originalLuminance = getLuminance();
    tracker.onFrame(
        previewWidth,
        previewHeight,
        getLuminanceStride(),
        sensorOrientation,
        originalLuminance,
        timestamp);
    trackingOverlay.postInvalidate();

    // No mutex needed as this method is not reentrant.
    if (computingDetection) {
      readyForNextImage();
      return;
    }
    computingDetection = true;
    LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

    rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

    if (luminanceCopy == null) {
      luminanceCopy = new byte[originalLuminance.length];
    }
    System.arraycopy(originalLuminance, 0, luminanceCopy, 0, originalLuminance.length);
    readyForNextImage();

    final Canvas canvas = new Canvas(croppedBitmap);
    canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);
    // For examining the actual TF input.
    if (SAVE_PREVIEW_BITMAP) {
      ImageUtils.saveBitmap(croppedBitmap);
    }

    runInBackground(
        new Runnable() {
          @RequiresApi(api = Build.VERSION_CODES.M)
          @Override
          public void run() {
            try{
              LOGGER.i("Running detection on image " + currTimestamp);
              final long startTime = SystemClock.uptimeMillis();
              final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);

              lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;

              cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
              final Canvas canvas = new Canvas(cropCopyBitmap);
              final Paint paint = new Paint();
              paint.setColor(Color.RED);
              paint.setStyle(Style.STROKE);
              paint.setStrokeWidth(2.0f);

              float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
              switch (MODE) {
                case TF_OD_API:
                  minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                  break;
              }

              final List<Classifier.Recognition> mappedRecognitions =
                      new LinkedList<Classifier.Recognition>();
              int  i = 0;
              for (final Classifier.Recognition result : results) {
                final RectF location = result.getLocation();

                if (location != null && result.getConfidence() >= minimumConfidence) {
                  canvas.drawRect(location, paint);
                  i= i+1;
                  cropToFrameTransform.mapRect(location);

                  result.setLocation(location);
                  mappedRecognitions.add(result);
                  Log.d("Test", result.getTitle());
                  //Log.d("Dist", "Distance: " + result.getLocation().height());
                  //getDistance(result.getLocation());
                  //speakDetectedObject(result.getTitle());
                  //Thread.sleep(2000);

                }
              }
              System.out.println("Count = "+ i);

              //5명이상이면 진동울림
              if(i>=5){
                Vibrator vibrator=(Vibrator)getSystemService(Context.VIBRATOR_SERVICE);
                vibrator.vibrate(500);
              }

              tracker.trackResults(mappedRecognitions, luminanceCopy, currTimestamp);
              trackingOverlay.postInvalidate();

              computingDetection = false;

              runOnUiThread(
                      () -> {
                        showFrameInfo(previewWidth + "x" + previewHeight);
                        showCropInfo(cropCopyBitmap.getWidth() + "x" + cropCopyBitmap.getHeight());
                        showInference(lastProcessingTimeMs + "ms");
                      });
            }catch (Exception ex){
              ex.printStackTrace();
            }

          }
        });
  }

  @Override
  protected int getLayoutId() {
    return R.layout.camera_connection_fragment_tracking;
  }

  @Override
  protected Size getDesiredPreviewFrameSize() {
    return DESIRED_PREVIEW_SIZE;
  }

  @Override
  protected void setUseNNAPI(final boolean isChecked) {
    runInBackground(() -> detector.setUseNNAPI(isChecked));
  }

  @Override
  protected void setNumThreads(final int numThreads) {
    runInBackground(() -> detector.setNumThreads(numThreads));
  }

  // Which detection model to use: by default uses Tensorflow Object Detection API frozen
  // checkpoints.
  private enum DetectorMode {
    TF_OD_API
  }

  void getDistance(RectF location){
    double focalLength;
    double imageHieght = Math.round((location.top - location.bottom) * 0.0264583333 *10)/10.0; // image height in centimeters
    if (isUseCamera2API()){
      focalLength = ((CameraConnectionFragment)getFragmentManager().findFragmentById(R.id.container)).getFocalLength();
    }
    else{
      focalLength = ((LegacyCameraConnectionFragment)getFragmentManager().findFragmentById(R.id.container)).getFocalLength();
    }Log.d("Dis", "distance: "+ Math.round(focalLength*1500/imageHieght)/10.0);
    Toast.makeText(this, "distance: "+ Math.round(focalLength*1500/imageHieght)/10.0, Toast.LENGTH_SHORT).show();
  }

}
