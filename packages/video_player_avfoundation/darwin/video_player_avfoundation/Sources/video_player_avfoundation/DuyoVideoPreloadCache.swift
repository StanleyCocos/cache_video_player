// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import CryptoKit
import Foundation

/// Duyo 视频预加载磁盘缓存。
final class DuyoVideoPreloadCache {
  static let shared = DuyoVideoPreloadCache()

  private static let preloadBytes: Int64 = 1_048_576
  private static let effectiveBytes: Int64 = 524_288
  private static let partialBytes: Int64 = 786_432
  private static let maxActivePreloads = 2
  private static let maxCacheBytes: Int64 = 200 * 1_024 * 1_024
  private static let fallbackTitle = "未命名視頻"

  private let lockQueue = DispatchQueue(label: "duyo.video.preload.cache")
  private let fileManager = FileManager.default
  private let cacheDirectory: URL
  private var queuedUrls: [String] = []
  private var activeTasks: [String: URLSessionDataTask] = [:]
  private var titlesByUrl: [String: String] = [:]

  private init() {
    let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first
      ?? URL(fileURLWithPath: NSTemporaryDirectory())
    cacheDirectory = caches.appendingPathComponent("duyo_video_preload", isDirectory: true)
    try? fileManager.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
  }

  /// 预加载单个视频。
  func preload(url: String?, title: String? = nil) {
    guard let videoUrl = sanitize(url) else {
      DuyoVideoLoadDebugLog.write("VideoPreloadCache", "preload ignored invalid url")
      return
    }
    registerTitle(url: videoUrl, title: title)
    DuyoVideoLoadDebugLog.write(
      "VideoPreloadCache",
      "\(titleLabel(url: videoUrl))開始下載 urlHash=\(DuyoVideoLoadDebugLog.urlHash(videoUrl))"
    )
    syncQueue(urls: [videoUrl], titlesByUrl: [videoUrl: titleOf(url: videoUrl)])
  }

  /// 同步当前可见视频预加载队列。
  func syncQueue(urls: [String], titlesByUrl: [String: String]) {
    let visibleUrls = sanitize(urls)
    DuyoVideoLoadDebugLog.write("VideoPreloadCache", "syncQueue visibleCount=\(visibleUrls.count)")
    lockQueue.async {
      self.registerTitlesLocked(titlesByUrl)
      self.queuedUrls.removeAll { url in
        !visibleUrls.contains(url)
      }
      for activeUrl in Array(self.activeTasks.keys) where !visibleUrls.contains(activeUrl) {
        self.cancelActive(url: activeUrl, reason: "离屏")
      }
      for visibleUrl in visibleUrls {
        let cachedBytes = self.cachedBytesLocked(url: visibleUrl)
        if cachedBytes >= Self.preloadBytes {
          DuyoVideoLoadDebugLog.write(
            "VideoPreloadCache",
            "\(self.titleLabelLocked(url: visibleUrl))已滿緩存 cachedBytes=\(cachedBytes) urlHash=\(DuyoVideoLoadDebugLog.urlHash(visibleUrl))"
          )
          continue
        }
        if self.activeTasks[visibleUrl] != nil || self.queuedUrls.contains(visibleUrl) {
          continue
        }
        self.queuedUrls.append(visibleUrl)
      }
      self.startNext()
    }
  }

  /// 点击视频时给未有效命中的当前视频让路。
  func prioritize(url: String?, title: String?) {
    guard let videoUrl = sanitize(url) else {
      DuyoVideoLoadDebugLog.write("VideoPreloadCache", "prioritize ignored invalid url")
      return
    }
    registerTitle(url: videoUrl, title: title)
    DuyoVideoLoadDebugLog.write(
      "VideoPreloadCache",
      "\(titleLabel(url: videoUrl))點擊播放 urlHash=\(DuyoVideoLoadDebugLog.urlHash(videoUrl))"
    )
    lockQueue.async {
      let cachedBytes = self.cachedBytesLocked(url: videoUrl)
      if cachedBytes >= Self.effectiveBytes {
        DuyoVideoLoadDebugLog.write(
          "VideoPreloadCache",
          "\(self.titleLabelLocked(url: videoUrl))點擊播放命中緩存 cachedBytes=\(cachedBytes) urlHash=\(DuyoVideoLoadDebugLog.urlHash(videoUrl))"
        )
        return
      }
      self.queuedUrls.removeAll()
      for activeUrl in Array(self.activeTasks.keys) where activeUrl != videoUrl {
        self.cancelActive(url: activeUrl, reason: "点击切换")
      }
    }
  }

