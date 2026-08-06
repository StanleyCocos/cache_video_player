package io.flutter.plugins.videoplayer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheKeyFactory;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 视频预加载磁盘缓存。 */
@OptIn(markerClass = UnstableApi.class)
final class VideoPreloadCache {
  static final long DEFAULT_PRELOAD_BYTES = 3L * 1024L * 1024L;
  static final long CACHE_MAX_BYTES = 200L * 1024L * 1024L;
  private static final int MAX_ACTIVE_PRELOADS = 2;
  private static final long CACHE_FRAGMENT_BYTES = 256L * 1024L;
  private static final int MAX_REMEMBERED_TITLES = 1000;
  private static final String TAG = "VideoLoad";
  private static final String UNTITLED = "未命名贴文";
  private static final Object LOCK = new Object();
  private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(MAX_ACTIVE_PRELOADS);
  @Nullable private static SimpleCache cache;
  private static final LinkedHashMap<String, ActivePreload> activePreloads = new LinkedHashMap<>();
  private static final LinkedHashSet<String> queuedUrls = new LinkedHashSet<>();
  private static final LinkedHashMap<String, Long> queuedPreloadBytesByUrl = new LinkedHashMap<>();
  private static final LinkedHashMap<String, String> titlesByUrl = new LinkedHashMap<>();
  private static final LinkedHashMap<Long, Long> playerStartMsById = new LinkedHashMap<>();
  private static boolean debugEnabled;

  private VideoPreloadCache() {}

  /** 创建播放用缓存数据源。 */
  @NonNull
  static DataSource.Factory buildFactory(
      @NonNull Context context,
      @NonNull DefaultHttpDataSource.Factory httpFactory,
      long playerId) {
    return buildFactory(context, httpFactory, playerId, null, false);
  }

  /** 创建可记录播放缓存命中的数据源。 */
  @NonNull
  static DataSource.Factory buildFactory(
      @NonNull Context context,
      @NonNull DefaultHttpDataSource.Factory httpFactory,
      long playerId,
      @Nullable String url,
      boolean logPlayerCacheRead) {
    updateDebugEnabled(context);
    boolean shouldLogPlayerCacheRead = debugEnabled && logPlayerCacheRead && url != null;
    SimpleCache simpleCache = getCache(context);
    DataSource.Factory upstreamFactory = new DefaultDataSource.Factory(context, httpFactory);
    DataSource.Factory playerUpstreamFactory =
        shouldLogPlayerCacheRead
            ? () -> new LoggingDataSource(upstreamFactory.createDataSource(), playerId, url.trim())
            : upstreamFactory;
    CacheDataSource.Factory factory =
        new CacheDataSource.Factory()
        .setCache(simpleCache)
        .setCacheKeyFactory(CacheKeyFactory.DEFAULT)
        .setUpstreamDataSourceFactory(playerUpstreamFactory)
        .setCacheWriteDataSinkFactory(
            new CacheDataSink.Factory().setCache(simpleCache).setFragmentSize(CACHE_FRAGMENT_BYTES));
    if (shouldLogPlayerCacheRead) {
      String videoUrl = url.trim();
      factory.setEventListener(
          new CacheDataSource.EventListener() {
            @Override
            public void onCachedBytesRead(long cacheSizeBytes, long cachedBytesRead) {
              logPlayerCacheRead(playerId, videoUrl, cacheSizeBytes, cachedBytesRead);
            }

            @Override
            public void onCacheIgnored(int reason) {}
          });
    }
    return factory;
  }

  /** 预加载视频首段。 */
  static void preload(
      @NonNull Context context,
      @Nullable String url,
      @Nullable String title,
      long preloadBytes,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent) {
    String videoUrl = url == null ? "" : url.trim();
    if (videoUrl.isEmpty()) {
      return;
    }
    List<String> urls = new ArrayList<>();
    urls.add(videoUrl);
    LinkedHashMap<String, String> titles = new LinkedHashMap<>();
    titles.put(videoUrl, title == null ? "" : title);
    syncQueue(context, urls, titles, preloadBytes, httpHeaders, userAgent);
  }

