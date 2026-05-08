package com.bitaim.carromaim;

import android.app.Application;
import android.util.Log;

import com.bitaim.carromaim.overlay.OverlayPackage;
import com.facebook.react.PackageList;
import com.facebook.react.ReactApplication;
import com.facebook.react.ReactNativeHost;
import com.facebook.react.ReactPackage;
import com.facebook.soloader.SoLoader;

import org.opencv.android.OpenCVLoader;

import java.util.List;

public class MainApplication extends Application implements ReactApplication {

    private static final String TAG = "BitAim";

    private final ReactNativeHost mReactNativeHost = new ReactNativeHost(this) {
        @Override public boolean getUseDeveloperSupport() { return false; }

        @Override
        protected List<ReactPackage> getPackages() {
            List<ReactPackage> packages = new PackageList(this).getPackages();
            packages.add(new OverlayPackage());
            return packages;
        }

        @Override
        protected String getJSMainModuleName() { return "index"; }
    };

    @Override public ReactNativeHost getReactNativeHost() { return mReactNativeHost; }

    @Override
    public void onCreate() {
        super.onCreate();
        SoLoader.init(this, false);
        if (!OpenCVLoader.initDebug()) {
            Log.e(TAG, "OpenCV init failed — auto-detect will not work");
        } else {
            Log.i(TAG, "OpenCV initialised");
        }
    }
}
