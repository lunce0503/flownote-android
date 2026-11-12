package com.flownote.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ScrollView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** Native-only updater. The web settings action can open this UI, never supply update inputs. */
final class AppUpdater {
    private static final String KEY = "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAqEiDcMSgRJbkrANV45HH62vR7niIflubC/l5H0gwQhemZChS2v4CkMGasFuj9d5eKvPAhaRMAP7BNSEIAdjsxFjQF4dDOzD5mvPbt/py0x2PrtHmIdrgq0vC04DtwShrxFAr94KHBarITr30MUD6wgJDo9d973lo9YjP/E0uriEyhDDduPDLmx7zaBWgJ0T6FOehf9Ki4iyFv95Qx7U9QOgnRpNWZRsyzkNuINL68EU7giTYX3rKuhiZVf8RcGn7oBCHshMiWCDdnpCwvGuk1Uc/xl1o8BiZAVH5tK0r5j/Dp3vUi4epUORDdoiMR+WphYY0+CYQjR3NuzwqrRpTrgDnvAXjlrzwhP770NmMmUplkIMx+bzkAcWVwRJrcynotp8R7jNJet3OgFNoIxVInceJKHC5DXyJEgAjnsXZP4AA7NiRL/DNGM392R76fvGy3kyVHsMz+jkafkSYxZb0tiyffHVNTyiO4IfSlP4RYtzSZsGBIRFdQNpAhkc7IaEPAgMBAAE=";
    private final Activity activity;
    private final String channel = BuildConfig.DEBUG ? "staging" : "production";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private final Runnable onStateChanged;
    private String status = "자동 확인 대기";
    private AlertDialog details;
    private TextView detailsText;
    private final File apk;
    private volatile JSONObject ready;
    private long lastCheck;
    private boolean closed;
    private final Runnable periodic = new Runnable() { @Override public void run() { check(false); handler.postDelayed(this, 60 * 60 * 1000L); } };

