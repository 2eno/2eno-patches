package app.twoeno.extension.interpals;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.ClickableSpan;
import android.text.style.ReplacementSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import app.twoeno.extension.shared.Logger;
import app.twoeno.extension.shared.Reflection;

/**
 * Filters the InterPals feed by age and gender.
 * <p>
 * A row "Age & gender" is added below the country in the filter screen of the feed.
 * The chosen values are added as query parameters to the feed requests.
 */
@SuppressWarnings("unused")
public final class FeedFilterPatch {
    private static final String TITLE = "Age & gender";
    private static final String ANY = "Any";
    private static final String FEED_PATH = "/feed";
    private static final String PREFERENCES = "2eno_feed_filter";
    private static final String KEY_SEX = "sex";
    private static final String KEY_MIN_AGE = "min_age";
    private static final String KEY_MAX_AGE = "max_age";
    private static final String MALE = "male";
    private static final String FEMALE = "female";
    private static final int MIN_AGE = 13;
    private static final int MAX_AGE = 99;
    private static final long UPDATE_DELAY_MS = 120;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<Activity, Boolean> watchedActivities = new WeakHashMap<>();

    private static SharedPreferences preferences;
    private static volatile Settings settings = new Settings(null, null, null);
    private static WeakReference<Row> overlay;

    private FeedFilterPatch() {
    }

    // region Settings

    private static final class Settings {
        final String sex;
        final Integer minAge;
        final Integer maxAge;

        Settings(String sex, Integer minAge, Integer maxAge) {
            this.sex = sex;
            this.minAge = minAge;
            this.maxAge = maxAge;
        }

        /**
         * Settings with only valid values. The ages are swapped if they are in the wrong order.
         */
        static Settings of(String sex, Integer minAge, Integer maxAge) {
            if (!MALE.equals(sex) && !FEMALE.equals(sex)) sex = null;
            if (minAge != null && (minAge < MIN_AGE || minAge > MAX_AGE)) minAge = null;
            if (maxAge != null && (maxAge < MIN_AGE || maxAge > MAX_AGE)) maxAge = null;
            if (minAge != null && maxAge != null && minAge > maxAge) return new Settings(sex, maxAge, minAge);
            return new Settings(sex, minAge, maxAge);
        }

        Map<String, String> queryParameters() {
            Map<String, String> parameters = new LinkedHashMap<>();
            if (sex != null) parameters.put(KEY_SEX, sex);
            if (minAge != null) parameters.put(KEY_MIN_AGE, String.valueOf(minAge));
            if (maxAge != null) parameters.put(KEY_MAX_AGE, String.valueOf(maxAge));
            return parameters;
        }

        String summary() {
            String gender = MALE.equals(sex) ? "Male" : FEMALE.equals(sex) ? "Female" : null;
            String age = null;
            if (minAge != null && maxAge != null) {
                age = minAge.equals(maxAge) ? String.valueOf(minAge) : minAge + "–" + maxAge;
            } else if (minAge != null) {
                age = minAge + "+";
            } else if (maxAge != null) {
                age = "up to " + maxAge;
            }
            if (gender != null && age != null) return gender + ", " + age;
            if (gender != null) return gender;
            if (age != null) return age;
            return ANY;
        }
    }

    private static Integer positiveOrNull(int value) {
        return value > 0 ? value : null;
    }

    private static void loadSettings() {
        if (preferences == null) return;
        settings = Settings.of(preferences.getString(KEY_SEX, null),
                positiveOrNull(preferences.getInt(KEY_MIN_AGE, 0)),
                positiveOrNull(preferences.getInt(KEY_MAX_AGE, 0)));
    }

    private static void saveSettings(Settings newSettings) {
        settings = newSettings;
        if (preferences != null) {
            preferences.edit()
                    .putString(KEY_SEX, newSettings.sex)
                    .putInt(KEY_MIN_AGE, newSettings.minAge == null ? 0 : newSettings.minAge)
                    .putInt(KEY_MAX_AGE, newSettings.maxAge == null ? 0 : newSettings.maxAge)
                    .apply();
        }
        Map<String, String> parameters = newSettings.queryParameters();
        Logger.info("Feed filter: " + (parameters.isEmpty() ? "off" : parameters));
    }

    // endregion

    // region Requests

