package com.arrradu.aa900.installer;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs on the phone, which is joined to the car's Wi-Fi hotspot. It reaches the car's ADB on port
 * 5555, installs the newest AA Lynk build from this project's GitHub releases, and collects a
 * report from the car that the user can share.
 */
public class MainActivity extends Activity {

    private static final String RELEASES_API =
            "https://api.github.com/repos/arrradu/Android-Auto/releases?per_page=10";
    private static final String CAR_PACKAGE = "com.arrradu.aa900";
    private static final String CAR_ACTIVITY =
            CAR_PACKAGE + "/com.andrerinas.openheadunit.main.MainActivity";
    private static final String OLD_PACKAGE = "com.andrerinas.headunitrevived";
    private static final String REMOTE_APK = "/data/local/tmp/aa-lynk.apk";
    private static final int REQUEST_PICK_APK = 1;

    /** Runtime permissions the car app needs; granting them here spares the prompts on the car. */
    private static final String[] CAR_PERMISSIONS = {
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.BLUETOOTH_CONNECT",
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.NEARBY_WIFI_DEVICES",
            "android.permission.RECORD_AUDIO",
            "android.permission.READ_PHONE_STATE",
            "android.permission.POST_NOTIFICATIONS",
    };

    /** What the report asks the car. Each one is run as its own shell command. */
    private static final String[] REPORT_COMMANDS = {
            "getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.product.model;"
                    + " getprop ro.build.display.id; getprop ro.board.platform",
            "am get-current-user; pm list users",
            "wm size; wm density",
            "pm list packages --user 12 | grep -i -E 'headunit|aa900'",
            "dumpsys package " + CAR_PACKAGE + " | grep -E 'versionName|versionCode|firstInstall|lastUpdate'",
            "dumpsys package " + CAR_PACKAGE + " | grep -E 'granted=false'",
            "ip -4 addr | grep inet",
            "dumpsys wifi | grep -i -E 'softap|SoftApState|mApState|band|frequency' | head -40",
            "dumpsys bluetooth_manager | head -80",
            "ps -A | grep -i -E 'aa900|headunit'",
            "logcat -d -b crash -t 300",
            // By process, not by text: the car's own services mention the package twice a second.
            "logcat -d -t 40000 --pid=$(pidof " + CAR_PACKAGE + ") | tail -5000",
    };

    private static final int MAX_LOG_CHARS = 2_000_000;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final StringBuilder fullLog = new StringBuilder();
    private final List<Button> buttons = new ArrayList<Button>();

    private TextView statusView;
    private TextView logView;
    private ScrollView scroll;
    private EditText commandBox;

    private final AdbClient.KeyProvider keys = new AdbClient.KeyProvider() {
        public byte[] loadPrivate() {
            return prefBytes("private");
        }

        public byte[] loadPublic() {
            return prefBytes("public");
        }

        public void save(byte[] priv, byte[] pub) {
            getSharedPreferences("adb-keys", MODE_PRIVATE).edit()
                    .putString("private", Base64.encodeToString(priv, Base64.NO_WRAP))
                    .putString("public", Base64.encodeToString(pub, Base64.NO_WRAP))
                    .apply();
        }

        public void onAuthRequested() {
            log(">>> Uită-te la ecranul mașinii: dacă apare întrebarea „Permiți depanarea?”, "
                    + "bifează „Permite întotdeauna” și apasă „Permite”.");
        }
    };

    private final AdbClient adb = new AdbClient(keys);

    private byte[] prefBytes(String key) {
        String value = getSharedPreferences("adb-keys", MODE_PRIVATE).getString(key, null);
        return value == null ? null : Base64.decode(value, Base64.NO_WRAP);
    }

