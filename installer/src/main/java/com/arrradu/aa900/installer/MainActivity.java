package com.arrradu.aa900.installer;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
    private final List<View> controls = new ArrayList<View>();

    private Ui look;
    private TextView statusView;
    private TextView moodView;
    private GradientDrawable moodDot;
    private int moodDotColor;
    private ProgressBar progressBar;
    private TextView logView;
    private ScrollView logScroll;
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
        look = new Ui(this);
        look.dressWindow(this);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(look.dp(20), look.dp(28), look.dp(20), look.dp(36));
        // Cards cast their shadow past their own edge; the page must not cut it off.
        page.setClipToPadding(false);
        page.setClipChildren(false);

        page.addView(header());
        page.addView(statusCard(), look.fillWithTop(26));

        LinearLayout install = look.primaryButton(
                "Instalează Android Auto", "Ultima versiune, direct în mașină");
        onJob(install, new Runnable() {
            public void run() {
                installFlow();
            }
        });
        page.addView(install, look.fillWithTop(20));

        page.addView(look.label("Alte acțiuni"), sectionLabel());
        page.addView(actionsCard(), look.fillWithTop(10));

        page.addView(look.label("Comandă pentru mașină"), sectionLabel());
        page.addView(commandCard(), look.fillWithTop(10));

        page.addView(look.label("Jurnal"), sectionLabel());
        page.addView(logCard(), look.fillWithTop(10));

        TextView version = look.text("Lynk&Co 900 AA Installer " + versionName(), 12, look.inkSoft, look.sans);
        version.setGravity(Gravity.CENTER);
        page.addView(version, look.fillWithTop(24));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setClipToPadding(false);
        scroller.setFitsSystemWindows(true);
        scroller.addView(page);
        setContentView(scroller);

        setMood(Ui.Mood.IDLE);
    }

    private LinearLayout.LayoutParams sectionLabel() {
        LinearLayout.LayoutParams params = look.fillWithTop(30);
        params.leftMargin = look.dp(6);
        return params;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private View header() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setElevation(look.dp(4));
        header.addView(icon, new LinearLayout.LayoutParams(look.dp(52), look.dp(52)));

        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(look.dp(16), 0, 0, 0);
        TextView eyebrow = look.label("Installer");
        eyebrow.setTextColor(look.accent);
        words.addView(eyebrow);
        TextView title = look.text("Lynk&Co 900", 30, look.ink, look.serif);
        title.setPadding(0, look.dp(6), 0, 0);
        words.addView(title);
        header.addView(words);
        return header;
    }

    private View statusCard() {
        LinearLayout card = look.card();
        card.setPadding(look.dp(22), look.dp(20), look.dp(22), look.dp(22));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        moodDotColor = look.idle;
        moodDot = look.rounded(moodDotColor, 5);
        View dot = new View(this);
        dot.setBackground(moodDot);
        pill.addView(dot, new LinearLayout.LayoutParams(look.dp(10), look.dp(10)));
        moodView = look.label("Pregătit");
        moodView.setPadding(look.dp(10), 0, 0, 0);
        pill.addView(moodView);
        card.addView(pill);

        statusView = look.text(
                "Conectează telefonul la Wi-Fi-ul mașinii, apoi apasă butonul de mai jos.",
                21, look.ink, look.serif);
        statusView.setLineSpacing(look.dp(5), 1f);
        card.addView(statusView, look.fillWithTop(14));

        progressBar = look.progressBar();
        progressBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, look.dp(7));
        bar.topMargin = look.dp(18);
        card.addView(progressBar, bar);
        return card;
    }

    private View actionsCard() {
        LinearLayout card = look.card();

        LinearLayout other = look.actionRow("+", "Instalează alt APK", "Alege un fișier din memoria telefonului");
        other.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickApk();
            }
        });
        controls.add(other);
        card.addView(other);
        card.addView(look.divider());

        LinearLayout diagnostic = look.actionRow("i", "Diagnostic", "Citește starea mașinii");
        onJob(diagnostic, new Runnable() {
            public void run() {
                reportFlow();
            }
        });
        card.addView(diagnostic);
        card.addView(look.divider());

        LinearLayout share = look.actionRow("\u2197", "Trimite raportul", "Către Claude sau altă aplicație");
        share.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                shareReport();
            }
        });
        card.addView(share);
        return card;
    }

    private View commandCard() {
        LinearLayout card = look.card();
        card.setPadding(look.dp(16), look.dp(16), look.dp(16), look.dp(16));

        commandBox = new EditText(this);
        commandBox.setHint("Lipește aici comanda primită de la Claude");
        commandBox.setHintTextColor(look.inkSoft);
        commandBox.setTextColor(look.ink);
        commandBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        commandBox.setTypeface(Typeface.MONOSPACE);
        commandBox.setMinLines(2);
        commandBox.setMaxLines(5);
        commandBox.setGravity(Gravity.TOP | Gravity.START);
        commandBox.setPadding(look.dp(14), look.dp(12), look.dp(14), look.dp(12));
        commandBox.setBackground(look.rounded(look.dark ? 0xFF262624 : 0xFFF5F4EE, 14));
        card.addView(commandBox, look.fill());

        TextView run = look.tonalButton("Rulează în mașină");
        onJob(run, new Runnable() {
            public void run() {
                commandFlow(commandBox.getText().toString().trim());
            }
        });
        card.addView(run, look.fillWithTop(12));
        return card;
    }

    private View logCard() {
        logView = look.text("Aici apare tot ce face aplicația.\n", 11, look.insetText, Typeface.MONOSPACE);
        logView.setIncludeFontPadding(true);
        logView.setTextIsSelectable(true);
        logView.setPadding(look.dp(16), look.dp(14), look.dp(16), look.dp(14));

        logScroll = new ScrollView(this);
        logScroll.setBackground(look.rounded(look.inset, 20));
        logScroll.setClipToOutline(true);
        logScroll.addView(logView);
        // The log scrolls inside a page that also scrolls: while a finger is on the log, the page waits.
        logScroll.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                v.getParent().requestDisallowInterceptTouchEvent(
                        event.getActionMasked() != MotionEvent.ACTION_UP
                                && event.getActionMasked() != MotionEvent.ACTION_CANCEL);
                return false;
            }
        });
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, look.dp(260)));
        return logScroll;
    }

    /** Wires a control to a job that talks to the car; such controls are greyed out while one runs. */
    private void onJob(View control, final Runnable job) {
        control.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                runJob(job);
            }
        });
        controls.add(control);
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

    /** One job at a time: the car's ADB link is a single connection. */
    private void runJob(final Runnable job) {
        setControlsEnabled(false);
        setMood(Ui.Mood.BUSY);
        showProgress(-1);
        worker.execute(new Runnable() {
            public void run() {
                try {
                    job.run();
                } catch (Throwable t) {
                    log("EROARE: " + t);
                    setStatus("A apărut o eroare. Apasă „Trimite raportul” și trimite-l lui Claude.");
                } finally {
                    setControlsEnabled(true);
                    hideProgress();
                    setMood(moodFor(lastStatus));
                }
            }
        });
    }

    /** Reads the outcome off the last thing the job told the user. */
    private static Ui.Mood moodFor(String status) {
        if (status.startsWith("Gata") || status.startsWith("Diagnostic gata")
                || status.startsWith("Comandă rulată")) {
            return Ui.Mood.OK;
        }
        return Ui.Mood.PROBLEM;
    }

    private void setControlsEnabled(final boolean enabled) {
        ui.post(new Runnable() {
            public void run() {
                for (View control : controls) look.setEnabled(control, enabled);
            }
        });
    }

    private volatile String lastStatus = "";

    private void setStatus(final String text) {
        lastStatus = text;
        ui.post(new Runnable() {
            public void run() {
                statusView.setText(text);
                statusView.setAlpha(0.35f);
                statusView.animate().alpha(1f).setDuration(260).start();
            }
        });
    }

    private void setMood(final Ui.Mood mood) {
        ui.post(new Runnable() {
            public void run() {
                int color = look.moodColor(mood);
                look.tintDot(moodDot, moodDotColor, color);
                moodDotColor = color;
                moodView.setText(look.moodWord(mood).toUpperCase());
                moodView.setTextColor(mood == Ui.Mood.IDLE ? look.inkSoft : color);
            }
        });
    }

    /** Shows the bar at a percentage, or as a running bar when the amount is unknown (-1). */
    private void showProgress(final int percent) {
        ui.post(new Runnable() {
            public void run() {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setIndeterminate(percent < 0);
                if (percent >= 0) progressBar.setProgress(percent, true);
            }
        });
    }

    private void hideProgress() {
        ui.post(new Runnable() {
            public void run() {
                progressBar.setVisibility(View.GONE);
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
                logScroll.post(new Runnable() {
                    public void run() {
                        logScroll.fullScroll(View.FOCUS_DOWN);
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

        // An update goes where the app already is. A first install goes where Open Headunit is,
        // if it is still there: that is where the owner set it up and where it works.
        for (String known : new String[]{CAR_PACKAGE, OLD_PACKAGE}) {
            List<Integer> found = new ArrayList<Integer>();
            for (int user : all) {
                if (sh("pm path --user " + user + " " + known, 20000).contains("package:")) {
                    found.add(user);
                }
            }
            if (!found.isEmpty()) {
                log(known + " este instalat în spațiile: " + found + ". Instalez în aceleași.");
                return found;
            }
        }

        int current = currentUser();
        log("Prima instalare. Instalez în spațiul curent: " + current);
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
            setStatus("Instalarea „" + label + "” a eșuat. Apasă „Trimite raportul” și trimite-l lui Claude.");
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

        setStatus("Descarc ultima versiune Android Auto de pe GitHub ...");
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
            setStatus("Instalarea a eșuat. Apasă „Trimite raportul” și trimite-l lui Claude.");
            return;
        }

        int launchUser = users.get(0);
        log("Pornesc Android Auto: " + sh("am start --user " + launchUser + " -n " + CAR_ACTIVITY, 30000).trim());
        setStatus(allOk
                ? "Gata. Android Auto este instalat și pornit pe ecranul mașinii."
                : "Android Auto este instalat, dar nu în toate spațiile. Trimite raportul lui Claude.");
    }

    private boolean installApk(File apk, int user) {
        final long size = apk.length();
        AdbClient.Progress progress = new AdbClient.Progress() {
            long lastShown = -1;

            public void onBytes(long done) {
                long percent = done * 100 / size;
                if (percent / 10 != lastShown / 10) {
                    lastShown = percent;
                    showProgress((int) percent);
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
        setStatus("Diagnostic gata. Apasă „Trimite raportul” ca să îl trimiți lui Claude.");
    }

    private void commandFlow(String command) {
        if (command.isEmpty()) {
            setStatus("Scrie mai întâi comanda în căsuță.");
            return;
        }
        if (!ensureConnected()) return;
        setStatus("Rulez comanda ...");
        log("$ " + command + "\n" + sh(command, 120000));
        setStatus("Comandă rulată. Apasă „Trimite raportul” ca să trimiți rezultatul lui Claude.");
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
                    if (total > 0) showProgress((int) (done * 100 / total));
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
            setStatus("Nu am încă nimic de trimis. Rulează mai întâi o instalare sau diagnosticul.");
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
