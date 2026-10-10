package app.twoeno.extension.interpals;

import android.app.Activity;
import android.app.Application;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.MimeTypeMap;
import android.widget.PopupMenu;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import app.twoeno.extension.shared.Logger;
import app.twoeno.extension.shared.Reflection;

/**
 * Adds "Download photo" to the menu of the photo viewer of InterPals.
 * <p>
 * The menu of the photo viewer is drawn by React Native. A tap on its button is intercepted
 * and a native menu is shown instead, which also offers the original menu by replaying the tap.
 */
@SuppressWarnings("unused")
public final class DownloadPhotosPatch {
    /**
     * The React Native testID of the menu button in the photo viewer, which becomes the view tag.
     */
    private static final String MENU_BUTTON_TAG = "PhotoPopupMenu";
    private static final int ITEM_DOWNLOAD = 1;
    private static final int ITEM_MORE = 2;
    private static final long TAP_DURATION_MS = 60;
    private static final String DIRECTORY = "InterPals";

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static ClassLoader appClassLoader;
    private static WeakReference<View> menuButton;
    private static boolean swallowingTouch;
    private static boolean replayingTap;

    private DownloadPhotosPatch() {
    }

    /**
     * Injection point: start of {@code Application.onCreate()}.
     */
    public static void install(Application application) {
        try {
            appClassLoader = application.getClassLoader();
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity activity) {
                    interceptTouches(activity);
                }

                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                }

