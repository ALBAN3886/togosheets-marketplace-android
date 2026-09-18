package com.aet.togosheets;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private WebView webView;
    private static final String URL = "https://alban3886.github.io/togosheets-pro/public.html";

    // API GitHub publique pour connaître la dernière version publiée (releases).
    private static final String UPDATE_CHECK_URL =
            "https://api.github.com/repos/ALBAN3886/togosheets-marketplace-android/releases/latest";
    private static final String UPDATE_APK_FILENAME = "togosheets-marketplace-update.apk";
    private static final int REQUEST_INSTALL_PERMISSION_CODE = 3001;

    // ── Mise à jour intégrée (bannière construite en code, pas de layout XML pour cette app) ──
    private LinearLayout updateBanner;
    private TextView updateText;
    private Button updateButton;
    private String pendingApkUrl;
    private long updateDownloadId = -1;
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == updateDownloadId) {
                installDownloadedApk();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        RelativeLayout layout = new RelativeLayout(this);
        RelativeLayout.LayoutParams webViewParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT);
        layout.addView(webView, webViewParams);

        buildUpdateBanner(layout);

        setContentView(layout);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());
        webView.loadUrl(URL);

        IntentFilter downloadFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(downloadReceiver, downloadFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, downloadFilter);
        }

        checkForUpdate(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(downloadReceiver); } catch (IllegalArgumentException ignored) {}
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ── Construction de la bannière de mise à jour (pas de fichier XML pour cette app) ──
    private void buildUpdateBanner(RelativeLayout parent) {
        updateBanner = new LinearLayout(this);
        updateBanner.setOrientation(LinearLayout.HORIZONTAL);
        updateBanner.setGravity(Gravity.CENTER_VERTICAL);
        updateBanner.setBackgroundColor(Color.parseColor("#16a34a"));
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        updateBanner.setPadding(pad, pad, pad, pad);
        updateBanner.setVisibility(View.GONE);

        updateText = new TextView(this);
        updateText.setTextColor(Color.WHITE);
        updateText.setTextSize(13);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        updateBanner.addView(updateText, textParams);

        updateButton = new Button(this);
        updateButton.setText("Mettre à jour");
        updateButton.setTextColor(Color.parseColor("#16a34a"));
        updateButton.setBackgroundColor(Color.WHITE);
        updateBanner.addView(updateButton);

        ImageButton dismiss = new ImageButton(this);
        dismiss.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        dismiss.setBackgroundColor(Color.TRANSPARENT);
        dismiss.setColorFilter(Color.WHITE);
        dismiss.setOnClickListener(v -> updateBanner.setVisibility(View.GONE));
        updateBanner.addView(dismiss);

        RelativeLayout.LayoutParams bannerParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT);
        bannerParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        parent.addView(updateBanner, bannerParams);
    }

    // ── Mise à jour intégrée : vérification, téléchargement, installation ──

    private void checkForUpdate(boolean showFeedback) {
        new Thread(() -> {
            try {
                int localVersion = getPackageManager()
                        .getPackageInfo(getPackageName(), 0).versionCode;

                URL url = new URL(UPDATE_CHECK_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);

                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    if (showFeedback) runOnUiThread(() ->
                        Toast.makeText(this, "Impossible de vérifier pour le moment.", Toast.LENGTH_SHORT).show());
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                conn.disconnect();

                JSONObject release = new JSONObject(sb.toString());
                String tagName = release.optString("tag_name", "");

                // Le tag est de la forme "v1.0.<numéro de build>" : on compare ce numéro
                // au versionCode local (les deux viennent du même compteur CI).
                Matcher m = Pattern.compile("(\\d+)$").matcher(tagName);
                if (!m.find()) {
                    if (showFeedback) runOnUiThread(() ->
                        Toast.makeText(this, "Impossible de vérifier pour le moment.", Toast.LENGTH_SHORT).show());
                    return;
                }
                int remoteVersion = Integer.parseInt(m.group(1));

                if (remoteVersion <= localVersion) {
                    if (showFeedback) runOnUiThread(() ->
                        Toast.makeText(this, "Tu es déjà à jour ✓", Toast.LENGTH_SHORT).show());
                    return; // déjà à jour
                }

                // Trouver l'URL de téléchargement de l'APK dans les assets de la release
                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = null;
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String name = asset.optString("name", "");
                        if (name.toLowerCase().endsWith(".apk")) {
                            apkUrl = asset.optString("browser_download_url", null);
                            break;
                        }
                    }
                }
                if (apkUrl == null) {
                    if (showFeedback) runOnUiThread(() ->
                        Toast.makeText(this, "Impossible de vérifier pour le moment.", Toast.LENGTH_SHORT).show());
                    return;
                }

                final String finalApkUrl = apkUrl;
                final String versionLabel = tagName;
                runOnUiThread(() -> showUpdateBanner(versionLabel, finalApkUrl));

            } catch (Exception e) {
                // Échec silencieux au démarrage ; message si vérification manuelle.
                if (showFeedback) runOnUiThread(() ->
                    Toast.makeText(this, "Impossible de vérifier pour le moment.", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showUpdateBanner(String versionLabel, String apkUrl) {
        pendingApkUrl = apkUrl;
        updateText.setText("Nouvelle version disponible (" + versionLabel + ")");
        updateButton.setText("Mettre à jour");
        updateButton.setEnabled(true);
        updateButton.setOnClickListener(v -> startApkDownload());
        updateBanner.setVisibility(View.VISIBLE);
    }

    /** Télécharge l'APK directement dans l'app (aucune sortie vers un navigateur). */
    private void startApkDownload() {
        if (pendingApkUrl == null) return;
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(pendingApkUrl));
            request.setTitle("Mise à jour TogoSheets Marketplace");
            request.setDescription("Téléchargement en cours…");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS, UPDATE_APK_FILENAME);
            request.setMimeType("application/vnd.android.package-archive");

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            File existing = new File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), UPDATE_APK_FILENAME);
            if (existing.exists()) existing.delete();

            updateDownloadId = dm.enqueue(request);
            updateButton.setEnabled(false);
            updateButton.setText("Téléchargement…");
        } catch (Exception e) {
            Toast.makeText(this, "Impossible de démarrer le téléchargement.", Toast.LENGTH_SHORT).show();
        }
    }

    /** Appelé quand le téléchargement de l'APK est terminé : vérifie puis lance l'installation. */
    private void installDownloadedApk() {
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(updateDownloadId);
        android.database.Cursor cursor = dm.query(query);
        boolean success = false;
        if (cursor != null) {
            if (cursor.moveToFirst()) {
                int statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                if (statusIdx >= 0 && cursor.getInt(statusIdx) == DownloadManager.STATUS_SUCCESSFUL) {
                    success = true;
                }
            }
            cursor.close();
        }

        runOnUiThread(() -> {
            updateButton.setEnabled(true);
            updateButton.setText("Mettre à jour");
        });

        if (!success) {
            runOnUiThread(() -> Toast.makeText(this, "Échec du téléchargement. Réessaie.", Toast.LENGTH_SHORT).show());
            return;
        }

        // Android 8+ : l'utilisateur doit autoriser l'installation depuis cette app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !getPackageManager().canRequestPackageInstalls()) {
            runOnUiThread(() -> {
                Toast.makeText(this, "Autorise l'installation pour continuer la mise à jour.", Toast.LENGTH_LONG).show();
                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(settingsIntent, REQUEST_INSTALL_PERMISSION_CODE);
            });
            return;
        }

        launchApkInstall();
    }

    private void launchApkInstall() {
        File apkFile = new File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), UPDATE_APK_FILENAME);
        if (!apkFile.exists()) return;
        Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(installIntent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_INSTALL_PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || getPackageManager().canRequestPackageInstalls()) {
                launchApkInstall();
            }
        }
    }
}
