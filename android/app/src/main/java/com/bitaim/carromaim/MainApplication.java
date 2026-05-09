package com.bitaim.carromaim;

import android.app.Application;
import android.util.Log;

import com.bitaim.carromaim.overlay.OverlayPackage;
import com.facebook.react.PackageList;
import com.facebook.react.ReactApplication;
import com.facebook.react.ReactNativeHost;
import com.facebook.react.ReactPackage;
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint;
import com.facebook.react.defaults.DefaultReactNativeHost;
import com.facebook.soloader.SoLoader;

import org.opencv.android.OpenCVLoader;

import java.util.List;

/**
 * MainApplication — React Native 0.72 + Hermes.
 *
 * MUST extend DefaultReactNativeHost (not the deprecated ReactNativeHost).
 * DefaultReactNativeHost reads hermesEnabled / newArchEnabled from gradle.properties
 * and properly initialises the Hermes JS engine.  Using the old ReactNativeHost
 * without isHermesEnabled() causes the app to attempt loading JavaScriptCore (JSC),
 * which is NOT bundled in Hermes-only builds → UnsatisfiedLinkError → instant crash.
 */
public class MainApplication extends Application implements ReactApplication {

    private static final String TAG = "BitAim";

    private final ReactNativeHost mReactNativeHost = new DefaultReactNativeHost(this) {

        /** false = load bundle from APK assets (correct for a standalone release/debug APK). */
        @Override
        public boolean getUseDeveloperSupport() { return false; }

        @Override
        protected List<ReactPackage> getPackages() {
            List<ReactPackage> packages = new PackageList(this).getPackages();
            packages.add(new OverlayPackage());
            return packages;
        }

        @Override
        protected String getJSMainModuleName() { return "index"; }

        /**
         * New Architecture (Fabric / TurboModules) is disabled.
         * Reads from gradle.properties newArchEnabled=false.
         */
        @Override
        protected boolean isNewArchEnabled() {
            return DefaultNewArchitectureEntryPoint.getFabricEnabled();
        }

        /**
         * CRITICAL: must return true so that RN 0.72 loads Hermes (libhermes.so)
         * instead of JSC (libjsc.so).  JSC is not bundled → crash without this.
         */
        @Override
        protected Boolean isHermesEnabled() { return true; }
    };

    @Override
    public ReactNativeHost getReactNativeHost() { return mReactNativeHost; }

    @Override
    public void onCreate() {
        super.onCreate();

        // SoLoader must come first — it bootstraps native library loading for React Native.
        SoLoader.init(this, false);

        // OpenCV init — wrapped in try/catch so a missing .so is non-fatal.
        // The overlay still works for manual aim; auto-detect is simply disabled.
        try {
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "OpenCV init failed — auto-detect will not work");
            } else {
                Log.i(TAG, "OpenCV initialised successfully");
            }
        } catch (Throwable t) {
            Log.e(TAG, "OpenCV load error (non-fatal): " + t.getMessage());
        }
    }
}