  /// 清空当前队列。
  func clear(reason: String) {
    cancel(reason: reason)
  }

  /// 取消当前队列。
  func cancel(reason: String) {
    DuyoVideoLoadDebugLog.write("VideoPreloadCache", "cancel reason=\(reason)")
    lockQueue.async {
      self.queuedUrls.removeAll()
      for activeUrl in Array(self.activeTasks.keys) {
        self.cancelActive(url: activeUrl, reason: reason)
      }
    }
  }

  /// 查询首段缓存字节数。
  func cachedBytes(url: String?) -> Int64 {
    guard let videoUrl = sanitize(url) else {
      DuyoVideoLoadDebugLog.write("VideoPreloadCache", "cachedBytes ignored invalid url")
      return 0
    }
    let bytes = lockQueue.sync {
      cachedBytesLocked(url: videoUrl)
    }
    DuyoVideoLoadDebugLog.write(
      "VideoPreloadCache",
      "\(titleLabel(url: videoUrl))緩存查詢 bytes=\(bytes) urlHash=\(DuyoVideoLoadDebugLog.urlHash(videoUrl))"
    )
    return bytes
  }

  private func startNext() {
    while activeTasks.count < Self.maxActivePreloads, !queuedUrls.isEmpty {
      let nextUrl = queuedUrls.removeFirst()
      let cachedBytes = cachedBytesLocked(url: nextUrl)
      if cachedBytes >= Self.preloadBytes {
        continue
      }
      var request = URLRequest(url: URL(string: nextUrl)!)
      request.setValue("bytes=0-\(Self.preloadBytes - 1)", forHTTPHeaderField: "Range")
      DuyoVideoLoadDebugLog.write(
        "VideoPreloadCache",
        "\(titleLabelLocked(url: nextUrl))開始下載 urlHash=\(DuyoVideoLoadDebugLog.urlHash(nextUrl))"
      )
      let task = URLSession.shared.dataTask(with: request) { data, _, error in
        self.lockQueue.async {
          self.finish(url: nextUrl, data: data, error: error)
        }
      }
      activeTasks[nextUrl] = task
      DuyoVideoLoadDebugLog.write(
        "VideoPreloadCache",
        "\(titleLabelLocked(url: nextUrl))加入下載 active=\(activeTasks.count) queued=\(queuedUrls.count) urlHash=\(DuyoVideoLoadDebugLog.urlHash(nextUrl))"
      )
      task.resume()
    }
  }

  private func finish(url: String, data: Data?, error: Error?) {
    activeTasks.removeValue(forKey: url)
    if let error {
      if (error as? URLError)?.code != .cancelled {
        logPreloadError(url: url, error: error)
      } else {
        DuyoVideoLoadDebugLog.write(
          "VideoPreloadCache",
          "\(self.titleLabelLocked(url: url))下載取消 urlHash=\(DuyoVideoLoadDebugLog.urlHash(url))"
        )
      }
      startNext()
      return
    }
    if let data {
      do {
        try data.write(to: cacheFile(url: url), options: .atomic)
        touchCacheFile(url: url)
        DuyoVideoLoadDebugLog.write(
          "VideoPreloadCache",
          "\(titleLabelLocked(url: url))下載完成 cachedBytes=\(cachedBytesLocked(url: url)) urlHash=\(DuyoVideoLoadDebugLog.urlHash(url))"
        )
      } catch {
        logPreloadError(url: url, error: error)
      }
      trimCacheLocked(protectedUrl: url)
    }
    startNext()
  }

  private func cancelActive(url: String, reason _: String) {
    guard let task = activeTasks.removeValue(forKey: url) else {
      return
    }
    task.cancel()
  }

  private func cachedBytesLocked(url: String) -> Int64 {
    let fileUrl = cacheFile(url: url)
    guard let size = try? fileManager.attributesOfItem(atPath: fileUrl.path)[.size] as? NSNumber else {
      return 0
    }
    touchCacheFile(url: url)
    return min(size.int64Value, Self.preloadBytes)
  }

