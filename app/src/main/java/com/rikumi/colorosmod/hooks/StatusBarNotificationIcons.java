package com.rikumi.colorosmod.hooks;

import static com.rikumi.colorosmod.XposedInit.log;
import static com.rikumi.colorosmod.XposedInit.readBool;
import static com.rikumi.colorosmod.XposedInit.readInt;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
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
 *
 * 触发点：IconManager#setIcon 是每次通知图标更新的必经之路，作为主入口；
 * updateDrawable/set 补刀；onDraw 只做兜底(引用相同即跳过)，避免每帧重复设图。
 */
public final class StatusBarNotificationIcons {
    public static final String KEY_SIZE_ENABLED = "statusbar_notification_icon_size_enabled";
    public static final String KEY_SIZE_PERCENT = "statusbar_notification_icon_size_percent";
    public static final String KEY_THEME_ENABLED = "statusbar_notification_icon_theme_enabled";
    /** 通知中心列表里的通知图标也用主题图标。 */
    public static final String KEY_LIST_THEME_ENABLED = "notification_list_theme_icons_enabled";

    private static final String ICON_VIEW = "com.android.systemui.statusbar.StatusBarIconView";
    private static final String ICON_CONTAINER =
            "com.android.systemui.statusbar.phone.NotificationIconContainer";
    private static final String ICON_MANAGER =
            "com.android.systemui.statusbar.notification.icon.IconManager";
    private static final String STATUS_BAR_ICON = "com.android.internal.statusbar.StatusBarIcon";
    private static final String OPLUS_CONTROLLER =
            "com.oplus.systemui.statusbar.phone.StatusBarIconControllerExImpl";
    /** 通知中心列表行图标的视图类（framework 内部类）。 */
    private static final String ROW_ICON_VIEW = "com.android.internal.widget.NotificationRowIconView";
    /** 通知行图标的数据源提供者（SystemUI 内部类），提供 shouldShowAppIcon/getAppIcon。 */
    private static final String[] ROW_ICON_PROVIDERS = {
            "com.android.systemui.statusbar.notification.row.icon.NotificationRowIconViewInflaterFactory$createIconProvider$1",
            "com.android.systemui.statusbar.notification.row.icon.NotificationRowIconViewInflaterFactory$createIconProvider$2",
    };

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 状态栏通知图标容器里的图标视图；只处理这些，锁屏/AOD 视图不碰。 */
    private static final Map<View, Boolean> STATUS_BAR_ICONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 通知中心列表里出现过的行图标视图，切换开关时用来重新加载。 */
    private static final Map<View, Boolean> ROW_ICONS =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 当前已被替换成主题图标的视图。 */
    private static final Map<View, Boolean> THEMED =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** 图标视图 -> 应用包名；由 setIcon/updateDrawable 写入，onDraw 只读。 */
    private static final Map<View, String> PACKAGES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> RESTORING = new ThreadLocal<>();
    private static volatile boolean active = true;
    /** StatusBarIconView 本身；ColorOS 实际使用的是其子类 StatIconView，必须按 isInstance 判断。 */
    private static volatile Class<?> iconViewClass;
    /** NotificationIconContainer 本身；用于按父容器识别状态栏图标视图。 */
    private static volatile Class<?> containerClass;
    /** 主题图标开关的缓存值，null 表示还没读过；refresh() 时清空。 */
    private static volatile Boolean themeCached;
    /** 通知中心列表主题图标开关的缓存值。 */
    private static volatile Boolean listThemeCached;
    private static int diagCount;
    private static final int DIAG_LIMIT = 40;

    public static void hook(XC_LoadPackage.LoadPackageParam lp) {
        hookContainer(lp);
        hookIconScale(lp);
        hookIconManager(lp);
        hookThemeDrawable(lp);
        hookThemeColor(lp);
        hookRowIconProviders(lp);
        log("HOOK OK status bar notification icon size/theme");
    }

    static float iconScale() {
        if (!active || !readBool(KEY_SIZE_ENABLED, false)) return 1f;
        int percent = readInt(KEY_SIZE_PERCENT, 100);
        if (percent < 40) percent = 40;
        if (percent > 200) percent = 200;
        return percent / 100f;
    }

