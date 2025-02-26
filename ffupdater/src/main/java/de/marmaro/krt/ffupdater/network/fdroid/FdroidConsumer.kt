package de.marmaro.krt.ffupdater.network.fdroid

import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.MainThread
import com.google.gson.JsonObject
import de.marmaro.krt.ffupdater.FFUpdater.Companion.LOG_TAG
import de.marmaro.krt.ffupdater.network.exceptions.NetworkException
import de.marmaro.krt.ffupdater.network.file.CacheBehaviour
import de.marmaro.krt.ffupdater.network.file.FileDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FdroidConsumer {

    @MainThread
    suspend fun getLatestUpdate(
        packageName: String,
        versionAcceptor: (Package) -> Boolean,
        cacheBehaviour: CacheBehaviour,
    ): Result {
        val url = "https://f-droid.org/api/v1/packages/$packageName"
        val rootJson = FileDownloader.downloadJsonObjectWithCache(url, cacheBehaviour)

        val appInfo = parseJson(rootJson)

        val latestVersion = getLatestUpdate(appInfo, versionAcceptor)
        val commitId = getLastCommitId(packageName, cacheBehaviour)
        val createdAt = getCreateDate(commitId, cacheBehaviour)

        Log.i(LOG_TAG, "FdroidConsumer: Found latest version ${latestVersion.versionName}")
        return Result(
            latestVersion.versionName,
            latestVersion.versionCode,
            "https://f-droid.org/repo/${packageName}_${latestVersion.versionCode}.apk",
            createdAt
        )
    }

    private suspend fun parseJson(rootJson: JsonObject): AppInfo {
        return try {
            withContext(Dispatchers.Default) {
                AppInfo(
                    packageName = rootJson["packageName"].asString,
                    suggestedVersionCode = rootJson["suggestedVersionCode"].asLong,
                    packages = rootJson["packages"].asJsonArray
                        .map { it.asJsonObject }
                        .map {
                            Package(
                                versionName = it["versionName"].asString,
                                versionCode = it["versionCode"].asLong
                            )
                        }
                )
            }
        } catch (e: Exception) {
            when (e) {
                is NullPointerException,
                is NumberFormatException,
                is IllegalStateException,
                is UnsupportedOperationException,
                is IndexOutOfBoundsException,
                -> throw NetworkException("Returned JSON is incorrect. Try delete the cache of FFUpdater.", e)
            }
            throw e
        }
    }

    /**
     * For FennecFdroid two different versions with the same version number are released.
     * The first version is for ARMEABI_V7A devices and the second version for ARM64_V8A.
     * This method helps to extract a specific version from the APi response.
     */
    private fun getLatestUpdate(appInfo: AppInfo, versionAcceptor: (Package) -> Boolean): Package {
        return appInfo.packages
            .filter(versionAcceptor)
            .maxBy { it.versionCode }
    }

    private suspend fun getLastCommitId(packageName: String, cacheBehaviour: CacheBehaviour): String {
        val url = "https://gitlab.com/api/v4/projects/36528/repository/files/metadata%2F${packageName}.yml?ref=master"
        val rootJson = FileDownloader.downloadJsonObjectWithCache(url, cacheBehaviour)
        return rootJson["last_commit_id"].asString
    }

    private suspend fun getCreateDate(commitId: String, cacheBehaviour: CacheBehaviour): String {
        val url = "https://gitlab.com/api/v4/projects/36528/repository/commits/$commitId"
        val rootJson = FileDownloader.downloadJsonObjectWithCache(url, cacheBehaviour)
        return rootJson["created_at"].asString
    }

    @Keep
    private data class AppInfo(
        val packageName: String,
        val suggestedVersionCode: Long,
        val packages: List<Package>,
    )

    @Keep
    data class Package(
        val versionName: String,
        val versionCode: Long,
    )

    @Keep
    data class Result(
        val versionName: String,
        val versionCode: Long,
        val downloadUrl: String,
        val createdAt: String,
    )
}