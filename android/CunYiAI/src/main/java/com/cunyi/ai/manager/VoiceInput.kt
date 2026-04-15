package com.cunyi.ai.manager

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/**
 * 语音输入工具 - 使用系统语音识别 Activity
 * 比 SpeechRecognizer 更稳定可靠
 */

/**
 * 在 Composable 中注册语音识别 launcher
 * 支持录音权限检查和错误回调
 */
@Composable
fun rememberVoiceInputLauncher(
    onResult: (String) -> Unit,
    onError: (String) -> Unit
): ActivityResultLauncher<Intent> {
    val context = LocalContext.current
    return androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when (result.resultCode) {
            Activity.RESULT_OK -> {
                val results = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val text = results?.firstOrNull() ?: ""
                if (text.isNotBlank()) {
                    onResult(text)
                }
            }
            Activity.RESULT_CANCELED -> {
                // 用户主动取消，不提示
            }
            else -> {
                // 识别失败，给出友好提示
                val errorMsg = when (result.resultCode) {
                    -1 -> "语音识别服务不可用，请检查是否安装了 Google 语音服务"
                    -2 -> "网络连接失败，请检查网络后重试"
                    -3 -> "音频录制失败，请检查麦克风是否正常"
                    -4 -> "语音识别超时，请重试"
                    -5 -> "设备不支持语音识别"
                    -6 -> "缺少录音权限，请在设置中开启麦克风权限"
                    else -> "语音识别失败（错误码: ${result.resultCode}），请使用文字输入"
                }
                onError(errorMsg)
            }
        }
    }
}

/**
 * 创建语音识别 Intent
 */
fun createVoiceIntent(): Intent {
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh")
        putExtra(RecognizerIntent.EXTRA_PROMPT, "请说出您的问题")
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // 添加备用语言
        putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, "zh-CN,zh,en-US,en")
    }
}

/**
 * 检查设备是否支持语音识别
 */
fun isSpeechRecognitionAvailable(context: android.content.Context): Boolean {
    val pm = context.packageManager
    return pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
}

/**
 * 检查录音权限是否已授予
 */
fun hasAudioPermission(context: android.content.Context): Boolean {
    return context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
}
