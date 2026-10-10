package app.twoeno.extension.interpals;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.twoeno.extension.shared.Logger;
import app.twoeno.extension.shared.Reflection;

/**
 * Disables the ad placements of InterPals.
 * <p>
 * The app downloads its ad placements from {@code /ad-config}. Every placement is turned off in that
 * response. Until the response arrives, the app uses defaults compiled into its Hermes bundle,
 * which are turned off by loading a patched copy of the bundle, see {@link HermesBundlePatcher}.
 */
@SuppressWarnings("unused")
public final class DisableAdPlacementsPatch {
    private static final String FILTERED_HEADER = "X-2eno-Filtered";
    private static final String AD_CONFIG_PATH = "/ad-config";
    private static final String BUNDLE_DIRECTORY = "2eno-patches";
    /**
     * Change it when the bundle patch changes, so bundles patched before are not used anymore.
     */
    private static final int BUNDLE_PATCH_VERSION = 1;

    // The json may be embedded as an escaped string.
    private static final Pattern ENABLED_TRUE = Pattern.compile("(\\\\?\"enabled\\\\?\"\\s*:\\s*)true");

    private DisableAdPlacementsPatch() {
    }

    /**
     * Conditional requests can be answered with "304 Not Modified" and the cached, unfiltered config.
     */
    private static final String[] CONDITIONAL_HEADERS = {"If-None-Match", "If-Modified-Since"};

    static boolean isInterPalsHost(String host) {
        return host != null && (host.equals("interpals.net") || host.endsWith(".interpals.net"));
    }

    static String trimTrailingSlashes(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') end--;
        return path.substring(0, end);
    }

    /**
     * @param url An {@code okhttp3.HttpUrl}.
     */
    private static boolean isAdConfig(Object url) throws ReflectiveOperationException {
        if (url == null || !isInterPalsHost((String) Reflection.call(url, "host"))) return false;
        String path = (String) Reflection.call(url, "encodedPath");
        return path != null && trimTrailingSlashes(path).endsWith(AD_CONFIG_PATH);
    }

    /**
     * Injection point: parameter of {@code okhttp3.internal.http.RealInterceptorChain.proceed(Request)}.
     *
     * @param request An {@code okhttp3.Request}.
     * @return The original request, or a copy without conditional headers if it requests the ad config.
     */
    public static Object filterRequest(Object request) {
        if (request == null) return null;
        try {
            if (!isAdConfig(Reflection.call(request, "url"))) return request;
            Method header = Reflection.findMethod(request.getClass(), "header", "java.lang.String");
            boolean conditional = false;
            for (String name : CONDITIONAL_HEADERS) {
                if (header.invoke(request, name) != null) conditional = true;
            }
            if (!conditional) return request;

            Object builder = Reflection.call(request, "newBuilder");
            Method removeHeader = Reflection.findMethod(builder.getClass(), "removeHeader", "java.lang.String");
            for (String name : CONDITIONAL_HEADERS) removeHeader.invoke(builder, name);
            return Reflection.call(builder, "build");
        } catch (Throwable ex) {
            Logger.error("filterRequest failure", ex);
            return request;
        }
    }

    /**
     * Sets every {@code "enabled": true} in the ad config to {@code false}.
     *
     * @return The changed json and the number of disabled placements.
     */
    static Object[] disablePlacements(String json) {
        Matcher matcher = ENABLED_TRUE.matcher(json);
        StringBuffer result = new StringBuffer(json.length());
        int count = 0;
        while (matcher.find()) {
            matcher.appendReplacement(result, Matcher.quoteReplacement(matcher.group(1) + "false"));
            count++;
        }
        matcher.appendTail(result);
        return new Object[]{result.toString(), count};
    }