    AppUpdater(Activity activity, Runnable onStateChanged) {
        this.activity = activity;
        this.onStateChanged = onStateChanged;
        preferences = activity.getSharedPreferences("app-updates", Activity.MODE_PRIVATE);
        apk = new File(activity.getFilesDir(), "updates/update.apk");
        if (preferences.getLong("installed-code", -1) != BuildConfig.VERSION_CODE) {
            record("설치 버전 확인 · " + BuildConfig.VERSION_NAME);
            preferences.edit().putLong("installed-code", BuildConfig.VERSION_CODE).apply();
        }
        handler.postDelayed(periodic, 3000);
    }
    void resume() { check(false); }
    void close() { closed = true; if (details != null) details.dismiss(); handler.removeCallbacksAndMessages(null); worker.shutdownNow(); }
    private void notifyState() {
        handler.post(() -> { if (!closed) { refreshDetails(); onStateChanged.run(); } });
    }
    private void ui(String text) { handler.post(() -> { if (!closed) { status = text; refreshDetails(); onStateChanged.run(); } }); }
    JSONObject snapshot() {
        JSONObject data = new JSONObject();
        try {
            data.put("schema", 1).put("version", BuildConfig.VERSION_NAME).put("channel", channel).put("status", status);
            JSONObject manifest = ready;
            if (manifest != null) data.put("availableVersion", manifest.getString("version"));
        } catch (Exception ignored) { }
        return data;
    }
    private void record(String event) {
        try {
            JSONArray old = new JSONArray(preferences.getString("history", "[]")), next = new JSONArray();
            for (int i = Math.max(0, old.length() - 29); i < old.length(); i++) next.put(old.getString(i));
            next.put(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(new java.util.Date()) + " · " + event);
            preferences.edit().putString("history", next.toString()).apply();
        } catch (Exception ignored) { }
    }
    private String detailsMessage() {
        StringBuilder text = new StringBuilder("설치 버전: " + BuildConfig.VERSION_NAME + "\n채널: " + channel + "\n상태: " + status + "\n\n새 버전은 자동 다운로드하며 설치에는 확인이 필요합니다.\n");
        JSONObject manifest = ready;
        try {
            if (manifest != null) { text.append("\n새 버전: ").append(manifest.getString("version")).append("\n"); JSONArray notes = manifest.getJSONArray("notes"); for (int i = 0; i < notes.length(); i++) text.append("• ").append(notes.getString(i)).append("\n"); }
            JSONArray history = new JSONArray(preferences.getString("history", "[]")); text.append("\n확인·다운로드 이력\n");
            for (int i = history.length() - 1; i >= Math.max(0, history.length() - 10); i--) text.append(history.getString(i)).append("\n");
        } catch (Exception ignored) { }
        return text.toString();
    }
    void showDetails() {
        if (closed || activity.isFinishing() || (details != null && details.isShowing())) return;
        ScrollView scroll = new ScrollView(activity);
        detailsText = new TextView(activity);
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        detailsText.setPadding(padding, padding / 2, padding, padding / 2);
        detailsText.setTextColor(0xff171714); detailsText.setTextSize(15);
        scroll.addView(detailsText);
        details = new AlertDialog.Builder(activity).setTitle("앱 업데이트").setView(scroll)
            .setNegativeButton("닫기", null).setPositiveButton("업데이트 확인", null).setNeutralButton("설치", null).create();
        details.setOnDismissListener(dialog -> { details = null; detailsText = null; });
        details.setOnShowListener(dialog -> {
            details.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> check(true));
            details.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> install());
            refreshDetails();
        });
        details.show();
    }
    private void refreshDetails() {
        if (details == null || !details.isShowing() || detailsText == null) return;
        detailsText.setText(detailsMessage());
        details.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!busy.get());
        Button installButton = details.getButton(AlertDialog.BUTTON_NEUTRAL);
        installButton.setVisibility(ready == null ? android.view.View.GONE : android.view.View.VISIBLE);
        installButton.setEnabled(!busy.get());
    }
    void check(boolean force) {
        if (closed || (!force && System.currentTimeMillis() - lastCheck < 15 * 60 * 1000L) || !busy.compareAndSet(false, true)) return;
        lastCheck = System.currentTimeMillis();
        notifyState();
        worker.execute(() -> {
            File partial = new File(apk.getParentFile(), "update.apk.part");
            try {
                ready = null; ui("업데이트 확인 중…");
                JSONObject envelope = new JSONObject(new String(fetchSmall(UpdatePolicy.origin(channel) + "/updates/" + channel + "/android.json"), StandardCharsets.UTF_8));
                byte[] payload = Base64.decode(envelope.getString("payload"), Base64.DEFAULT);
                if (!UpdatePolicy.verify(payload, Base64.decode(envelope.getString("signature"), Base64.DEFAULT), Base64.decode(KEY, Base64.DEFAULT))) throw new SecurityException("signature");
                JSONObject manifest = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                if (manifest.getInt("schema") != 1 || !"android".equals(manifest.getString("platform")) || !channel.equals(manifest.getString("channel"))) throw new SecurityException("channel");
                String version = manifest.getString("version"); long code = manifest.getLong("versionCode");
                if (UpdatePolicy.compareVersions(version, BuildConfig.VERSION_NAME.replace("-staging", "")) <= 0 || code <= BuildConfig.VERSION_CODE) { record("최신 버전 " + BuildConfig.VERSION_NAME); ui("최신 버전 · " + BuildConfig.VERSION_NAME); return; }
                if (code < preferences.getLong("highest-code", BuildConfig.VERSION_CODE)) throw new SecurityException("rollback");
                JSONObject item = manifest.getJSONObject("artifacts").getJSONObject("apk");
                String url = item.getString("url"), sha = item.getString("sha256"); long size = item.getLong("size");
                if (!UpdatePolicy.allowedArtifact(url, channel, version) || !sha.matches("[a-f0-9]{64}") || size < 1 || size > 100 * 1024 * 1024L || code > Integer.MAX_VALUE) throw new SecurityException("artifact");
                JSONArray notes = manifest.getJSONArray("notes"); if (notes.length() > 30) throw new SecurityException("notes");
                for (int i = 0; i < notes.length(); i++) if (notes.getString(i).length() > 1000) throw new SecurityException("notes");
                preferences.edit().putLong("highest-code", code).apply();
                if (!apk.getParentFile().isDirectory() && !apk.getParentFile().mkdirs()) throw new java.io.IOException("directory");
                if (!hashMatches(apk, size, sha)) {
                    record(version + " 다운로드 시작"); HttpsURLConnection connection = connect(url);
                    try (var input = connection.getInputStream(); var output = new FileOutputStream(partial)) {
                        byte[] buffer = new byte[32768]; long received = 0; int count, lastProgress = -1;
                        long started = System.currentTimeMillis();
                        while ((count = input.read(buffer)) != -1) {
                            if (Thread.currentThread().isInterrupted() || System.currentTimeMillis() - started > 20 * 60 * 1000L) throw new java.io.IOException("interrupted");
                            received += count; if (received > size) throw new SecurityException("size"); output.write(buffer, 0, count);
                            int progress = (int) (received * 100 / size); if (progress != lastProgress) { lastProgress = progress; ui("새 버전 " + version + " 다운로드 " + progress + "%"); }
                        }
                    } finally { connection.disconnect(); }
                    if (!hashMatches(partial, size, sha)) throw new SecurityException("checksum");
                    verifyApk(partial, code, version);
                    Files.move(partial.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                verifyApk(apk, code, version); ready = manifest;
                record(version + " 다운로드·서명 검증 완료"); ui("새 버전 " + version + " 준비됨 · 설치 확인 필요");
            } catch (Exception error) { ready = null; record("확인/다운로드 실패 · 다시 확인으로 재시도"); ui("업데이트 실패 · 다시 확인해 주세요"); }
            finally { if (partial.exists() && !partial.delete()) partial.deleteOnExit(); busy.set(false); notifyState(); }
        });
    }
    private HttpsURLConnection connect(String value) throws java.io.IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(value).openConnection();
        connection.setConnectTimeout(20000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        if (connection.getResponseCode() != 200) { connection.disconnect(); throw new java.io.IOException("HTTP"); }
        return connection;
    }
    private byte[] fetchSmall(String url) throws java.io.IOException {
        HttpsURLConnection connection = connect(url);
        try (var input = connection.getInputStream(); var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) { if (output.size() + count > 128 * 1024) throw new java.io.IOException("manifest size"); output.write(buffer, 0, count); }
            return output.toByteArray();
        } finally { connection.disconnect(); }
    }
    private boolean hashMatches(File file, long size, String expected) throws Exception {
        if (!file.isFile() || file.length() != size) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = new FileInputStream(file)) { byte[] buffer = new byte[32768]; int count; while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count); }
        StringBuilder hex = new StringBuilder(); for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return expected.equals(hex.toString());
    }
    @SuppressWarnings("deprecation")
    private void verifyApk(File file, long code, String version) throws Exception {
        PackageManager manager = activity.getPackageManager();
        PackageInfo candidate = manager.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo installed = manager.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (candidate == null || !activity.getPackageName().equals(candidate.packageName) || candidate.getLongVersionCode() != code || candidate.getLongVersionCode() <= installed.getLongVersionCode() || !(version + (BuildConfig.DEBUG ? "-staging" : "")).equals(candidate.versionName) || candidate.signingInfo == null || installed.signingInfo == null) throw new SecurityException("package version");
        var candidateSigners = candidate.signingInfo.getApkContentsSigners(); var installedSigners = installed.signingInfo.getApkContentsSigners();
        if (candidateSigners.length != installedSigners.length) throw new SecurityException("signer count");
        for (var signer : installedSigners) if (Arrays.stream(candidateSigners).noneMatch(other -> MessageDigest.isEqual(signer.toByteArray(), other.toByteArray()))) throw new SecurityException("signer mismatch");
    }
    private void install() {
        JSONObject manifest = ready; if (manifest == null || !busy.compareAndSet(false, true)) return;
        notifyState();
        worker.execute(() -> {
            try {
                JSONObject item = manifest.getJSONObject("artifacts").getJSONObject("apk");
                if (!hashMatches(apk, item.getLong("size"), item.getString("sha256"))) throw new SecurityException("checksum");
                verifyApk(apk, manifest.getLong("versionCode"), manifest.getString("version"));
                handler.post(() -> {
                    if (closed) return;
                    try {
                        if (!activity.getPackageManager().canRequestPackageInstalls()) {
                            new AlertDialog.Builder(activity).setTitle("앱 업데이트 설치 허용").setMessage("설정에서 Flownote의 ‘이 출처 허용’을 켠 뒤 돌아와 설치를 다시 눌러 주세요.")
                                .setPositiveButton("설정 열기", (dialog, which) -> {
                                    try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName()))); }
                                    catch (RuntimeException unavailable) { ui("설정 앱을 열 수 없습니다 · 기기 설정에서 설치 허용을 확인해 주세요"); }
                                }).setNegativeButton("취소", null).show();
                        } else {
                            Uri uri = Uri.parse("content://" + activity.getPackageName() + ".updates/package.apk");
                            activity.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                            record("시스템 설치 확인 화면 열기");
                        }
                    } catch (RuntimeException error) { ui("설치 화면을 열지 못했습니다 · 재시도"); }
                });
            } catch (Exception error) { ready = null; record("설치 전 검증 실패 · 다시 다운로드 필요"); ui("설치 파일 검증 실패 · 재시도"); }
            finally { busy.set(false); notifyState(); }
        });
    }
}
