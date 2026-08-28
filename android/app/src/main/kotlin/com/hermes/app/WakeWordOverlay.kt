package com.hermes.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * 빅스비 "듣고 있어요" 스타일 미니 오버레이. [WakeWordService]가 웨이크워드 감지 후
 * 헤드리스 STT를 시작할 때 [show], 결과/에러/타임아웃 시 [hide] — 액티비티를 전혀 안 띄우고
 * 다른 앱 위에 작은 알림만 겹쳐 보여준다.
 *
 * `ComposeView` 대신 일반 [TextView]로 만든다 — 액티비티 밖에서 Compose를 쓰려면
 * `LifecycleOwner`/`ViewModelStoreOwner`/`SavedStateRegistryOwner`를 직접 배선해야 하는데,
 * 상호작용 전혀 없는 정적 텍스트 버블 하나에 그 정도 배선은 과함.
 */
class WakeWordOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var bubbleView: TextView? = null

    /** [android.provider.Settings.canDrawOverlays]가 false면 조용히 아무것도 안 띄우고
     * 리턴한다 — 오버레이는 있으면 좋은 것이지 헤드리스 STT 자체를 막는 필수 게이트가 아니다. */
    fun show() {
        if (bubbleView != null) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW 권한 없음 — 오버레이 생략, 헤드리스 STT는 계속 진행")
            return
        }
        val density = context.resources.displayMetrics.density
        val padH = (16 * density).toInt()
        val padV = (10 * density).toInt()
        val view = TextView(context).apply {
            text = "제우스가 듣고 있어요"
            setTextColor(Color.WHITE)
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.parseColor("#CC202020"))
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = padV * 6
        }
        runCatching { windowManager.addView(view, params) }
            .onSuccess { bubbleView = view }
            .onFailure { Log.w(TAG, "오버레이 addView 실패", it) }
    }

    fun hide() {
        bubbleView?.let { view -> runCatching { windowManager.removeView(view) } }
        bubbleView = null
    }

    companion object {
        private const val TAG = "WakeWordOverlay"
    }
}