    // ------------------------------------------------------------------ screen

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, dp(40), pad, pad);

        TextView title = new TextView(this);
        title.setText("Lynk&Co 900 AA Installer");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusView.setPadding(0, dp(8), 0, dp(8));
        statusView.setText("Conectează telefonul la Wi-Fi-ul mașinii (hotspot), apoi apasă butonul 1.");
        root.addView(statusView);

        addButton(root, "1. Instalează Lynk&Co 900 AA în mașină", new Runnable() {
            public void run() {
                installFlow();
            }
        });
        addButton(root, "2. Citește starea mașinii (diagnostic)", new Runnable() {
            public void run() {
                reportFlow();
            }
        });
        addButton(root, "3. Trimite raportul lui Claude", null).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                shareReport();
            }
        });

        addButton(root, "4. Instalează alt APK din telefon", null).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickApk();
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addSmallButton(row, "Oprește Open Headunit", new Runnable() {
            public void run() {
                oldAppFlow(false);
            }
        });
        addSmallButton(row, "Repornește Open Headunit", new Runnable() {
            public void run() {
                oldAppFlow(true);
            }
        });
        root.addView(row);

        commandBox = new EditText(this);
        commandBox.setHint("Comandă primită de la Claude (opțional)");
        commandBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        commandBox.setTypeface(Typeface.MONOSPACE);
        commandBox.setMaxLines(4);
        root.addView(commandBox);
        addButton(root, "Rulează comanda în mașină", new Runnable() {
            public void run() {
                commandFlow(commandBox.getText().toString().trim());
            }
        });

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(0, dp(12), 0, dp(40));

        scroll = new ScrollView(this);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    /** Opens the phone's file picker; the chosen file comes back in [onActivityResult]. */
    private void pickApk() {
        // Any file type: many file apps do not label an APK as one, and would grey it out.
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(pick, REQUEST_PICK_APK);
        } catch (Throwable t) {
            log("Nu am putut deschide selectorul de fișiere: " + t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_APK || resultCode != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        runJob(new Runnable() {
            public void run() {
                otherApkFlow(uri);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        new Thread(new Runnable() {
            public void run() {
                adb.close();
            }
        }).start();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private Button addButton(LinearLayout parent, String text, final Runnable job) {
        final Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        if (job != null) {
            button.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    runJob(job);
                }
            });
            buttons.add(button);
        }
        parent.addView(button, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return button;
    }

    private void addSmallButton(LinearLayout row, String text, final Runnable job) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                runJob(job);
            }
        });
        buttons.add(button);
        row.addView(button, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }

    /** One job at a time: the car's ADB link is a single connection. */
    private void runJob(final Runnable job) {
        setButtonsEnabled(false);
        worker.execute(new Runnable() {
            public void run() {
                try {
                    job.run();
                } catch (Throwable t) {
                    log("EROARE: " + t);
                    setStatus("A apărut o eroare. Apasă 3 și trimite raportul lui Claude.");
                } finally {
                    setButtonsEnabled(true);
                }
            }
        });
    }

    private void setButtonsEnabled(final boolean enabled) {
        ui.post(new Runnable() {
            public void run() {
                for (Button button : buttons) button.setEnabled(enabled);
            }
        });
    }

    private void setStatus(final String text) {
        ui.post(new Runnable() {
            public void run() {
                statusView.setText(text);
            }
        });
    }

    private void log(String line) {
        final String stamped = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date())
                + "  " + line + "\n";
        synchronized (fullLog) {
            fullLog.append(stamped);
            if (fullLog.length() > MAX_LOG_CHARS) {
                fullLog.delete(0, fullLog.length() - MAX_LOG_CHARS);
            }
        }
        ui.post(new Runnable() {
            public void run() {
                // The screen keeps only the tail; the shared report carries everything.
                CharSequence shown = logView.getText();
                String next = shown + stamped;
                if (next.length() > 20_000) next = next.substring(next.length() - 20_000);
                logView.setText(next);
                scroll.post(new Runnable() {
                    public void run() {
                        scroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ the car link

    /** Connects to the car's ADB unless a live connection already answers. */
    private boolean ensureConnected() {
        if (adb.isConnected()) {
            try {
                if (adb.shell("echo ok", 5000).contains("ok")) return true;
            } catch (Throwable ignored) {
                // Fall through and reconnect.
            }
            adb.close();
        }

        setStatus("Caut mașina în rețeaua Wi-Fi ...");
        CarFinder.Wifi wifi = CarFinder.currentWifi(this);
        if (wifi == null) {
            log("Telefonul nu este conectat la niciun Wi-Fi.");
            setStatus("Conectează telefonul la Wi-Fi-ul mașinii (hotspot) și apasă din nou.");
            return false;
        }
        log("Wi-Fi: IP telefon " + wifi.localIp + ", gateway " + wifi.gateway);

        List<String> hits = CarFinder.discover(this, wifi, 700);
        if (hits.isEmpty()) {
            log("Nu am găsit niciun dispozitiv cu portul 5555 deschis în această rețea.");
            setStatus("Nu găsesc mașina. Verifică: telefonul e pe hotspotul mașinii, "
                    + "ADB e activat în mașină, ecranul mașinii e aprins.");
            return false;
        }
        log("Dispozitive găsite: " + hits);

        adb.socketFactory = wifi.network.getSocketFactory();
        for (String ip : hits) {
            try {
                setStatus("Mă conectez la " + ip + ":5555 ... Uită-te la ecranul mașinii.");
                adb.close();
                adb.connect(ip, CarFinder.ADB_PORT, 6000, 60000);
                log("Conectat la mașină: " + ip + ":5555");
                return true;
            } catch (Throwable t) {
                log("Conectarea la " + ip + " a eșuat: " + t.getMessage()
                        + " [" + adb.authTrace + "]");
            }
        }
        setStatus("Mașina nu a acceptat conexiunea. Repornește ecranul mașinii și încearcă din nou.");
        return false;
    }

    /** Runs one shell command on the car and returns its output; failures come back as text. */
    private String sh(String command, int timeoutMs) {
        try {
            return adb.shell(command, timeoutMs);
        } catch (Throwable t) {
            return "[comanda a eșuat: " + t.getMessage() + "]";
        }
    }

    /** The Android users (the car's "screen spaces") to install for. */
    private List<Integer> targetUsers() {
        List<Integer> all = new ArrayList<Integer>();
        Matcher matcher = Pattern.compile("UserInfo\\{(\\d+):").matcher(sh("pm list users", 20000));
        while (matcher.find()) all.add(Integer.parseInt(matcher.group(1)));

        // Where Open Headunit already lives is where the user installed it and where it works.
        List<Integer> withOld = new ArrayList<Integer>();
        for (int user : all) {
            if (sh("pm path --user " + user + " " + OLD_PACKAGE, 20000).contains("package:")) {
                withOld.add(user);
            }
        }
        if (!withOld.isEmpty()) {
            log("Open Headunit este instalat în spațiile: " + withOld + ". Instalez în aceleași.");
            return withOld;
        }

        int current = currentUser();
        log("Open Headunit nu a fost găsit. Instalez în spațiul curent: " + current);
        List<Integer> one = new ArrayList<Integer>();
        one.add(current);
        return one;
    }

    /** The Android user the car's main screen is running as. */
    private int currentUser() {
        Matcher digits = Pattern.compile("(\\d+)").matcher(sh("am get-current-user", 20000));
        return digits.find() ? Integer.parseInt(digits.group(1)) : 0;
    }

    // ------------------------------------------------------------------ jobs

    /** Installs an APK the user picked from the phone's storage. */
    private void otherApkFlow(Uri uri) {
        log("===== INSTALARE ALT APK =====");
        setStatus("Copiez fișierul ales ...");
        File apk = new File(getCacheDir(), "other.apk");
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            OutputStream out = new FileOutputStream(apk);
            try {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            } finally {
                out.close();
                in.close();
            }
        } catch (Throwable t) {
            log("Nu am putut citi fișierul: " + t);
            setStatus("Nu am putut citi fișierul ales.");
            return;
        }

        PackageInfo info = getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
        if (info == null) {
            log("Fișierul ales nu este un APK valid (" + apk.length() + " octeți).");
            setStatus("Fișierul ales nu este un APK. Arhivele .apks, .xapk și .apkm nu se pot instala așa.");
            return;
        }
        String label = info.packageName;
        if (info.applicationInfo != null) {
            // The label can only be read once the info knows where its APK is.
            info.applicationInfo.sourceDir = apk.getPath();
            info.applicationInfo.publicSourceDir = apk.getPath();
            label = String.valueOf(info.applicationInfo.loadLabel(getPackageManager()));
        }
        log("Aplicație: " + label + " (" + info.packageName + "), versiunea " + info.versionName
                + ", " + (apk.length() / (1024 * 1024)) + " MB");

        if (!ensureConnected()) return;
        int user = currentUser();
        setStatus("Instalez „" + label + "” în mașină (spațiul " + user + ") ... Nu închide aplicația.");
        if (!installApk(apk, user)) {
            setStatus("Instalarea „" + label + "” a eșuat. Apasă 3 și trimite raportul lui Claude.");
            return;
        }

        // Start it once, so it is on screen even if the car's menu does not list it yet.
        String component = sh("cmd package resolve-activity --brief --user " + user
                + " -c android.intent.category.LAUNCHER " + info.packageName + " | tail -1", 20000).trim();
        if (component.contains("/")) {
            log("Pornesc: " + sh("am start --user " + user + " -n " + component, 30000).trim());
        } else {
            log("Aplicația nu are un ecran de pornire propriu; nu o pot deschide automat.");
        }
        setStatus("Gata. „" + label + "” este instalată în mașină.");
    }

    private void installFlow() {
        log("===== INSTALARE =====");
        if (!ensureConnected()) return;

        setStatus("Descarc ultima versiune AA Lynk de pe GitHub ...");
        File apk = downloadLatestApk();
        if (apk == null) {
            setStatus("Nu am putut descărca APK-ul. Verifică internetul și încearcă din nou.");
            return;
        }

        List<Integer> users = targetUsers();
        boolean installedOnce = false;
        boolean allOk = true;
        for (int user : users) {
            setStatus("Instalez în mașină (spațiul " + user + ") ... Nu închide aplicația.");
            boolean ok = installedOnce ? installExisting(user) || installApk(apk, user)
                    : installApk(apk, user);
            if (ok) {
                installedOnce = true;
                grantPermissions(user);
            } else {
                allOk = false;
            }
        }
        if (!installedOnce) {
            setStatus("Instalarea a eșuat. Apasă 3 și trimite raportul lui Claude.");
            return;
        }

        int launchUser = users.get(0);
        log("Pornesc AA Lynk: " + sh("am start --user " + launchUser + " -n " + CAR_ACTIVITY, 30000).trim());
        setStatus(allOk
                ? "Gata. AA Lynk este instalat și pornit pe ecranul mașinii."
                : "AA Lynk este instalat, dar nu în toate spațiile. Trimite raportul lui Claude.");
    }

    private boolean installApk(File apk, int user) {
        final long size = apk.length();
        AdbClient.Progress progress = new AdbClient.Progress() {
            long lastShown = -1;

            public void onBytes(long done) {
                long percent = done * 100 / size;
                if (percent / 10 != lastShown / 10) {
                    lastShown = percent;
                    log("  trimis " + percent + "%");
                }
            }
        };

        // First the way `adb install` works: the file streamed straight into the package manager.
        String[] services = {
                "exec:cmd package install -S " + size + " -r -t --user " + user,
                "exec:pm install -S " + size + " -r -t --user " + user,
        };
        for (String service : services) {
            try {
                log("Instalare directă: " + service);
                InputStream in = new FileInputStream(apk);
                String out;
                try {
                    out = adb.streamToService(service, in, progress, 180000);
                } finally {
                    in.close();
                }
                log("  răspuns: " + out.trim());
                if (out.contains("Success")) return true;
            } catch (Throwable t) {
                log("  a eșuat: " + t.getMessage());
                if (!ensureConnected()) return false;
            }
        }

        // Then by copying the file to the car first. The word is split because some of these head
        // units drop any ADB command that contains it.
        try {
            log("Instalare prin copiere: " + REMOTE_APK);
            InputStream in = new FileInputStream(apk);
            long pushed;
            try {
                pushed = adb.pushViaShell(in, REMOTE_APK, progress);
            } finally {
                in.close();
            }
            log("  copiat " + pushed + " din " + size + " octeți");
            String out = sh("a=in; b=stall; pm ${a}${b} -r -t --user " + user + " " + REMOTE_APK, 300000);
            log("  răspuns: " + out.trim());
            sh("rm -f " + REMOTE_APK, 20000);
            return out.contains("Success");
        } catch (Throwable t) {
            log("  a eșuat: " + t.getMessage());
            return false;
        }
    }

    /** For a second space: the package is already on the car, so it only needs enabling there. */
    private boolean installExisting(int user) {
        String out = sh("a=in; b=stall; cmd package ${a}${b}-existing --user " + user + " " + CAR_PACKAGE, 60000);
        log("Activare în spațiul " + user + ": " + out.trim());
        return out.contains("installed for user");
    }

    private void grantPermissions(int user) {
        StringBuilder refused = new StringBuilder();
        for (String permission : CAR_PERMISSIONS) {
            String out = sh("pm grant --user " + user + " " + CAR_PACKAGE + " " + permission, 20000).trim();
            if (!out.isEmpty()) refused.append("\n    ").append(permission).append(": ").append(firstLine(out));
        }
        sh("appops set --user " + user + " " + CAR_PACKAGE + " SYSTEM_ALERT_WINDOW allow", 20000);
        log("Permisiuni acordate în spațiul " + user + "."
                + (refused.length() > 0 ? " Neacordate:" + refused : ""));
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return end < 0 ? text : text.substring(0, end);
    }

    private void reportFlow() {
        log("===== DIAGNOSTIC =====");
        if (!ensureConnected()) return;
        setStatus("Citesc starea mașinii ...");
        for (String command : REPORT_COMMANDS) {
            log("$ " + command + "\n" + sh(command, 60000));
        }
        setStatus("Diagnostic gata. Apasă 3 ca să trimiți raportul lui Claude.");
    }

    private void commandFlow(String command) {
        if (command.isEmpty()) {
            setStatus("Scrie mai întâi comanda în căsuță.");
            return;
        }
        if (!ensureConnected()) return;
        setStatus("Rulez comanda ...");
        log("$ " + command + "\n" + sh(command, 120000));
        setStatus("Comandă rulată. Apasă 3 ca să trimiți rezultatul lui Claude.");
    }

    /** Stops Open Headunit so it does not fight AA Lynk for the same Bluetooth channel, or undoes that. */
    private void oldAppFlow(boolean enable) {
        log(enable ? "===== REPORNESC OPEN HEADUNIT =====" : "===== OPRESC OPEN HEADUNIT =====");
        if (!ensureConnected()) return;
        Matcher matcher = Pattern.compile("UserInfo\\{(\\d+):").matcher(sh("pm list users", 20000));
        while (matcher.find()) {
            String user = matcher.group(1);
            if (!sh("pm path --user " + user + " " + OLD_PACKAGE, 20000).contains("package:")) continue;
            if (enable) {
                log("spațiul " + user + ": " + sh("pm enable --user " + user + " " + OLD_PACKAGE, 20000).trim());
            } else {
                sh("am force-stop --user " + user + " " + OLD_PACKAGE, 20000);
                log("spațiul " + user + ": " + sh("pm disable-user --user " + user + " " + OLD_PACKAGE, 20000).trim());
            }
        }
        setStatus(enable ? "Open Headunit este din nou activ." : "Open Headunit este oprit. Îl poți reporni oricând.");
    }

    // ------------------------------------------------------------------ download and share

    private File downloadLatestApk() {
        try {
            JSONArray releases = new JSONArray(new String(httpGet(RELEASES_API), "UTF-8"));
            String name = null;
            String url = null;
            for (int i = 0; i < releases.length() && url == null; i++) {
                JSONArray assets = releases.getJSONObject(i).optJSONArray("assets");
                if (assets == null) continue;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.getJSONObject(j);
                    String assetName = asset.optString("name");
                    // "AA-Lynk-12.apk" is the car app; the installer's own APK is named differently.
                    if (assetName.matches("AA-Lynk-\\d+\\.apk")) {
                        name = assetName;
                        url = asset.getString("browser_download_url");
                        break;
                    }
                }
            }
            if (url == null) {
                log("Nu am găsit niciun APK în pagina Releases.");
                return null;
            }

            File out = new File(getCacheDir(), "aa-lynk.apk");
            log("Descarc " + name + " ...");
            HttpURLConnection conn = open(url);
            long total = conn.getContentLengthLong();
            InputStream in = conn.getInputStream();
            OutputStream file = new FileOutputStream(out);
            try {
                byte[] buffer = new byte[64 * 1024];
                long done = 0;
                long lastShown = -1;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    file.write(buffer, 0, read);
                    done += read;
                    long step = total > 0 ? done * 10 / total : done / (10 * 1024 * 1024);
                    if (step != lastShown) {
                        lastShown = step;
                        log("  descărcat " + (done / (1024 * 1024)) + " MB");
                    }
                }
            } finally {
                file.close();
                in.close();
                conn.disconnect();
            }
            log("Descărcat: " + name + ", " + (out.length() / (1024 * 1024)) + " MB");
            return out;
        } catch (Throwable t) {
            log("Descărcarea a eșuat: " + t);
            return null;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "aa-lynk-installer");
        conn.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        int code = conn.getResponseCode();
        if (code / 100 != 2) throw new Exception("HTTP " + code + " la " + url);
        return conn;
    }

    private static byte[] httpGet(String url) throws Exception {
        HttpURLConnection conn = open(url);
        InputStream in = conn.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        } finally {
            in.close();
            conn.disconnect();
        }
    }

    private void shareReport() {
        String text;
        synchronized (fullLog) {
            text = fullLog.toString();
        }
        if (text.isEmpty()) {
            setStatus("Nu am încă nimic de trimis. Apasă mai întâi 1 sau 2.");
            return;
        }
        try {
            OutputStream out = new FileOutputStream(ReportProvider.file(this));
            try {
                out.write(text.getBytes("UTF-8"));
            } finally {
                out.close();
            }
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, ReportProvider.URI)
                    .putExtra(Intent.EXTRA_SUBJECT, "Raport AA Lynk")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // The chooser passes the read grant on only for a URI carried as clip data.
            send.setClipData(ClipData.newRawUri("Raport AA Lynk", ReportProvider.URI));
            startActivity(Intent.createChooser(send, "Trimite raportul"));
        } catch (Throwable t) {
            log("Nu am putut pregăti raportul: " + t);
        }
    }
}