  /** 同步当前可见视频预加载队列。 */
  static void syncQueue(
      @NonNull Context context,
      @NonNull List<String> urls,
      @NonNull Map<String, String> incomingTitlesByUrl,
      long preloadBytes,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent) {
    LinkedHashSet<String> visibleUrls = sanitizeUrls(urls);
    long requestedPreloadBytes = normalizePreloadBytes(preloadBytes);
    updateDebugEnabled(context);
    synchronized (LOCK) {
      rememberTitlesLocked(incomingTitlesByUrl);
      queuedUrls.clear();
      queuedPreloadBytesByUrl.clear();
      List<String> activeUrls = new ArrayList<>(activePreloads.keySet());
      for (String activeUrl : activeUrls) {
        ActivePreload activePreload = activePreloads.get(activeUrl);
        if (!visibleUrls.contains(activeUrl)) {
          cancelActiveLocked(activeUrl, "离屏");
        } else if (activePreload != null && activePreload.preloadBytes != requestedPreloadBytes) {
          cancelActiveLocked(activeUrl, "缓存大小切换");
        }
      }
      for (String visibleUrl : visibleUrls) {
        long cachedBytes = cachedBytes(context, visibleUrl, requestedPreloadBytes);
        if (cachedBytes >= requestedPreloadBytes) {
          queuedUrls.remove(visibleUrl);
          queuedPreloadBytesByUrl.remove(visibleUrl);
          continue;
        }
        if (activePreloads.containsKey(visibleUrl) || queuedUrls.contains(visibleUrl)) {
          continue;
        }
        queuedUrls.add(visibleUrl);
        queuedPreloadBytesByUrl.put(visibleUrl, requestedPreloadBytes);
      }
      startNextLocked(context, httpHeaders, userAgent);
    }
  }

  /** 点击视频时停止其它队列，只保留正在预加载的同一个 URL。 */
  static void prioritize(
      @NonNull Context context,
      @Nullable String url,
      @Nullable String title,
      long preloadBytes,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent) {
    String videoUrl = url == null ? "" : url.trim();
    if (videoUrl.isEmpty()) {
      return;
    }
    long requestedPreloadBytes = normalizePreloadBytes(preloadBytes);
    updateDebugEnabled(context);
    synchronized (LOCK) {
      rememberTitleLocked(videoUrl, title);
      long cachedBytes = cachedBytes(context, videoUrl, requestedPreloadBytes);
      logCacheStateLocked(videoUrl, cachedBytes);
      if (cachedBytes >= Math.min(512L * 1024L, requestedPreloadBytes)) {
        return;
      }
      queuedUrls.clear();
      queuedPreloadBytesByUrl.clear();
      List<String> activeUrls = new ArrayList<>(activePreloads.keySet());
      for (String activeUrl : activeUrls) {
        if (!videoUrl.equals(activeUrl)) {
          cancelActiveLocked(activeUrl, "点击切换");
        }
      }
    }
  }

  /** 取消当前预加载。 */
  static void cancel(@NonNull String reason) {
    synchronized (LOCK) {
      queuedUrls.clear();
      cancelAllActiveLocked(reason);
    }
  }

  /** 清空当前预加载队列。 */
  static void clear(@NonNull String reason) {
    cancel(reason);
  }

  /** 查询首段缓存字节数。 */
  static long cachedBytes(@NonNull Context context, @Nullable String url) {
    return cachedBytes(context, url, DEFAULT_PRELOAD_BYTES);
  }

  /** 查询指定首段大小下的缓存字节数。 */
  static long cachedBytes(@NonNull Context context, @Nullable String url, long preloadBytes) {
    String videoUrl = url == null ? "" : url.trim();
    if (videoUrl.isEmpty()) {
      return 0;
    }
    long bytes = getCache(context).getCachedBytes(videoUrl, 0, normalizePreloadBytes(preloadBytes));
    return bytes;
  }

