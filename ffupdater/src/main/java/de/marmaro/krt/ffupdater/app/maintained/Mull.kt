package de.marmaro.krt.ffupdater.app.maintained

import android.os.Build
import de.marmaro.krt.ffupdater.R
import de.marmaro.krt.ffupdater.app.entity.LatestUpdate
import de.marmaro.krt.ffupdater.device.ABI
import de.marmaro.krt.ffupdater.device.DeviceAbiExtractor
import de.marmaro.krt.ffupdater.network.ApiConsumer
import de.marmaro.krt.ffupdater.network.fdroid.FdroidConsumer

/**
 * https://f-droid.org/en/packages/us.spotco.fennec_dos/
 */
class Mull(
    private val apiConsumer: ApiConsumer = ApiConsumer.INSTANCE,
    private val deviceAbiExtractor: DeviceAbiExtractor = DeviceAbiExtractor.INSTANCE,
) : AppBase() {
    override val packageName = "us.spotco.fennec_dos"
    override val displayTitle = R.string.mull__title
    override val displayDescription = R.string.mull__description
    override val displayWarning = R.string.mull__warning
    override val displayDownloadSource = R.string.download_source__fdroid
    override val displayIcon = R.mipmap.ic_logo_mull
    override val minApiLevel = Build.VERSION_CODES.LOLLIPOP
    override val supportedAbis = listOf(ABI.ARM64_V8A, ABI.ARMEABI_V7A)
    override val normalInstallation = true

    @Suppress("SpellCheckingInspection")
    override val signatureHash = "ff81f5be56396594eee70fef2832256e15214122e2ba9cedd26005ffd4bcaaa8"

    override suspend fun checkForUpdate(): LatestUpdate {
        val updateIndex = deviceAbiExtractor.supportedAbis
            .first { abi -> abi in supportedAbis }
            .let { abi ->
                when (abi) {
                    ABI.ARMEABI_V7A -> 0
                    ABI.ARM64_V8A -> 1
                    else -> throw IllegalArgumentException("ABI '$abi' is not supported")
                }
            }

        val fdroidConsumer = FdroidConsumer(packageName, apiConsumer)
        val updates = fdroidConsumer.updateCheck()

        check(updates.size == 2) { "Mull must have two releases with the same version name" }
        val result = updates[updateIndex]
        return LatestUpdate(
            downloadUrl = result.url,
            version = result.versionName,
            publishDate = null,
            fileSizeBytes = null,
            fileHash = null,
            firstReleaseHasAssets = true
        )
    }

}