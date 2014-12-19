/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.camera.processing;

import com.android.camera.app.CameraAppUI;
import com.android.camera.one.v2.camera2proxy.ImageProxy;
import com.android.camera.processing.TaskImageContainer.ProcessingPriority;
import com.android.camera.debug.Log;
import com.android.camera.session.CaptureSession;

import android.graphics.Paint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This ImageBackend is created for the purpose of creating a task-running
 * infrastructure that has two-level of priority and doing the book-keeping to
 * keep track of tasks that use Android Images. Android.media.images are
 * critical system resources that MUST be properly managed in order to maintain
 * camera application performance. Android.media.images are merely Java handles
 * to regions of physically contiguous memory used by the camera hardware as a
 * destination for imaging data. In general, this physically contiguous memory
 * is not counted as an application resource, but as a system resources held by
 * the application and does NOT count against the limits of application memory.
 * The performance pressures of both computing and memory resources must often
 * be prioritized in releasing Android.media.images in a timely manner. In order
 * to properly balance these concerns, most image processing requested should be
 * routed through this object. This object is also responsible for releasing
 * Android.media image as soon as possible, so as not to stall the camera
 * hardware subsystem. Image that reserve these images are a subclass of the
 * basic Java Runnable with a few conditions placed upon their run()
 * implementation:
 * <ol>
 * <li>The task will try to release the image as early as possible by calling
 * the releaseSemaphoreReference as soon as a reference to the original image is
 * no longer required.</li>
 * <li>A set of tasks that require ImageData must only happen on the first
 * receiveImage call. receiveImage must only be called once per image.</li>
 * <li>However, the submitted tasks may spawn new tasks via the appendTask with
 * any image that have had a task submitted, but NOT released via
 * releaseSemaphoreReference.</li>
 * <li>Computation that is dependent on multiple images should be written into
 * this task framework in a distributed manner where image task can be computed
 * independently and join their results to a common shared object.This style of
 * implementation allows for the earliest release of Android Images while
 * honoring the resources priorities set by this class. See the Lucky shot
 * implementation for a concrete example for this shared object and its
 * respective task {@link TaskLuckyShotSession} {@link LuckyShotSession}</li>
 * </ol>
 */
public class ImageBackend implements ImageConsumer {

    protected static final int FAST_THREAD_PRIORITY = Thread.MAX_PRIORITY;

    protected static final int SLOW_THREAD_PRIORITY = Thread.NORM_PRIORITY;

    protected static final int NUM_THREADS_FAST = 2;

    protected static final int NUM_THREADS_SLOW = 2;

    protected final SimpleCache mSimpleCache;

    protected final Map<ImageProxy, ImageReleaseProtocol> mImageSemaphoreMap;

    protected final ExecutorService mThreadPoolFast;

    protected final ExecutorService mThreadPoolSlow;

    private final static Log.Tag TAG = new Log.Tag("ImageBackend");

    // Some invariants to know that we're keeping track of everything
    // that reflect the state of mImageSemaphoreMap
    private int mOutstandingImageRefs = 0;

    private int mOutstandingImageOpened = 0;

    private int mOutstandingImageClosed = 0;

    // Objects that may be registered to this objects events.
    private ImageProcessorProxyListener mProxyListener = null;

    // Default constructor, values are conservatively targeted to the Nexus 6
    public ImageBackend() {
        mThreadPoolFast = Executors.newFixedThreadPool(NUM_THREADS_FAST, new FastThreadFactory());
        mThreadPoolSlow = Executors.newFixedThreadPool(NUM_THREADS_SLOW, new SlowThreadFactory());
        mProxyListener = new ImageProcessorProxyListener();
        mImageSemaphoreMap = new HashMap<ImageProxy, ImageReleaseProtocol>();
        mSimpleCache = new SimpleCache(NUM_THREADS_SLOW);
    }

    /**
     * Direct Injection Constructor for Testing purposes.
     *
     * @param fastService Service where Tasks of FAST Priority are placed.
     * @param slowService Service where Tasks of SLOW Priority are placed.
     * @param imageProcessorProxyListener iamge proxy listener to be used
     */
    public ImageBackend(ExecutorService fastService, ExecutorService slowService,
            ImageProcessorProxyListener imageProcessorProxyListener) {
        mThreadPoolFast = fastService;
        mThreadPoolSlow = slowService;
        mProxyListener = imageProcessorProxyListener;
        mImageSemaphoreMap = new HashMap<ImageProxy, ImageReleaseProtocol>();
        mSimpleCache = new SimpleCache(NUM_THREADS_SLOW);
    }