  private static void preloadOnExecutor(
      @NonNull Context context,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent,
      @NonNull ActivePreload activePreload) {
    String url = activePreload.url;
    long preloadBytes = activePreload.preloadBytes;
    CacheWriter writer =
        new CacheWriter(
            (CacheDataSource)
                buildFactory(context, buildHttpFactory(httpHeaders, userAgent), 0)
                    .createDataSource(),
            new DataSpec.Builder().setUri(url).setPosition(0).setLength(preloadBytes).build(),
            null,
            null);
    synchronized (LOCK) {
      if (activePreloads.get(url) != activePreload) {
        return;
      }
      activePreload.writer = writer;
    }
    try {
      writer.cache();
      logPreloadFinished(
          url, cachedBytes(context, url, preloadBytes), activePreload.elapsedMs());
    } catch (InterruptedIOException ignored) {
    } catch (IOException error) {
    } finally {
      synchronized (LOCK) {
        if (activePreloads.get(url) == activePreload) {
          activePreloads.remove(url);
          startNextLocked(context, httpHeaders, userAgent);
        }
      }
    }
  }

  private static void startNextLocked(
      @NonNull Context context,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent) {
    while (activePreloads.size() < MAX_ACTIVE_PRELOADS) {
      String nextUrl = pollNextUncachedLocked(context);
      if (nextUrl == null) {
        return;
      }
      long preloadBytes = normalizePreloadBytes(queuedPreloadBytesByUrl.remove(nextUrl));
      ActivePreload activePreload = new ActivePreload(nextUrl, preloadBytes);
      activePreloads.put(nextUrl, activePreload);
      logPreloadStarted(nextUrl);
      EXECUTOR.execute(() -> preloadOnExecutor(context, httpHeaders, userAgent, activePreload));
    }
  }

  @Nullable
  private static String pollNextUncachedLocked(@NonNull Context context) {
    while (!queuedUrls.isEmpty()) {
      String url = queuedUrls.iterator().next();
      queuedUrls.remove(url);
      long preloadBytes = normalizePreloadBytes(queuedPreloadBytesByUrl.get(url));
      long cachedBytes = cachedBytes(context, url, preloadBytes);
      if (cachedBytes >= preloadBytes) {
        queuedPreloadBytesByUrl.remove(url);
        continue;
      }
      return url;
    }
    return null;
  }

