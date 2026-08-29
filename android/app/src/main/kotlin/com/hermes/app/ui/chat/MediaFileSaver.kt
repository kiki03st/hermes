package com.hermes.app.ui.chat

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * 전체화면 뷰어의 다운로드/공유 버튼 구현. 둘 다 프레임워크 API에 직접 의존해서
 * Robolectric 없이는 유닛테스트 불가능하다(이 리포의 다른 프레임워크 밀착 코드,
 * 예: `ChatScreen.kt`의 `readSelectedFile`과 같은 처지) — 순수 로직은 [clampZoom]처럼
 * 이미 분리해뒀다.
 *
 * `minSdk 26`이지만 다운로드는 API 29+(스코프드 스토리지)만 지원한다 — 실제 대상 기기가
 * 전부 최신 Android라 구닥다리(`WRITE_EXTERNAL_STORAGE` + `MediaStore.Images.Media.DATA`)
 * 분기를 안 만든다(설계 문서 결정, YAGNI).
 */

/** [bytes]를 기기 갤러리(`Pictures/Hermes/`)에 저장한다. API 29 미만에서는 아무 것도
 * 안 하고 false를 돌려준다(위 설계 결정). */
fun saveImageToGallery(context: Context, filename: String, bytes: ByteArray): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false

    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Hermes")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
    values.clear()
    values.put(MediaStore.Images.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    return true
}

/** [bytes]를 `cacheDir/shared/`에 임시로 쓰고 `FileProvider`로 얻은 `content://` URI로
 * 시스템 공유 시트를 띄운다 — 다운로드(갤러리 저장)와 독립적, 저장 안 해도 바로
 * 공유된다(설계 문서 결정). */
fun shareImage(context: Context, filename: String, bytes: ByteArray) {
    val sharedDir = File(context.cacheDir, "shared").apply { mkdirs() }
    val file = File(sharedDir, filename)
    file.writeBytes(bytes)

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