    // REMOVE When plumbed properly

    /**
     * Receiver to a valid UI that can handle events started by the
     * ImageBackend.
     */
    private CameraAppUI mCameraAppUI;

    // REMOVE When plumbed properly

    /**
     * Returns whether the ImageBackend has a valid handle to UI event handler.
     * NOTE: there are still synchronization issues
     *
     * @return whether the ImageBackend has a valid handle to UI event handler
     */
    public synchronized boolean hasValidUI() {
        return (mCameraAppUI != null);
    }

    // REMOVE When plumbed properly

    /**
     * Setter for a valid UI handle. Should be called when the Activity has
     * created or resumed its UI Handler.
     *
     * @param cameraAppUI
     */
    public synchronized void registerAppUI(CameraAppUI cameraAppUI) {
        mCameraAppUI = cameraAppUI;
    }

    // REMOVE When plumbed properly
    /**
     * Invalidates the UI Handle.  Should be called when the Activity has destroyed or paused its UI Handler.
     */
    public synchronized void unregisterAppUI() {
        mCameraAppUI = null;
    }

    // REMOVE When plumbed properly

    /**
     * Getter for Valid UI Handler
     * @return Valid UI Handler.  If null, there is no valid UI Handler.
     */
    public synchronized CameraAppUI getAppUI() {
        return mCameraAppUI;
    }

    /**
     * Simple getter for the simple cache functionality associated with this
     * instantiation. Needs to be accessed by the tasks in order to get/return
     * memory. TODO: Replace with something better.
     *
     * @return cache object that implements a simple memory pool for this
     *         object.
     */
    public SimpleCache getCache() {
        return mSimpleCache;
    }

    /**
     * Simple getting for the associated listener object associated with this
     * instantiation that handles registration of events listeners.
     *
     * @return listener proxy that handles events messaging for this object.
     */
    public ImageProcessorProxyListener getProxyListener() {
        return mProxyListener;
    }

    public void setCameraImageProcessorListener(ImageProcessorListener listener) {
        this.mProxyListener.registerListener(listener, null);
    }

    /**
     * Wrapper function for all log messages created by this object. Default
     * implementation is to send messages to the Android logger. For test
     * purposes, this method can be overridden to avoid "Stub!" Runtime
     * exceptions in Unit Tests.
     */
    public void logWrapper(String message) {
        Log.e(TAG, message);
    }

    /**
     * @return Number of Image references currently held by this instance
     */
    @Override
    public int numberOfReservedOpenImages() {
        synchronized (mImageSemaphoreMap) {
            // since mOutstandingImageOpened, mOutstandingImageClosed reflect
            // the historical state of mImageSemaphoreMap, we need to lock on
            // before we return a value.
            return mOutstandingImageOpened - mOutstandingImageClosed;
        }
    }

    /**
     * Signals the ImageBackend that a tasks has released a reference to the
     * image. Imagebackend determines whether all references have been released
     * and applies its specified release protocol of closing image and/or
     * unblocking the caller. Should ONLY be called by the tasks running on this
     * class.
     *
     * @param img the image to be released by the task.
     * @param executor the executor on which the image close is run. if null,
     *            image close is run by the calling thread (usually the main
     *            task thread).
     */
    public void releaseSemaphoreReference(final ImageProxy img, Executor executor) {
        synchronized (mImageSemaphoreMap) {
            ImageReleaseProtocol protocol = mImageSemaphoreMap.get(img);
            if (protocol == null || protocol.getCount() <= 0) {
                // That means task implementation has allowed an unbalanced
                // semaphore release.
                throw new RuntimeException(
                        "ERROR: Task implementation did NOT balance its release.");
            }

            // Normal operation from here.
            protocol.addCount(-1);
            mOutstandingImageRefs--;
            logWrapper("Ref release.  Total refs = " + mOutstandingImageRefs);
            if (protocol.getCount() == 0) {
                // Image is ready to be released
                // Remove the image from the map so that it may be submitted
                // again.
                mImageSemaphoreMap.remove(img);

                // Conditionally close the image, specified by initial
                // receiveImage call
                if (protocol.closeOnRelease) {
                    closeImageExecutorSafe(img, executor);
                    logWrapper("Ref release close.");
                }

                // Conditionally signal the blocking thread to go.
                if (protocol.blockUntilRelease) {
                    protocol.signal();
                }
            } else {
                // Image is still being held by other tasks.
                // Otherwise, update the semaphore
                mImageSemaphoreMap.put(img, protocol);
            }
        }
    }

