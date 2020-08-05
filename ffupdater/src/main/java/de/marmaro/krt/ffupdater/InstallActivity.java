package de.marmaro.krt.ffupdater;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.StatFs;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.util.Pair;

import com.google.common.base.Preconditions;

import org.apache.commons.codec.binary.ApacheCodecHex;

import java.io.File;
import java.util.Objects;

import de.marmaro.krt.ffupdater.device.DeviceEnvironment;
import de.marmaro.krt.ffupdater.download.DownloadManagerAdapter;
import de.marmaro.krt.ffupdater.security.CertificateFingerprint;
import de.marmaro.krt.ffupdater.settings.SettingsHelper;
import de.marmaro.krt.ffupdater.utils.Utils;
import de.marmaro.krt.ffupdater.version.AvailableVersions;

import static android.os.Environment.DIRECTORY_DOWNLOADS;

/**
 * Activity for downloading and installing apps on devices with API Level >= 24/Nougat.
 * Reason: If have to use the DownloadManager because this is the easiest way to download the app and access it with
 * the scheme format (for example: content://downloads/all_downloads/20).
 * The DownloadManager is more difficult to use then the default java way, but the DownloadManager offers more features
 * like restarting downloads, showing the current download status etc.
 */
public class InstallActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_INSTALL = 401;
    public static final String EXTRA_APP_NAME = "app_name";
    public static final String EXTRA_DOWNLOAD_URL = "download_url";

    private DownloadManagerAdapter downloadManager;
    private App app;
    private String downloadUrl;
    private String installingVersionOrTimestamp;
    private AvailableVersions availableVersions;
    private long downloadId = -1;
    private boolean killSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.download_activity);
        AppCompatDelegate.setDefaultNightMode(SettingsHelper.getThemePreference(this, new DeviceEnvironment()));

        registerReceiver(onDownloadComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        downloadManager = new DownloadManagerAdapter((DownloadManager) getSystemService(DOWNLOAD_SERVICE));

        findViewById(R.id.installConfirmationButton).setOnClickListener(v -> install());

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        availableVersions = new AvailableVersions(this);

        Bundle extras = Objects.requireNonNull(getIntent().getExtras());
        String appName = extras.getString(EXTRA_APP_NAME);
        app = App.valueOf(Objects.requireNonNull(appName));
        downloadUrl = Objects.requireNonNull(extras.getString(EXTRA_DOWNLOAD_URL)); //TODO
        installingVersionOrTimestamp = availableVersions.getAvailableVersionOrTimestamp(app);

        hideAllEntries();
        checkFreeSpace();
        fetchUrlForDownload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(onDownloadComplete);
        availableVersions.shutdown();
        killSwitch = true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_INSTALL) {
            actionInstallationFinished(resultCode == Activity.RESULT_OK);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void fetchUrlForDownload() {
        if (!downloadUrl.isEmpty()) {
            actionFetchSuccessful();
            downloadApplication();
            return;
        }

        if (!availableVersions.getDownloadUrl(app).isEmpty()) {
            downloadUrl = availableVersions.getDownloadUrl(app);
            actionFetchSuccessful();
            downloadApplication();
            return;
        }

        actionFetching();
        availableVersions.checkUpdateForApp(app, this, () -> {
            if (!availableVersions.getDownloadUrl(app).isEmpty()) {
                actionFetchSuccessful();
                downloadUrl = availableVersions.getDownloadUrl(app);
                downloadApplication();
            } else {
                actionFetchUnsuccessful();
            }
        });
    }

    private void downloadApplication() {
        downloadId = downloadManager.enqueue(
                this,
                downloadUrl,
                app.getTitle(this),
                DownloadManager.Request.VISIBILITY_VISIBLE);

        actionDownloadBegin();
        new Thread(() -> {
            int previousStatus = -1;
            while (!killSwitch) {
                Pair<Integer, Integer> statusAndProgress = downloadManager.getStatusAndProgress(downloadId);
                int status = Objects.requireNonNull(statusAndProgress.first);
                if (previousStatus != status) {
                    previousStatus = status;
                    actionDownloadUpdateStatus(status);
                }
                if (status == DownloadManager.STATUS_FAILED || status == DownloadManager.STATUS_SUCCESSFUL) {
                    return;
                }

                int progress = Objects.requireNonNull(statusAndProgress.second);
                actionDownloadUpdateProgressBar(progress);

                Utils.sleepAndIgnoreInterruptedException(500);
            }
        }).start();
    }

    private final BroadcastReceiver onDownloadComplete = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            long id = Objects.requireNonNull(intent.getExtras()).getLong(DownloadManager.EXTRA_DOWNLOAD_ID);
            if (id != downloadId) {
                // received an older message - skip
                return;
            }
            if (Objects.requireNonNull(downloadManager.getStatusAndProgress(id).first) == DownloadManager.STATUS_FAILED) {
                actionDownloadFailed();
                return;
            }
            actionDownloadFinished();
            actionVerifyingSignature();
            new Thread(() -> {
                File downloadedFile = downloadManager.getFileForDownloadedFile(id);
                Pair<Boolean, String> check = CertificateFingerprint.checkFingerprintOfFile(getPackageManager(), downloadedFile, app);
                if (Objects.requireNonNull(check.first)) {
                    actionSignatureGood(check.second);
                } else {
                    actionSignatureBad(check.second);
                }
            }).start();
        }
    };

    private void install() {
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        if (Build.VERSION.SDK_INT < 24) {
            intent.setData(Uri.fromFile(downloadManager.getFileForDownloadedFile(downloadId)));
        } else {
            intent.setData(downloadManager.getUriForDownloadedFile(downloadId));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(DownloadManager.EXTRA_DOWNLOAD_ID, downloadId);
        }
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true);
        intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        startActivityForResult(intent, REQUEST_CODE_INSTALL);
    }

    private void hideAllEntries() {
        findViewById(R.id.tooLowMemory).setVisibility(View.GONE);
        findViewById(R.id.fetchUrl).setVisibility(View.GONE);
        findViewById(R.id.fetchedUrlSuccess).setVisibility(View.GONE);
        findViewById(R.id.fetchedUrlFailure).setVisibility(View.GONE);
        findViewById(R.id.downloadingFile).setVisibility(View.GONE);
        findViewById(R.id.downloadedFile).setVisibility(View.GONE);
        findViewById(R.id.downloadFileFailed).setVisibility(View.GONE);
        findViewById(R.id.verifyDownloadFingerprint).setVisibility(View.GONE);
        findViewById(R.id.fingerprintDownloadGood).setVisibility(View.GONE);
        findViewById(R.id.fingerprintDownloadBad).setVisibility(View.GONE);
        findViewById(R.id.installConfirmation).setVisibility(View.GONE);
        findViewById(R.id.verifyInstalledFingerprint).setVisibility(View.GONE);
        findViewById(R.id.fingerprintInstalledGood).setVisibility(View.GONE);
        findViewById(R.id.fingerprintInstalledBad).setVisibility(View.GONE);
        findViewById(R.id.installerSuccess).setVisibility(View.GONE);
        findViewById(R.id.installerFailed).setVisibility(View.GONE);
    }

    private void checkFreeSpace() {
        File externalFilesDir = getExternalFilesDir(DIRECTORY_DOWNLOADS);
        Preconditions.checkNotNull(externalFilesDir);
        long freeBytes = new StatFs(externalFilesDir.getPath()).getFreeBytes();
        if (freeBytes > 104_857_600) {
            return;
        }

        findViewById(R.id.tooLowMemory).setVisibility(View.VISIBLE);
        long freeMBytes = freeBytes / (1024 * 1024);
        TextView description = findViewById(R.id.tooLowMemoryDescription);
        description.setText(getString(R.string.too_low_memory_description, freeMBytes));
    }


    private void actionFetching() {
        findViewById(R.id.fetchUrl).setVisibility(View.VISIBLE);
        findTextViewById(R.id.fetchUrlTextView).setText(getString(R.string.fetch_url_for_download, app.getDownloadSource(this)));
    }

    private void actionFetchSuccessful() {
        findViewById(R.id.fetchUrl).setVisibility(View.GONE);
        findViewById(R.id.fetchedUrlSuccess).setVisibility(View.VISIBLE);
        findTextViewById(R.id.fetchedUrlSuccessTextView).setText(getString(R.string.fetched_url_for_download_successfully, app.getDownloadSource(this)));
    }

    private void actionFetchUnsuccessful() {
        findViewById(R.id.fetchUrl).setVisibility(View.GONE);
        findViewById(R.id.fetchedUrlFailure).setVisibility(View.VISIBLE);
        findTextViewById(R.id.fetchedUrlFailureTextView).setText(getString(R.string.fetched_url_for_download_unsuccessfully, app.getDownloadSource(this)));
        findViewById(R.id.installerFailed).setVisibility(View.VISIBLE);
    }

    private void actionDownloadBegin() {
        runOnUiThread(() -> {
            findViewById(R.id.downloadingFile).setVisibility(View.VISIBLE);
            findTextViewById(R.id.downloadingFileUrl).setText(downloadUrl);
        });
    }

    private void actionDownloadUpdateProgressBar(int percent) {
        runOnUiThread(() -> ((ProgressBar) findViewById(R.id.downloadingFileProgressBar)).setProgress(percent));

    }

    private void actionDownloadUpdateStatus(int status) {
        runOnUiThread(() -> {
            String text = getString(R.string.download_application_from_with_status, getDownloadStatusAsString(status));
            findTextViewById(R.id.downloadingFileText).setText(text);
        });
    }

    private String getDownloadStatusAsString(int status) {
        switch (status) {
            case DownloadManager.STATUS_RUNNING:
                return "running";
            case DownloadManager.STATUS_SUCCESSFUL:
                return "success";
            case DownloadManager.STATUS_FAILED:
                return "failed";
            case DownloadManager.STATUS_PAUSED:
                return "paused";
            case DownloadManager.STATUS_PENDING:
                return "pending";
        }
        return "";
    }

    private void actionDownloadFailed() {
        runOnUiThread(() -> {
            findViewById(R.id.downloadingFile).setVisibility(View.GONE);
            findViewById(R.id.downloadFileFailed).setVisibility(View.VISIBLE);
            findTextViewById(R.id.downloadFileFailedUrl).setText(downloadUrl);
            findViewById(R.id.installerFailed).setVisibility(View.VISIBLE);
        });
    }

    private void actionDownloadFinished() {
        runOnUiThread(() -> {
            runOnUiThread(() -> findViewById(R.id.downloadingFile).setVisibility(View.GONE));
            findViewById(R.id.downloadedFile).setVisibility(View.VISIBLE);
            findTextViewById(R.id.downloadedFileUrl).setText(downloadUrl);
        });
    }

    private void actionVerifyingSignature() {
        runOnUiThread(() -> findViewById(R.id.verifyDownloadFingerprint).setVisibility(View.VISIBLE));
    }

    private void actionSignatureGood(String hash) {
        runOnUiThread(() -> {
            findViewById(R.id.verifyDownloadFingerprint).setVisibility(View.GONE);
            findViewById(R.id.fingerprintDownloadGood).setVisibility(View.VISIBLE);
            findTextViewById(R.id.fingerprintDownloadGoodHash).setText(hash);
            findViewById(R.id.installConfirmation).setVisibility(View.VISIBLE);
        });
    }

    private void actionSignatureBad(String hash) {
        runOnUiThread(() -> {
            findViewById(R.id.verifyDownloadFingerprint).setVisibility(View.GONE);
            findViewById(R.id.fingerprintDownloadBad).setVisibility(View.VISIBLE);
            findTextViewById(R.id.fingerprintDownloadBadHashActual).setText(hash);
            findTextViewById(R.id.fingerprintDownloadBadHashExpected).setText(ApacheCodecHex.encodeHexString(app.getSignatureHash()));
            findViewById(R.id.installerFailed).setVisibility(View.VISIBLE);
        });
    }

    private void actionInstallationFinished(boolean success) {
        runOnUiThread(() -> {
            findViewById(R.id.installConfirmation).setVisibility(View.GONE);
            if (success) {
                findViewById(R.id.installerSuccess).setVisibility(View.VISIBLE);
                actionVerifyInstalledAppSignature();
            } else {
                findViewById(R.id.installerFailed).setVisibility(View.VISIBLE);
            }
        });
        if (success) {
            availableVersions.setInstalledVersionOrTimestamp(app, installingVersionOrTimestamp);
        }
        downloadManager.remove(downloadId);
    }

    private void actionVerifyInstalledAppSignature() {
        runOnUiThread(() -> findViewById(R.id.verifyInstalledFingerprint).setVisibility(View.VISIBLE));
        new Thread(() -> {
            Pair<Boolean, String> validCertificate = CertificateFingerprint.checkFingerprintOfInstalledApp(getPackageManager(), app);
            runOnUiThread(() -> {
                findViewById(R.id.verifyInstalledFingerprint).setVisibility(View.GONE);
                if (Objects.requireNonNull(validCertificate.first)) {
                    findViewById(R.id.fingerprintInstalledGood).setVisibility(View.VISIBLE);
                    findTextViewById(R.id.fingerprintInstalledGoodHash).setText(validCertificate.second);
                } else {
                    findViewById(R.id.fingerprintInstalledBad).setVisibility(View.VISIBLE);
                    findTextViewById(R.id.fingerprintInstalledBadHashActual).setText(validCertificate.second);
                    findTextViewById(R.id.fingerprintInstalledBadHashExpected).setText(ApacheCodecHex.encodeHexString(app.getSignatureHash()));
                }
            });
        }).start();
    }

    private TextView findTextViewById(int id) {
        return findViewById(id);
    }
}