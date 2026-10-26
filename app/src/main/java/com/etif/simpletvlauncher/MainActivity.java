package com.etif.simpletvlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ContentValues;
import android.database.Cursor;
import android.content.ContentUris;
import android.webkit.MimeTypeMap;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.media.tv.TvContract;
import android.provider.MediaStore;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_BG = 4101;
    private static final int REQ_FONT = 4102;
    private static final String PREFS = "launcher_prefs";
    private LauncherView launcherView;
    private LocalUploadServer uploadServer;
    private SharedPreferences prefs;
    private JSONObject translations = new JSONObject();
    private Locale displayLocale = Locale.getDefault();
    private static final String[] LANG_CODES = {"auto","es","en","ca","ja","zh","fr","de","pt","it","ru"};
    private static final String[] LANG_NAMES = {"Automático","Español","English","Català","日本語","中文（简体）","Français","Deutsch","Português","Italiano","Русский"};

    private String tr(String original) { return translations.optString(original, original); }

    private void applyLanguage() {
        String pref = prefs.getString("language", "auto");
        String code = pref.equals("auto") ? Locale.getDefault().getLanguage() : pref;
        displayLocale = pref.equals("auto") ? Locale.getDefault() : Locale.forLanguageTag(code);
        translations = new JSONObject();
        try (InputStream in = getAssets().open("lang_" + code + ".json")) {
            byte[] bytes = new byte[in.available()];
            int read = 0, n;
            while (read < bytes.length && (n = in.read(bytes, read, bytes.length - read)) > 0) read += n;
            translations = new JSONObject(new String(bytes, 0, read, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        applyLanguage();
        launcherView = new LauncherView(this);
        setContentView(launcherView);
        enterImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersive();
        if (launcherView != null) launcherView.reloadPreferences();
    }

    @Override protected void onStop() {
        super.onStop();
        stopUploadServer();
    }

    private void saveLauncherScreenshot() {
        if (launcherView == null || launcherView.getWidth() <= 0 || launcherView.getHeight() <= 0) return;
        try {
            Bitmap bitmap = Bitmap.createBitmap(launcherView.getWidth(), launcherView.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            launcherView.draw(canvas);
            String name = "SimpleTVLauncher_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date()) + "_" + Long.toString(System.nanoTime() % 1000000L) + ".png";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SimpleTVLauncher");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new java.io.IOException("MediaStore unavailable");
                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null || !bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new java.io.IOException("Screenshot write failed");
                } catch(Exception error) {getContentResolver().delete(uri,null,null);throw error;}
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING,0);
                getContentResolver().update(uri,values,null,null);
            } else {
                File folder = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Screenshots");
                if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException("Cannot create screenshot directory");
                try (OutputStream out = new FileOutputStream(new File(folder,name))) {
                    bitmap.compress(Bitmap.CompressFormat.PNG,100,out);
                }
            }
            bitmap.recycle();
            toast("Screenshot saved: " + name);
        } catch (Exception e) { toast("Screenshot error: " + e.getMessage()); }
    }

    private void startUploadServer() {
        stopUploadServer();
        try {
            uploadServer = new LocalUploadServer(this);
            uploadServer.start();
            launcherView.openPanel("server");
        } catch (Exception ex) { toast("No se pudo iniciar el servidor: " + ex.getMessage()); }
    }
    private void stopUploadServer() {
        if (uploadServer != null) { uploadServer.close(); uploadServer = null; }
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersive();
    }

    @Override
    public void onBackPressed() {
        if (launcherView != null && launcherView.panelOpen()) {
            launcherView.goBack();
            return;
        }
        // El mando BACK solo retrocede por menús internos.
        // En la pantalla principal no se muestra ninguna opción BACK.
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            final int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {}

        if (requestCode == REQ_BG) {
            prefs.edit().putString("custom_bg_uri", uri.toString()).putInt("bg_style", 99).apply();
            launcherView.reloadPreferences();
        } else if (requestCode == REQ_FONT) {
            copyCustomFont(uri);
        } else if (requestCode == 4103) {
            openSelectedFile(uri);
        }
    }

    private List<TvInputInfo> availableVideoInputs() {
        ArrayList<TvInputInfo> result = new ArrayList<>();
        try {
            TvInputManager manager = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (manager == null) return result;
            Set<String> seenIds = new HashSet<>();
            boolean tunerSeen = false;
            for (TvInputInfo item : manager.getTvInputList()) {
                // Ignore apps publishing unrelated TV input services. Keep real
                // passthrough connections and a single integrated tuner.
                boolean tuner = item.getType() == TvInputInfo.TYPE_TUNER;
                if (!tuner && !item.isPassthroughInput()) continue;
                if (tuner && tunerSeen) continue;
                if (!seenIds.add(item.getId())) continue;
                if (tuner) tunerSeen = true;
                result.add(item);
            }
            String raw = prefs.getString("video_input_order", "[]");
            JSONArray order = new JSONArray(raw);
            java.util.Map<String, Integer> positions = new java.util.HashMap<>();
            for (int i = 0; i < order.length(); i++) positions.put(order.optString(i), i);
            result.sort((one, two) -> Integer.compare(
                positions.getOrDefault(one.getId(), 10000), positions.getOrDefault(two.getId(), 10000)));
        } catch (Exception ignored) { }
        return result;
    }

    private boolean inputHidden(String id) {
        return prefs.getStringSet("video_input_hidden", Collections.emptySet()).contains(id);
    }

    private void setInputHidden(String id, boolean hidden) {
        Set<String> values = new HashSet<>(prefs.getStringSet("video_input_hidden", Collections.emptySet()));
        if (hidden) values.add(id); else values.remove(id);
        prefs.edit().putStringSet("video_input_hidden", values).apply();
    }

    private void moveVideoInput(String id, int delta) {
        List<TvInputInfo> items = availableVideoInputs();
        ArrayList<String> ids = new ArrayList<>();
        for (TvInputInfo item : items) ids.add(item.getId());
        int old = ids.indexOf(id), next = old + delta;
        if (old < 0 || next < 0 || next >= ids.size()) return;
        Collections.swap(ids, old, next);
        JSONArray order = new JSONArray();
        for (String value : ids) order.put(value);
        prefs.edit().putString("video_input_order", order.toString()).apply();
    }

    private void openVideoInput(String inputId) {
        if (inputId == null) { openAndroidSettings("android.settings.TV_INPUT_SETTINGS"); return; }
        try {
            Uri channel = TvContract.buildChannelUriForPassthroughInput(inputId);
            Intent intent = new Intent(Intent.ACTION_VIEW, channel);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent); return;
            }
        } catch (Exception ignored) { }
        // Some TV manufacturers expose inputs only through their system input picker.
        try {
            Intent settings = new Intent("android.settings.TV_INPUT_SETTINGS");
            startActivity(settings);
        } catch (Exception ignored) { toast(tr("Abre las entradas desde el mando de la TV")); }
    }

    private void copyCustomFont(Uri uri) {
        File out = new File(getFilesDir(), "custom_font.bin");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream fos = new FileOutputStream(out)) {
            if (in == null) throw new Exception("No se pudo abrir la fuente");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            Typeface.createFromFile(out);
            prefs.edit().putString("font", "custom").apply();
            launcherView.reloadPreferences();
            toast("Fuente aplicada");
        } catch (Exception e) {
            out.delete();
            toast("Ese archivo no parece una fuente válida");
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    public void showSettings() { launcherView.openPanel("settings"); }

    private void editLinks() {
        List<LinkItem> items = loadLinks();
        ArrayList<String> labels = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) labels.add((i + 1) + ".  " + items.get(i).label);
        labels.add("＋ Añadir enlace");
        new AlertDialog.Builder(this)
                .setTitle("Editar enlaces")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == items.size()) addLink();
                    else editOneLink(which);
                })
                .setNegativeButton("Atrás", (d, w) -> showSettings())
                .show();
    }

    private void addLink() {
        launcherView.openPanel("add");
    }

    private void editOneLink(int index) {
        List<LinkItem> items = loadLinks();
        if (index < 0 || index >= items.size()) return;
        LinkItem item = items.get(index);
        String targetSummary = item.target.startsWith("app:") ? "App: " + item.target.substring(4)
                : item.target.startsWith("http") ? item.target
                : item.target.equals("internal:apps") ? "Lista de aplicaciones" : item.target;
        String[] actions = {
                "Cambiar nombre",
                tr("Elegir aplicación"),
                "Escribir URL",
                "Abrir lista de apps",
                "Mover arriba",
                "Mover abajo",
                tr("Eliminar"),
                "Destino actual: " + targetSummary
        };
        new AlertDialog.Builder(this)
                .setTitle(item.label)
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            askText("Nombre", item.label, false, value -> {
                                List<LinkItem> list = loadLinks();
                                if (index < list.size()) {
                                    list.get(index).label = value.trim().isEmpty() ? item.label : value.trim();
                                    saveLinks(list); launcherView.reloadPreferences(); editOneLink(index);
                                }
                            });
                            break;
                        case 1: chooseAppForLink(index); break;
                        case 2:
                            askText("URL", item.target.startsWith("http") ? item.target : "https://", true, value -> {
                                String url = value.trim();
                                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
                                List<LinkItem> list = loadLinks();
                                if (index < list.size()) { list.get(index).target = url; saveLinks(list); launcherView.reloadPreferences(); editOneLink(index); }
                            });
                            break;
                        case 3:
                            updateTarget(index, "internal:apps"); editOneLink(index); break;
                        case 4:
                            moveLink(index, -1); editLinks(); break;
                        case 5:
                            moveLink(index, 1); editLinks(); break;
                        case 6:
                            confirmDelete(index); break;
                        default:
                            editOneLink(index); break;
                    }
                })
                .setNegativeButton("Atrás", (d, w) -> editLinks())
                .show();
    }

    private void confirmDelete(int index) {
        List<LinkItem> items = loadLinks();
        if (index < 0 || index >= items.size()) return;
        new AlertDialog.Builder(this)
                .setTitle("Eliminar " + items.get(index).label)
                .setMessage("¿Seguro?")
                .setPositiveButton(tr("Eliminar"), (d, w) -> {
                    List<LinkItem> list = loadLinks();
                    if (index < list.size()) list.remove(index);
                    
                    saveLinks(list); launcherView.reloadPreferences(); editLinks();
                })
                .setNegativeButton(tr("Cancelar"), (d, w) -> editOneLink(index))
                .show();
    }

    private void moveLink(int index, int delta) {
        List<LinkItem> items = loadLinks();
        int j = index + delta;
        if (index < 0 || index >= items.size() || j < 0 || j >= items.size()) return;
        Collections.swap(items, index, j);
        saveLinks(items);
        launcherView.reloadPreferences();
    }

    private void updateTarget(int index, String target) {
        List<LinkItem> items = loadLinks();
        if (index >= 0 && index < items.size()) {
            items.get(index).target = target;
            saveLinks(items);
            launcherView.reloadPreferences();
        }
    }

    private void chooseAppForLink(int index) {
        List<AppEntry> apps = getLaunchableApps();
        if (apps.isEmpty()) { toast("No se han encontrado aplicaciones lanzables"); return; }
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) names[i] = apps.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle(tr("Elegir aplicación"))
                .setItems(names, (d, which) -> {
                    AppEntry app = apps.get(which);
                    List<LinkItem> list = loadLinks();
                    if (index < list.size()) {
                        list.get(index).target = "app:" + app.packageName;
                        list.get(index).label = app.label;
                        saveLinks(list);
                        launcherView.reloadPreferences();
                        editOneLink(index);
                    }
                })
                .setNegativeButton(tr("Cancelar"), (d, w) -> editOneLink(index))
                .show();
    }

    public void showAllApps() { launcherView.openPanel("apps"); }

    private List<AppEntry> getLaunchableApps() {
        PackageManager pm = getPackageManager();
        ArrayList<AppEntry> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Intent[] intents = new Intent[] {
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER),
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        };
        for (Intent intent : intents) {
            List<ResolveInfo> infos = pm.queryIntentActivities(intent, 0);
            for (ResolveInfo info : infos) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || seen.contains(pkg)) continue;
                seen.add(pkg);
                CharSequence cs = info.loadLabel(pm);
                result.add(new AppEntry(cs == null ? pkg : cs.toString(), pkg));
            }
        }
        Collections.sort(result, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                return a.label.toLowerCase(Locale.getDefault()).compareTo(b.label.toLowerCase(Locale.getDefault()));
            }
        });
        return result;
    }

    public void launchTarget(String target) {
        try {
            if (target == null || target.isEmpty()) return;
            if (target.equals("internal:apps")) {
                showAllApps();
            } else if (target.startsWith("app:")) {
                launchPackage(target.substring(4));
            } else if (target.startsWith("http://") || target.startsWith("https://")) {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
            } else {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
            }
        } catch (Exception e) {
            toast("No se puede abrir este enlace");
        }
    }

    private void launchPackage(String pkg) {
        PackageManager pm = getPackageManager();
        Intent intent = null;
        if (Build.VERSION.SDK_INT >= 21) intent = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (intent == null) intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) { toast("La aplicación no está instalada o no se puede abrir"); return; }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void chooseBackground() {
        String[] options = {tr("Noche"), tr("Aurora"), tr("Violeta"), tr("Carbono"), tr("Océano"), "Negro puro", "Elegir imagen…"};
        new AlertDialog.Builder(this)
                .setTitle("Fondo")
                .setItems(options, (d, which) -> {
                    if (which < 6) {
                        prefs.edit().putInt("bg_style", which).remove("custom_bg_uri").apply();
                        launcherView.reloadPreferences();
                        chooseBackground();
                    } else {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("image/*");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        try { startActivityForResult(i, REQ_BG); } catch (ActivityNotFoundException e) { toast("No hay selector de archivos disponible"); }
                    }
                })
                .setNegativeButton("Atrás", (d, w) -> showSettings())
                .show();
    }

    private void chooseFont() {
        String[] options = {"Sans", "Sans condensada", "Serif", tr("Monoespaciada"), "Elegir .ttf/.otf…"};
        String current = prefs.getString("font", "sans");
        int checked = current.equals("condensed") ? 1 : current.equals("serif") ? 2 : current.equals("mono") ? 3 : current.equals("custom") ? 4 : 0;
        new AlertDialog.Builder(this)
                .setTitle("Fuente")
                .setSingleChoiceItems(options, checked, (d, which) -> {
                    if (which == 4) {
                        d.dismiss();
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        try { startActivityForResult(i, REQ_FONT); } catch (ActivityNotFoundException e) { toast("No hay selector de archivos disponible"); }
                    } else {
                        String value = which == 1 ? "condensed" : which == 2 ? "serif" : which == 3 ? "mono" : "sans";
                        prefs.edit().putString("font", value).apply();
                        launcherView.reloadPreferences();
                    }
                })
                .setPositiveButton("Hecho", null)
                .setNegativeButton("Atrás", (d, w) -> showSettings())
                .show();
    }

    private void chooseTextSize() {
        String[] options = {tr("Pequeño"), tr("Normal"), tr("Grande"), tr("Muy grande")};
        int current = prefs.getInt("text_size", 1);
        new AlertDialog.Builder(this)
                .setTitle(tr("Tamaño del texto"))
                .setSingleChoiceItems(options, current, (d, which) -> {
                    prefs.edit().putInt("text_size", which).apply();
                    launcherView.reloadPreferences();
                })
                .setPositiveButton("Hecho", null)
                .setNegativeButton("Atrás", (d, w) -> showSettings())
                .show();
    }

    private void chooseClockOptions() {
        boolean showDate = prefs.getBoolean("show_date", true);
        boolean seconds = prefs.getBoolean("show_seconds", false);
        String[] options = {"Mostrar fecha", "Mostrar segundos"};
        boolean[] checked = {showDate, seconds};
        new AlertDialog.Builder(this)
                .setTitle(tr("Reloj y fecha"))
                .setMultiChoiceItems(options, checked, (d, which, isChecked) -> {
                    if (which == 0) prefs.edit().putBoolean("show_date", isChecked).apply();
                    else prefs.edit().putBoolean("show_seconds", isChecked).apply();
                    launcherView.reloadPreferences();
                })
                .setPositiveButton("Hecho", null)
                .setNegativeButton("Atrás", (d, w) -> showSettings())
                .show();
    }

    private void chooseBackMode() {
        String[] options = {"No hacer nada", "Abrir ajustes", "Volver a la app anterior"};
        int current = prefs.getInt("back_mode", 0);
        new AlertDialog.Builder(this)
                .setTitle("Botón BACK")
                .setSingleChoiceItems(options, current, (d, which) -> {
                    prefs.edit().putInt("back_mode", which).apply();
                    d.dismiss();
                    showSettings();
                })
                .setNegativeButton(tr("Cancelar"), null)
                .show();
    }

    private void requestHomeRole() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Class<?> roleManagerClass = Class.forName("android.app.role.RoleManager");
                Object roleManager = getSystemService("role");
                String roleHome = (String) roleManagerClass.getField("ROLE_HOME").get(null);
                boolean held = (Boolean) roleManagerClass.getMethod("isRoleHeld", String.class).invoke(roleManager, roleHome);
                if (held) { toast("Simple TV Launcher ya es el launcher predeterminado"); return; }
                Intent intent = (Intent) roleManagerClass.getMethod("createRequestRoleIntent", String.class).invoke(roleManager, roleHome);
                startActivity(intent);
                return;
            } catch (Exception ignored) {}
        }
        try {
            startActivity(new Intent("android.settings.HOME_SETTINGS"));
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    private void openHomeSettings() {
        try { startActivity(new Intent("android.settings.HOME_SETTINGS")); }
        catch (Exception first) {
            try { startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)); }
            catch (Exception second) { toast("El fabricante no ofrece selector de launcher"); }
        }
    }

    private void suppressHomeCaptureBriefly() {
        // Accessibility redirection must not steal focus from a legitimate external activity.
        prefs.edit().putLong("external_launch_until", android.os.SystemClock.elapsedRealtime() + 30000L).apply();
    }

    // Centraliza las aperturas de ajustes: una sola actividad por acción del mando.
    // Evita abrir varias copias cuando el fabricante reenvía pulsaciones repetidas.
    private long lastSystemSettingsLaunch = 0L;
    private boolean launchSystemSettings(Intent intent) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSystemSettingsLaunch < 1200L) return true;
        try {
            // No NEW_TASK ni CLEAR_TOP: una sola transición desde esta Activity.
            startActivity(intent);
            lastSystemSettingsLaunch = now;
            suppressHomeCaptureBriefly();
            return true;
        } catch (android.content.ActivityNotFoundException | SecurityException ex) {
            return false;
        } catch (Exception ex) {
            return false;
        }
    }
    private void openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < 26) return;
        Intent perApp = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
        if (launchSystemSettings(perApp)) return;
        // Algunos Android TV carecen de la pantalla por aplicación.
        Intent appDetails = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        if (launchSystemSettings(appDetails)) return;
        toast("Abre Ajustes de Android y permite instalar aplicaciones desde Simple TV Launcher");
    }

    private void openAndroidSettings(String action) {
        for (String candidate : new String[]{action, Settings.ACTION_SETTINGS,
                Settings.ACTION_APPLICATION_SETTINGS}) {
            if (launchSystemSettings(new Intent(candidate))) return;
        }
        if (!launchTvSettings()) toast("No se pudieron abrir los ajustes de Android");
    }

    private boolean launchTvSettings() {
        for (String pkg : new String[]{"com.android.tv.settings", "com.google.android.tv.settings",
                "com.xiaomi.mitv.settings", "com.android.settings"}) {
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) { suppressHomeCaptureBriefly(); startActivity(launch); return true; }
            } catch (Exception ignored) { }
        }
        return false;
    }
    private void openXiaomiTvSettings() {
        String[] packages = {"com.android.tv.settings", "com.google.android.tv.settings", "com.xiaomi.mitv.settings"};
        for (String pkg : packages) {
            try {
                Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch != null) { startActivity(launch); return; }
            } catch (Exception ignored) { }
        }
        openAndroidSettings(Settings.ACTION_SETTINGS);
    }
    private void openOwnAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        if (!launchSystemSettings(intent)) openAndroidSettings(Settings.ACTION_APPLICATION_SETTINGS);
    }
    private void openHomeCaptureSettings() {
        prefs.edit().putBoolean("home_capture_enabled", true).apply();
        new AlertDialog.Builder(this)
                .setTitle("Capturar botón HOME")
                .setMessage("Android reserva el botón HOME. Como alternativa, puedes activar el servicio de accesibilidad Simple TV Launcher para intentar redirigirlo aquí.\n\nEn la pantalla que se abre, entra en Servicios instalados y activa Simple TV Launcher · HOME.\n\nLa función depende del fabricante del televisor y puede mostrar brevemente su pantalla de inicio. Puedes desactivarla en Accesibilidad cuando quieras.")
                .setPositiveButton("Abrir Accesibilidad", (d, which) -> {
                    try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                    catch (Exception ex) { toast("Abre Accesibilidad desde Ajustes de Android"); }
                })
                .setNegativeButton(tr("Cancelar"), (d, which) -> prefs.edit().putBoolean("home_capture_enabled", false).apply())
                .show();
    }

    private final ArrayList<Uri> explorerUris = new ArrayList<>();
    private final ArrayList<String> explorerFolders = new ArrayList<>();
    private String explorerPath = Environment.DIRECTORY_DOWNLOADS + "/";

    private String formatFileDate(long millis) {
        if (millis <= 0) return "";
        return new SimpleDateFormat("dd/MM/yyyy  HH:mm", displayLocale).format(new Date(millis));
    }

    private void refreshExplorer(List<String> rows) {
        rows.clear(); explorerUris.clear(); explorerFolders.clear();
        // Navegación lógica: compatible tanto con MediaStore como con el acceso
        // directo al almacenamiento, cuando el firmware lo permite.
        if (!explorerPath.isEmpty()) {
            rows.add("..   " + tr("Carpeta anterior"));
            explorerUris.add(null); explorerFolders.add(".. ");
        }
        java.util.TreeMap<String, String> folders = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        java.util.TreeMap<String, ExplorerFile> files = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (Build.VERSION.SDK_INT >= 29) {
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            String[] cols = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATE_MODIFIED};
            try (Cursor c = getContentResolver().query(collection, cols, null, null, null)) {
                if (c != null) while (c.moveToNext()) {
                    String path = c.getString(2);
                    if (path == null || !path.startsWith(explorerPath)) continue;
                    String rest = path.substring(explorerPath.length());
                    if (!rest.isEmpty()) {
                        String folder = rest.split("/")[0];
                        if (!folder.isEmpty()) folders.put(folder, explorerPath + folder + "/");
                        continue;
                    }
                    String name = c.getString(1);
                    if (name == null) continue;
                    Uri uri = ContentUris.withAppendedId(collection, c.getLong(0));
                    long changed = c.isNull(3) ? 0 : c.getLong(3) * 1000L;
                    files.put(name, new ExplorerFile(uri, changed));
                }
            } catch (Exception ignored) { }
        }
        File root = Environment.getExternalStorageDirectory();
        File current = explorerPath.isEmpty() ? root : new File(root, explorerPath);
        addExplorerDirectory(current, folders, files);
        for (java.util.Map.Entry<String, String> e : folders.entrySet()) {
            rows.add("📁  " + e.getKey()); explorerUris.add(null); explorerFolders.add(e.getValue());
        }
        for (java.util.Map.Entry<String, ExplorerFile> e : files.entrySet()) {
            rows.add(e.getKey() + (e.getValue().modified > 0 ? "    ·    " + formatFileDate(e.getValue().modified) : ""));
            explorerUris.add(e.getValue().uri); explorerFolders.add(null);
        }
        rows.add(tr("Explorar otras carpetas…"));
        explorerUris.add(null); explorerFolders.add("picker");
    }

    private static class ExplorerFile {
        final Uri uri; final long modified;
        ExplorerFile(Uri uri, long modified) { this.uri = uri; this.modified = modified; }
    }

    private void addExplorerDirectory(File directory, java.util.Map<String, String> folders,
                                      java.util.Map<String, ExplorerFile> files) {
        File[] children;
        try { children = directory.listFiles(); } catch (Exception e) { return; }
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) folders.put(child.getName(), explorerPath + child.getName() + "/");
            else if (child.isFile() && !files.containsKey(child.getName())) {
                files.put(child.getName(), new ExplorerFile(Uri.fromFile(child), child.lastModified()));
            }
        }
    }

    private void openExplorerFile(int index) {
        if (index < 0 || index >= explorerFolders.size()) return;
        String folder = explorerFolders.get(index);
        if ("picker".equals(folder)) {
            try {
                // Conservar ACTION_OPEN_DOCUMENT: probado por el usuario en Xiaomi.
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, 4103);
            } catch (Exception e) { toast("El televisor no dispone de explorador de archivos"); }
            return;
        }
        if (".. ".equals(folder)) {
            String p = explorerPath.endsWith("/") ? explorerPath.substring(0, explorerPath.length()-1) : explorerPath;
            int last = p.lastIndexOf('/');
            explorerPath = last < 0 ? "" : p.substring(0,last+1);
            launcherView.openPanel("explorer"); return;
        }
        if (folder != null) { explorerPath = folder; launcherView.openPanel("explorer"); return; }
        Uri uri = explorerUris.get(index);
        if (uri != null) openSelectedFile(uri);
    }

    private void openSelectedFile(Uri uri) {
        try {
            // Convertir rutas locales a un content:// URI con permisos temporales.
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                File file = new File(uri.getPath()).getCanonicalFile();
                uri = new Uri.Builder().scheme("content")
                        .authority(getPackageName() + ".sharedfiles")
                        .appendPath("file").appendPath(file.getAbsolutePath()).build();
            }
            String filename = uri.getLastPathSegment();
            try (Cursor c = getContentResolver().query(uri,
                    new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) filename = c.getString(0);
            } catch (Exception ignored) { }
            String mime = getContentResolver().getType(uri);
            if (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime)) {
                String ext = filename != null && filename.lastIndexOf('.') >= 0
                        ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
                mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime == null) {
                    if ("apk".equals(ext)) mime = "application/vnd.android.package-archive";
                    else if ("mkv".equals(ext)) mime = "video/x-matroska";
                    else if ("srt".equals(ext)) mime = "application/x-subrip";
                    else mime = "*/*";
                }
            }
            // APKs are installation requests, not ordinary file viewers.
            // The system installer needs an exact APK MIME type and the
            // user must authorize this app as an installation source.
            boolean isApk = filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".apk");
            if (isApk) {
                mime = "application/vnd.android.package-archive";
                if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                    new AlertDialog.Builder(this)
                        .setMessage("Para instalar este APK, permite la instalación de aplicaciones desde Simple TV Launcher en los ajustes de Android.")
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton("Abrir ajustes", (dialog, which) -> {
                            openInstallPermissionSettings();
                        }).show();
                    return;
                }
            }
            // NO createChooser(): el resolvedor nativo de Android permite
            // elegir Solo una vez / Siempre cuando el firmware lo soporta.
            Intent view = new Intent(isApk ? Intent.ACTION_INSTALL_PACKAGE : Intent.ACTION_VIEW);
            view.setDataAndType(uri, mime);
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            view.setClipData(android.content.ClipData.newUri(getContentResolver(), "Archivo", uri));
            // Some Xiaomi resolvers fail to propagate a URI grant to the selected app.
            // Grant read access explicitly to compatible activities as well.
            try {
                for (ResolveInfo info : getPackageManager().queryIntentActivities(view,
                        PackageManager.MATCH_DEFAULT_ONLY)) {
                    if (info.activityInfo != null) {
                        grantUriPermission(info.activityInfo.packageName, uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    }
                }
            } catch (Exception ignored) { }
            suppressHomeCaptureBriefly();
            startActivity(view);
        } catch (android.content.ActivityNotFoundException ex) {
            toast("No hay ninguna aplicación compatible para abrir este archivo");
        } catch (Exception ex) {
            toast("Error al abrir archivo: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void resetSettings() {
        new AlertDialog.Builder(this)
                .setTitle(tr("Restablecer"))
                .setMessage("Se restaurarán enlaces, fondo, fuente y comportamiento de botones.")
                .setPositiveButton(tr("Restablecer"), (d, w) -> {
                    prefs.edit().clear().apply();
                    new File(getFilesDir(), "custom_font.bin").delete();
                    launcherView.reloadPreferences();
                    toast("Ajustes restaurados");
                })
                .setNegativeButton(tr("Cancelar"), null)
                .show();
    }

    private void showAbout() {
        String version = "desconocida";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("Simple TV Launcher")
                .setMessage(tr("Versión ") + version + "\n\nLauncher minimalista para Android TV.\n\nD-pad: mover\nOK: abrir\nMENU / SETTINGS: ajustes\nHOME: vuelve aquí cuando se configura como launcher predeterminado.")
                .setPositiveButton(tr("Cerrar"), null)
                .show();
    }

    private interface TextCallback { void done(String value); }

    private void askText(String title, String initial, boolean url, TextCallback callback) {
        final EditText input = new EditText(this);
        input.setText(initial);
        input.setSingleLine(true);
        input.setPadding(24, 16, 24, 16);
        input.setInputType(url ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setSelection(input.length());
        // On Android TV the keyboard owns D-pad navigation while editing; the
        // IME Done action must also commit the link without requiring touch.
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(tr(title))
                .setView(input)
                .setPositiveButton(tr("Guardar"), (d, w) -> callback.done(input.getText().toString()))
                .setNegativeButton(tr("Cancelar"), null)
                .create();
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                    (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                        && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                if (dialog.isShowing()) dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                return true;
            }
            return false;
        });
        dialog.getWindow();
        dialog.setOnShowListener(d -> {
            input.requestFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            input.post(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null && dialog.isShowing()) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            });
        });
        dialog.show();
    }

    private List<LinkItem> loadLinks() {
        String raw = prefs.getString("links", null);
        ArrayList<LinkItem> items = new ArrayList<>();
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String target = o.optString("target", "");
                    if (!target.equals("internal:apps")) items.add(new LinkItem(o.optString("label", "Enlace"), target));
                }
            } catch (JSONException ignored) {}
        }
        if (raw == null) {
            items.add(new LinkItem("YouTube", "app:com.google.android.youtube.tv"));
            items.add(new LinkItem("Netflix", "app:com.netflix.ninja"));
            items.add(new LinkItem("Kodi", "app:org.xbmc.kodi"));
        }
        return items;
    }

    private void saveLinks(List<LinkItem> items) {
        JSONArray arr = new JSONArray();
        try {
            for (LinkItem item : items) {
                JSONObject o = new JSONObject();
                o.put("label", item.label);
                o.put("target", item.target);
                arr.put(o);
            }
            prefs.edit().putString("links", arr.toString()).apply();
        } catch (JSONException ignored) {}
    }

    private static final class LinkItem {
        String label;
        String target;
        LinkItem(String label, String target) { this.label = label; this.target = target; }
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        AppEntry(String label, String packageName) { this.label = label; this.packageName = packageName; }
    }

    private final class LauncherView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint overlay = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<LinkItem> links = new ArrayList<>();
        private int selected = 0;
        private float scroll = 0f;
        private float targetScroll = 0f;
        private long animationStart = 0L;
        private float animationFrom = 0f;
        private float animationDuration() { return prefs.getBoolean("menu_animations", true) ? 90f : 0f; }
        private float panelFocus = 0f;
        private float panelFocusFrom = 0f;
        private float panelFocusTarget = 0f;
        private long panelFocusStart = 0L;
        private float panelScroll = 0f;
        private float panelScrollFrom = 0f;
        private float panelScrollTarget = 0f;
        private long panelAnimationStart = 0L;
        private boolean okPressed = false;
        private boolean longOkTriggered = false;
        private final Runnable longOkAction = () -> {
            if (okPressed && !panelOpen() && selected < links.size()) {
                longOkTriggered = true;
                openEdit(selected);
            }
        };
        private int bgStyle = 0;
        private int textSizeMode = 1;
        private boolean showDate = true;
        private boolean showTime = true;
        private boolean showSeconds = false;
        private Typeface typeface = Typeface.create("sans", Typeface.NORMAL);
        private Bitmap customBg;
        private long pulseUntil = 0L;
        private float touchDownY;
        private boolean touchMoved;

        private String panel = "";
        private int panelSelected = 0;
        private int settingsSelected = 0; // Remember the Settings row across submenus.
        private int editingIndex = -1;
        private final ArrayList<String> panelRows = new ArrayList<>();
        private final ArrayList<String> panelTargets = new ArrayList<>();
        private long panelEnterAt = 0L;
        private long panelExitAt = 0L;
        private String exitedPanel = "";
        private ArrayList<String> exitedRows = new ArrayList<>();
        private ArrayList<String> exitedTargets = new ArrayList<>();
        private float exitedScroll = 0f, exitedFocus = 0f;
        private static final float PANEL_TRANSITION_MS = 125f;

        private void beginPanelExit() {
            if (!prefs.getBoolean("menu_animations", true)) return;
            exitedPanel = previousPanel;
            exitedRows = new ArrayList<>(panelRows);
            exitedTargets = new ArrayList<>(panelTargets);
            exitedScroll = panelScroll;
            exitedFocus = panelFocus;
            panelExitAt = android.os.SystemClock.uptimeMillis();
        }
        private String previousPanel = "";

        boolean panelOpen() { return !panel.isEmpty(); }

        void openPanel(String type) {
            if ("settings".equals(panel)) settingsSelected = panelSelected;
            previousPanel = type;
            exitedPanel = "";
            if (!panelOpen()) panelEnterAt = android.os.SystemClock.uptimeMillis() - 25L;
            panel = type;
            panelSelected = "settings".equals(type) ? settingsSelected : 0;
            panelFocus = panelFocusFrom = panelFocusTarget = 0f;
            panelFocusStart = 0L;
            panelScroll = 0f;
            panelScrollFrom = 0f;
            panelScrollTarget = 0f;
            panelAnimationStart = 0L;
            buildPanel();
            if ("server".equals(panel)) panelSelected = Math.max(0, panelRows.size()-1);
            if ("settings".equals(panel)) {
                panelSelected = Math.max(0, Math.min(panelSelected, panelRows.size() - 1));
                panelFocus = panelFocusFrom = panelFocusTarget = panelSelected;
                int visible = panelVisibleCount();
                panelScroll = panelScrollFrom = panelScrollTarget = Math.max(0,
                    Math.min(panelSelected - visible / 2f, Math.max(0, panelRows.size() - visible)));
            }
            invalidate();
        }

        private void openEdit(int index) { editingIndex = index; openPanel("edit"); }

        void goBack() {
            previousPanel = panel;
            switch (panel) {
                case "settings": panel = ""; beginPanelExit(); break;
                case "server": stopUploadServer(); openPanel("settings"); return;
                case "edit": case "add": openPanel("links"); return;
                case "pickapp": openPanel(editingIndex < 0 ? "add" : "edit"); return;
                case "links": case "background": case "font": case "size":
                    openPanel("settings"); return;
                case "apps": panel = ""; beginPanelExit(); invalidate(); return;
                case "access": openPanel("home"); return;
                case "explorer":
                    if (!explorerPath.isEmpty()) {
                        String p = explorerPath.endsWith("/") ? explorerPath.substring(0, explorerPath.length()-1) : explorerPath;
                        int last = p.lastIndexOf('/');
                        explorerPath = last < 0 ? "" : p.substring(0,last+1);
                        openPanel("explorer");
                    } else openPanel("settings");
                    return;
                case "clock": case "home": case "language": case "appearance": openPanel("settings"); return;
                case "inputmanage": openPanel("inputs"); return;
                case "inputs": panel = ""; beginPanelExit(); invalidate(); return;
                default: panel = ""; beginPanelExit();
            }
            invalidate();
        }

        private void buildPanel() {
            panelRows.clear(); panelTargets.clear();
            switch (panel) {
                case "settings":
                    Collections.addAll(panelRows,tr("Enlaces"), tr("Fondos"), tr("Fuentes"), tr("Tamaño del texto"), tr("Reloj y fecha"), tr("Launcher predeterminado"), tr("Idioma"), tr("Transferencia web"), tr("Explorador de archivos"), tr("Opciones de menús"), tr("Ajustes de Android"), tr("Restablecer"), tr("Información")); break;
                case "links":
                    for (LinkItem link : loadLinks()) panelRows.add(resolveLinkLabel(link));
                    panelRows.add(tr("+ Añadir aplicación")); panelRows.add(tr("+ Añadir URL")); break;
                case "add": panelRows.add(tr("Elegir aplicación")); panelRows.add(tr("Añadir URL")); break;
                case "edit":
                    panelRows.add(tr("Cambiar por aplicación")); panelRows.add(tr("Cambiar por URL")); panelRows.add(tr("Subir")); panelRows.add(tr("Bajar")); panelRows.add(tr("Eliminar")); break;
                case "pickapp": case "apps":
                    preloadInstalledIcons();
                    for (AppEntry app : getLaunchableApps()) { panelRows.add(app.label); panelTargets.add(app.packageName); }
                    break;
                case "background":
                    Collections.addAll(panelRows,tr("Noche"), tr("Aurora"), tr("Violeta"), tr("Carbono"), tr("Océano"), tr("Negro"), tr("Montañas · imagen"), tr("Atardecer · imagen"), "Cordillera · imagen", "Noche estrellada · imagen", tr("Mi propia imagen…")); break;
                case "font": Collections.addAll(panelRows,"Sans", tr("Condensada"), "Serif", tr("Monoespaciada"), tr("Fuente personalizada…")); break;
                case "size": Collections.addAll(panelRows,tr("Pequeño"), tr("Normal"), tr("Grande"), tr("Muy grande")); break;
                case "appearance":
                    panelRows.add(tr("Transparencia: ") + (prefs.getBoolean("menu_transparency", true) ? tr("Sí") : tr("No")));
                    panelRows.add(tr("Posición del panel: ") + tr(prefs.getString("menu_side", "right").equals("right") ? "Derecha" : "Izquierda"));
                    panelRows.add(tr("Animaciones: ") + (prefs.getBoolean("menu_animations", true) ? tr("Sí") : tr("No")));
                    panelRows.add(tr("Mostrar iconos: ") + (prefs.getBoolean("menu_icons", true) ? tr("Sí") : tr("No")));
                    panelRows.add(tr("Estilo de selección: ") + tr(prefs.getString("selection_style", "selector")));
                    break;
                case "inputs":
                    refreshInputIconCache();
                    for (TvInputInfo tv : availableVideoInputs()) {
                        if (inputHidden(tv.getId())) continue;
                        panelRows.add(String.valueOf(tv.loadLabel(MainActivity.this)));
                        panelTargets.add(tv.getId());
                    }
                    panelRows.add(tr("Organizar entradas"));
                    panelTargets.add("@manage");
                    break;
                case "inputmanage":
                    refreshInputIconCache();
                    for (TvInputInfo tv : availableVideoInputs()) {
                        panelRows.add((inputHidden(tv.getId()) ? "○  " : "●  ") + tv.loadLabel(MainActivity.this));
                        panelTargets.add(tv.getId());
                    }
                    break;
                case "clock":
                    panelRows.add(tr("Posición de la fecha: ") + tr(prefs.getString("date_position", "bottom")));
                    panelRows.add(tr("Hora: ") + (showTime ? tr("Sí") : tr("No")));
                    panelRows.add(tr("Fecha: ") + (showDate ? tr("Sí") : tr("No")));
                    panelRows.add(tr("Segundos: ") + (showSeconds ? tr("Sí") : tr("No"))); break;
                case "language":
                    for (int j = 0; j < LANG_CODES.length; j++) panelRows.add((j == 0 ? tr("Automático") : LANG_NAMES[j]) + (prefs.getString("language", "auto").equals(LANG_CODES[j]) ? "  ✓" : ""));
                    break;
                case "home": Collections.addAll(panelRows,tr("Solicitar launcher predeterminado"), tr("Ajustes de pantalla de inicio"), tr("Capturar HOME (accesibilidad)"), tr("Ajustes de aplicaciones"), tr("Accesibilidad alternativa")); break;
                case "access": Collections.addAll(panelRows, tr("Accesibilidad de Android"), tr("Ajustes generales de Android"), tr("Ajustes de TV Xiaomi"), tr("Ajustes de esta aplicación"), tr("Información para activar HOME")); break;
                case "explorer": refreshExplorer(panelRows); panelSelected = 0; break;
                case "server":
                    if (uploadServer != null) {
                        panelRows.add(uploadServer.address());
                        panelRows.add(tr("Sube o descarga archivos desde el navegador"));
                    }
                    panelRows.add(tr("Detener servidor")); break;
            }
            if (panelSelected >= panelRows.size()) panelSelected = Math.max(0, panelRows.size()-1);
        }

        private String resolveLinkLabel(LinkItem item) {
            if (!item.target.startsWith("app:")) return item.label;
            String pkg = item.target.substring(4);
            try {
                PackageManager pm = getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception ignored) { return item.label; }
        }

        private void saveAppLink(String pkg, String label) {
            List<LinkItem> list = loadLinks();
            if (editingIndex >= 0 && editingIndex < list.size()) {
                list.get(editingIndex).target = "app:" + pkg;
                list.get(editingIndex).label = label;
            } else list.add(new LinkItem(label, "app:" + pkg));
            saveLinks(list); reloadPreferences(); openPanel("links");
        }

        private void askUrl(boolean existing) {
            String initial = existing && editingIndex >= 0 && editingIndex < loadLinks().size() ? loadLinks().get(editingIndex).target : "https://";
            askText("URL", initial.startsWith("http") ? initial : "https://", true, value -> {
                String url = value.trim();
                if (url.isEmpty()) { openPanel("links"); return; }
                if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
                String label;
                try { label = Uri.parse(url).getHost(); } catch (Exception ex) { label = url; }
                if (label == null || label.isEmpty()) label = "Web";
                List<LinkItem> list = loadLinks();
                if (existing && editingIndex >= 0 && editingIndex < list.size()) list.set(editingIndex, new LinkItem(label,url));
                else list.add(new LinkItem(label,url));
                saveLinks(list); reloadPreferences(); openPanel("links");
            });
        }

        private void activatePanel() {
            int i = panelSelected;
            if (i < 0 || i >= panelRows.size()) return;
            switch (panel) {
                case "settings":
                    switch (i) {
                        case 0: openPanel("links"); break;
                        case 1: openPanel("background"); break;
                        case 2: openPanel("font"); break;
                        case 3: openPanel("size"); break;
                        case 4: openPanel("clock"); break;
                        case 5: openPanel("home"); break;
                        case 6: openPanel("language"); break;
                        case 7: startUploadServer(); break;
                        case 8: explorerPath = Environment.DIRECTORY_DOWNLOADS + "/"; openPanel("explorer"); break;
                        case 9: openPanel("appearance"); break;
                        case 10: openAndroidSettings(Settings.ACTION_SETTINGS); break;
                        case 11: resetSettings(); break;
                        case 12: showAbout(); break;
                    } break;
                case "links": {
                    int count = loadLinks().size();
                    if (i < count) openEdit(i);
                    else if (i == count) { editingIndex = -1; openPanel("pickapp"); }
                    else { editingIndex = -1; askUrl(false); }
                    break;
                }
                case "add": if (i == 0) openPanel("pickapp"); else askUrl(false); break;
                case "edit":
                    switch (i) {
                        case 0: openPanel("pickapp"); break;
                        case 1: askUrl(true); break;
                        case 2: {
                            int next = Math.max(0, editingIndex - 1);
                            moveLink(editingIndex, -1);
                            editingIndex = next;
                            buildPanel(); invalidate(); break;
                        }
                        case 3: {
                            int next = Math.min(loadLinks().size() - 1, editingIndex + 1);
                            moveLink(editingIndex, 1);
                            editingIndex = next;
                            buildPanel(); invalidate(); break;
                        }
                        case 4: confirmDeletePanel(); break;
                    } break;
                case "pickapp": saveAppLink(panelTargets.get(i), panelRows.get(i)); break;
                case "apps": launchPackage(panelTargets.get(i)); break;
                case "explorer": openExplorerFile(i); break;
                case "background":
                    if (i <= 9) { int chosenStyle = i >= 8 ? i + 2 : i; prefs.edit().putInt("bg_style",chosenStyle).remove("custom_bg_uri").apply(); reloadPreferences(); }
                    else { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*"); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); try { startActivityForResult(intent, REQ_BG); } catch (Exception ex) { toast("No hay selector de imágenes"); } }
                    break;
                case "font":
                    if (i < 4) { String[] fonts = {"sans","condensed","serif","mono"}; prefs.edit().putString("font",fonts[i]).apply(); reloadPreferences(); }
                    else { Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"); intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); try { startActivityForResult(intent, REQ_FONT); } catch (Exception ex) { toast("No hay selector de archivos"); } }
                    break;
                case "size": prefs.edit().putInt("text_size",i).apply(); reloadPreferences(); break;
                case "clock":
                    if (i == 0) { String old = prefs.getString("date_position", "bottom"); prefs.edit().putString("date_position", "top".equals(old) ? "bottom" : "bottom".equals(old) ? "hidden" : "top").apply(); }
                    else if (i == 1) prefs.edit().putBoolean("show_time", !showTime).apply();
                    else if (i == 2) prefs.edit().putBoolean("show_date", !showDate).apply();
                    else prefs.edit().putBoolean("show_seconds", !showSeconds).apply();
                    reloadPreferences(); buildPanel(); break;
                case "appearance":
                    if (i == 4) { String next = "zoom".equals(prefs.getString("selection_style", "selector")) ? "selector" : "zoom"; prefs.edit().putString("selection_style", next).apply(); buildPanel(); invalidate(); break; }
                    if (i == 1) {
                        String side = prefs.getString("menu_side", "right");
                        prefs.edit().putString("menu_side", "right".equals(side) ? "left" : "right").apply();
                        buildPanel(); invalidate(); break;
                    }
                    String appearanceKey = i == 0 ? "menu_transparency" : i == 2 ? "menu_animations" : "menu_icons";
                    boolean currentValue = prefs.getBoolean(appearanceKey, true);
                    prefs.edit().putBoolean(appearanceKey, !currentValue).apply();
                    buildPanel(); invalidate(); break;
                case "inputs":
                    if ("@manage".equals(panelTargets.get(i))) openPanel("inputmanage");
                    else openVideoInput(panelTargets.get(i));
                    break;
                case "inputmanage": {
                    final String inputId = panelTargets.get(i);
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle(panelRows.get(i))
                        .setItems(new String[]{tr("Mostrar / ocultar"), tr("Subir"), tr("Bajar")}, (dialog, choice) -> {
                            if (choice == 0) setInputHidden(inputId, !inputHidden(inputId));
                            else moveVideoInput(inputId, choice == 1 ? -1 : 1);
                            int keep = panelSelected;
                            buildPanel();
                            panelSelected = Math.min(keep, Math.max(0, panelRows.size()-1));
                            panelFocus = panelFocusFrom = panelFocusTarget = panelSelected;
                            invalidate();
                        }).show();
                    break;
                }
                case "language":
                    prefs.edit().putString("language", LANG_CODES[i]).apply();
                    applyLanguage(); reloadPreferences(); openPanel("language"); break;
                case "server":
                    if (i == panelRows.size() - 1) { stopUploadServer(); openPanel("settings"); }
                    break;
                case "access":
                    if (i == 0) openAndroidSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    else if (i == 1) openAndroidSettings(Settings.ACTION_SETTINGS);
                    else if (i == 2) openXiaomiTvSettings();
                    else if (i == 3) openOwnAppSettings();
                    else new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Xiaomi TV · HOME")
                        .setMessage("Abre Ajustes de Android TV manualmente → Preferencias del dispositivo o Sistema → Accesibilidad → Servicios descargados/instalados → Simple TV Launcher · HOME. Activa el servicio. La ruta depende del modelo. Android puede impedir que un servicio capture HOME.")
                        .setPositiveButton(tr("Cerrar"), null).show();
                    break;
                case "home":
                    if (i == 0) requestHomeRole();
                    else if (i == 1) openHomeSettings();
                    else if (i == 2) openHomeCaptureSettings();
                    else if (i == 3) openAndroidSettings(Settings.ACTION_APPLICATION_SETTINGS);
                    else openPanel("access");
                    break;
            }
            invalidate();
        }

        private void confirmDeletePanel() {
            final int index = editingIndex;
            new AlertDialog.Builder(MainActivity.this).setTitle(tr("Eliminar enlace")).setMessage(tr("¿Eliminar este acceso?"))
                .setPositiveButton(tr("Eliminar"), (d,w) -> {
                    List<LinkItem> list = loadLinks();
                    if (index >= 0 && index < list.size()) list.remove(index);
                    saveLinks(list); reloadPreferences(); openPanel("links");
                }).setNegativeButton(tr("Cancelar"), null).show();
        }

        // Todas las ventanas comparten la misma geometría. La zona superior se
        // reserva para el reloj y la fecha; las filas quedan recortadas al panel.
        private boolean panelOnRight() { return "right".equals(prefs.getString("menu_side", "right")); }
        private float panelLeft(int w) { return panelOnRight() ? Math.max(dp(40), w * .38f) : dp(55); }
        private float panelTop(int h) { return panelOnRight() ? Math.max(dp(142), h * .21f) : dp(24); }
        private float panelBottom(int h) { return h * .94f; }
        private float panelRowHeight() { return dp("explorer".equals(panel) ? 39 : 43); }
        private float panelListTop(int h) {
            // Ajustes no tiene encabezado: la primera fila utiliza el espacio superior.
            // El explorador y los demás paneles conservan su encabezado independiente.
            return panelTop(h) + dp("settings".equals(panel) ? 43 : 91);
        }
        private float panelListBottom(int h) { return panelBottom(h) - dp(67); }
        private int panelVisibleCount() { return Math.max(1, (int)((panelListBottom(getHeight()) - panelListTop(getHeight())) / panelRowHeight())); }

        private void drawPanel(Canvas c,int w,int h) {
            if (!panelOpen()) return;
            float left = panelLeft(w), topEdge = panelTop(h), bottomEdge = panelBottom(h);
            float rightEdge = panelOnRight() ? w - dp(32) : Math.min(w * .46f, dp(640));
            overlay.setColor(prefs.getBoolean("menu_transparency", true) ? 0xE6000000 : Color.BLACK);
            overlay.setShader(null);
            c.drawRoundRect(new RectF(left-dp(24),topEdge,rightEdge,bottomEdge),dp(18),dp(18),overlay);
            String title;
            switch (panel) {
                case "settings": title=""; break;
                case "appearance": title=tr("Opciones de menús"); break;
                case "inputmanage": title=tr("Organizar entradas"); break;
                case "inputs": title=tr("Entradas de vídeo"); break;
                case "access": title=tr("Accesibilidad alternativa"); break;
                case "server": title=tr("Transferencia web"); break;
                case "explorer": title=tr("Explorador de archivos"); break;
                case "language": title=tr("Idioma"); break;
                case "links": title=tr("Enlaces"); break;
                case "background": title=tr("Fondos"); break;
                case "font": title=tr("Fuentes"); break;
                case "size": title=tr("Tamaño del texto"); break;
                case "clock": title=tr("Reloj y fecha"); break;
                case "home": title=tr("Launcher predeterminado"); break;
                case "apps": title=tr("Todas las apps"); break;
                case "pickapp": title=tr("Elegir aplicación"); break;
                case "edit": title=tr("Editar enlace"); break;
                default: title=tr("Ajustes");
            }
            paint.setTypeface(typeface); paint.setColor(Color.WHITE); paint.setAlpha(185);
            paint.setTextSize(dp(18)); paint.setTextAlign(Paint.Align.LEFT);
            if (!title.isEmpty()) c.drawText(title,left,topEdge+dp(43),paint);
            if ("explorer".equals(panel)) {
                // La carpeta actual es un rótulo, no una entrada seleccionable.
                // La mostramos a la derecha de «Explorador de archivos».
                String location = explorerPath.isEmpty() ? "/" : "/" + explorerPath;
                paint.setTypeface(typeface);paint.setTextAlign(Paint.Align.RIGHT);
                paint.setColor(0xFFE0E0E0);paint.setAlpha(220);paint.setTextSize(dp(15));
                float room = Math.max(dp(70), rightEdge - (left + dp(225)));
                while (paint.measureText(location)>room && location.length()>5)
                    location = "…" + location.substring(2);
                c.drawText(location,rightEdge-dp(12),topEdge+dp(43),paint);
                paint.setTextAlign(Paint.Align.LEFT);
            }
            if ("server".equals(panel)) {
                // Información no seleccionable; solo «Detener servidor» recibe OK.
                paint.setTypeface(typeface); paint.setTextAlign(Paint.Align.LEFT);
                float available = rightEdge-left-dp(20);
                int infoCount = Math.max(0, panelRows.size()-1);
                float infoY = topEdge + dp(119);
                for (int j=0; j<infoCount; j++) {
                    paint.setColor(Color.WHITE); paint.setAlpha(j==0 ? 255 : 190);
                    paint.setTextSize(dp(j==0 ? 20 : 20));
                    String value=panelRows.get(j);
                    while (paint.measureText(value)>available && value.length()>4)
                        value=value.substring(0,value.length()-2)+"…";
                    c.drawText(value,left,infoY+j*dp(56),paint);
                }
                paint.setColor(Color.WHITE);paint.setAlpha(255);paint.setTextSize(dp(30));
                c.drawText(tr("Detener servidor"),left,bottomEdge-dp(90),paint);
                paint.setAlpha(145);paint.setTextSize(dp(14));
                c.drawText(tr("OK seleccionar  ·  BACK volver"),left,bottomEdge-dp(30),paint);
                return;
            }
            float listTop=panelListTop(h),listBottom=panelListBottom(h),row=panelRowHeight();
            int count=panelRows.size(), visible=Math.max(1,(int)((listBottom-listTop)/row));
            int first=Math.max(0,(int)Math.floor(panelScroll)-1);
            int end=Math.min(count,(int)Math.ceil(panelScroll+visible)+2);
            int checkpoint=c.save();
            c.clipRect(left-dp(8),listTop-dp(27),rightEdge-dp(9),listBottom);
            for (int j=first;j<end;j++) {
                float focus = j == panelSelected ? 1f : 0f;
                float y=listTop+(j-panelScroll)*row;
                if (y < listTop-row || y > listBottom+row) continue;
                paint.setTypeface(typeface);paint.setTextAlign(Paint.Align.LEFT);
                paint.setColor(Color.WHITE);paint.setAlpha((int)(150+focus*105));
                boolean zoomRows = !"selector".equals(prefs.getString("selection_style", "selector"));
                paint.setTextSize(dp("explorer".equals(panel)?(17f+(zoomRows?3f*focus:0f)):(22f+(zoomRows?5f*focus:0f))));
                String value=panelRows.get(j);
                float maxWidth=rightEdge-left-dp(23);
                while(paint.measureText(value)>maxWidth && value.length()>4)
                    value=value.substring(0,value.length()-2)+"…";
                boolean icons = ("apps".equals(panel) || "settings".equals(panel)) && prefs.getBoolean("menu_icons", true); // Main settings and installed apps have icons; deeper submenus are text-only.
                if (focus > 0.5f && "selector".equals(prefs.getString("selection_style", "selector"))) {
                    float textX = left + (icons ? dp(36) : 0);
                    float textEnd = Math.min(rightEdge - dp(10), textX + paint.measureText(value) + dp(12));
                    float highlightLeft = left - dp(8);
                    overlay.setShader(null); overlay.setColor(0x688D9BAA);
                    Paint.FontMetrics fm = paint.getFontMetrics();
                    c.drawRoundRect(highlightLeft, y + fm.ascent - dp(9),
                        Math.max(highlightLeft + dp(35), textEnd), y + fm.descent + dp(9), dp(7), dp(7), overlay);
                }
                if (icons) {
                    float appIconY = y + (paint.getFontMetrics().ascent + paint.getFontMetrics().descent) * 0.5f;
                    if ("settings".equals(panel)) drawSettingsIcon(c, left + dp(12), appIconY, j);
                    else if (j < panelTargets.size() && !drawInstalledIcon(c, panelTargets.get(j), left + dp(12), appIconY))
                        drawAppFallback(c, left + dp(12), appIconY, panelRows.get(j));
                }
                c.drawText(value,left+(icons ? dp(36) : 0),y,paint);
            }
            c.restoreToCount(checkpoint);
            paint.setTypeface(typeface);paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(Color.WHITE);paint.setAlpha(150);paint.setTextSize(dp(14));
            c.drawText(tr("OK seleccionar  ·  BACK volver"),left,bottomEdge-dp(30),paint);
        }


        private void drawSettingsIcon(Canvas c, float x, float y, int index) {
            // Compact geometric symbols, drawn without font glyph dependencies.
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0xFFEAEAEA); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2));
            float r=dp(9);
            if (index==1) { c.drawRect(x-r,y-r*.7f,x+r,y+r*.7f,p); c.drawLine(x-r,y+r*.35f,x-r*.3f,y-dp(2),p); c.drawLine(x-r*.3f,y-dp(2),x+r*.2f,y+r*.35f,p); }
            else if (index==2 || index==3) { p.setStyle(Paint.Style.FILL);p.setTextSize(dp(17));p.setTextAlign(Paint.Align.CENTER);c.drawText(index==2?"Aa":"T",x,y+dp(6),p); }
            else if (index==4) { c.drawCircle(x,y,r,p);c.drawLine(x,y,x,y-r*.55f,p);c.drawLine(x,y,x+r*.5f,y,p); }
            else if (index==7) { c.drawArc(x-r,y-r,x+r,y+r,205,130,false,p); c.drawArc(x-r,y-r,x+r,y+r,25,130,false,p); }
            else if (index==8) { c.drawRect(x-r,y-r*.6f,x+r,y+r*.65f,p);c.drawLine(x-r,y-r*.6f,x-dp(3),y-r,p); }
            else if (index==11) { c.drawArc(x-r,y-r,x+r,y+r,65,285,false,p); c.drawLine(x-r,y-r*.5f,x-r,y+r*.25f,p); }
            else if (index==12) { c.drawCircle(x,y,r,p);c.drawLine(x,y-dp(1),x,y+dp(6),p);c.drawCircle(x,y-dp(5),dp(1),p); }
            else if (index==9) { for(int k=-1;k<=1;k++){float yy=y+k*dp(6);c.drawLine(x-r,yy,x+r,yy,p);c.drawCircle(x+(k==0?dp(4): -dp(3)),yy,dp(2),p);} }
            else if (index==10 || index==5) {c.drawRect(x-r,y-r*.7f,x+r,y+r*.45f,p);c.drawLine(x-dp(4),y+r,x+dp(4),y+r,p);}
            else if (index==6) {c.drawCircle(x,y,r,p);c.drawOval(x-dp(4),y-r,x+dp(4),y+r,p);c.drawLine(x-r,y,x+r,y,p);}
            else {c.drawCircle(x,y,r,p);c.drawCircle(x,y,dp(3),p);}
        }

        // Draw installed application icons supplied by Android. Cache them so scrolling
        // never performs a PackageManager lookup or decodes a drawable per frame.
        private final Map<String, Drawable> installedIcons = new HashMap<>();
        private void preloadInstalledIcons() {
            PackageManager pm = getPackageManager();
            for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
                for (ResolveInfo entry : pm.queryIntentActivities(query, 0)) {
                    if (entry.activityInfo == null) continue;
                    String pkg = entry.activityInfo.packageName;
                    if (installedIcons.containsKey(pkg)) continue;
                    try {
                        Drawable icon = entry.loadIcon(pm);
                        if (icon != null) installedIcons.put(pkg, icon.mutate());
                    } catch (Exception ignored) {}
                }
            }
        }
        private final Set<String> missingInstalledIcons = new HashSet<>();
        private boolean drawInstalledIcon(Canvas c, String pkg, float x, float y) {
            if (pkg == null || pkg.isEmpty()) return false;
            Drawable icon = installedIcons.get(pkg);
            if (icon == null && !missingInstalledIcons.contains(pkg)) {
                try {
                    icon = getPackageManager().getApplicationIcon(pkg);
                    if (icon != null) installedIcons.put(pkg, icon);
                } catch (Exception ignored) { missingInstalledIcons.add(pkg); }
            }
            if (icon == null) return false;
            drawDrawableIcon(c, icon, x, y);
            return true;
        }
        private void drawDrawableIcon(Canvas c, Drawable drawable, float x, float y) {
            int half = Math.round(dp(13));
            drawable.setBounds(Math.round(x)-half, Math.round(y)-half,
                    Math.round(x)+half, Math.round(y)+half);
            drawable.draw(c);
        }
        private final Map<String, TvInputInfo> inputCache = new HashMap<>();
        private final Map<String, Drawable> inputDrawables = new HashMap<>();
        private final Set<String> missingInputDrawables = new HashSet<>();
        private void refreshInputIconCache() {
            inputCache.clear();
            inputDrawables.clear();
            missingInputDrawables.clear();
            for (TvInputInfo info : availableVideoInputs()) inputCache.put(info.getId(), info);
        }
        private void drawVideoInputIcon(Canvas c, String id, float x, float y) {
            TvInputInfo info = inputCache.get(id);
            if (info != null) {
                Drawable icon = inputDrawables.get(id);
                if (icon == null && !missingInputDrawables.contains(id)) {
                    try {
                        icon = info.loadIcon(MainActivity.this);
                        if (icon != null) inputDrawables.put(id, icon);
                        else missingInputDrawables.add(id);
                    } catch (Exception ignored) { missingInputDrawables.add(id); }
                }
                if (icon != null) { drawDrawableIcon(c, icon, x, y); return; }
            }
            boolean tuner = info != null && info.getType() == TvInputInfo.TYPE_TUNER;
            iconPaint.setColor(Color.WHITE);
            iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(dp(1.8f));
            iconPaint.setStrokeCap(Paint.Cap.ROUND);
            if (tuner) {
                // Antenna with a base: distinctive fallback for the TV tuner.
                c.drawLine(x, y-dp(9), x, y+dp(9), iconPaint);
                c.drawLine(x-dp(7), y-dp(8), x, y-dp(1), iconPaint);
                c.drawLine(x+dp(7), y-dp(8), x, y-dp(1), iconPaint);
                c.drawLine(x-dp(6), y+dp(9), x+dp(6), y+dp(9), iconPaint);
            } else {
                // Monitor/HDMI fallback when manufacturer does not provide an icon.
                c.drawRoundRect(x-dp(12), y-dp(9), x+dp(12), y+dp(7),
                        dp(2), dp(2), iconPaint);
                c.drawLine(x, y+dp(7), x, y+dp(11), iconPaint);
                c.drawLine(x-dp(7), y+dp(11), x+dp(7), y+dp(11), iconPaint);
            }
        }
        private void drawAppFallback(Canvas c,float x,float y,String label) {
            // App icon missing from PackageManager: use its initial, never a generic rectangle.
            symbolPaint.setStyle(Paint.Style.FILL); symbolPaint.setTypeface(Typeface.DEFAULT_BOLD);
            symbolPaint.setTextAlign(Paint.Align.CENTER); symbolPaint.setTextSize(dp(20));
            symbolPaint.setColor(Color.WHITE);
            if (label != null && !label.isEmpty()) c.drawText(label.substring(0,1).toUpperCase(displayLocale),x,y+dp(7),symbolPaint);
            symbolPaint.setTextAlign(Paint.Align.LEFT);
        }
        private void drawMainSpecialIcon(Canvas c,float x,float y,int type) {
            iconPaint.setColor(Color.WHITE);iconPaint.setStyle(Paint.Style.STROKE);
            iconPaint.setStrokeWidth(dp(2));iconPaint.setStrokeCap(Paint.Cap.ROUND);
            if (type == 0) { // All apps: 2 x 2 grid
                for (int a=0;a<2;a++) for (int b=0;b<2;b++)
                    c.drawRoundRect(x+dp(a*11-11),y+dp(b*11-11),x+dp(a*11-3),y+dp(b*11-3),dp(1.5f),dp(1.5f),iconPaint);
            } else if (type == 1) { // Video input: monitor and connector
                c.drawRoundRect(x-dp(12),y-dp(9),x+dp(12),y+dp(7),dp(2),dp(2),iconPaint);
                c.drawLine(x,y+dp(7),x,y+dp(11),iconPaint);
                c.drawLine(x-dp(7),y+dp(11),x+dp(7),y+dp(11),iconPaint);
            } else { // Settings: sliders
                for (int row=0;row<3;row++) {
                    float yy=y+dp((row-1)*8);
                    c.drawLine(x-dp(11),yy,x+dp(11),yy,iconPaint);
                    float center=x+dp(row==1?5:-4);
                    iconPaint.setStyle(Paint.Style.FILL);
                    c.drawCircle(center,yy,dp(3),iconPaint);
                    iconPaint.setStyle(Paint.Style.STROKE);
                }
            }
        }
        private void drawRowIcon(Canvas c, float x, float y, int index) {
            if ("apps".equals(panel) && index < panelTargets.size()) {
                if (drawInstalledIcon(c, panelTargets.get(index), x, y)) return;
            } else if (("inputs".equals(panel) || "inputmanage".equals(panel))
                    && index < panelTargets.size() && !"@manage".equals(panelTargets.get(index))) {
                drawVideoInputIcon(c, panelTargets.get(index), x, y);
                return;
            } else if (("links".equals(panel)) && index < links.size()) {
                LinkItem item = links.get(index);
                if (item.target.startsWith("app:") && drawInstalledIcon(c, item.target.substring(4), x, y)) return;
            }
            drawPanelIcon(c, x, y, index);
        }
        private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint symbolPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private void drawPanelIcon(Canvas c, float x, float y, int index) {
            // Small monochrome vector symbols; no images, allocations or bitmap scaling.
            Paint ip = iconPaint;
            ip.setColor(0xFFF0F0F0);
            ip.setStyle(Paint.Style.STROKE);
            ip.setStrokeWidth(dp(1.7f));
            ip.setStrokeCap(Paint.Cap.ROUND);
            float r = dp(8);
            int kind = "settings".equals(panel) ? index : "inputs".equals(panel) ? 4 : "explorer".equals(panel) ? 8 : 10;
            switch (kind % 7) {
                case 0: c.drawRoundRect(x-r,y-r*.6f,x+r,y+r*.6f,dp(3),dp(3),ip); c.drawLine(x-r*.3f,y,x+r*.3f,y,ip); break;
                case 1: c.drawRect(x-r,y-r*.7f,x+r,y+r*.7f,ip); c.drawLine(x-r,y+r*.7f,x+r*.2f,y-dp(2),ip); break;
                case 2: c.drawText("Aa",x-r,y+r*.5f,iconTextPaint()); break;
                case 3: c.drawCircle(x,y,r,ip);c.drawLine(x,y,x,y-r*.5f,ip);c.drawLine(x,y,x+r*.5f,y,ip);break;
                case 4: c.drawRoundRect(x-r,y-r*.6f,x+r,y+r*.6f,dp(2),dp(2),ip);c.drawLine(x-r*.6f,y+r*.9f,x+r*.6f,y+r*.9f,ip);break;
                case 5: c.drawCircle(x,y,r,ip);c.drawLine(x-r,y,x+r,y,ip);c.drawOval(x-r*.4f,y-r,x+r*.4f,y+r,ip);break;
                default: c.drawRect(x-r,y-r*.7f,x+r,y+r*.7f,ip);c.drawLine(x-r,y-r*.2f,x+r,y-r*.2f,ip);break;
            }
        }
        private Paint iconTextPaint() {
            symbolPaint.setColor(Color.WHITE); symbolPaint.setStyle(Paint.Style.FILL);
            symbolPaint.setTextSize(dp(13)); return symbolPaint;
        }

        LauncherView(Context context) {
            super(context);
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
            reloadPreferences();
        }

        void reloadPreferences() {
            applyLanguage();
            links = loadLinks();
            preloadInstalledIcons();
            for (LinkItem item : links) if (item.target.startsWith("app:")) item.label = resolveLinkLabel(item);
            if (selected > links.size() + 2) selected = links.size() + 2;
            targetScroll = selected;
            scroll = selected;
            animationFrom = selected;
            bgStyle = prefs.getInt("bg_style", 0);
            if (bgStyle == 8 || bgStyle == 9) { bgStyle = 0; prefs.edit().putInt("bg_style", 0).apply(); }
            textSizeMode = prefs.getInt("text_size", 1);
            showDate = prefs.getBoolean("show_date", true);
            showTime = prefs.getBoolean("show_time", true);
            showSeconds = prefs.getBoolean("show_seconds", false);
            typeface = resolveTypeface();
            loadCustomBackground();
            invalidate();
        }

        private Typeface resolveTypeface() {
            String mode = prefs.getString("font", "sans");
            if (mode.equals("serif")) return Typeface.create("serif", Typeface.NORMAL);
            if (mode.equals("mono")) return Typeface.create("monospace", Typeface.NORMAL);
            if (mode.equals("condensed")) return Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            if (mode.equals("custom")) {
                try {
                    File f = new File(getFilesDir(), "custom_font.bin");
                    if (f.exists()) return Typeface.createFromFile(f);
                } catch (Exception ignored) {}
            }
            return Typeface.create("sans-serif", Typeface.NORMAL);
        }

        private void loadCustomBackground() {
            if (customBg != null) { customBg.recycle(); customBg = null; }
            if (bgStyle >= 6 && bgStyle <= 11) {
                int id = getResources().getIdentifier("background_" + (bgStyle - 5), "drawable", getPackageName());
                if (id != 0) {
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    customBg = BitmapFactory.decodeResource(getResources(), id, opts);
                }
                return;
            }
            if (bgStyle != 99) return;
            String s = prefs.getString("custom_bg_uri", null);
            if (s == null) return;
            try {
                Uri uri = Uri.parse(s);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream in = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
                int sample = 1;
                int max = Math.max(bounds.outWidth, bounds.outHeight);
                while (max / sample > 2200) sample *= 2;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                try (InputStream in = getContentResolver().openInputStream(uri)) { customBg = BitmapFactory.decodeStream(in, null, opts); }
            } catch (Exception ignored) {}
        }

        void pulseHint() {
            pulseUntil = System.currentTimeMillis() + 650;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            final int w = getWidth();
            final int h = getHeight();
            if (w <= 0 || h <= 0) return;

            // Dejar el fondo totalmente opaco: la pintura se reutiliza para el texto
            // y conserva el alpha de la última opción dibujada si no se reinicia.
            paint.setAlpha(255);
            paint.setColor(Color.WHITE);
            paint.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
            // Transición de duración fija, sin cola exponencial prolongada.
            long now = android.os.SystemClock.uptimeMillis();
            boolean animating = Math.abs(scroll - targetScroll) > 0.002f;
            if (animating) {
                float t = animationDuration() == 0f ? 1f : Math.min(1f, (now - animationStart) / animationDuration());
                float eased = 1f - (1f - t) * (1f - t);
                scroll = animationFrom + (targetScroll - animationFrom) * eased;
                if (t >= 1f) { scroll = targetScroll; animating = false; }
            }
            boolean panelAnimating = Math.abs(panelScroll - panelScrollTarget) > 0.002f;
            if (panelAnimating) {
                float t = animationDuration() == 0f ? 1f : Math.min(1f, (now - panelAnimationStart) / animationDuration());
                float eased = 1f - (1f - t) * (1f - t);
                panelScroll = panelScrollFrom + (panelScrollTarget - panelScrollFrom) * eased;
                if (t >= 1f) { panelScroll = panelScrollTarget; panelAnimating = false; }
            }

            boolean focusAnimating = Math.abs(panelFocus - panelFocusTarget) > 0.002f;
            if (focusAnimating) {
                float t = animationDuration() == 0f ? 1f : Math.min(1f, (now - panelFocusStart) / animationDuration());
                float eased = 1f - (1f - t) * (1f - t);
                panelFocus = panelFocusFrom + (panelFocusTarget - panelFocusFrom) * eased;
                if (t >= 1f) { panelFocus = panelFocusTarget; focusAnimating = false; }
            }
            drawBackground(c, w, h);
            drawMenu(c, w, h);
            boolean panelTransitions = false;
            if (panelOpen()) {
                float elapsed = now - panelEnterAt;
                float progress = !prefs.getBoolean("menu_animations", true) ? 1f : Math.min(1f, elapsed / PANEL_TRANSITION_MS);
                int cp = c.save();
                c.clipRect(0f, 0f, w, h);
                c.translate((panelOnRight() ? 1f : -1f)*(1f-progress)*dp(65), 0f);
                drawPanel(c, w, h);
                c.restoreToCount(cp);
                panelTransitions = progress < 1f;
            } else if (!exitedPanel.isEmpty()) {
                float progress = Math.min(1f, (now-panelExitAt)/PANEL_TRANSITION_MS);
                if (progress >= 1f) exitedPanel = "";
                else {
                    String original = panel;
                    ArrayList<String> originalRows = new ArrayList<>(panelRows);
                    ArrayList<String> originalTargets = new ArrayList<>(panelTargets);
                    float oldScroll = panelScroll, oldFocus = panelFocus;
                    panel = exitedPanel; panelRows.clear(); panelRows.addAll(exitedRows);
                    panelTargets.clear(); panelTargets.addAll(exitedTargets);
                    panelScroll = exitedScroll; panelFocus = exitedFocus;
                    int cp = c.save();
                    c.clipRect(0f, 0f, w, h);
                    c.translate((panelOnRight() ? 1f : -1f)*progress*dp(65),0f);
                    drawPanel(c,w,h);
                    c.restoreToCount(cp);
                    panel = original; panelRows.clear(); panelRows.addAll(originalRows);
                    panelTargets.clear(); panelTargets.addAll(originalTargets);
                    panelScroll = oldScroll; panelFocus = oldFocus;
                    panelTransitions = true;
                }
            }
            drawClock(c, w, h);

            if (animating || panelAnimating || focusAnimating || panelTransitions) postInvalidateOnAnimation();
            else postInvalidateDelayed(showSeconds ? 250 : 1000);
        }

        private void drawBackground(Canvas c, int w, int h) {
            paint.setAlpha(255);
            if ((bgStyle == 99 || (bgStyle >= 6 && bgStyle <= 11)) && customBg != null) {
                Rect src = new Rect(0, 0, customBg.getWidth(), customBg.getHeight());
                float srcRatio = src.width() / (float) src.height();
                float dstRatio = w / (float) h;
                if (srcRatio > dstRatio) {
                    int newW = Math.round(src.height() * dstRatio);
                    int left = (src.width() - newW) / 2;
                    src.set(left, 0, left + newW, src.height());
                } else {
                    int newH = Math.round(src.width() / dstRatio);
                    int top = (src.height() - newH) / 2;
                    src.set(0, top, src.width(), top + newH);
                }
                c.drawBitmap(customBg, src, new Rect(0, 0, w, h), paint);
                overlay.setShader(new LinearGradient(0, 0, w, 0, 0xB8000000, 0x15000000, Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, h, overlay);
                overlay.setShader(null);
                return;
            }

            int a, b, glow;
            switch (bgStyle) {
                case 1: a = 0xFF071A18; b = 0xFF0B2D2A; glow = 0x6636D1B5; break;
                case 2: a = 0xFF120D21; b = 0xFF26143C; glow = 0x666F4AB8; break;
                case 3: a = 0xFF111111; b = 0xFF292929; glow = 0x553C3C3C; break;
                case 4: a = 0xFF061826; b = 0xFF0A3349; glow = 0x6659BCE0; break;
                case 5: a = Color.BLACK; b = Color.BLACK; glow = 0x00000000; break;
                default: a = 0xFF070B14; b = 0xFF111C2F; glow = 0x665280D9; break;
            }
            paint.setShader(new LinearGradient(0, 0, w, h, a, b, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            if (bgStyle != 5) {
                paint.setShader(new RadialGradient(w * 0.76f, h * 0.32f, Math.max(w, h) * 0.48f, glow, 0x00000000, Shader.TileMode.CLAMP));
                c.drawCircle(w * 0.76f, h * 0.32f, Math.max(w, h) * 0.48f, paint);
                paint.setShader(null);
            }
            overlay.setShader(new LinearGradient(0, 0, w * 0.58f, 0, 0x9B000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w * 0.7f, h, overlay);
            overlay.setShader(null);
        }

        private void drawClock(Canvas c, int w, int h) {
            boolean is24 = DateFormat.is24HourFormat(MainActivity.this);
            String pattern = is24 ? (showSeconds ? "HH:mm:ss" : "HH:mm") : (showSeconds ? "h:mm:ss" : "h:mm");
            String time = new SimpleDateFormat(pattern, displayLocale).format(new Date());
            float pad = dp(62);
            paint.setTypeface(typeface);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setColor(Color.WHITE);
            paint.setAlpha(245);
            paint.setTextSize(dp(44));
            if (showTime) c.drawText(time, w - pad, dp(78), paint);

            if (showDate && !"hidden".equals(prefs.getString("date_position", "bottom"))) {
                String date = new SimpleDateFormat("en".equals(displayLocale.getLanguage()) ? "EEEE, MMMM d" : "EEEE, d MMMM", displayLocale).format(new Date());
                paint.setTextSize(dp(16));
                paint.setAlpha(180);
                c.drawText(capitalize(date), w - pad, ("bottom".equals(prefs.getString("date_position", "bottom")) ? dp(showTime ? 108 : 78) : dp(showTime ? 28 : 78)), paint);
            }
        }

        private String capitalize(String value) {
            if (value == null || value.isEmpty()) return value;
            return value.substring(0, 1).toUpperCase(displayLocale) + value.substring(1);
        }

        private void drawMenu(Canvas c, int w, int h) {
            final int total = links.size() + 3;
            if (total <= 0) return;
            float baseSize = textSizeMode == 0 ? 29 : textSizeMode == 2 ? 40 : textSizeMode == 3 ? 47 : 34;
            float row = dp(baseSize * 1.72f);
            // Situar la opción seleccionada en el tercio superior, no en el centro.
            float centerY = h * 0.32f;
            float left = dp(70);

            paint.setTextAlign(Paint.Align.LEFT);
            paint.setTypeface(typeface);

            for (int i = 0; i < total; i++) {
                float distance = i - scroll;
                if (Math.abs(distance) > 4.4f) continue;
                float focus = i == selected ? 1f : 0f;
                float y = centerY + distance * row;
                boolean zoomMain = !"selector".equals(prefs.getString("selection_style", "selector"));
                float size = dp(baseSize) * (1f + (zoomMain ? 0.34f * focus : 0f));
                float x = left;
                int alpha = (int) (95 + 160 * Math.max(focus, Math.max(0f, 1f - Math.abs(distance) / 4f)));

                boolean special = i >= links.size();
                paint.setTextSize(special ? size * 0.78f : size);
                paint.setTypeface(special ? Typeface.create(typeface, Typeface.BOLD) : typeface);
                paint.setColor(Color.WHITE);
                paint.setAlpha(special ? Math.min(alpha, 210) : alpha);
                paint.setShadowLayer(focus > 0.5f ? dp(18) : 0, 0, 0, 0x55000000);
                String label = i == links.size() ? tr("Todas las apps") : i == links.size() + 1 ? tr("Entradas de vídeo") : i == links.size() + 2 ? tr("Ajustes") : links.get(i).label;
                boolean menuIcons = prefs.getBoolean("menu_icons", true);
                if (menuIcons) {
                    Paint.FontMetrics iconFontMetrics = paint.getFontMetrics();
                    float iconX = x + dp(14), iconY = y + (iconFontMetrics.ascent + iconFontMetrics.descent) * 0.5f;
                    boolean drawn = false;
                    if (i < links.size() && links.get(i).target.startsWith("app:"))
                        drawn = drawInstalledIcon(c, links.get(i).target.substring(4), iconX, iconY);
                    if (!drawn) {
                        if (i >= links.size()) drawMainSpecialIcon(c, iconX, iconY, i-links.size());
                        else drawAppFallback(c, iconX, iconY, label);
                    }
                    x += dp(39);
                }
                if (!zoomMain && focus > 0.5f) {
                    overlay.setShader(null); overlay.setColor(0x668D9BAA);
                    Paint.FontMetrics fm = paint.getFontMetrics();
                    float highlightEnd = Math.min(w - dp(15), x + paint.measureText(label) + dp(14));
                    c.drawRoundRect(left-dp(14), y + fm.ascent - dp(11),
                        Math.max(left+dp(35),highlightEnd), y + fm.descent + dp(11), dp(8), dp(8), overlay);
                    // Redraw the icon on top of the selection background.
                    if (menuIcons) {
                        float iconY = y + (fm.ascent + fm.descent) * 0.5f;
                        boolean drawn = i < links.size() && links.get(i).target.startsWith("app:")
                            && drawInstalledIcon(c, links.get(i).target.substring(4), left + dp(14), iconY);
                        if (!drawn) {
                            if (i >= links.size()) drawMainSpecialIcon(c, left + dp(14), iconY, i-links.size());
                            else drawAppFallback(c, left + dp(14), iconY, label);
                        }
                    }
                }
                c.drawText(label, x, y, paint);
                paint.clearShadowLayer();


            }

            if (System.currentTimeMillis() < pulseUntil) {
                paint.setTextAlign(Paint.Align.LEFT);
                paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                paint.setTextSize(dp(14));
                paint.setColor(Color.WHITE);
                paint.setAlpha(145);
                c.drawText("BACK configurado para permanecer en el launcher", dp(70), h - dp(38), paint);
                postInvalidateDelayed(80);
            }
        }

        private void selectMain(int index) {
            if (index == selected) return;
            selected = index;
            animationFrom = scroll;
            targetScroll = selected;
            animationStart = android.os.SystemClock.uptimeMillis();
            postInvalidateOnAnimation();
        }

        private void selectPanel(int index) {
            if (index == panelSelected) return;
            panelSelected = index;
            panelFocusFrom = panelFocus;
            panelFocusTarget = index;
            panelFocusStart = android.os.SystemClock.uptimeMillis();
            int visible = panelVisibleCount();
            float target = Math.max(0, Math.min(panelSelected - visible / 2f, Math.max(0,panelRows.size()-visible)));
            if (target != panelScrollTarget) {
                panelScrollFrom = panelScroll;
                panelScrollTarget = target;
                panelAnimationStart = android.os.SystemClock.uptimeMillis();
            }
            postInvalidateOnAnimation();
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            // Una pulsación larga abre el editor, pero los eventos repetidos de OK
            // siguen llegando hasta soltar el mando: NO deben activar la primera fila.
            if (isOkKey(keyCode) && okPressed) return true;
            if ((keyCode == KeyEvent.KEYCODE_1 || keyCode == KeyEvent.KEYCODE_NUMPAD_1) && event.getRepeatCount() == 0) {
                saveLauncherScreenshot(); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_0 || keyCode == KeyEvent.KEYCODE_NUMPAD_0) {
                if ("server".equals(panel)) { stopUploadServer(); openPanel("settings"); }
                else startUploadServer();
                return true;
            }
            if (panelOpen()) {
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { goBack(); return true; }
                if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { if (!"server".equals(panel) && (event.getRepeatCount() == 0 || event.getRepeatCount() % 2 == 0)) selectPanel(Math.max(0,panelSelected-1));return true; }
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { if (!"server".equals(panel) && (event.getRepeatCount() == 0 || event.getRepeatCount() % 2 == 0)) selectPanel(Math.min(panelRows.size()-1,panelSelected+1));return true; }
                if (isOkKey(keyCode) || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // El OK inicial solo se activa en el evento DOWN si no viene de
                    // la pulsación larga del launcher (comprobación de arriba).
                    if (event.getRepeatCount() == 0) activatePanel();
                    return true;
                }
                return true;
            }

            int max = links.size() + 2;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                if (event.getRepeatCount() == 0 || event.getRepeatCount() % 2 == 0) selectMain(Math.max(0, selected - 1)); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (event.getRepeatCount() == 0 || event.getRepeatCount() % 2 == 0) selectMain(Math.min(max, selected + 1)); return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (event.getRepeatCount() == 0) activateSelected();
                return true;
            }
            if (isOkKey(keyCode)) {
                if (!okPressed) {
                    okPressed = true;
                    longOkTriggered = false;
                    postDelayed(longOkAction, 530);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
                showSettings(); return true;
            }
            return super.onKeyDown(keyCode, event);
        }

        private boolean isOkKey(int key) {
            return key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER
                    || key == KeyEvent.KEYCODE_NUMPAD_ENTER;
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            if (isOkKey(keyCode) && okPressed) {
                okPressed = false;
                removeCallbacks(longOkAction);
                if (!longOkTriggered && !panelOpen()) activateSelected();
                longOkTriggered = false;
                return true;
            }
            return super.onKeyUp(keyCode, event);
        }

        private void activateSelected() {
            if (selected == links.size()) showAllApps();
            else if (selected == links.size() + 1) openPanel("inputs");
            else if (selected > links.size()) showSettings();
            else launchTarget(links.get(selected).target);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (panelOpen()) return true;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownY = e.getY(); touchMoved = false; return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getY() - touchDownY) > dp(24)) touchMoved = true;
                    return true;
                case MotionEvent.ACTION_UP:
                    float dy = e.getY() - touchDownY;
                    if (touchMoved) {
                        if (dy < 0) selectMain(Math.min(links.size() + 2, selected + 1));
                        else selectMain(Math.max(0, selected - 1));
                    } else {
                        activateSelected();
                    }
                    return true;
            }
            return super.onTouchEvent(e);
        }

        private float dp(float value) {
            return value * getResources().getDisplayMetrics().density;
        }
    }
}
