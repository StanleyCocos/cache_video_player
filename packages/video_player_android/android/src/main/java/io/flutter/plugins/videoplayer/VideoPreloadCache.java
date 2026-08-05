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
  private static final String FALLBACK_TITLE = "未命名視頻";
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
    VideoLoadDebugLog.write("VideoPreloadCache", "buildFactory playerId=" + playerId);
    return new CacheDataSource.Factory()
        .setCache(simpleCache)
        .setCacheKeyFactory(CacheKeyFactory.DEFAULT)
        .setUpstreamDataSourceFactory(new DefaultDataSource.Factory(context, httpFactory))
        .setCacheWriteDataSinkFactory(
            new CacheDataSink.Factory().setCache(simpleCache).setFragmentSize(CACHE_FRAGMENT_BYTES));
  }

  /** 预加载视频首段。 */
  static void preload(
      @NonNull Context context,
      @Nullable String url,
      @Nullable String title,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent) {
    String videoUrl = url == null ? "" : url.trim();
    if (videoUrl.isEmpty()) {
      VideoLoadDebugLog.write("VideoPreloadCache", "preload ignored empty url");
      return;
    }
    registerTitle(videoUrl, title);
    VideoLoadDebugLog.write(
        "VideoPreloadCache",
        titleLabel(videoUrl) + "開始下載 urlHash=" + VideoLoadDebugLog.urlHash(videoUrl));
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
    VideoLoadDebugLog.write(
        "VideoPreloadCache", "syncQueue visibleCount=" + visibleUrls.size());
    synchronized (LOCK) {
      registerTitlesLocked(incomingTitlesByUrl);
      queuedUrls.removeIf(
          queuedUrl -> {
            return !visibleUrls.contains(queuedUrl);
          });
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
          VideoLoadDebugLog.write(
              "VideoPreloadCache",
              titleLabelLocked(visibleUrl)
                  + "已滿緩存 cachedBytes="
                  + cachedBytes
                  + " urlHash="
                  + VideoLoadDebugLog.urlHash(visibleUrl));
          queuedUrls.remove(visibleUrl);
          continue;
        }
        if (activePreloads.containsKey(visibleUrl) || queuedUrls.contains(visibleUrl)) {
          continue;
        }
        queuedUrls.add(visibleUrl);
      }
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
      VideoLoadDebugLog.write("VideoPreloadCache", "prioritize ignored empty url");
      return;
    }
    registerTitle(videoUrl, title);
    VideoLoadDebugLog.write(
        "VideoPreloadCache",
        titleLabel(videoUrl) + "點擊播放 urlHash=" + VideoLoadDebugLog.urlHash(videoUrl));
    synchronized (LOCK) {
      long cachedBytes = cachedBytes(context, videoUrl);
      if (cachedBytes >= EFFECTIVE_BYTES) {
        VideoLoadDebugLog.write(
            "VideoPreloadCache",
            titleLabelLocked(videoUrl)
                + "點擊播放命中緩存 cachedBytes="
                + cachedBytes
                + " urlHash="
                + VideoLoadDebugLog.urlHash(videoUrl));
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
    VideoLoadDebugLog.write("VideoPreloadCache", "cancel reason=" + reason);
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
      VideoLoadDebugLog.write("VideoPreloadCache", "cachedBytes ignored empty url");
      return 0;
    }
    long bytes = getCache(context).getCachedBytes(videoUrl, 0, PRELOAD_BYTES);
    VideoLoadDebugLog.write(
        "VideoPreloadCache",
        titleLabel(videoUrl)
            + "緩存查詢 bytes="
            + bytes
            + " urlHash="
            + VideoLoadDebugLog.urlHash(videoUrl));
    return bytes;
  }

  private static void preloadOnExecutor(
      @NonNull Context context,
      @NonNull Map<String, String> httpHeaders,
      @Nullable String userAgent,
      @NonNull ActivePreload activePreload) {
    String url = activePreload.url;
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
      VideoLoadDebugLog.write(
          "VideoPreloadCache", titleLabel(url) + "開始下載 urlHash=" + VideoLoadDebugLog.urlHash(url));
      writer.cache();
      VideoLoadDebugLog.write(
          "VideoPreloadCache",
          titleLabel(url)
              + "下載完成 cachedBytes="
              + cachedBytes(context, url)
              + " urlHash="
              + VideoLoadDebugLog.urlHash(url));
    } catch (InterruptedIOException ignored) {
      VideoLoadDebugLog.write(
          "VideoPreloadCache", titleLabel(url) + "下載取消 urlHash=" + VideoLoadDebugLog.urlHash(url));
    } catch (IOException error) {
      VideoLoadDebugLog.warn(
          "VideoPreloadCache",
          titleLabel(url)
              + "下載失敗 error="
              + error.getClass().getSimpleName()
              + " cachedBytesAfter="
              + cachedBytes(context, url)
              + " urlHash="
              + VideoLoadDebugLog.urlHash(url));
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
      ActivePreload activePreload = new ActivePreload(nextUrl);
      activePreloads.put(nextUrl, activePreload);
      VideoLoadDebugLog.write(
          "VideoPreloadCache",
          titleLabelLocked(nextUrl)
              + "加入下載 active="
              + activePreloads.size()
              + " queued="
              + queuedUrls.size()
              + " urlHash="
              + VideoLoadDebugLog.urlHash(nextUrl));
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
        VideoLoadDebugLog.write(
            "VideoPreloadCache",
            titleLabelLocked(url)
                + "跳過下載 已滿緩存 cachedBytes="
                + cachedBytes
                + " urlHash="
                + VideoLoadDebugLog.urlHash(url));
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
  }

  @NonNull
  static String titleOf(@Nullable String url) {
    String videoUrl = url == null ? "" : url.trim();
    if (videoUrl.isEmpty()) {
      return FALLBACK_TITLE;
    }
    synchronized (LOCK) {
      String title = titlesByUrl.get(videoUrl);
      return title == null || title.trim().isEmpty() ? FALLBACK_TITLE : title;
    }
  }

  @NonNull
  static String titleLabel(@Nullable String url) {
    return "《" + titleOf(url) + "》";
  }

  static void registerTitle(@Nullable String url, @Nullable String title) {
    String videoUrl = url == null ? "" : url.trim();
    if (videoUrl.isEmpty()) {
      return;
    }
    String cleanTitle = title == null ? "" : title.trim();
    synchronized (LOCK) {
      if (cleanTitle.isEmpty() && titlesByUrl.containsKey(videoUrl)) {
        return;
      }
      titlesByUrl.put(videoUrl, cleanTitle.isEmpty() ? FALLBACK_TITLE : cleanTitle);
    }
  }

  private static void registerTitlesLocked(@NonNull Map<String, String> incomingTitlesByUrl) {
    for (Map.Entry<String, String> entry : incomingTitlesByUrl.entrySet()) {
      String url = entry.getKey() == null ? "" : entry.getKey().trim();
      if (url.isEmpty()) {
        continue;
      }
      String title = entry.getValue() == null ? "" : entry.getValue().trim();
      titlesByUrl.put(url, title.isEmpty() ? FALLBACK_TITLE : title);
    }
  }

  @NonNull
  private static String titleLabelLocked(@NonNull String url) {
    String title = titlesByUrl.get(url);
    return "《" + (title == null || title.trim().isEmpty() ? FALLBACK_TITLE : title) + "》";
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
    @Nullable CacheWriter writer;

    ActivePreload(@NonNull String url) {
      this.url = url;
    }
  }
}
