package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.log;
import static com.rikumi.colorosmod.XposedInit.readBool;
import static com.rikumi.colorosmod.XposedInit.readInt;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.widget.ImageView;

import com.rikumi.colorosmod.xposed.XC_LoadPackage;
import com.rikumi.colorosmod.xposed.XC_MethodHook;
import com.rikumi.colorosmod.xposed.XposedBridge;
import com.rikumi.colorosmod.xposed.XposedHelpers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 状态栏通知图标：尺寸缩放 + 主题图标。
 *
 * 尺寸：沿用 MonochromeIconGeometryHooks 的容器测量入口调整占位宽度，
 * 并在 StatusBarIconView#maybeUpdateIconScaleDimens 之后把 mIconScale 乘上用户比例，
 * 绘制尺寸随之缩放。
 *
 * 主题图标：ColorOS 主题商店的主题图标包落在 /data/theme/icons（zip），
 * 内含 allApps.xml（图标文件名 -> 包名）与 res/drawable-xxhdpi/*.png。
 * SystemUI 与桌面同为 platform_app 域，SELinux 允许读取 oem_theme_data_file。
 * 命中时直接把彩色主题图标设为状态栏图标的 drawable，并跳过系统的单色着色。
 */
public final class StatusBarNotificationIcons {
    public static final String KEY_SIZE_ENABLED = "statusbar_notification_icon_size_enabled";
    public static final String KEY_SIZE_PERCENT = "statusbar_notification_icon_size_percent";
    public static final String KEY_THEME_STYLE = "statusbar_notification_icon_style";
    public static final int STYLE_DEFAULT = 0;
    public static final int STYLE_THEME = 1;

