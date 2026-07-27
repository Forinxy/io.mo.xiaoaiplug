package io.mo.xiaoaiplug.ui

import android.app.Application
import android.content.Intent
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.mo.xiaoaiplug.config.ReleaseInfo
import io.mo.xiaoaiplug.config.UpdateChecker
import io.mo.xiaoaiplug.config.UpdateResult
import io.mo.xiaoaiplug.ui.theme.UiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页那一行「检查更新」的状态。启动时的自动检查**不**走这个，它全程不出声。 */
sealed interface ManualCheck {
    data object Idle : ManualCheck
    data object Running : ManualCheck
    data object UpToDate : ManualCheck
    data class Failed(val reason: String) : ManualCheck
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = UiPrefs.get(app)

    /** 非 null 就是要弹的那个版本。 */
    private val _pending = MutableStateFlow<ReleaseInfo?>(null)
    val pending: StateFlow<ReleaseInfo?> = _pending.asStateFlow()

    private val _manual = MutableStateFlow<ManualCheck>(ManualCheck.Idle)
    val manual: StateFlow<ManualCheck> = _manual.asStateFlow()

    val autoCheckUpdate: StateFlow<Boolean> = prefs.autoCheckUpdate

    val currentVersion: String = UpdateChecker.currentVersion(app).orEmpty()

    /**
     * 防重入。**必须同步置位**，不能挪进 launch 里：协程启动有延迟，
     * 连点「检查更新」时好几次都会先过掉这道检查，再各自发一发请求。
     */
    private var checking = false

    /** 一次进程生命周期只自动查一次。 */
    private var launchChecked = false

    /**
     * 启动时自动查。开关关掉就直接不查 —— 不是查了不弹，是根本不发请求。
     *
     * 失败一律吞掉：用户没主动要求过这次检查，为它弹一条「连不上 GitHub」
     * 只是在没网的时候每次开应用都骂他一句。
     */
    fun checkOnLaunch() {
        if (launchChecked) return
        launchChecked = true
        if (!prefs.autoCheckUpdate.value) return
        check(silent = true)
    }

    /** 设置里手动点。无视自动开关，结果（包括失败）都要说出来。 */
    fun checkManually() = check(silent = false)

    private fun check(silent: Boolean) {
        if (checking) return
        checking = true
        if (!silent) _manual.value = ManualCheck.Running

        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { UpdateChecker.check(getApplication()) }
            } finally {
                // finally 而不是等 when 走完：协程被取消时也得放开，
                // 否则这个 VM 剩下的寿命里再也查不了更新。
                checking = false
            }

            when (result) {
                is UpdateResult.Available -> {
                    _pending.value = result.release
                    _manual.value = ManualCheck.Idle
                }
                UpdateResult.UpToDate ->
                    _manual.value = if (silent) ManualCheck.Idle else ManualCheck.UpToDate
                is UpdateResult.Failed ->
                    _manual.value =
                        if (silent) ManualCheck.Idle else ManualCheck.Failed(result.reason)
            }
        }
    }

    fun setAutoCheckUpdate(enabled: Boolean) = prefs.setAutoCheckUpdate(enabled)

    /**
     * 「稍后再说」。**只关这一次**，不记「跳过这个版本」——
     * 用户要的就是每次启动提醒，真嫌烦的出口是设置里那个开关。
     */
    fun dismiss() {
        _pending.value = null
    }

    /** 「立即更新」：把下载地址交给系统（浏览器/下载器），然后关掉弹窗。 */
    fun openDownload() {
        val url = _pending.value?.downloadUrl
        _pending.value = null
        if (url.isNullOrBlank()) return
        // 没装浏览器 / 被策略拦掉都会抛 ActivityNotFoundException，不值当崩掉界面
        runCatching {
            getApplication<Application>().startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