  private func cacheFile(url: String) -> URL {
    cacheDirectory.appendingPathComponent("\(cacheKey(url: url)).bin")
  }

  private func cacheKey(url: String) -> String {
    SHA256.hash(data: Data(url.utf8)).map { String(format: "%02x", $0) }.joined()
  }

  func titleOf(url: String?) -> String {
    guard let videoUrl = sanitize(url) else {
      return Self.fallbackTitle
    }
    return lockQueue.sync {
      titleOfLocked(url: videoUrl)
    }
  }

  func titleLabel(url: String?) -> String {
    "《\(titleOf(url: url))》"
  }

  private func registerTitle(url: String, title: String?) {
    let cleanTitle = normalizeTitle(title)
    lockQueue.sync {
      if cleanTitle == Self.fallbackTitle, self.titlesByUrl[url] != nil {
        return
      }
      self.titlesByUrl[url] = cleanTitle
    }
  }

  private func registerTitlesLocked(_ incomingTitlesByUrl: [String: String]) {
    for (url, title) in incomingTitlesByUrl {
      guard let videoUrl = sanitize(url) else { continue }
      titlesByUrl[videoUrl] = normalizeTitle(title)
    }
  }

  private func titleOfLocked(url: String) -> String {
    normalizeTitle(titlesByUrl[url])
  }

  private func titleLabelLocked(url: String) -> String {
    "《\(titleOfLocked(url: url))》"
  }

  private func normalizeTitle(_ title: String?) -> String {
    let value = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return value.isEmpty ? Self.fallbackTitle : value
  }

  private func sanitize(_ url: String?) -> String? {
    guard let url = url?.trimmingCharacters(in: .whitespacesAndNewlines), !url.isEmpty else {
      return nil
    }
    guard let components = URLComponents(string: url),
          components.scheme == "http" || components.scheme == "https" else {
      return nil
    }
    return url
  }

  private func sanitize(_ urls: [String]) -> [String] {
    var result: [String] = []
    var seen = Set<String>()
    for url in urls {
      guard let cleanUrl = sanitize(url), !seen.contains(cleanUrl) else {
        continue
      }
      seen.insert(cleanUrl)
      result.append(cleanUrl)
    }
    return result
  }

  private func touchCacheFile(url: String) {
    try? fileManager.setAttributes(
      [.modificationDate: Date()],
      ofItemAtPath: cacheFile(url: url).path
    )
  }

  private func trimCacheLocked(protectedUrl: String) {
    let files = cacheFiles()
    let totalBytes = files.reduce(Int64(0)) { partialResult, file in
      partialResult + file.size
    }
    guard totalBytes > Self.maxCacheBytes else {
      return
    }

    var remainingBytes = totalBytes
    let protectedPath = cacheFile(url: protectedUrl).path
    let removableFiles = files
      .filter { $0.url.path != protectedPath }
      .sorted { left, right in
        left.modifiedAt < right.modifiedAt
      }

    for file in removableFiles {
      if remainingBytes <= Self.maxCacheBytes {
        break
      }
      try? fileManager.removeItem(at: file.url)
      remainingBytes -= file.size
    }
  }

  private func cacheFiles() -> [CacheFile] {
    guard let urls = try? fileManager.contentsOfDirectory(
      at: cacheDirectory,
      includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey],
      options: [.skipsHiddenFiles]
    ) else {
      return []
    }

    return urls.compactMap { url in
      guard url.pathExtension == "bin",
            let attributes = try? fileManager.attributesOfItem(atPath: url.path),
            let size = attributes[.size] as? NSNumber else {
        return nil
      }
      let modifiedAt = attributes[.modificationDate] as? Date ?? .distantPast
      return CacheFile(url: url, size: size.int64Value, modifiedAt: modifiedAt)
    }
  }

  private func logPreloadError(url: String, error: Error) {
    DuyoVideoLoadDebugLog.write(
      "VideoPreloadCache",
      "\(titleLabelLocked(url: url))下載失敗 error=\(type(of: error)) cachedBytesAfter=\(cachedBytesLocked(url: url)) urlHash=\(DuyoVideoLoadDebugLog.urlHash(url))"
    )
  }

}

private struct CacheFile {
  let url: URL
  let size: Int64
  let modifiedAt: Date
}