    private static final String ICON_VIEW = "com.android.systemui.statusbar.StatusBarIconView";
    private static final String ICON_CONTAINER =
            "com.android.systemui.statusbar.phone.NotificationIconContainer";
    private static final String OPLUS_CONTROLLER =
            "com.oplus.systemui.statusbar.phone.StatusBarIconControllerExImpl";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 状态栏通知图标容器里的图标视图；只处理这些，锁屏/AOD/永不复位。 */
    private static final Map<View, Boolean> STATUS_BAR_ICONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 当前已被替换成主题图标的视图。 */
    private static final Map<View, Boolean> THEMED =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> RESTORING = new ThreadLocal<>();
    private static volatile boolean active = true;

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        hookContainer(lp);
        hookIconScale(lp);
        hookThemeDrawable(lp);
        hookThemeColor(lp);
        log("HOOK OK status bar notification icon size/theme");
    }

    static float iconScale() {
        if (!active || !readBool(KEY_SIZE_ENABLED, false)) return 1f;
        int percent = readInt(KEY_SIZE_PERCENT, 100);
        if (percent < 40) percent = 40;
        if (percent > 200) percent = 200;
        return percent / 100f;
    }

    static boolean themeEnabled() {
        return active && readInt(KEY_THEME_STYLE, STYLE_DEFAULT) == STYLE_THEME;
    }

    private static void hookContainer(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> container = XposedHelpers.findClass(ICON_CONTAINER, lp.classLoader);
            XposedHelpers.findAndHookMethod(container, "onViewAdded", View.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.args[0] instanceof View view)) return;
                    STATUS_BAR_ICONS.put(view, Boolean.TRUE);
                    if (!view.getClass().getName().equals(ICON_VIEW)) return;
                    apply(view);
                }
            });
            XposedHelpers.findAndHookMethod(container, "onViewRemoved", View.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    STATUS_BAR_ICONS.remove(p.args[0]);
                    THEMED.remove(p.args[0]);
                }
            });
        } catch (Throwable t) { log("HOOK FAIL notification icon container: " + t); }
    }

    private static void hookIconScale(XC_LoadPackage.LoadPackageParam lp) {
        try {
            XposedHelpers.findAndHookMethod(ICON_VIEW, lp.classLoader, "maybeUpdateIconScaleDimens",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            float scale = iconScale();
                            if (!(p.thisObject instanceof View view) || scale == 1f) return;
                            try {
                                float current = XposedHelpers.getFloatField(view, "mIconScale");
                                XposedHelpers.setFloatField(view, "mIconScale", current * scale);
                                view.invalidate();
                            } catch (Throwable ignored) { }
                        }
                    });
        } catch (Throwable t) { log("HOOK FAIL StatusBarIconView#maybeUpdateIconScaleDimens: " + t); }
    }

    private static void hookThemeDrawable(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> icon = XposedHelpers.findClass(ICON_VIEW, lp.classLoader);
            XposedBridge.hookAllMethods(icon, "updateDrawable", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject instanceof View view) apply(view);
                }
            });
        } catch (Throwable t) { log("HOOK FAIL StatusBarIconView#updateDrawable: " + t); }
    }

    /** 系统会把通知小图标染成单色/灰度；主题图标要保持彩色。 */
    private static void hookThemeColor(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> icon = XposedHelpers.findClass(ICON_VIEW, lp.classLoader);
            for (Method method : icon.getDeclaredMethods()) {
                if (!method.getName().startsWith("updateIconColor")) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!(p.thisObject instanceof ImageView view) || !THEMED.containsKey(view)) return;
                        p.setResult(null);
                    }
                });
            }
        } catch (Throwable t) { log("HOOK FAIL StatusBarIconView#updateIconColor: " + t); }
        try {
            Class<?> controller = XposedHelpers.findClass(OPLUS_CONTROLLER, lp.classLoader);
            XposedBridge.hookAllMethods(controller, "updateStatusBarIconGrayScale", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    for (Object arg : p.args) {
                        if (arg instanceof ImageView view && THEMED.containsKey(view)) {
                            p.setResult(null);
                            return;
                        }
                    }
                }
            });
            log("HOOK OK StatusBarIconControllerExImpl#updateStatusBarIconGrayScale");
        } catch (Throwable t) { log("HOOK FAIL updateStatusBarIconGrayScale: " + t); }
    }

    private static void apply(View view) {
        if (!active || !view.getClass().getName().equals(ICON_VIEW)) return;
        if (Boolean.TRUE.equals(RESTORING.get())) return;
        if (!STATUS_BAR_ICONS.containsKey(view)) return;
        try {
            if (!themeEnabled()) {
                if (THEMED.remove(view) != null) restore(view);
                return;
            }
            Drawable drawable = ThemeIconPack.drawable(packageOf(view));
            ImageView icon = (ImageView) view;
            if (drawable == null) {
                if (THEMED.remove(view) != null) restore(view);
                return;
            }
            THEMED.put(view, Boolean.TRUE);
            icon.clearColorFilter();
            icon.setImageDrawable(drawable);
        } catch (Throwable t) { log("theme notification icon apply failed: " + t); }
    }

    private static void restore(View view) {
        if (Boolean.TRUE.equals(RESTORING.get())) return;
        RESTORING.set(Boolean.TRUE);
        try {
            XposedHelpers.callMethod(view, "updateDrawable", true);
        } catch (Throwable ignored) {
            try { XposedHelpers.callMethod(view, "updateDrawable"); } catch (Throwable ignored2) { }
        } finally { RESTORING.remove(); }
    }

    private static String packageOf(View view) {
        try {
            Object sbn = XposedHelpers.getObjectField(view, "mNotification");
            if (sbn instanceof StatusBarNotification notification) return notification.getPackageName();
        } catch (Throwable ignored) { }
        try {
            Object slot = XposedHelpers.callMethod(view, "getSlot");
            if (slot instanceof String text) {
                String[] parts = text.split("\\|");
                if (parts.length >= 2 && !parts[1].isEmpty()) return parts[1];
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public static void refresh() {
        MAIN.post(() -> {
            if (!active) return;
            // 宽度由 MonochromeIconGeometryHooks 的容器测量入口统一按比例重算。
            MonochromeIconGeometryHooks.refresh();
            java.util.ArrayList<View> views;
            synchronized (STATUS_BAR_ICONS) { views = new java.util.ArrayList<>(STATUS_BAR_ICONS.keySet()); }
            for (View view : views) {
                if (view == null) continue;
                try {
                    XposedHelpers.callMethod(view, "maybeUpdateIconScaleDimens");
                    if (view.getParent() instanceof View parent) parent.requestLayout();
                    if (themeEnabled()) apply(view); else if (THEMED.remove(view) != null) restore(view);
                    view.invalidate();
                } catch (Throwable t) { log("refresh status bar notification icon failed: " + t); }
            }
        });
    }

    public static void cleanup() {
        active = false;
        MAIN.removeCallbacksAndMessages(null);
        java.util.ArrayList<View> views;
        synchronized (STATUS_BAR_ICONS) { views = new java.util.ArrayList<>(STATUS_BAR_ICONS.keySet()); }
        for (View view : views) {
            if (view != null && THEMED.remove(view) != null) restore(view);
        }
        RESTORING.remove();
        ThemeIconPack.reset();
        MonochromeIconGeometryHooks.refresh();
    }

    /**
     * 主题商店图标包读取器。
     * /data/theme/icons 为 zip：allApps.xml 给出 图标文件名 -> 包名，图标本体在 res/drawable-* 下。
     * 主题未覆盖某应用时 allApps.xml 里没有对应条目，此时返回 null 由调用方保留系统图标。
     */
    private static final class ThemeIconPack {
        private static final File ARCHIVE = new File("/data/theme/icons");
        private static final Pattern ICON = Pattern.compile("<icon\\b([^>]*)>");
        private static final Pattern NAME = Pattern.compile("\\bname\\s*=\\s*\"([^\"]+)\"");
        private static final Pattern PACKAGE = Pattern.compile("\\bpackage\\s*=\\s*\"([^\"]+)\"");
        private static final int MAX_DRAWABLES = 24;
        private static final Map<String, Drawable> CACHE = new LinkedHashMap<>(16, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Drawable> eldest) {
                return size() > MAX_DRAWABLES;
            }
        };
        private static ZipFile archive;
        private static Map<String, String> packages;
        private static Map<String, String> files;
        private static long stamp;
        private static boolean logged;

        static synchronized Drawable drawable(String pkg) {
            if (pkg == null || pkg.isEmpty() || !open()) return null;
            String entry = packages.get(pkg);
            if (entry == null) return null;
            Drawable cached = CACHE.get(entry);
            if (cached != null) return cached;
            try {
                ZipEntry item = archive.getEntry(entry);
                if (item == null) return null;
                Bitmap bitmap;
                try (InputStream in = archive.getInputStream(item)) { bitmap = BitmapFactory.decodeStream(in); }
                if (bitmap == null) return null;
                bitmap.setDensity(Bitmap.DENSITY_NONE);
                Drawable drawable = new BitmapDrawable(null, bitmap);
                CACHE.put(entry, drawable);
                return drawable;
            } catch (Throwable t) {
                if (!logged) { logged = true; log("theme icon decode failed: " + t); }
                return null;
            }
        }

        private static boolean open() {
            long modified = ARCHIVE.isFile() ? ARCHIVE.lastModified() : -1L;
            if (archive != null && modified == stamp) return true;
            if (modified <= 0L) return false;
            close();
            try {
                ZipFile zip = new ZipFile(ARCHIVE);
                Map<String, String> names = new HashMap<>();
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry item = entries.nextElement();
                    String path = item.getName();
                    if (item.isDirectory()) continue;
                    if (!path.endsWith(".png") && !path.endsWith(".webp")) continue;
                    names.putIfAbsent(path.substring(path.lastIndexOf('/') + 1), path);
                }
                Map<String, String> owned = parse(zip, names);
                if (owned == null || owned.isEmpty()) { zip.close(); return false; }
                archive = zip;
                packages = owned;
                files = names;
                stamp = modified;
                return true;
            } catch (Throwable t) {
                if (!logged) { logged = true; log("theme icon pack open failed: " + t); }
                close();
                return false;
            }
        }

        private static Map<String, String> parse(ZipFile zip, Map<String, String> names) throws Exception {
            ZipEntry all = zip.getEntry("allApps.xml");
            if (all == null) return null;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream in = zip.getInputStream(all)) {
                byte[] chunk = new byte[8192];
                int read;
                while ((read = in.read(chunk)) > 0) buffer.write(chunk, 0, read);
            }
            String xml = buffer.toString("UTF-8");
            Map<String, String> result = new HashMap<>();
            Matcher icon = ICON.matcher(xml);
            while (icon.find()) {
                String attributes = icon.group(1);
                Matcher name = NAME.matcher(attributes);
                Matcher packageName = PACKAGE.matcher(attributes);
                if (!name.find() || !packageName.find()) continue;
                String entry = names.get(name.group(1));
                if (entry == null) continue;
                result.put(packageName.group(1), entry);
            }
            return result;
        }

        private static void close() {
            try { if (archive != null) archive.close(); } catch (Throwable ignored) { }
            archive = null;
            packages = null;
            files = null;
            CACHE.clear();
        }

        static synchronized void reset() {
            close();
            stamp = 0L;
        }

        private ThemeIconPack() { }
    }

    private StatusBarNotificationIcons() { }
}