    /**
     * 只有状态栏窗口（TYPE_STATUS_BAR）里的图标才处理。
     * 下拉控制中心顶部那排图标属于 NotificationShade 窗口，不能跟着放大或换图标。
     * 判定不出来时按状态栏处理，避免整个功能失效。
     */
    static boolean inStatusBarWindow(View view) {
        if (view == null) return true;
        try {
            View root = view.getRootView();
            android.view.ViewGroup.LayoutParams params = root.getLayoutParams();
            if (params instanceof android.view.WindowManager.LayoutParams wlp) {
                return wlp.type == android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
            }
        } catch (Throwable ignored) { }
        return true;
    }

    static float iconScaleFor(View view) {
        return inStatusBarWindow(view) ? iconScale() : 1f;
    }

    /** ColorOS 的状态栏图标是其子类 StatIconView，按类继承判断。 */
    static boolean isIconView(View view) {
        Class<?> cls = iconViewClass;
        if (cls != null) return cls.isInstance(view);
        return view != null && ICON_VIEW.equals(view.getClass().getName());
    }

    static boolean themeEnabled() {
        if (!active) return false;
        Boolean cached = themeCached;
        if (cached == null) {
            cached = readBool(KEY_THEME_ENABLED, false);
            themeCached = cached;
        }
        return cached;
    }

    static boolean listThemeEnabled() {
        if (!active) return false;
        Boolean cached = listThemeCached;
        if (cached == null) {
            cached = readBool(KEY_LIST_THEME_ENABLED, false);
            listThemeCached = cached;
        }
        return cached;
    }

    /** 只处理真正挂在状态栏图标容器下的视图，避免把通知列表/锁屏的图标一起改掉。 */
    private static boolean isStatusBarIcon(View view) {
        if (STATUS_BAR_ICONS.containsKey(view)) return true;
        Class<?> cls = containerClass;
        if (cls == null) return true; // 容器类没拿到时宁可变通，也不能整个功能不工作
        Object parent = view.getParent();
        if (!cls.isInstance(parent)) return false;
        STATUS_BAR_ICONS.put(view, Boolean.TRUE);
        return true;
    }

