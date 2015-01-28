/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.camera.one.v2.photo;

import android.annotation.TargetApi;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;

import com.android.camera.async.BufferQueue;
import com.android.camera.async.Updatable;
import com.android.camera.async.UpdatableCountDownLatch;
import com.android.camera.one.v2.autofocus.AETriggerStateMachine;
import com.android.camera.one.v2.autofocus.AFTriggerStateMachine;
import com.android.camera.one.v2.camera2proxy.CameraCaptureSessionClosedException;
import com.android.camera.one.v2.camera2proxy.ImageProxy;
import com.android.camera.one.v2.core.FrameServer;
import com.android.camera.one.v2.core.Request;
import com.android.camera.one.v2.core.RequestBuilder;
import com.android.camera.one.v2.core.ResourceAcquisitionFailedException;
import com.android.camera.one.v2.imagesaver.ImageSaver;
import com.android.camera.one.v2.sharedimagereader.ImageStreamFactory;
import com.android.camera.one.v2.sharedimagereader.imagedistributor.ImageStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.ParametersAreNonnullByDefault;

import static com.android.camera.one.v2.core.ResponseListeners.forFrameExposure;
import static com.android.camera.one.v2.core.ResponseListeners.forPartialMetadata;

/**
 * Captures a burst after waiting for AF and AE convergence.
 */
@ParametersAreNonnullByDefault
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
class ConvergedImageCaptureCommand implements ImageCaptureCommand {
    private final ImageStreamFactory mImageReader;
    private final FrameServer mFrameServer;
    private final RequestBuilder.Factory mRepeatingRequestBuilder;
    private final int mRepeatingRequestTemplate;
    private final int mStillCaptureRequestTemplate;
    private final List<RequestBuilder.Factory> mBurst;

    /**
     * @param imageReader Creates the {@link ImageStream} used for capturing
     *            images to be saved.
     * @param frameServer Used for interacting with the camera device.
     * @param repeatingRequestBuilder Creates request builders to use for
     *            repeating requests sent during the scanning phase and after
     *            capture is complete.
     * @param repeatingRequestTemplate The template type to use for repeating
     *            requests.
     * @param repeatingRequestTemplate The template type to use for capture
     *            requests.
     * @param burst Creates request builders to use for each image captured from
     *            the burst.
     */
    public ConvergedImageCaptureCommand(ImageStreamFactory imageReader, FrameServer frameServer,
            RequestBuilder.Factory repeatingRequestBuilder,
            int repeatingRequestTemplate, int stillCaptureRequestTemplate,
            List<RequestBuilder.Factory> burst) {
        mImageReader = imageReader;
        mFrameServer = frameServer;
        mRepeatingRequestBuilder = repeatingRequestBuilder;
        mRepeatingRequestTemplate = repeatingRequestTemplate;
        mStillCaptureRequestTemplate = stillCaptureRequestTemplate;
        mBurst = burst;
    }

    /**
     * Sends a request to take a picture and blocks until it completes.
     */
    @Override
    public void run(Updatable<Void> imageExposureUpdatable, ImageSaver imageSaver) throws
            InterruptedException, CameraAccessException, CameraCaptureSessionClosedException,
            ResourceAcquisitionFailedException {
        try (
                FrameServer.Session session = mFrameServer.createExclusiveSession();
                ImageStream imageStream = mImageReader.createPreallocatedStream(mBurst.size())) {
            waitForAFConvergence(session);
            waitForAEConvergence(session);
            captureBurst(session, imageStream, imageExposureUpdatable, imageSaver);
            resetRepeating(session);
        } finally {
            imageSaver.close();
        }
    }

