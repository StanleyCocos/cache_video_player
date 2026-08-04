// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.datasource.DataSpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 视频加载链路日志。 */
public final class VideoLoadTrace {
  private static final String TAG = "VideoLoadTrace";
  private static final Map<Long, Long> START_MS_BY_PLAYER_ID = new HashMap<>();
  private final long playerId;
  private final long startMs;

  public VideoLoadTrace(long playerId) {
    this.playerId = playerId;
    Long storedStartMs = START_MS_BY_PLAYER_ID.get(playerId);
    this.startMs = storedStartMs == null ? System.currentTimeMillis() : storedStartMs;
  }

  /** 开始记录播放器链路。 */
  static VideoLoadTrace start(long playerId) {
    START_MS_BY_PLAYER_ID.put(playerId, System.currentTimeMillis());
    return new VideoLoadTrace(playerId);
  }

  /** 结束播放器链路。 */
  static void end(long playerId) {
    START_MS_BY_PLAYER_ID.remove(playerId);
  }

  /** 输出事件日志。 */
  public void log(@NonNull String event) {
    log(event, "");
  }

  /** 输出事件日志。 */
  public void log(@NonNull String event, @NonNull String fields) {
    long elapsedMs = System.currentTimeMillis() - startMs;
    String suffix = fields.isEmpty() ? "" : " " + fields;
    Log.d(TAG, "playerId=" + playerId + " +" + elapsedMs + "ms event=" + event + suffix);
  }

  /** 输出 URL 脱敏字段。 */
  static String urlFields(@Nullable String value) {
    String url = value == null ? "" : value.trim();
    Uri uri = Uri.parse(url);
    return "urlLength="
        + url.length()
        + " urlHash="
        + Math.abs(url.hashCode())
        + " scheme="
        + safe(uri.getScheme())
        + " host="
        + safe(uri.getHost())
        + " path="
        + safe(uri.getPath());
  }

  /** 输出 DataSpec 字段。 */
  static String dataSpecFields(@NonNull DataSpec dataSpec) {
    return urlFields(dataSpec.uri.toString())
        + " method="
        + dataSpec.getHttpMethodString()
        + " position="
        + dataSpec.position
        + " length="
        + dataSpec.length;
  }

  /** 输出响应头字段。 */
  static String responseHeaderFields(@NonNull Map<String, List<String>> headers) {
    return "contentLength="
        + header(headers, "content-length")
        + " contentType="
        + header(headers, "content-type")
        + " acceptRanges="
        + header(headers, "accept-ranges")
        + " contentRange="
        + header(headers, "content-range")
        + " xCache="
        + header(headers, "x-cache")
        + " age="
        + header(headers, "age")
        + " server="
        + header(headers, "server");
  }

  private static String header(@NonNull Map<String, List<String>> headers, @NonNull String name) {
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      String key = entry.getKey();
      if (key == null || !key.equalsIgnoreCase(name)) {
        continue;
      }
      List<String> values = entry.getValue();
      if (values == null || values.isEmpty()) {
        return "";
      }
      return safe(values.get(0));
    }
    return "";
  }

  private static String safe(@Nullable String value) {
    return value == null ? "" : value.replace(' ', '_');
  }
}