                @Override
                public void onActivityStarted(Activity activity) {
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                }
            });
        } catch (Throwable ex) {
            Logger.error("DownloadPhotosPatch install failure", ex);
        }
    }

    private static final class TouchInterceptor implements InvocationHandler {
        private final Activity activity;
        private final Window.Callback callback;

        TouchInterceptor(Activity activity, Window.Callback callback) {
            this.activity = activity;
            this.callback = callback;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (!replayingTap && method.getName().equals("dispatchTouchEvent")
                    && args != null && args.length == 1 && args[0] instanceof MotionEvent) {
                try {
                    if (handleTouch(activity, (MotionEvent) args[0])) return true;
                } catch (Throwable ex) {
                    Logger.error("Photo menu touch handling failure", ex);
                }
            }
            try {
                return method.invoke(callback, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        }
    }

    /**
     * Wraps the window callback of the activity, which receives all touches before the views.
     */
    private static void interceptTouches(Activity activity) {
        Window window = activity.getWindow();
        if (window == null) return;
        Window.Callback callback = window.getCallback();
        if (callback == null) return;
        if (Proxy.isProxyClass(callback.getClass())
                && Proxy.getInvocationHandler(callback) instanceof TouchInterceptor) return;

        window.setCallback((Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(),
                new Class<?>[]{Window.Callback.class},
                new TouchInterceptor(activity, callback)));
    }

    /**
     * @return If the touch is consumed.
     */
    private static boolean handleTouch(Activity activity, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                View button = findMenuButton(activity.getWindow().getDecorView());
                swallowingTouch = button != null && hits(button, event);
                return swallowingTouch;
            }
            case MotionEvent.ACTION_UP: {
                if (!swallowingTouch) return false;
                swallowingTouch = false;
                View button = menuButton == null ? null : menuButton.get();
                if (button != null && hits(button, event)) showMenu(activity, button);
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                boolean swallowed = swallowingTouch;
                swallowingTouch = false;
                return swallowed;
            }
            default:
                return swallowingTouch;
        }
    }

    private static boolean hits(View view, MotionEvent event) {
        if (!view.isShown() || view.getWidth() == 0 || view.getHeight() == 0) return false;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float x = event.getRawX() - location[0];
        float y = event.getRawY() - location[1];
        return x >= 0 && y >= 0 && x < view.getWidth() && y < view.getHeight();
    }

    private static View findMenuButton(View root) {
        View button = menuButton == null ? null : menuButton.get();
        if (button != null && button.isAttachedToWindow() && button.isShown()) return button;
        button = findViewWithTag(root);
        if (button != null) menuButton = new WeakReference<>(button);
        return button;
    }

    private static View findViewWithTag(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (MENU_BUTTON_TAG.equals(view.getTag())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findViewWithTag(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void showMenu(Activity activity, View anchor) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(0, ITEM_DOWNLOAD, 0, "Download photo");
        menu.getMenu().add(0, ITEM_MORE, 1, "More options…");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == ITEM_DOWNLOAD) {
                download(activity);
            } else if (item.getItemId() == ITEM_MORE) {
                replayTap(activity, anchor);
            }
            return true;
        });
        menu.show();
    }

    /**
     * Opens the original menu by tapping its button again, without intercepting the tap.
     */
    private static void replayTap(Activity activity, View button) {
        if (!button.isShown()) return;
        int[] location = new int[2];
        button.getLocationInWindow(location);
        float x = location[0] + button.getWidth() / 2f;
        float y = location[1] + button.getHeight() / 2f;
        long downTime = SystemClock.uptimeMillis();
        dispatchTap(activity, downTime, downTime, MotionEvent.ACTION_DOWN, x, y);
        mainHandler.postDelayed(() -> dispatchTap(activity, downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP, x, y), TAP_DURATION_MS);
    }

    private static void dispatchTap(Activity activity, long downTime, long eventTime, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0);
        replayingTap = true;
        try {
            activity.dispatchTouchEvent(event);
        } finally {
            replayingTap = false;
            event.recycle();
        }
    }

    // region Download

    private static final class Source {
        final String url;
        final Map<String, String> headers;

        Source(String url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    private static Class<?> appClass(String name) {
        try {
            return Class.forName(name, false, appClassLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isPhoto(View view) {
        Class<?> photoView = appClass("com.reactnative.photoview.PhotoView");
        Class<?> fastImageView = appClass("com.dylanvann.fastimage.FastImageViewWithUrl");
        return (photoView != null && photoView.isInstance(view))
                || (fastImageView != null && fastImageView.isInstance(view));
    }

    /**
     * The largest photo covering the center of the screen at almost full width.
     */
    private static View visiblePhoto(View root) {
        View[] best = new View[1];
        long[] bestArea = new long[1];
        visiblePhoto(root, root.getWidth(), root.getHeight(), new Rect(), best, bestArea);
        return best[0];
    }

    private static void visiblePhoto(View view, int width, int height, Rect rect, View[] best, long[] bestArea) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (isPhoto(view)) {
            if (view.getGlobalVisibleRect(rect) && rect.width() >= width * 0.9
                    && rect.contains(width / 2, height / 2)) {
                long area = (long) rect.width() * rect.height();
                if (area > bestArea[0]) {
                    best[0] = view;
                    bestArea[0] = area;
                }
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                visiblePhoto(group.getChildAt(i), width, height, rect, best, bestArea);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Source sourceOf(View view) throws ReflectiveOperationException {
        Class<?> photoView = appClass("com.reactnative.photoview.PhotoView");
        if (photoView != null && photoView.isInstance(view)) {
            Object uri = Reflection.getField(view, "mUri");
            if (uri == null) return null;
            Map<String, String> headers = new HashMap<>();
            Object readableMap = Reflection.getField(view, "mHeaders");
            if (readableMap != null) {
                Map<String, Object> map = (Map<String, Object>) Reflection.call(readableMap, "toHashMap");
                for (Map.Entry<String, Object> entry : map.entrySet()) {
                    if (entry.getValue() != null) headers.put(entry.getKey(), entry.getValue().toString());
                }
            }
            return new Source(uri.toString(), headers);
        }

        // FastImage.
        Object glideUrl = Reflection.getField(view, "glideUrl");
        if (glideUrl == null) return null;
        Map<String, String> headers;
        try {
            headers = (Map<String, String>) Reflection.call(Reflection.getField(glideUrl, "headers"), "getHeaders");
        } catch (Throwable ignored) {
            headers = Collections.emptyMap();
        }
        return new Source(glideUrl.toString(), headers);
    }

    private static void download(Activity activity) {
        View photo = visiblePhoto(activity.getWindow().getDecorView());
        if (photo == null) {
            toast(activity, "No photo on screen");
            return;
        }
        Source source;
        try {
            source = sourceOf(photo);
        } catch (Throwable ex) {
            Logger.error("Photo source not found", ex);
            source = null;
        }
        if (source == null || !source.url.startsWith("http")) {
            toast(activity, "Photo has no web address");
            return;
        }

        toast(activity, "Downloading photo…");
        Context context = activity.getApplicationContext();
        Source photoSource = source;
        new Thread(() -> {
            Throwable error = null;
            try {
                save(context, photoSource);
                Logger.info("Saved photo " + photoSource.url);
            } catch (Throwable ex) {
                Logger.error("Photo download failure", ex);
                error = ex;
            }
            Throwable result = error;
            mainHandler.post(() -> toast(context, result == null
                    ? "Saved to Pictures/" + DIRECTORY
                    : "Download failed: " + result.getMessage()));
        }, "2eno-photo-download").start();
    }

    private static void save(Context context, Source source) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(source.url).openConnection();
        try {
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            for (Map.Entry<String, String> header : source.headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code);

            String mimeType = connection.getContentType();
            if (mimeType != null) mimeType = mimeType.split(";", 2)[0].trim();
            if (mimeType == null || !mimeType.startsWith("image/")) mimeType = "image/jpeg";
            String fileName = fileName(Uri.parse(source.url), mimeType);

            if (Build.VERSION.SDK_INT >= 29) {
                saveToMediaStore(context.getContentResolver(), connection, fileName, mimeType);
            } else {
                @SuppressWarnings("deprecation")
                File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), DIRECTORY);
                //noinspection ResultOfMethodCallIgnored
                directory.mkdirs();
                try (InputStream input = connection.getInputStream();
                     OutputStream output = new FileOutputStream(new File(directory, fileName))) {
                    copy(input, output);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    @android.annotation.TargetApi(29)
    private static void saveToMediaStore(ContentResolver resolver, HttpURLConnection connection,
                                         String fileName, String mimeType) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + DIRECTORY);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri item = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (item == null) throw new IOException("MediaStore insert failed");
        try {
            try (InputStream input = connection.getInputStream();
                 OutputStream output = resolver.openOutputStream(item)) {
                if (output == null) throw new IOException("Cannot write " + item);
                copy(input, output);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(item, values, null, null);
        } catch (IOException | RuntimeException ex) {
            resolver.delete(item, null, null);
            throw ex;
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private static String fileName(Uri uri, String mimeType) {
        String name = uri.getLastPathSegment();
        if (name != null) {
            name = name.substring(name.lastIndexOf('/') + 1);
            int extension = name.lastIndexOf('.');
            if (extension >= 0) name = name.substring(0, extension);
        }
        if (name == null || name.trim().isEmpty()) name = "photo";
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension == null) extension = "jpg";
        return "interpals_" + name + "_" + System.currentTimeMillis() + "." + extension;
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }

    // endregion
}