    /**
     * Spawns dependent tasks from internal implementation of a task. If a
     * dependent task does NOT require the image reference, it should be passed
     * a null pointer as an image reference. In general, this method should be
     * called after the task has completed its own computations, but before it
     * has released its own image reference (via the releaseSemaphoreReference
     * call).
     *
     * @param tasks The set of tasks to be run
     * @return whether tasks are successfully submitted.
     */
    public boolean appendTasks(ImageProxy img, Set<TaskImageContainer> tasks) {
        // Make sure that referred images are all the same, if it exists.
        // And count how image references need to be kept track of.
        int countImageRefs = numPropagatedImageReferences(img, tasks);

        if (img != null) {
            // If you're still holding onto the reference, make sure you keep
            // count
            incrementSemaphoreReferenceCount(img, countImageRefs);
        }

        scheduleTasks(tasks);
        return true;
    }

    /**
     * Spawns a single dependent task from internal implementation of a task.
     *
     * @param task The task to be run
     * @return whether tasks are successfully submitted.
     */
    public boolean appendTasks(ImageProxy img, TaskImageContainer task) {
        Set<TaskImageContainer> tasks = new HashSet<TaskImageContainer>(1);
        tasks.add(task);
        return appendTasks(img, tasks);
    }

    /**
     * Implements that top-level image single task submission that is defined by
     * the ImageConsumer interface.
     *
     * @param img Image required by the task
     * @param task Task to be run
     * @param blockUntilImageRelease If true, call blocks until the object img
     *            is no longer referred by any task. If false, call is
     *            non-blocking
     * @param closeOnImageRelease If true, images is closed when the object img
     *            is is no longer referred by any task. If false, After an image
     *            is submitted, it should never be submitted again to the
     *            interface until all tasks and their spawned tasks are
     *            finished.
     */
    @Override
    public boolean receiveImage(ImageProxy img, TaskImageContainer task,
            boolean blockUntilImageRelease, boolean closeOnImageRelease, CaptureSession session)
            throws InterruptedException {
        Set<TaskImageContainer> passTasks = new HashSet<TaskImageContainer>(1);
        passTasks.add(task);
        return receiveImage(img, passTasks, blockUntilImageRelease, closeOnImageRelease, session);
    }

    /**
     * Implements that top-level image single task submission that is defined by
     * the ImageConsumer interface.
     *
     * @param img Image required by the task
     * @param tasks A set of Tasks to be run
     * @param blockUntilImageRelease If true, call blocks until the object img
     *            is no longer referred by any task. If false, call is
     *            non-blocking
     * @param closeOnImageRelease If true, images is closed when the object img
     *            is is no longer referred by any task. If false, After an image
     *            is submitted, it should never be submitted again to the
     *            interface until all tasks and their spawned tasks are
     *            finished.
     * @return whether the blocking completed properly
     */
    @Override
    public boolean receiveImage(ImageProxy img, Set<TaskImageContainer> tasks,
            boolean blockUntilImageRelease, boolean closeOnImageRelease, CaptureSession session)
            throws InterruptedException {

        // Short circuit if no tasks submitted.
        if (tasks == null || tasks.size() <= 0) {
            return false;
        }

        if (img == null) {
            // TODO: Determine whether you need to be so strict at the top level
            throw new RuntimeException("ERROR: Initial call must reference valid Image!");
        }

        // Make sure that referred images are all the same, if it exists.
        // And count how image references need to be kept track of.
        int countImageRefs = numPropagatedImageReferences(img, tasks);

        // Set the semaphore, given that the number of tasks that need to be
        // scheduled
        // and the boolean flags for imaging closing and thread blocking
        ImageReleaseProtocol protocol = setSemaphoreReferenceCount(img, countImageRefs,
                blockUntilImageRelease, closeOnImageRelease);

        // Put the tasks on their respective queues.
        scheduleTasks(tasks);

        // Implement blocking if required
        if (protocol.blockUntilRelease) {
            protocol.block();
        }

        return true;
    }

