package io.flutter.plugins.videoplayer;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** 视频加载调试日志。 */
final class VideoLoadDebugLog {
  private static final String TAG = "DuyoVideoLoad";
  private static boolean enabled = false;

  private VideoLoadDebugLog() {}

  /** 设置日志开关。 */
  static void setEnabled(boolean value) {
    enabled = value;
    Log.d(TAG, "setLogEnabled enabled=" + value);
  }

  /** 输出调试日志。 */
  static void write(@NonNull String scope, @NonNull String message) {
    if (!enabled) {
      return;
    }
    Log.d(TAG, "[" + scope + "] " + message);
  }

  /** 输出警告日志。 */
  static void warn(@NonNull String scope, @NonNull String message) {
    if (!enabled) {
      return;
    }
    Log.w(TAG, "[" + scope + "] " + message);
  }

  /** 返回不暴露完整地址的 URL 标识。 */
  static String urlHash(@Nullable String url) {
    if (url == null) {
      return "00000000";
    }
    long hash = 0x811c9dc5L;
    for (int i = 0; i < url.length(); i++) {
      hash ^= url.charAt(i);
      hash = (hash * 0x01000193L) & 0xffffffffL;
    }
    String value = Long.toHexString(hash);
    return ("00000000" + value).substring(value.length());
  }
}