    /**
     * Injection point: parameter of {@code okhttp3.internal.http.RealInterceptorChain.proceed(Request)}.
     *
     * @param request An {@code okhttp3.Request}.
     * @return The original request, or a copy with the filter added if it requests the feed.
     */
    public static Object filterRequest(Object request) {
        if (request == null) return null;
        try {
            Map<String, String> parameters = settings.queryParameters();
            if (parameters.isEmpty()) return request;
            if (!"GET".equals(Reflection.call(request, "method"))) return request;

            Object url = Reflection.call(request, "url");
            if (!DisableAdPlacementsPatch.isInterPalsHost((String) Reflection.call(url, "host"))) return request;
            String path = (String) Reflection.call(url, "encodedPath");
            if (path == null || !DisableAdPlacementsPatch.trimTrailingSlashes(path).endsWith(FEED_PATH)) return request;

            Method queryParameter = Reflection.findMethod(url.getClass(), "queryParameter", "java.lang.String");
            // The feed of a single user.
            if (queryParameter.invoke(url, "owner_id") != null) return request;

            boolean changed = false;
            for (Map.Entry<String, String> parameter : parameters.entrySet()) {
                if (!parameter.getValue().equals(queryParameter.invoke(url, parameter.getKey()))) changed = true;
            }
            if (!changed) return request;

            Object urlBuilder = Reflection.call(url, "newBuilder");
            Method setQueryParameter = Reflection.findMethod(urlBuilder.getClass(), "setQueryParameter",
                    "java.lang.String", "java.lang.String");
            for (Map.Entry<String, String> parameter : parameters.entrySet()) {
                setQueryParameter.invoke(urlBuilder, parameter.getKey(), parameter.getValue());
            }
            Object newUrl = Reflection.call(urlBuilder, "build");

            Object requestBuilder = Reflection.call(request, "newBuilder");
            Reflection.findMethod(requestBuilder.getClass(), "url", "okhttp3.HttpUrl").invoke(requestBuilder, newUrl);
            return Reflection.call(requestBuilder, "build");
        } catch (Throwable ex) {
            Logger.error("Feed filter request failure", ex);
            return request;
        }
    }

    // endregion

    // region Screen

    /**
     * Injection point: start of {@code Application.onCreate()}.
     */
    public static void install(Application application) {
        try {
            preferences = application.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
            loadSettings();
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity activity) {
                    watch(activity);
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
            Logger.error("FeedFilterPatch install failure", ex);
        }
    }