    /**
     * Implements that top-level image task submission short-cut that is defined
     * by the ImageConsumer interface.
     *
     * @param img Image required by the task
     * @param executor Executor to run events and image closes, in case of
     *            control leakage
     * @param processingFlags Magical bit vector that specifies jobs to be run
     *            After an image is submitted, it should never be submitted
     *            again to the interface until all tasks and their spawned tasks
     *            are finished.
     */
    @Override
    public boolean receiveImage(ImageProxy img, Executor executor,
            Set<ImageTaskFlags> processingFlags, CaptureSession session)
            throws InterruptedException {

        Set<TaskImageContainer> tasksToExecute = new HashSet<TaskImageContainer>();

        if (img == null) {
            // No data to process, just pure message.
            return true;
        }

        // Now add the pre-mixed versions of the tasks.

        if (processingFlags.contains(ImageTaskFlags.COMPRESS_IMAGE_TO_JPEG)
                || processingFlags.contains(ImageTaskFlags.WRITE_IMAGE_TO_DISK)) {
            // Add this type of task to the appropriate queue.
            tasksToExecute.add(new TaskCompressImageToJpeg(img, executor, this, session));
        }

        if (processingFlags.contains(ImageTaskFlags.CONVERT_IMAGE_TO_RGB_PREVIEW)) {
            // Add this type of task to the appropriate queue.
            tasksToExecute.add(new TaskConvertImageToRGBPreview(img, executor, this, session, 160,
                    100));
        }

        if (processingFlags.contains(ImageTaskFlags.WRITE_IMAGE_TO_DISK)) {
            // Add this type of task to the appropriate queue.
            // Has a dependency as well on the result JPEG_COMPRESSION
            // TODO: Put disk writing implementation within the framework.
        }

        receiveImage(img, tasksToExecute,
                processingFlags.contains(ImageTaskFlags.BLOCK_UNTIL_IMAGE_RELEASE),
                processingFlags.contains(ImageTaskFlags.CLOSE_IMAGE_ON_RELEASE), session);

        return true;
    }

    /**
     * Factory functions, in case, you want some shake and bake functionality.
     */
    public TaskConvertImageToRGBPreview createTaskConvertImageToRGBPreview(ImageProxy imageProxy,
            Executor executor, ImageBackend imageBackend, CaptureSession session, int targetWidth,
            int targetHeight) {
        return new TaskConvertImageToRGBPreview(imageProxy, executor, imageBackend, session,
                targetWidth,
                targetHeight);
    }

    public TaskCompressImageToJpeg createTaskCompressImageToJpeg(ImageProxy imageProxy,
            Executor executor, ImageBackend imageBackend, CaptureSession session) {
        return new TaskCompressImageToJpeg(imageProxy, executor, imageBackend, session);
    }

    /**
     * Blocks and waits for all tasks to complete.
     */
    @Override
    public void shutdown() {
        mThreadPoolSlow.shutdown();
        mThreadPoolFast.shutdown();
    }

    /**
     * Puts the tasks on the specified queue. May be more complicated in the
     * future.
     *
     * @param tasks The set of tasks to be run
     */
    protected void scheduleTasks(Set<TaskImageContainer> tasks) {
        for (TaskImageContainer task : tasks) {
            if (task.getProcessingPriority() == ProcessingPriority.FAST) {
                mThreadPoolFast.execute(task);
            } else {
                mThreadPoolSlow.execute(task);
            }
        }
    }

    /**
     * Initializes the semaphore count for the image
     *
     * @return The protocol object that keeps tracks of the image reference
     *         count and actions to be taken on release.
     */
    protected ImageReleaseProtocol setSemaphoreReferenceCount(ImageProxy img, int count,
            boolean blockUntilRelease, boolean closeOnRelease) throws RuntimeException {
        synchronized (mImageSemaphoreMap) {
            if (mImageSemaphoreMap.get(img) != null) {
                throw new RuntimeException(
                        "ERROR: Rewriting of Semaphore Lock.  Image references may not freed properly");
            }

            // Create the new booking-keeping object.
            ImageReleaseProtocol protocol = new ImageReleaseProtocol(blockUntilRelease,
                    closeOnRelease);
            protocol.count = count;

            mImageSemaphoreMap.put(img, protocol);
            mOutstandingImageRefs += count;
            mOutstandingImageOpened++;
            logWrapper("Received an opened image: " + mOutstandingImageOpened + "/"
                    + mOutstandingImageClosed);
            logWrapper("Setting an image reference count of " + count + "   Total refs = "
                    + mOutstandingImageRefs);
            return protocol;
        }
    }