    private static void hookContainer(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> container = XposedHelpers.findClass(ICON_CONTAINER, lp.classLoader);
            containerClass = container;
            XposedHelpers.findAndHookMethod(container, "onViewAdded", View.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!(p.args[0] instanceof View view)) return;
                    STATUS_BAR_ICONS.put(view, Boolean.TRUE);
                    apply(view);
                }
            });
            XposedHelpers.findAndHookMethod(container, "onViewRemoved", View.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    STATUS_BAR_ICONS.remove(p.args[0]);
                    THEMED.remove(p.args[0]);
                    PACKAGES.remove(p.args[0]);
                }
            });
        } catch (Throwable t) { log("HOOK FAIL notification icon container: " + t); }
    }

    private static void hookIconScale(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> icon = XposedHelpers.findClass(ICON_VIEW, lp.classLoader);
            iconViewClass = icon;
            XposedHelpers.findAndHookMethod(icon, "maybeUpdateIconScaleDimens",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            if (!(p.thisObject instanceof View view)) return;
                            float scale = iconScaleFor(view);
                            if (scale == 1f) return;
                            try {
                                float current = XposedHelpers.getFloatField(view, "mIconScale");
                                XposedHelpers.setFloatField(view, "mIconScale", current * scale);
                                view.invalidate();
                            } catch (Throwable ignored) { }
                        }
                    });
        } catch (Throwable t) { log("HOOK FAIL StatusBarIconView#maybeUpdateIconScaleDimens: " + t); }
    }

    /**
     * 主入口：IconManager 每次刷新通知图标都会走 setIcon，且直接带着图标视图。
     * 这里先把包名记下来（比从视图字段反推可靠），再做主题图标替换。
     */
    private static void hookIconManager(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> manager = XposedHelpers.findClass(ICON_MANAGER, lp.classLoader);
            // 不写死签名：只要有一个参数是状态栏图标视图就认，避免 ColorOS 改了形参顺序后失效。
            XposedBridge.hookAllMethods(manager, "setIcon", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    View view = null;
                    for (Object arg : p.args) {
                        if (arg instanceof View candidate && isIconView(candidate)) { view = candidate; break; }
                    }
                    if (view == null) return;
                    String pkg = null;
                    for (Object arg : p.args) {
                        String resolved = packageOfEntry(arg);
                        if (resolved != null) { pkg = resolved; break; }
                    }
                    if (pkg != null) PACKAGES.put(view, pkg);
                    apply(view, pkg);
                }
            });
            log("HOOK OK IconManager#setIcon");
        } catch (Throwable t) { log("HOOK FAIL IconManager#setIcon: " + t); }
    }

    private static void hookThemeDrawable(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> icon = XposedHelpers.findClass(ICON_VIEW, lp.classLoader);
            XposedBridge.hookAllMethods(icon, "updateDrawable", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject instanceof View view) apply(view);
                }
            });
            Class<?> descriptor = XposedHelpers.findClass(STATUS_BAR_ICON, lp.classLoader);
            XposedHelpers.findAndHookMethod(icon, "set", descriptor, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.thisObject instanceof View view) apply(view);
                }
            });
            // 兜底：系统在别处覆盖 drawable 时，绘制前一帧纠正回来。
            XposedHelpers.findAndHookMethod(icon, "onDraw", Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.thisObject instanceof View view) ensure(view);
                }
            });
            log("HOOK OK StatusBarIconView#updateDrawable/set/onDraw");
        } catch (Throwable t) { log("HOOK FAIL StatusBarIconView#updateDrawable: " + t); }
    }

    /** 系统会把通知小图标染成单色/灰度；主题图标要保持彩色。 */
    private static void hookThemeColor(XC_LoadPackage.LoadPackageParam lp) {
        try {
            Class<?> icon = XposedHelpers.findClass(ICON_VIEW, lp.classLoader);
            for (Method method : icon.getDeclaredMethods()) {
                if (!method.getName().startsWith("updateIconColor")) continue;
                Class<?> type = method.getReturnType();
                final boolean skippable = type == void.class || !type.isPrimitive();
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!skippable) return;
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

    /**
     * 通知中心列表里的通知图标：系统通过 NotificationRowIconView 的 IconProvider
     * 决定画应用图标还是小图标。这里在开关打开且主题包里有该应用图标时，
     * 强制走"应用图标"分支，并直接把它换成主题包里的彩色图标。
     */
    private static void hookRowIconProviders(XC_LoadPackage.LoadPackageParam lp) {
        for (String name : ROW_ICON_PROVIDERS) {
            String tag = name.substring(name.lastIndexOf('$'));
            try {
                Class<?> provider = XposedHelpers.findClass(name, lp.classLoader);
                XposedBridge.hookAllMethods(provider, "shouldShowAppIcon", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam p) {
                        if (!listThemeEnabled()) return;
                        if (themeDrawableOf(p.thisObject) == null) return;
                        p.setResult(Boolean.TRUE);
                    }
                });
                XposedBridge.hookAllMethods(provider, "getAppIcon", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam p) {
                        if (!listThemeEnabled()) return;
                        Drawable themed = themeDrawableOf(p.thisObject);
                        if (themed != null) p.setResult(themed);
                    }
                });
                log("HOOK OK row icon provider " + tag);
            } catch (Throwable t) { log("HOOK FAIL row icon provider " + tag + ": " + t); }
        }
        try {
            Class<?> rowView = XposedHelpers.findClass(ROW_ICON_VIEW, lp.classLoader);
            XposedBridge.hookAllMethods(rowView, "loadAppIcon", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (p.thisObject instanceof View view) ROW_ICONS.put(view, Boolean.TRUE);
                }
            });
            log("HOOK OK NotificationRowIconView#loadAppIcon");
        } catch (Throwable t) { log("HOOK FAIL NotificationRowIconView#loadAppIcon: " + t); }
    }

    private static Drawable themeDrawableOf(Object provider) {
        if (provider == null) return null;
        try {
            Object sbn = XposedHelpers.getObjectField(provider, "$sbn");
            if (sbn instanceof StatusBarNotification notification) {
                return ThemeIconPack.drawable(notification.getPackageName());
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static void apply(View view) {
        apply(view, null);
    }

    private static void apply(View view, String pkgHint) {
        if (!active) return;
        Class<?> cls = iconViewClass;
        if (cls != null && !cls.isInstance(view)) return;
        if (Boolean.TRUE.equals(RESTORING.get())) return;
        if (!isStatusBarIcon(view)) return;
        try {
            String pkg = pkgHint;
            if (pkg == null || pkg.isEmpty()) pkg = PACKAGES.get(view);
            if (pkg == null || pkg.isEmpty()) pkg = packageOf(view);
            if (pkg != null && !pkg.isEmpty()) PACKAGES.put(view, pkg);
            if (!themeEnabled()) {
                if (THEMED.remove(view) != null) restore(view);
                return;
            }
            Drawable drawable = ThemeIconPack.drawable(pkg);
            ImageView icon = (ImageView) view;
            if (drawable == null) {
                if (THEMED.remove(view) != null) restore(view);
                return;
            }
            THEMED.put(view, Boolean.TRUE);
            if (icon.getDrawable() == drawable) return;
            icon.clearColorFilter();
            icon.setImageDrawable(drawable);
            diag("themed " + pkg);
        } catch (Throwable t) { log("theme notification icon apply failed: " + t); }
    }

    /**
     * 每帧兜底：只在 drawable 被系统换掉时才重新设置，命中时几乎没有开销。
     * 新通知的 setIcon/updateDrawable 一旦都被系统侧内联掉，这里是最后一道保险。
     */
    /**
     * 每帧兜底：只在 drawable 被系统换掉时才重新设置，命中时几乎没有开销。
     * 新通知的 setIcon/updateDrawable 一旦都被系统侧内联掉，这里是最后一道保险。
     */
    private static void ensure(View view) {
        if (!active || !themeEnabled()) return;
        apply(view);
    }

    private static void restore(View view) {
        if (Boolean.TRUE.equals(RESTORING.get())) return;
        RESTORING.set(Boolean.TRUE);
        try {
            XposedHelpers.callMethod(view, "updateDrawable");
        } catch (Throwable ignored) {
            try { XposedHelpers.callMethod(view, "updateDrawable", true); } catch (Throwable ignored2) { }
        } finally { RESTORING.remove(); }
    }

    private static String packageOfEntry(Object entry) {
        if (entry == null) return null;
        try {
            Object sbn = XposedHelpers.callMethod(entry, "getSbn");
            if (sbn instanceof StatusBarNotification notification) return notification.getPackageName();
        } catch (Throwable ignored) { }
        return null;
    }

    private static String packageOf(View view) {
        try {
            Object sbn = XposedHelpers.getObjectField(view, "mNotification");
            if (sbn instanceof StatusBarNotification notification) return notification.getPackageName();
        } catch (Throwable ignored) { }
        try {
            Object slot = XposedHelpers.callMethod(view, "getSlot");
            if (slot instanceof String text) {
                for (String part : text.split("\\|")) {
                    if (part.isEmpty()) continue;
                    if (part.indexOf('.') > 0 && !Character.isDigit(part.charAt(0))) return part;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    public static void refresh() {
        themeCached = null;
        listThemeCached = null;
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
            java.util.ArrayList<View> rows;
            synchronized (ROW_ICONS) { rows = new java.util.ArrayList<>(ROW_ICONS.keySet()); }
            for (View row : rows) {
                if (row == null) continue;
                try { XposedHelpers.callMethod(row, "loadAppIcon"); }
                catch (Throwable t) { log("refresh row icon failed: " + t); }
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

    private static void diag(String msg) {
        if (diagCount >= DIAG_LIMIT) return;
        diagCount++;
        log("[theme icon " + diagCount + "] " + msg);
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
        private static final int MAX_DRAWABLES = 32;
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