    /**
     * Checks for the filter screen after every layout change of the activity.
     */
    private static void watch(Activity activity) {
        if (watchedActivities.put(activity, true) != null) return;
        if (activity.getWindow() == null) return;
        Runnable update = () -> {
            try {
                update(activity);
            } catch (Throwable ex) {
                Logger.error("Feed filter screen update failure", ex);
            }
        };
        activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            mainHandler.removeCallbacks(update);
            mainHandler.postDelayed(update, UPDATE_DELAY_MS);
        });
    }

    private static final class Screen {
        final View languageLabel;
        final View countryLabel;
        final View countryBox;
        final View countryValue;

        Screen(View languageLabel, View countryLabel, View countryBox, View countryValue) {
            this.languageLabel = languageLabel;
            this.countryLabel = countryLabel;
            this.countryBox = countryBox;
            this.countryValue = countryValue;
        }
    }

    private static void update(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        Row row = overlay == null ? null : overlay.get();
        if (row != null && row.getParent() != content) row = null;

        Screen screen = findScreen(activity.getWindow().getDecorView(), row);
        if (screen == null) {
            if (row != null) content.removeView(row);
            return;
        }
        if (row == null) {
            row = new Row(activity);
            content.addView(row, new FrameLayout.LayoutParams(0, 0));
            overlay = new WeakReference<>(row);
        }
        row.match(content, screen, settings.summary());
    }

    private static final class ScreenSearch {
        final List<View> texts = new ArrayList<>();
        View showResults;
        View language;
        View country;
        boolean hasAgeOrGender;
    }

    /**
     * The filter screen of the feed, unless it already has its own age and gender filter.
     */
    private static Screen findScreen(View root, View skip) {
        ScreenSearch search = new ScreenSearch();
        visit(root, skip, search);
        View country = search.country;
        View language = search.language;
        View showResults = search.showResults;
        if (country == null || language == null || showResults == null || search.hasAgeOrGender) return null;
        if (!showResults.isShown() || !language.isShown() || !country.isShown()) return null;

        // The value of the country is the first text below its label.
        int countryBottom = bounds(country).bottom;
        View value = null;
        int valueTop = Integer.MAX_VALUE;
        for (View text : search.texts) {
            if (text == country || !text.isShown()) continue;
            int top = bounds(text).top;
            if (top >= countryBottom && top < valueTop) {
                value = text;
                valueTop = top;
            }
        }
        if (value == null) return null;

        // The box around the value.
        View box = value;
        while (true) {
            ViewParent parent = box.getParent();
            if (!(parent instanceof View) || bounds((View) parent).top < countryBottom) break;
            box = (View) parent;
        }
        if (box == value) return null;
        return new Screen(language, country, box, value);
    }

    private static void visit(View view, View skip, ScreenSearch search) {
        if (view == skip || view.getVisibility() != View.VISIBLE) return;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) visit(group.getChildAt(i), skip, search);
            return;
        }
        CharSequence text = text(view);
        if (text == null) return;
        String label = text.toString().trim();
        if (label.isEmpty()) return;

        search.texts.add(view);
        if (FilterLabels.SHOW_RESULTS.contains(label)) {
            if (search.showResults == null) search.showResults = view;
        } else if (FilterLabels.LANGUAGE.contains(label)) {
            if (search.language == null) search.language = view;
        } else if (FilterLabels.COUNTRY.contains(label)) {
            if (search.country == null) search.country = view;
        } else if (FilterLabels.AGE_OR_GENDER.contains(label)) {
            search.hasAgeOrGender = true;
        }
    }

    private static Rect bounds(View view) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        return new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
    }

    /**
     * The text of a TextView or of a React Native text view, which draws a prepared layout.
     */
    private static CharSequence text(View view) {
        if (view instanceof TextView) return ((TextView) view).getText();
        if (!view.getClass().getName().endsWith("PreparedLayoutTextView")) return null;
        try {
            Object text = Reflection.call(view, "getText");
            return text instanceof CharSequence ? (CharSequence) text : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Layout layout(View view) {
        try {
            Object preparedLayout = Reflection.call(view, "getPreparedLayout");
            if (preparedLayout == null) return null;
            Object layout = preparedLayout.getClass().getField("layout").get(preparedLayout);
            return layout instanceof Layout ? (Layout) layout : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Shows the text in the style of a text view of the app.
     */
    private static void copyStyle(TextView target, String text, View source) {
        TextPaint paint = null;
        if (source instanceof TextView) {
            paint = ((TextView) source).getPaint();
        } else {
            Layout layout = layout(source);
            if (layout != null) paint = layout.getPaint();
        }
        if (paint != null) {
            target.setTextColor(paint.getColor());
            target.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, paint.getTextSize());
            target.setTypeface(paint.getTypeface());
            target.setLetterSpacing(paint.getLetterSpacing());
        }
        if (source instanceof TextView) {
            target.setIncludeFontPadding(((TextView) source).getIncludeFontPadding());
        }

        CharSequence sourceText = text(source);
        if (!(sourceText instanceof Spanned)) {
            if (!target.getText().toString().equals(text)) target.setText(text);
            return;
        }
        Spanned spanned = (Spanned) sourceText;
        SpannableString styled = new SpannableString(text);
        for (CharacterStyle style : spanned.getSpans(0, spanned.length(), CharacterStyle.class)) {
            if (style instanceof ClickableSpan || style instanceof ReplacementSpan) continue;
            styled.setSpan(style, 0, text.length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
        target.setText(styled);
    }

    /**
     * The "Age & gender" row, a copy of the country row, placed below the country row.
     */
    private static final class Row extends FrameLayout {
        private final TextView label;
        private final View box;
        private final TextView value;
        private int snapshotWidth;
        private int snapshotHeight;

        Row(Activity activity) {
            super(activity);
            label = new TextView(activity);
            box = new View(activity);
            value = new TextView(activity);
            label.setClickable(false);
            value.setMaxLines(1);
            value.setEllipsize(TextUtils.TruncateAt.END);
            addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(box, new FrameLayout.LayoutParams(0, 0));
            addView(value, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.setOnClickListener(v -> showDialog(activity));
            box.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                box.setAlpha(action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE ? 0.6f : 1f);
                value.setAlpha(box.getAlpha());
                return false;
            });
        }

        void match(ViewGroup content, Screen screen, String summary) {
            copyStyle(label, TITLE, screen.countryLabel);
            copyStyle(value, summary, screen.countryValue);

            Rect contentBounds = bounds(content);
            Rect labelBounds = bounds(screen.countryLabel);
            Rect boxBounds = bounds(screen.countryBox);
            Rect valueBounds = bounds(screen.countryValue);
            // The distance between the language and the country row.
            int rowDistance = labelBounds.top - bounds(screen.languageLabel).top;
            // Space for the arrow at the end of the box.
            int arrowWidth = (int) (boxBounds.width() * 0.15f);
            snapshot(screen, boxBounds, valueBounds);

            int left = -contentBounds.left;
            place(label, labelBounds.left + left, 0, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            place(box, boxBounds.left + left, boxBounds.top - labelBounds.top, boxBounds.width(), boxBounds.height());
            place(value, valueBounds.left + left, valueBounds.top - labelBounds.top,
                    boxBounds.right - arrowWidth - valueBounds.left, ViewGroup.LayoutParams.WRAP_CONTENT);
            place(this, 0, labelBounds.top + rowDistance - contentBounds.top,
                    contentBounds.width(), boxBounds.bottom - labelBounds.top);
        }

        /**
         * Uses an image of the country box, without the country, as background of the box.
         */
        private void snapshot(Screen screen, Rect boxBounds, Rect valueBounds) {
            int width = boxBounds.width();
            int height = boxBounds.height();
            if (width <= 0 || height <= 0 || (width == snapshotWidth && height == snapshotHeight)) return;

            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.translate(-screen.countryBox.getScrollX(), -screen.countryBox.getScrollY());
            screen.countryBox.draw(canvas);

            // Paint over the country with the background color of the box.
            Rect valueRect = new Rect(valueBounds);
            valueRect.offset(-boxBounds.left, -boxBounds.top);
            int background = bitmap.getPixel(Math.min(Math.max(valueRect.left / 2, 0), width - 1), height / 2);
            Rect cover = new Rect(valueRect.left - 2, valueRect.top - 2, valueRect.right + 2, valueRect.bottom + 2);
            if (cover.intersect(0, 0, width, height)) {
                Paint paint = new Paint();
                paint.setColor(background);
                new Canvas(bitmap).drawRect(cover, paint);
            }

            box.setBackground(new BitmapDrawable(getResources(), bitmap));
            snapshotWidth = width;
            snapshotHeight = height;
        }

        private static void place(View view, int left, int top, int width, int height) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
            if (params.leftMargin == left && params.topMargin == top && params.width == width && params.height == height) {
                return;
            }
            params.leftMargin = left;
            params.topMargin = top;
            params.width = width;
            params.height = height;
            view.setLayoutParams(params);
        }
    }

    // endregion

    // region Dialog

    private static void showDialog(Activity activity) {
        Settings current = settings;
        float density = activity.getResources().getDisplayMetrics().density;

        String[] ages = new String[MAX_AGE - MIN_AGE + 2];
        ages[0] = ANY;
        for (int age = MIN_AGE; age <= MAX_AGE; age++) ages[age - MIN_AGE + 1] = String.valueOf(age);

        RadioGroup genders = new RadioGroup(activity);
        genders.setOrientation(LinearLayout.HORIZONTAL);
        String[][] genderOptions = {{ANY, null}, {"Female", FEMALE}, {"Male", MALE}};
        for (int i = 0; i < genderOptions.length; i++) {
            RadioButton button = new RadioButton(activity);
            button.setId(i + 1);
            button.setText(genderOptions[i][0]);
            button.setTag(genderOptions[i][1]);
            genders.addView(button);
            if (Objects.equals(genderOptions[i][1], current.sex)) genders.check(i + 1);
        }

        NumberPicker minAge = agePicker(activity, ages, current.minAge);
        NumberPicker maxAge = agePicker(activity, ages, current.maxAge);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * density);
        layout.setPadding(padding, (int) (8 * density), padding, 0);
        TextView genderLabel = label(activity, "Gender");
        genderLabel.setGravity(Gravity.START);
        layout.addView(genderLabel);
        layout.addView(genders);
        TextView ageLabel = label(activity, "Age");
        ageLabel.setGravity(Gravity.START);
        ageLabel.setPadding(0, (int) (12 * density), 0, 0);
        layout.addView(ageLabel);
        LinearLayout ageRange = new LinearLayout(activity);
        ageRange.setGravity(Gravity.CENTER);
        ageRange.addView(label(activity, "from  "));
        ageRange.addView(minAge);
        ageRange.addView(label(activity, "  to  "));
        ageRange.addView(maxAge);
        layout.addView(ageRange);

        new AlertDialog.Builder(activity)
                .setTitle(TITLE)
                .setView(layout)
                .setPositiveButton("Apply", (dialog, which) -> {
                    RadioButton checked = genders.findViewById(genders.getCheckedRadioButtonId());
                    applyFilter(activity, Settings.of(checked == null ? null : (String) checked.getTag(),
                            age(minAge), age(maxAge)));
                })
                .setNeutralButton("Reset", (dialog, which) -> applyFilter(activity, new Settings(null, null, null)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private static NumberPicker agePicker(Activity activity, String[] ages, Integer age) {
        NumberPicker picker = new NumberPicker(activity);
        picker.setMinValue(0);
        picker.setMaxValue(ages.length - 1);
        picker.setDisplayedValues(ages);
        picker.setWrapSelectorWheel(false);
        picker.setValue(age == null ? 0 : age - MIN_AGE + 1);
        return picker;
    }

    private static Integer age(NumberPicker picker) {
        return picker.getValue() > 0 ? picker.getValue() - 1 + MIN_AGE : null;
    }

    private static TextView label(Activity activity, String text) {
        TextView label = new TextView(activity);
        label.setText(text);
        label.setGravity(Gravity.CENTER);
        return label;
    }

    private static void applyFilter(Activity activity, Settings newSettings) {
        saveSettings(newSettings);
        try {
            update(activity);
        } catch (Throwable ignored) {
        }
        Toast.makeText(activity, "Tap \"Show results\" or pull down the feed to refresh", Toast.LENGTH_LONG).show();
    }

    // endregion

    /**
     * Labels of the filter screen in all languages of the app.
     */
    private static final class FilterLabels {
        static final Set<String> SHOW_RESULTS = set("Afficher les résultats", "Afișează rezultatele",
                "Ergebnisse anzeigen", "Hiện kết quả", "Mostra risultati", "Mostrar resultados", "Näytä tulokset",
                "Pokaż wyniki", "Resultaten tonen", "Show results", "Sonuçları göster", "Találatok mutatása",
                "Tampilkan hasil", "Vis resultater", "Visa resultat", "Zobrazit výsledky",
                "Εμφάνιση αποτελεσμάτων", "Покажи резултатите", "Показати результати", "Показать результаты",
                "परिणाम दिखाएँ", "แสดงผลลัพธ์", "显示结果", "結果を表示", "顯示結果", "결과 보기");

        static final Set<String> LANGUAGE = set("Bahasa", "Dil", "Idioma", "Jazyk", "Język", "Kieli", "Language",
                "Langue", "Limbă", "Lingua", "Ngôn ngữ", "Nyelv", "Sprache", "Sprog", "Språk", "Taal", "Γλώσσα",
                "Език", "Мова", "Язык", "भाषा", "ภาษา", "言語", "語言", "语言", "언어");

        static final Set<String> COUNTRY = set("Country", "Kraj", "Land", "Maa", "Negara", "Ország", "Paese",
                "Pays", "País", "Quốc gia", "Země", "Ülke", "Țară", "Χώρα", "Държава", "Країна", "Страна", "देश",
                "ประเทศ", "国", "国家", "國家", "국가");

        /**
         * If any of these is shown, the screen already has an age or gender filter.
         */
        static final Set<String> AGE_OR_GENDER = set("Age", "Alder", "Alter", "Bărbat", "Bărbat sau femeie",
                "Edad", "Erkek", "Erkek veya Kadın", "Età", "Female", "Femeie", "Femenino", "Feminino", "Femme",
                "Femmina", "Férfi", "Férfi vagy nő", "Homme", "Homme ou Femme", "Idade", "Ikä", "Kadın", "Kobieta",
                "Kvinde", "Kvinna", "Laki-laki", "Laki-laki atau perempuan", "Leeftijd", "Male", "Male or Female",
                "Man", "Man eller kvinna", "Man of vrouw", "Mand", "Mand eller kvinde", "Maschio",
                "Maschio o Femmina", "Masculino", "Masculino o Femenino", "Masculino ou feminino", "Mies",
                "Mies tai nainen", "Muž", "Muž nebo žena", "Männlich", "Männlich oder Weiblich", "Mężczyzna",
                "Mężczyzna lub kobieta", "Nainen", "Nam", "Nam hoặc nữ", "Nő", "Nữ", "Perempuan", "Tuổi", "Usia",
                "Vrouw", "Vârstă", "Věk", "Weiblich", "Wiek", "Yaş", "Âge", "Ålder", "Életkor", "Žena", "Άνδρας",
                "Άνδρας ή γυναίκα", "Γυναίκα", "Ηλικία", "Возраст", "Възраст", "Вік", "Жена", "Женский", "Жінка",
                "Мужской", "Мужской или женский", "Мъж", "Мъж или жена", "Чоловік", "Чоловіки та жінки", "उम्र",
                "पुरुष", "पुरुष या महिला", "महिला", "ชาย", "ชายหรือหญิง", "หญิง", "อายุ", "女", "女性", "年齡", "年齢",
                "年龄", "男", "男性", "男性または女性", "男性或女性", "男或女", "나이", "남성", "남성 또는 여성", "여성");

        private static Set<String> set(String... labels) {
            return new HashSet<>(Arrays.asList(labels));
        }
    }
}