  @NonNull
  private static SimpleCache getCache(@NonNull Context context) {
    synchronized (LOCK) {
      if (cache == null) {
        File cacheDir = new File(context.getCacheDir(), "duyo_video_cache");
        cache =
            new SimpleCache(
                cacheDir,
                new LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES),
                new StandaloneDatabaseProvider(context));
      }
      return cache;
    }
  }

  @NonNull
  private static DefaultHttpDataSource.Factory buildHttpFactory(
      @NonNull Map<String, String> httpHeaders, @Nullable String userAgent) {
    DefaultHttpDataSource.Factory factory =
        new DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true);
    if (!httpHeaders.isEmpty()) {
      factory.setDefaultRequestProperties(httpHeaders);
    }
    return factory;
  }

  private static void cancelAllActiveLocked(@NonNull String reason) {
    List<String> activeUrls = new ArrayList<>(activePreloads.keySet());
    for (String activeUrl : activeUrls) {
      cancelActiveLocked(activeUrl, reason);
    }
  }

  private static void cancelActiveLocked(@NonNull String url, @NonNull String reason) {
    ActivePreload activePreload = activePreloads.remove(url);
    queuedPreloadBytesByUrl.remove(url);
    if (activePreload == null) {
      return;
    }
    CacheWriter writer = activePreload.writer;
    if (writer != null) {
      writer.cancel();
    }
  }

  private static long normalizePreloadBytes(@Nullable Long preloadBytes) {
    return preloadBytes == null
        ? DEFAULT_PRELOAD_BYTES
        : normalizePreloadBytes(preloadBytes.longValue());
  }

  private static long normalizePreloadBytes(long preloadBytes) {
    return preloadBytes > 0 ? preloadBytes : DEFAULT_PRELOAD_BYTES;
  }

  private static void rememberTitlesLocked(@NonNull Map<String, String> incomingTitlesByUrl) {
    for (Map.Entry<String, String> entry : incomingTitlesByUrl.entrySet()) {
      rememberTitleLocked(entry.getKey(), entry.getValue());
    }
  }

  private static void updateDebugEnabled(@NonNull Context context) {
    debugEnabled = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
  }

  private static void rememberTitleLocked(@Nullable String url, @Nullable String title) {
    String videoUrl = url == null ? "" : url.trim();
    if (videoUrl.isEmpty()) {
      return;
    }
    titlesByUrl.put(videoUrl, normalizeTitle(title));
    trimRememberedTitlesLocked();
  }

  @NonNull
  private static String titleOfLocked(@NonNull String url) {
    String title = titlesByUrl.get(url);
    return normalizeTitle(title);
  }

  @NonNull
  private static String normalizeTitle(@Nullable String title) {
    String value = title == null ? "" : title.trim();
    return value.isEmpty() ? UNTITLED : value;
  }

  private static void logPreloadStarted(@NonNull String url) {
    if (!debugEnabled) {
      return;
    }
    synchronized (LOCK) {
      Log.d(TAG, "列表《" + titleOfLocked(url) + "》开始缓存");
    }
  }

  private static void trimRememberedTitlesLocked() {
    if (titlesByUrl.size() <= MAX_REMEMBERED_TITLES) {
      return;
    }
    LinkedHashSet<String> protectedUrls = new LinkedHashSet<>();
    protectedUrls.addAll(queuedUrls);
    protectedUrls.addAll(activePreloads.keySet());
    List<String> urls = new ArrayList<>(titlesByUrl.keySet());
    for (String url : urls) {
      if (!protectedUrls.contains(url)) {
        titlesByUrl.remove(url);
        if (titlesByUrl.size() <= MAX_REMEMBERED_TITLES) {
          return;
        }
      }
    }
  }

  private static void logPreloadFinished(@NonNull String url, long bytes, long costMs) {
    if (!debugEnabled) {
      return;
    }
    synchronized (LOCK) {
      Log.d(
          TAG,
          "缓存《" + titleOfLocked(url) + "》已经完成 bytes=" + bytes + " cost=" + costMs + "ms");
    }
  }

  private static void logCacheStateLocked(@NonNull String url, long bytes) {
    if (!debugEnabled) {
      return;
    }
    String state = bytes > 0 ? "有缓存" : "没有缓存";
    Log.d(TAG, "《" + titleOfLocked(url) + "》" + state + " bytes=" + bytes);
  }

  private static void logPlayerCacheRead(
      long playerId, @NonNull String url, long cacheSizeBytes, long bytes) {
    if (!debugEnabled || bytes <= 0) {
      return;
    }
    synchronized (LOCK) {
      Log.d(
          TAG,
          "《"
              + titleOfLocked(url)
              + "》播放器读取了缓存 bytes="
              + bytes
              + " cacheSizeBytes="
              + cacheSizeBytes
              + " playerId="
              + playerId
              + " sinceCreate="
              + sinceCreateTextLocked(playerId));
    }
  }

  private static void logPlayerNetworkEvent(
      long playerId, @NonNull String url, @NonNull String message) {
    if (!debugEnabled) {
      return;
    }
    synchronized (LOCK) {
      Log.d(
          TAG,
          "《"
              + titleOfLocked(url)
              + "》播放器网络 "
              + message
              + " playerId="
              + playerId
              + " sinceCreate="
              + sinceCreateTextLocked(playerId));
    }
  }

  static long nowMs() {
    return SystemClock.elapsedRealtime();
  }

  static void rememberPlayerStart(long playerId, long startMs) {
    synchronized (LOCK) {
      playerStartMsById.put(playerId, startMs);
    }
  }

  static void forgetPlayerStart(long playerId) {
    synchronized (LOCK) {
      playerStartMsById.remove(playerId);
    }
  }

  static void logPlayerTimeline(
      long playerId, @Nullable String url, long startMs, @NonNull String message) {
    if (!debugEnabled) {
      return;
    }
    String videoUrl = url == null ? "" : url.trim();
    long elapsedMs = SystemClock.elapsedRealtime() - startMs;
    synchronized (LOCK) {
      Log.d(
          TAG,
          "《"
              + titleOfLocked(videoUrl)
              + "》ExoPlayer "
              + message
              + " playerId="
              + playerId
              + " sinceCreate="
              + elapsedMs
              + "ms");
    }
  }

  @NonNull
  private static String sinceCreateTextLocked(long playerId) {
    Long startMs = playerStartMsById.get(playerId);
    if (startMs == null || startMs <= 0) {
      return "unknown";
    }
    return (SystemClock.elapsedRealtime() - startMs) + "ms";
  }

  @NonNull
  private static LinkedHashSet<String> sanitizeUrls(@NonNull List<String> urls) {
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String url : urls) {
      if (url == null) {
        continue;
      }
      String videoUrl = url.trim();
      if (!videoUrl.isEmpty()) {
        result.add(videoUrl);
      }
    }
    return result;
  }

  private static final class ActivePreload {
    @NonNull final String url;
    final long preloadBytes;
    final long startedMs;
    @Nullable CacheWriter writer;

    ActivePreload(@NonNull String url, long preloadBytes) {
      this.url = url;
      this.preloadBytes = preloadBytes;
      startedMs = SystemClock.elapsedRealtime();
    }

    long elapsedMs() {
      return SystemClock.elapsedRealtime() - startedMs;
    }
  }

  private static final class LoggingDataSource implements DataSource {
    @NonNull private final DataSource upstream;
    private final long playerId;
    @NonNull private final String url;
    private long openStartedMs;
    private long totalBytesRead;
    private boolean loggedFirstByte;

    LoggingDataSource(@NonNull DataSource upstream, long playerId, @NonNull String url) {
      this.upstream = upstream;
      this.playerId = playerId;
      this.url = url;
    }

    @Override
    public void addTransferListener(@NonNull TransferListener transferListener) {
      upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws IOException {
      openStartedMs = SystemClock.elapsedRealtime();
      totalBytesRead = 0;
      loggedFirstByte = false;
      logPlayerNetworkEvent(
          playerId,
          url,
          "open 开始 range position=" + dataSpec.position + " length=" + dataSpec.length);
      try {
        long resolvedLength = upstream.open(dataSpec);
        logPlayerNetworkEvent(
            playerId,
            url,
            "open 结束 cost="
                + (SystemClock.elapsedRealtime() - openStartedMs)
                + "ms resolvedLength="
                + resolvedLength);
        return resolvedLength;
      } catch (IOException error) {
        logPlayerNetworkEvent(
            playerId,
            url,
            "open 失败 cost="
                + (SystemClock.elapsedRealtime() - openStartedMs)
                + "ms error="
                + error.getClass().getSimpleName());
        throw error;
      }
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws IOException {
      int bytesRead = upstream.read(buffer, offset, length);
      if (bytesRead > 0) {
        totalBytesRead += bytesRead;
        if (!loggedFirstByte) {
          loggedFirstByte = true;
          logPlayerNetworkEvent(playerId, url, "first byte bytes=" + bytesRead);
        }
      }
      return bytesRead;
    }

    @Override
    @Nullable
    public Uri getUri() {
      return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
      return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
      try {
        upstream.close();
      } finally {
        long costMs = openStartedMs > 0 ? SystemClock.elapsedRealtime() - openStartedMs : 0;
        logPlayerNetworkEvent(
            playerId,
            url,
            "close totalBytes="
                + totalBytesRead
                + " cost="
                + costMs
                + "ms");
      }
    }
  }
}
