/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.display;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.IVirtualDisplayCallback;
import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionCallback;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemProperties;
import android.os.IBinder.DeathRecipient;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;

import java.io.PrintWriter;

/**
 * A display adapter that provides virtual displays on behalf of applications.
 * <p>
 * Display adapters are guarded by the {@link DisplayManagerService.SyncRoot} lock.
 * </p>
 */
final class VirtualDisplayAdapter extends DisplayAdapter {
    static final String TAG = "VirtualDisplayAdapter";
    static final boolean DEBUG = false;

    private final ArrayMap<IBinder, VirtualDisplayDevice> mVirtualDisplayDevices =
            new ArrayMap<IBinder, VirtualDisplayDevice>();
    private Handler mHandler;

    // Called with SyncRoot lock held.
    public VirtualDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener) {
        super(syncRoot, context, handler, listener, TAG);
        mHandler = handler;
    }

    public DisplayDevice createVirtualDisplayLocked(IVirtualDisplayCallback callback,
            IMediaProjection projection, int ownerUid, String ownerPackageName,
            String name, int width, int height, int densityDpi, Surface surface, int flags) {
        boolean secure = (flags & DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE) != 0;
        IBinder appToken = callback.asBinder();
        IBinder displayToken = SurfaceControl.createDisplay(name, secure);
        VirtualDisplayDevice device = new VirtualDisplayDevice(displayToken, appToken,
                ownerUid, ownerPackageName, name, width, height, densityDpi, surface, flags,
                new Callback(callback, mHandler));

        mVirtualDisplayDevices.put(appToken, device);

        try {
            if (projection != null) {
                projection.registerCallback(new MediaProjectionCallback(appToken));
            }
            appToken.linkToDeath(device, 0);
        } catch (RemoteException ex) {
            mVirtualDisplayDevices.remove(appToken);
            device.destroyLocked();
            return null;
        }

        // Return the display device without actually sending the event indicating
        // that it was added.  The caller will handle it.
        return device;
    }

    public void resizeVirtualDisplayLocked(IBinder appToken,
            int width, int height, int densityDpi) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.get(appToken);
        if (device != null) {
            device.resizeLocked(width, height, densityDpi);
        }
    }


    public void setVirtualDisplaySurfaceLocked(IBinder appToken, Surface surface) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.get(appToken);
        if (device != null) {
            device.setSurfaceLocked(surface);
        }
    }

    public DisplayDevice releaseVirtualDisplayLocked(IBinder appToken) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.remove(appToken);
        if (device != null) {
            device.destroyLocked();
            appToken.unlinkToDeath(device, 0);
        }

        // Return the display device that was removed without actually sending the
        // event indicating that it was removed.  The caller will handle it.
        return device;
    }

    private void handleBinderDiedLocked(IBinder appToken) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.remove(appToken);
        if (device != null) {
            Slog.i(TAG, "Virtual display device released because application token died: "
                    + device.mOwnerPackageName);
            device.destroyLocked();
            sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_REMOVED);
        }
    }

    private void handleMediaProjectionStoppedLocked(IBinder appToken) {
        VirtualDisplayDevice device = mVirtualDisplayDevices.remove(appToken);
        if (device != null) {
            Slog.i(TAG, "Virtual display device released because media projection stopped: "
                    + device.mName);
            device.stopLocked();
        }
    }

    private final class VirtualDisplayDevice extends DisplayDevice implements DeathRecipient {
        private static final int PENDING_SURFACE_CHANGE = 0x01;
        private static final int PENDING_RESIZE = 0x02;

        private final IBinder mAppToken;
        private final int mOwnerUid;
        final String mOwnerPackageName;
        final String mName;
        private final int mFlags;
        private final Callback mCallback;

        private int mWidth;
        private int mHeight;
        private int mDensityDpi;
        private Surface mSurface;
        private DisplayDeviceInfo mInfo;
        private int mDisplayState;
        private boolean mStopped;
        private int mPendingChanges;

        public VirtualDisplayDevice(IBinder displayToken, IBinder appToken,
                int ownerUid, String ownerPackageName,
                String name, int width, int height, int densityDpi, Surface surface, int flags,
                Callback callback) {
            super(VirtualDisplayAdapter.this, displayToken);
            mAppToken = appToken;
            mOwnerUid = ownerUid;
            mOwnerPackageName = ownerPackageName;
            mName = name;
            mWidth = width;
            mHeight = height;
            mDensityDpi = densityDpi;
            mSurface = surface;
            mFlags = flags;
            mCallback = callback;
            mDisplayState = Display.STATE_UNKNOWN;
            mPendingChanges |= PENDING_SURFACE_CHANGE;
        }

        @Override
        public void binderDied() {
            synchronized (getSyncRoot()) {
                if (mSurface != null) {
                    handleBinderDiedLocked(mAppToken);
                }
            }
        }

        public void destroyLocked() {
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            SurfaceControl.destroyDisplay(getDisplayTokenLocked());
            mCallback.dispatchDisplayStopped();
        }

        @Override
        public Runnable requestDisplayStateLocked(int state) {
            if (state != mDisplayState) {
                mDisplayState = state;
                if (state == Display.STATE_OFF) {
                    mCallback.dispatchDisplayPaused();
                } else {
                    mCallback.dispatchDisplayResumed();
                }
            }
            return null;
        }

        @Override
        public void performTraversalInTransactionLocked() {
            if ((mPendingChanges & PENDING_RESIZE) != 0) {
                SurfaceControl.setDisplaySize(getDisplayTokenLocked(), mWidth, mHeight);
            }
            if ((mPendingChanges & PENDING_SURFACE_CHANGE) != 0) {
                setSurfaceInTransactionLocked(mSurface);
            }
            mPendingChanges = 0;
        }

        public void setSurfaceLocked(Surface surface) {
            if (!mStopped && mSurface != surface) {
                if ((mSurface != null) != (surface != null)) {
                    sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
                }
                sendTraversalRequestLocked();
                mSurface = surface;
                mInfo = null;
                mPendingChanges |= PENDING_SURFACE_CHANGE;
            }
        }

        public void resizeLocked(int width, int height, int densityDpi) {
            if (mWidth != width || mHeight != height || mDensityDpi != densityDpi) {
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
                sendTraversalRequestLocked();
                mWidth = width;
                mHeight = height;
                mDensityDpi = densityDpi;
                mInfo = null;
                mPendingChanges |= PENDING_RESIZE;
            }
        }

        public void stopLocked() {
            setSurfaceLocked(null);
            mStopped = true;
        }

        @Override
        public void dumpLocked(PrintWriter pw) {
            super.dumpLocked(pw);
            pw.println("mFlags=" + mFlags);
            pw.println("mDisplayState=" + Display.stateToString(mDisplayState));
            pw.println("mStopped=" + mStopped);
        }


        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                mInfo = new DisplayDeviceInfo();
                mInfo.name = mName;
                mInfo.width = mWidth;
                mInfo.height = mHeight;
                mInfo.refreshRate = 60;
                mInfo.supportedRefreshRates = new float[] { 60.0f };
                mInfo.densityDpi = mDensityDpi;
                mInfo.xDpi = mDensityDpi;
                mInfo.yDpi = mDensityDpi;
                mInfo.presentationDeadlineNanos = 1000000000L / (int) mInfo.refreshRate; // 1 frame
                mInfo.flags = 0;
                if ((mFlags & DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) == 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_PRIVATE
                            | DisplayDeviceInfo.FLAG_NEVER_BLANK;
                }
                if ((mFlags & DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR) != 0) {
                    mInfo.flags &= ~DisplayDeviceInfo.FLAG_NEVER_BLANK;
                } else {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY;
                }

                if ((mFlags & DisplayManager.VIRTUAL_DISPLAY_FLAG_SECURE) != 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_SECURE;
                }
                if ((mFlags & DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION) != 0) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_PRESENTATION;

                    if ((mFlags & DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC) != 0) {
                        // For demonstration purposes, allow rotation of the external display.
                        // In the future we might allow the user to configure this directly.
                        if ("portrait".equals(SystemProperties.get(
                                "persist.demo.remoterotation"))) {
                            mInfo.rotation = Surface.ROTATION_270;
                        }
                    }
                }
                mInfo.type = Display.TYPE_VIRTUAL;
                mInfo.touch = DisplayDeviceInfo.TOUCH_NONE;
                mInfo.state = mSurface != null ? Display.STATE_ON : Display.STATE_OFF;
                mInfo.ownerUid = mOwnerUid;
                mInfo.ownerPackageName = mOwnerPackageName;
            }
            return mInfo;
        }
    }

    private static class Callback extends Handler {
        private static final int MSG_ON_DISPLAY_PAUSED = 0;
        private static final int MSG_ON_DISPLAY_RESUMED = 1;
        private static final int MSG_ON_DISPLAY_STOPPED = 2;

        private final IVirtualDisplayCallback mCallback;

        public Callback(IVirtualDisplayCallback callback, Handler handler) {
            super(handler.getLooper());
            mCallback = callback;
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case MSG_ON_DISPLAY_PAUSED:
                        mCallback.onPaused();
                        break;
                    case MSG_ON_DISPLAY_RESUMED:
                        mCallback.onResumed();
                        break;
                    case MSG_ON_DISPLAY_STOPPED:
                        mCallback.onStopped();
                        break;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to notify listener of virtual display event.", e);
            }
        }

        public void dispatchDisplayPaused() {
            sendEmptyMessage(MSG_ON_DISPLAY_PAUSED);
        }

        public void dispatchDisplayResumed() {
            sendEmptyMessage(MSG_ON_DISPLAY_RESUMED);
        }

        public void dispatchDisplayStopped() {
            sendEmptyMessage(MSG_ON_DISPLAY_STOPPED);
        }
    }

    private final class MediaProjectionCallback extends IMediaProjectionCallback.Stub {
        private IBinder mAppToken;
        public MediaProjectionCallback(IBinder appToken) {
            mAppToken = appToken;
        }

        @Override
        public void onStop() {
            synchronized (getSyncRoot()) {
                handleMediaProjectionStoppedLocked(mAppToken);
            }
        }
    }
}