    /**
     * Injection point: return value of {@code okhttp3.internal.http.RealInterceptorChain.proceed(Request)}.
     *
     * @param response An {@code okhttp3.Response}.
     * @return The original response, or a copy with all ad placements disabled.
     */
    public static Object filterResponse(Object response) {
        if (response == null) return null;
        try {
            Class<?> responseClass = response.getClass();
            if (Reflection.findMethod(responseClass, "header", "java.lang.String")
                    .invoke(response, FILTERED_HEADER) != null) return response;
            // Compressed bodies are only seen if the app decompresses itself. Leave them alone.
            if (Reflection.findMethod(responseClass, "header", "java.lang.String")
                    .invoke(response, "Content-Encoding") != null) return response;

            int code = (int) Reflection.call(response, "code");
            if (code < 200 || code >= 300) return response;
            if (!isAdConfig(Reflection.call(Reflection.call(response, "request"), "url"))) return response;

            Object body = Reflection.call(response, "body");
            if (body == null) return response;
            Object contentType = Reflection.call(body, "contentType");
            // Reading the body consumes it, so a new response must be built in any case.
            String json = (String) Reflection.call(body, "string");
            Object[] result = disablePlacements(json);
            Logger.info("Disabled " + result[1] + " ad placement(s)");

            ClassLoader classLoader = responseClass.getClassLoader();
            Class<?> responseBodyClass = Class.forName("okhttp3.ResponseBody", false, classLoader);
            Object newBody = Reflection.findMethod(responseBodyClass, "create", "okhttp3.MediaType", "java.lang.String")
                    .invoke(null, contentType, result[0]);
            Object builder = Reflection.call(response, "newBuilder");
            Class<?> builderClass = builder.getClass();
            Reflection.findMethod(builderClass, "body", "okhttp3.ResponseBody").invoke(builder, newBody);
            Reflection.findMethod(builderClass, "header", "java.lang.String", "java.lang.String")
                    .invoke(builder, FILTERED_HEADER, "1");
            return Reflection.call(builder, "build");
        } catch (Throwable ex) {
            Logger.error("filterResponse failure", ex);
            return response;
        }
    }

    /**
     * Injection point: start of {@code JSBundleLoader.Companion.createAssetLoader(Context, String, boolean)}.
     *
     * @return The path of the patched bundle, to be loaded with {@code createFileLoader} instead,
     * or null to load the original bundle.
     */
    public static String patchedBundlePath(Context context, String assetUrl) {
        if (context == null || assetUrl == null) return null;
        try {
            File bundle = patchedBundle(context, assetUrl);
            if (bundle == null) return null;
            Logger.info("Loading patched bundle " + bundle.getName());
            return bundle.getPath();
        } catch (Throwable ex) {
            Logger.error("Could not patch the bundle, using the original one", ex);
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static File patchedBundle(Context context, String assetUrl) throws Exception {
        String assetName = assetUrl.startsWith("assets://") ? assetUrl.substring("assets://".length()) : assetUrl;
        PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        long versionCode = Build.VERSION.SDK_INT >= 28 ? packageInfo.getLongVersionCode() : packageInfo.versionCode;

        // A new app version or update brings a new bundle.
        File directory = new File(context.getFilesDir(), BUNDLE_DIRECTORY);
        File patched = new File(directory, BUNDLE_PATCH_VERSION + "-" + versionCode + "-"
                + packageInfo.lastUpdateTime + "-" + assetName.replace('/', '_'));
        File skipped = new File(patched.getPath() + ".skipped");
        if (patched.isFile()) return patched;
        if (skipped.isFile()) return null;

        // Remove bundles of older app versions.
        File[] oldFiles = directory.listFiles();
        if (oldFiles != null) {
            for (File oldFile : oldFiles) {
                //noinspection ResultOfMethodCallIgnored
                oldFile.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();

        byte[] bundle;
        try (InputStream input = context.getAssets().open(assetName)) {
            bundle = readAll(input);
        }

        HermesBundlePatcher.Result result = HermesBundlePatcher.patch(bundle);
        if (result.bundle == null) {
            write(skipped, result.description.getBytes());
            Logger.info("Bundle not patched: " + result.description);
            return null;
        }

        File temporary = new File(patched.getPath() + ".tmp");
        write(temporary, result.bundle);
        if (!temporary.renameTo(patched)) throw new IOException("Cannot write " + patched);
        Logger.info("Patched bundle: " + result.description);
        return patched;
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(input.available(), 8192));
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static void write(File file, byte[] data) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(data);
        }
    }
}