    private RequestBuilder createAFTriggerRequest() throws CameraAccessException {
        RequestBuilder triggerBuilder = mRepeatingRequestBuilder
                .create(CameraDevice.TEMPLATE_PREVIEW);
        triggerBuilder.setParam(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        triggerBuilder.setParam(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest
                .CONTROL_AF_TRIGGER_START);
        return triggerBuilder;
    }

    private RequestBuilder createAFTriggerCancelRequest() throws CameraAccessException {
        RequestBuilder triggerBuilder = mRepeatingRequestBuilder
                .create(CameraDevice.TEMPLATE_PREVIEW);
        triggerBuilder.setParam(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        triggerBuilder.setParam(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest
                .CONTROL_AF_TRIGGER_CANCEL);
        return triggerBuilder;
    }

    private RequestBuilder createAFIdleRequest() throws CameraAccessException {
        RequestBuilder triggerBuilder = mRepeatingRequestBuilder
                .create(CameraDevice.TEMPLATE_PREVIEW);
        triggerBuilder.setParam(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        triggerBuilder.setParam(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest
                .CONTROL_AF_TRIGGER_IDLE);
        return triggerBuilder;
    }

    private void waitForAFConvergence(FrameServer.Session session) throws CameraAccessException,
            InterruptedException, ResourceAcquisitionFailedException,
            CameraCaptureSessionClosedException {
        UpdatableCountDownLatch<Void> afConvergenceLatch = new UpdatableCountDownLatch<>(1);
        AFTriggerStateMachine afStateMachine = new AFTriggerStateMachine(afConvergenceLatch);

        RequestBuilder triggerBuilder = createAFTriggerRequest();
        triggerBuilder.addResponseListener(forPartialMetadata(afStateMachine));

        RequestBuilder idleBuilder = createAFIdleRequest();
        idleBuilder.addResponseListener(forPartialMetadata(afStateMachine));

        session.submitRequest(Arrays.asList(idleBuilder.build()),
                FrameServer.RequestType.REPEATING);

        session.submitRequest(Arrays.asList(triggerBuilder.build()),
                FrameServer.RequestType.NON_REPEATING);

        afConvergenceLatch.await();
    }

    private RequestBuilder createAETriggerRequest() throws CameraAccessException {
        RequestBuilder triggerBuilder = mRepeatingRequestBuilder
                .create(mRepeatingRequestTemplate);
        triggerBuilder.setParam(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        triggerBuilder.setParam(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest
                .CONTROL_AE_PRECAPTURE_TRIGGER_START);
        return triggerBuilder;
    }

    private RequestBuilder createAEIdleRequest() throws CameraAccessException {
        RequestBuilder triggerBuilder = mRepeatingRequestBuilder
                .create(mRepeatingRequestTemplate);
        triggerBuilder.setParam(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        triggerBuilder.setParam(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest
                .CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        return triggerBuilder;
    }

    private void waitForAEConvergence(FrameServer.Session session) throws CameraAccessException,
            InterruptedException, ResourceAcquisitionFailedException,
            CameraCaptureSessionClosedException {
        UpdatableCountDownLatch<Void> aeConvergenceLatch = new UpdatableCountDownLatch<>(1);
        AETriggerStateMachine afStateMachine = new AETriggerStateMachine(aeConvergenceLatch);

        RequestBuilder triggerBuilder = createAETriggerRequest();
        triggerBuilder.addResponseListener(forPartialMetadata(afStateMachine));

        RequestBuilder idleBuilder = createAEIdleRequest();
        idleBuilder.addResponseListener(forPartialMetadata(afStateMachine));

        session.submitRequest(Arrays.asList(idleBuilder.build()),
                FrameServer.RequestType.REPEATING);

        session.submitRequest(Arrays.asList(triggerBuilder.build()),
                FrameServer.RequestType.NON_REPEATING);

        aeConvergenceLatch.await();
    }

    private void captureBurst(FrameServer.Session session, ImageStream imageStream, Updatable<Void>
            imageExposureUpdatable, ImageSaver imageSaver) throws CameraAccessException,
            InterruptedException, ResourceAcquisitionFailedException,
            CameraCaptureSessionClosedException {
        List<Request> burstRequest = new ArrayList<>(mBurst.size());
        boolean first = true;
        for (RequestBuilder.Factory builderTemplate : mBurst) {
            RequestBuilder builder = builderTemplate.create(mStillCaptureRequestTemplate);
            if (first) {
                first = false;
                builder.addResponseListener(forFrameExposure(imageExposureUpdatable));
            }
            builder.addStream(imageStream);
            burstRequest.add(builder.build());
        }

        session.submitRequest(burstRequest, FrameServer.RequestType.NON_REPEATING);

        for (int i = 0; i < mBurst.size(); i++) {
            try {
                ImageProxy image = imageStream.getNext();
                imageSaver.addFullSizeImage(image);
            } catch (BufferQueue.BufferQueueClosedException e) {
                // No more images will be available, so just quit.
                return;
            }
        }
    }

    private void resetRepeating(FrameServer.Session session) throws InterruptedException,
            CameraCaptureSessionClosedException, CameraAccessException,
            ResourceAcquisitionFailedException {
        RequestBuilder triggerCancelBuilder = createAFTriggerCancelRequest();
        session.submitRequest(Arrays.asList(triggerCancelBuilder.build()),
                FrameServer.RequestType.NON_REPEATING);

        RequestBuilder repeatingBuilder = mRepeatingRequestBuilder.create
                (mRepeatingRequestTemplate);
        session.submitRequest(Arrays.asList(repeatingBuilder.build()),
                FrameServer.RequestType.REPEATING);
    }
}
