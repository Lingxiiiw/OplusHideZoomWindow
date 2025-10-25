package moe.xzr.oplushidezoomwindow;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Entry implements IXposedHookLoadPackage {
    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"android".equals(lpparam.packageName)) return;

        XposedBridge.log("OplusHideZoom: hooking ColorOS WindowSurfaceController");

        int FLAG_SECURE = 0x00000040;
        try {
            Class<?> lpClass = XposedHelpers.findClass("android.view.WindowManager$LayoutParams", lpparam.classLoader);
            FLAG_SECURE = XposedHelpers.getStaticIntField(lpClass, "FLAG_SECURE");
        } catch (Throwable t) {
            XposedBridge.log("OplusHideZoom: fallback FLAG_SECURE");
        }

        Class<?> windowStateAnimatorClass =
                XposedHelpers.findClass("com.android.server.wm.WindowStateAnimator", lpparam.classLoader);
        Class<?> surfaceSessionClass =
                XposedHelpers.findClass("android.view.SurfaceSession", lpparam.classLoader);

        Class<?> oplusExtClass = null;
        try {
            oplusExtClass = XposedHelpers.findClass("com.oplus.wms.OplusWindowSurfaceControllerExt", lpparam.classLoader);
            XposedBridge.log("OplusHideZoom: found OplusWindowSurfaceControllerExt");
        } catch (Throwable ignored) {}

        // Hook 带 Oplus 扩展的构造
        try {
            if (oplusExtClass != null) {
                XposedHelpers.findAndHookConstructor(
                        "com.android.server.wm.WindowSurfaceController",
                        lpparam.classLoader,
                        windowStateAnimatorClass,
                        surfaceSessionClass,
                        String.class,
                        int.class,
                        int.class,
                        oplusExtClass,
                        new WindowHook(FLAG_SECURE, windowStateAnimatorClass));
                XposedBridge.log("OplusHideZoom: hooked with Oplus ext signature");
            }
        } catch (Throwable e) {
            XposedBridge.log("OplusHideZoom: hook with Oplus ext failed: " + e);
        }

        // Hook 无 Oplus 扩展构造
        try {
            XposedHelpers.findAndHookConstructor(
                    "com.android.server.wm.WindowSurfaceController",
                    lpparam.classLoader,
                    windowStateAnimatorClass,
                    surfaceSessionClass,
                    String.class,
                    int.class,
                    int.class,
                    new WindowHook(FLAG_SECURE, windowStateAnimatorClass));
            XposedBridge.log("OplusHideZoom: hooked default WindowSurfaceController");
        } catch (Throwable e) {
            XposedBridge.log("OplusHideZoom: default hook failed: " + e);
        }

        // Part 2 – 去掉阴影
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.Task",
                    lpparam.classLoader,
                    "getShadowRadius",
                    boolean.class,
                    XC_MethodReplacement.returnConstant(0.0f));
        } catch (Throwable t) {
            XposedBridge.log("OplusHideZoom: failed to hook getShadowRadius: " + t);
        }

        // Part 3 – 重建 Surface
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.server.wm.Task",
                    lpparam.classLoader,
                    "setWindowingModeInSurfaceTransaction",
                    int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object windowState = XposedHelpers.callMethod(param.thisObject, "getTopVisibleAppMainWindow");
                                Object root = XposedHelpers.getObjectField(param.thisObject, "mRootWindowContainer");
                                if (windowState != null) {
                                    XposedHelpers.callMethod(windowState, "destroySurfaceUnchecked");
                                    if (root != null)
                                        XposedHelpers.callMethod(root, "resumeFocusedTasksTopActivities");
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable e) {
            XposedBridge.log("OplusHideZoom: failed to hook setWindowingModeInSurfaceTransaction: " + e);
        }
    }

    // -----------------------------
    // Hook 核心逻辑提取
    // -----------------------------
    static class WindowHook extends XC_MethodHook {
        private final int FLAG_SECURE;
        private final Class<?> windowStateAnimatorClass;

        WindowHook(int flag, Class<?> animatorCls) {
            this.FLAG_SECURE = flag;
            this.windowStateAnimatorClass = animatorCls;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object winAnimator = null;
                for (Object arg : param.args) {
                    if (arg != null && windowStateAnimatorClass.isInstance(arg)) {
                        winAnimator = arg;
                        break;
                    }
                }
                if (winAnimator == null) return;

                Object winState = XposedHelpers.getObjectField(winAnimator, "mWin");
                String tag = String.valueOf(XposedHelpers.callMethod(winState, "getWindowTag"));
                String pkg = (String) XposedHelpers.callMethod(winState, "getOwningPackage");

                int flagsIndex = -1;
                for (int i = param.args.length - 1; i >= 0; i--) {
                    if (param.args[i] instanceof Integer) {
                        flagsIndex = i;
                        break;
                    }
                }
                if (flagsIndex == -1) return;

                int flags = (int) param.args[flagsIndex];
                boolean needHide = false;

                if (tag.contains("LongshotCapture")
                        || tag.contains("ZoomFloatHandleView")
                        || "InputMethod".equals(tag)
                        || "com.oplus.appplatform".equals(pkg)
                        || "com.coloros.smartsidebar".equals(pkg)) {
                    needHide = true;
                }

                // 检查是否为 Zoom 窗口
                try {
                    Object ext = XposedHelpers.getObjectField(winState, "mWindowStateExt");
                    if (ext != null) {
                        int mode = (int) XposedHelpers.callMethod(winState, "getWindowingMode");
                        boolean zoom = (boolean) XposedHelpers.callMethod(ext, "checkIfWindowingModeZoom", mode);
                        if (zoom) needHide = true;
                    }
                } catch (Throwable ignored) {}

                if (needHide) {
                    flags |= FLAG_SECURE;
                    param.args[flagsIndex] = flags;
                    XposedBridge.log("OplusHideZoom: FLAG_SECURE -> " + tag + " / " + pkg);
                }
            } catch (Throwable t) {
                XposedBridge.log("OplusHideZoom: error in beforeHookedMethod: " + t);
            }
        }
    }
}