    /**
     * Increments the semaphore count for the image. Should ONLY be internally
     * via appendTasks by internal tasks. Otherwise, image references could get
     * out of whack.
     *
     * @return Number of Image references currently held by this instance
     */
    protected void incrementSemaphoreReferenceCount(ImageProxy img, int count)
            throws RuntimeException {
        synchronized (mImageSemaphoreMap) {
            ImageReleaseProtocol protocol = mImageSemaphoreMap.get(img);
            if (mImageSemaphoreMap.get(img) == null) {
                throw new RuntimeException(
                        "Image Reference has already been released or has never been held.");
            }

            protocol.addCount(count);
            mImageSemaphoreMap.put(img, protocol);

            mOutstandingImageRefs += count;
        }
    }

    /**
     * Close an Image with a executor if it's available and does the proper
     * booking keeping on the object.
     *
     * @param img Image to be closed
     * @param executor Executor to be used, if executor is null, the close is
     *            run on the task thread
     */
    private void closeImageExecutorSafe(final ImageProxy img, Executor executor) {
        Runnable closeTask = new Runnable() {
            @Override
            public void run() {
                img.close();
                mOutstandingImageClosed++;
                logWrapper("Release of image occurred.  Good fun. " + "Total Images Open/Closed = "
                        + mOutstandingImageOpened + "/" + mOutstandingImageClosed);
            }
        };
        if (executor == null) {
            // Just run it on the main thread.
            closeTask.run();
        } else {
            executor.execute(closeTask);
        }
    }

    /**
     * Calculates the number of new Image references in a set of dependent
     * tasks. Checks to make sure no new image references are being introduced.
     *
     * @param tasks The set of dependent tasks to be run
     */
    private int numPropagatedImageReferences(ImageProxy img, Set<TaskImageContainer> tasks)
            throws RuntimeException {
        int countImageRefs = 0;
        for (TaskImageContainer task : tasks) {
            if (task.mImageProxy != null && task.mImageProxy != img) {
                throw new RuntimeException("ERROR:  Spawned tasks cannot reference new images!");
            }

            if (task.mImageProxy != null) {
                countImageRefs++;
            }
        }

        return countImageRefs;
    }

    /**
     * A simple tuple class to keep track of image reference, and whether to
     * block and/or close on final image release. Instantiated on every task
     * submission call.
     */
    static private class ImageReleaseProtocol {

        public final boolean blockUntilRelease;

        public final boolean closeOnRelease;

        private int count;

        private final ReentrantLock mLock = new ReentrantLock();

        private Condition mSignal;

        // TODO: Backport to Reentrant lock
        public void setCount(int value) {
            mLock.lock();
            count = value;
            mLock.unlock();
        }

        public int getCount() {
            int value;
            mLock.lock();
            value = count;
            mLock.unlock();
            return value;
        }

        public void addCount(int value) {
            mLock.lock();
            count += value;
            mLock.unlock();
        }

        ImageReleaseProtocol(boolean block, boolean close) {
            blockUntilRelease = block;
            closeOnRelease = close;
            count = 0;
            mSignal = mLock.newCondition();
        }

        public void block() throws InterruptedException {
            mLock.lock();
            try {
                while (count != 0) {
                    // Spin to deal with spurious signals.
                    mSignal.await();
                }
                mLock.unlock();
            } catch (InterruptedException e) {
                // TODO: on interruption, figure out what to do.
                throw (e);
            }
        }

        public void signal() {
            mLock.lock();
            mSignal.signal();
            mLock.unlock();
        }

    }

    // Thread factories for a default constructor
    private class FastThreadFactory implements ThreadFactory {

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setPriority(FAST_THREAD_PRIORITY);
            return t;
        }
    }

    private class SlowThreadFactory implements ThreadFactory {

        public Thread newThread(Runnable r) {
            Thread t = new Thread(r);
            t.setPriority(SLOW_THREAD_PRIORITY);
            return t;
        }
    }

    // TODO: Remove with a better implementation. Just to avoid
    // the GC not getting rid of elements. Should be hooked up to properly
    // implemented memory pool.
    public class SimpleCache extends ArrayList<byte[]> {

        public SimpleCache(int numEntries) {
            super(numEntries);
        }

        public synchronized void cacheSave(byte[] mem) {
            add(mem);
        }

        public synchronized byte[] cacheGet() {
            if (size() < 1) {
                return null;
            } else {
                return mSimpleCache.remove(0);
            }
        }
    }

}
