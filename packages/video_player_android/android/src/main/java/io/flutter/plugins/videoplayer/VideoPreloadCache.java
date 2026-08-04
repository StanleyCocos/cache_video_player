package io.flutter.plugins.videoplayer;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheKeyFactory;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
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
  static final long PRELOAD_BYTES = 1024L * 1024L;
  static final long EFFECTIVE_BYTES = 512L * 1024L;
  static final long PARTIAL_BYTES = 768L * 1024L;
  static final long CACHE_MAX_BYTES = 200L * 1024L * 1024L;
  private static final int MAX_ACTIVE_PRELOADS = 2;
  private static final long CACHE_FRAGMENT_BYTES = 256L * 1024L;
  private static final Object LOCK = new Object();
  private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(MAX_ACTIVE_PRELOADS);
  @Nullable private static SimpleCache cache;
  private static int nextGeneration;
  private static final LinkedHashMap<String, ActivePreload> activePreloads = new LinkedHashMap<>();
  private static final LinkedHashSet<String> queuedUrls = new LinkedHashSet<>();
  private static final LinkedHashMap<String, String> titlesByUrl = new LinkedHashMap<>();

  private VideoPreloadCache() {}

  /** 创建播放用缓存数据源。 */
  @NonNull
  static DataSource.Factory buildFactory(
      @NonNull Context context,
      @NonNull DefaultHttpDataSource.Factory httpFactory,
      long playerId) {
    SimpleCache simpleCache = getCache(context);
    return new CacheDataSource.Factory()
        .setCache(simpleCache)
        .setCacheKeyFactory(CacheKeyFactory.DEFAULT)
        .setUpstreamDataSourceFactory(new DefaultDataSource.Factory(context, httpFactory))
        .setCacheWriteDataSinkFactory(
            new CacheDataSink.Factory().setCache(simpleCache).setFragmentSize(CACHE_FRAGMENT_BYTES))
        .setEventListener(new TraceCacheEventListener(playerId));
  }

  /** 输出播放前首段缓存状态。 */
  static void logHitBeforePlay(@NonNull Context context, long playerId, @Nullable String url) {
    long bytes = cachedBytes(context, url);
    new VideoLoadTrace(playerId)
        .log(
            "native.cache.hitBeforePlay",
            "fullHit="
                + (bytes >= PRELOAD_BYTES)
                + " effectiveHit="
                + (bytes >= EFFECTIVE_BYTES)
                + " partialHit="
                + (bytes >= PARTIAL_BYTES)
                + " 消息="
                + videoLabel(url)
                + "播放"
                + (bytes >= EFFECTIVE_BYTES ? "命中緩存" : "未命中緩存")
                + " cachedBytes="
                + bytes
                + " requiredBytes="
                + PRELOAD_BYTES
                + " effectiveRequiredBytes="
                + EFFECTIVE_BYTES
                + " partialRequiredBytes="
                + PARTIAL_BYTES
                + " "
                + VideoLoadTrace.urlFields(url));
  }

  /** 预加载视频首段。 */
  static void preload(
      @NonNull Context context,
      @Nullable String url,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent) {
    String videoUrl = url == null ? "" : url.trim();
    if (videoUrl.isEmpty()) {
      return;
    }
    List<String> urls = new ArrayList<>();
    urls.add(videoUrl);
    syncQueue(context, urls, new LinkedHashMap<>(), httpHeaders, userAgent);
  }

  /** 同步当前可见视频预加载队列。 */
  static void syncQueue(
      @NonNull Context context,
      @NonNull List<String> urls,
      @NonNull Map<String, String> incomingTitlesByUrl,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent) {
    LinkedHashSet<String> visibleUrls = sanitizeUrls(urls);
    synchronized (LOCK) {
      syncTitlesLocked(visibleUrls, incomingTitlesByUrl);
      LinkedHashSet<String> previousQueuedUrls = new LinkedHashSet<>(queuedUrls);
      queuedUrls.removeIf(
          queuedUrl -> {
            boolean removed = !visibleUrls.contains(queuedUrl);
            if (removed) {
              logQueueUrl("preload.queue.remove", videoLabel(queuedUrl) + "離開隊列 原因=离屏", queuedUrl);
            }
            return removed;
          });
      previousQueuedUrls.retainAll(queuedUrls);
      queuedUrls.clear();
      List<String> activeUrls = new ArrayList<>(activePreloads.keySet());
      for (String activeUrl : activeUrls) {
        if (!visibleUrls.contains(activeUrl)) {
          cancelActiveLocked(activeUrl, "离屏");
        }
      }
      for (String visibleUrl : visibleUrls) {
        long cachedBytes = cachedBytes(context, visibleUrl);
        if (cachedBytes >= PRELOAD_BYTES) {
          logQueueUrl(
              "preload.queue.skipCached",
              videoLabel(visibleUrl) + "已緩存 已缓存字节=" + cachedBytes,
              visibleUrl);
          queuedUrls.remove(visibleUrl);
          continue;
        }
        if (activePreloads.containsKey(visibleUrl) || queuedUrls.contains(visibleUrl)) {
          continue;
        }
        queuedUrls.add(visibleUrl);
        if (!previousQueuedUrls.contains(visibleUrl)) {
          logQueueUrl("preload.queue.add", videoLabel(visibleUrl) + "進入隊列", visibleUrl);
        }
      }
      new VideoLoadTrace(0)
          .log(
              "preload.queue.sync",
              "可见视频数="
                  + visibleUrls.size()
                  + " 等待数="
                  + queuedUrls.size()
                  + " active数="
                  + activePreloads.size()
                  + " active队列="
                  + shortLabels(activePreloads.keySet())
                  + " 可见视频="
                  + shortLabels(visibleUrls)
                  + " 等待队列="
                  + shortLabels(queuedUrls));
      startNextLocked(context, httpHeaders, userAgent);
    }
  }

  /** 点击视频时停止其它队列，只保留正在预加载的同一个 URL。 */
  static void prioritize(
      @NonNull Context context,
      @Nullable String url,
      @Nullable String title,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent) {
    String videoUrl = url == null ? "" : url.trim();
    if (videoUrl.isEmpty()) {
      return;
    }
    synchronized (LOCK) {
      putTitleLocked(videoUrl, title);
      long cachedBytes = cachedBytes(context, videoUrl);
      logQueueUrl(
          "preload.queue.prioritize",
          videoLabel(videoUrl)
              + "被點擊 effectiveHit="
              + (cachedBytes >= EFFECTIVE_BYTES)
              + " 已缓存字节="
              + cachedBytes,
          videoUrl);
      if (cachedBytes >= EFFECTIVE_BYTES) {
        return;
      }
      queuedUrls.clear();
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
    String videoUrl = url == null ? "" : url.trim();
    if (videoUrl.isEmpty()) {
      return 0;
    }
    return getCache(context).getCachedBytes(videoUrl, 0, PRELOAD_BYTES);
  }

  private static void preloadOnExecutor(
      @NonNull Context context,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent,
      @NonNull ActivePreload activePreload) {
    String url = activePreload.url;
    long startMs = System.currentTimeMillis();
    CacheWriter writer =
        new CacheWriter(
            (CacheDataSource)
                buildFactory(context, buildHttpFactory(httpHeaders, userAgent), 0)
                    .createDataSource(),
            new DataSpec.Builder().setUri(url).setPosition(0).setLength(PRELOAD_BYTES).build(),
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
      long cachedBytes = cachedBytes(context, url);
      new VideoLoadTrace(0)
          .log(
              "preload.end",
              "generation="
                  + activePreload.generation
                  + " "
                  + videoLabel(url)
                  + "緩存完成"
                  + " effectiveHit="
                  + (cachedBytes >= EFFECTIVE_BYTES)
                  + " bytes="
                  + Math.min(cachedBytes, PRELOAD_BYTES)
                  + " cachedBytesAfter="
                  + cachedBytes
                  + " durationMs="
                  + (System.currentTimeMillis() - startMs)
                  + " "
                  + VideoLoadTrace.urlFields(url));
    } catch (InterruptedIOException error) {
      long cachedBytes = cachedBytes(context, url);
      new VideoLoadTrace(0)
          .log(
              "preload.cancelled",
              "generation="
                  + activePreload.generation
                  + " "
                  + videoLabel(url)
                  + "緩存已取消"
                  + " 已缓存字节="
                  + cachedBytes
                  + " "
                  + VideoLoadTrace.urlFields(url));
    } catch (IOException error) {
      long cachedBytes = cachedBytes(context, url);
      new VideoLoadTrace(0)
          .log(
              "preload.error",
              "generation="
                  + activePreload.generation
                  + " "
                  + videoLabel(url)
                  + "緩存失敗"
                  + " error="
                  + error.getClass().getSimpleName()
                  + " cachedBytesAfter="
                  + cachedBytes
                  + " "
                  + VideoLoadTrace.urlFields(url));
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
      ActivePreload activePreload = new ActivePreload(nextUrl, ++nextGeneration);
      activePreloads.put(nextUrl, activePreload);
      long cachedBytes = cachedBytes(context, nextUrl);
      new VideoLoadTrace(0)
          .log(
              "preload.start",
              "generation="
                  + activePreload.generation
                  + " "
                  + videoLabel(nextUrl)
                  + "開始緩存"
                  + " range=bytes=0-1048575 已缓存字节="
                  + cachedBytes
                  + " "
                  + VideoLoadTrace.urlFields(nextUrl));
      EXECUTOR.execute(() -> preloadOnExecutor(context, httpHeaders, userAgent, activePreload));
    }
  }

  @Nullable
  private static String pollNextUncachedLocked(@NonNull Context context) {
    while (!queuedUrls.isEmpty()) {
      String url = queuedUrls.iterator().next();
      queuedUrls.remove(url);
      long cachedBytes = cachedBytes(context, url);
      if (cachedBytes >= PRELOAD_BYTES) {
        logQueueUrl(
            "preload.queue.skipCached",
            videoLabel(url) + "已緩存 已缓存字节=" + cachedBytes,
            url);
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
    if (activePreload == null) {
      return;
    }
    CacheWriter writer = activePreload.writer;
    if (writer != null) {
      writer.cancel();
    }
    new VideoLoadTrace(0)
        .log(
            "preload.cancel",
            "generation="
                + activePreload.generation
                + " "
                + videoLabel(url)
                + "緩存取消"
                + " 原因="
                + readableReason(reason)
                + " "
                + VideoLoadTrace.urlFields(url));
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

  private static void logQueueUrl(
      @NonNull String event, @NonNull String fields, @NonNull String url) {
    new VideoLoadTrace(0).log(event, fields + " " + VideoLoadTrace.urlFields(url));
  }

  private static void syncTitlesLocked(
      @NonNull LinkedHashSet<String> visibleUrls,
      @NonNull Map<String, String> incomingTitlesByUrl) {
    titlesByUrl.keySet().removeIf(
        url ->
            !visibleUrls.contains(url)
                && !queuedUrls.contains(url)
                && !activePreloads.containsKey(url));
    for (String url : visibleUrls) {
      putTitleLocked(url, incomingTitlesByUrl.get(url));
    }
  }

  private static void putTitleLocked(@NonNull String url, @Nullable String title) {
    String cleanTitle = title == null ? "" : title.trim().replaceAll("\\s+", " ");
    if (!cleanTitle.isEmpty()) {
      titlesByUrl.put(url, cleanTitle);
    }
  }

  @NonNull
  private static String videoLabel(@Nullable String url) {
    String title = url == null ? "" : titlesByUrl.getOrDefault(url, "");
    if (title.trim().isEmpty()) {
      title = shortPath(url);
    }
    return "《" + title + "》的視頻";
  }

  @NonNull
  private static String shortLabels(@NonNull Iterable<String> urls) {
    StringBuilder builder = new StringBuilder("[");
    boolean first = true;
    for (String url : urls) {
      if (!first) {
        builder.append(",");
      }
      builder.append(videoLabel(url)).append(":").append(shortPath(url));
      first = false;
    }
    return builder.append("]").toString();
  }

  @NonNull
  private static String shortPath(@Nullable String url) {
    if (url == null || url.trim().isEmpty()) {
      return "无";
    }
    try {
      String path = URI.create(url).getPath();
      return path == null || path.isEmpty() ? url : path;
    } catch (IllegalArgumentException error) {
      return url;
    }
  }

  @NonNull
  private static String readableReason(@NonNull String reason) {
    switch (reason) {
      case "dispose":
        return "销毁";
      case "emptyVisible":
        return "无可见视频";
      case "replace":
        return "替换";
      case "clear":
        return "清空";
      case "tap":
        return "点击";
      default:
        return reason;
    }
  }

  private static final class TraceCacheEventListener implements CacheDataSource.EventListener {
    private final VideoLoadTrace trace;

    TraceCacheEventListener(long playerId) {
      trace = new VideoLoadTrace(playerId);
    }

    @Override
    public void onCachedBytesRead(long cacheSizeBytes, long cachedBytesRead) {
    }

    @Override
    public void onCacheIgnored(int reason) {
      trace.log("native.cache.ignored", "原因=" + reason);
    }
  }

  private static final class ActivePreload {
    @NonNull final String url;
    final int generation;
    @Nullable CacheWriter writer;

    ActivePreload(@NonNull String url, int generation) {
      this.url = url;
      this.generation = generation;
    }
  }
}
