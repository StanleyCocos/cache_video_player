// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import Foundation

/// Duyo 视频预加载磁盘缓存。
final class DuyoVideoPreloadCache {
  static let shared = DuyoVideoPreloadCache()

  private static let preloadBytes: Int64 = 1_048_576
  private static let effectiveBytes: Int64 = 524_288
  private static let partialBytes: Int64 = 786_432
  private static let maxActivePreloads = 2

  private let lockQueue = DispatchQueue(label: "duyo.video.preload.cache")
  private let fileManager = FileManager.default
  private let cacheDirectory: URL
  private var titlesByUrl: [String: String] = [:]
  private var queuedUrls: [String] = []
  private var activeTasks: [String: URLSessionDataTask] = [:]
  private var activeStartMs: [String: Int64] = [:]
  private var generation = 0

  private init() {
    let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first
      ?? URL(fileURLWithPath: NSTemporaryDirectory())
    cacheDirectory = caches.appendingPathComponent("duyo_video_preload", isDirectory: true)
    try? fileManager.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
  }

  /// 预加载单个视频。
  func preload(url: String?, title: String? = nil) {
    guard let videoUrl = sanitize(url) else {
      return
    }
    syncQueue(urls: [videoUrl], titlesByUrl: [videoUrl: title ?? ""])
  }

  /// 同步当前可见视频预加载队列。
  func syncQueue(urls: [String], titlesByUrl incomingTitlesByUrl: [String: String]) {
    let visibleUrls = sanitize(urls)
    lockQueue.async {
      self.syncTitles(visibleUrls: visibleUrls, incomingTitlesByUrl: incomingTitlesByUrl)
      self.queuedUrls.removeAll { url in
        let removed = !visibleUrls.contains(url)
        if removed {
          self.log("preload.queue.remove", "\(self.videoLabel(url))離開隊列 原因=离屏 \(self.urlFields(url))")
        }
        return removed
      }
      for activeUrl in Array(self.activeTasks.keys) where !visibleUrls.contains(activeUrl) {
        self.cancelActive(url: activeUrl, reason: "离屏")
      }
      for visibleUrl in visibleUrls {
        let cachedBytes = self.cachedBytesLocked(url: visibleUrl)
        if cachedBytes >= Self.preloadBytes {
          self.log(
            "preload.queue.skipCached",
            "\(self.videoLabel(visibleUrl))已緩存 已缓存字节=\(cachedBytes) \(self.urlFields(visibleUrl))"
          )
          continue
        }
        if self.activeTasks[visibleUrl] != nil || self.queuedUrls.contains(visibleUrl) {
          continue
        }
        self.queuedUrls.append(visibleUrl)
        self.log("preload.queue.add", "\(self.videoLabel(visibleUrl))進入隊列 \(self.urlFields(visibleUrl))")
      }
      self.log(
        "preload.queue.sync",
        "可见视频数=\(visibleUrls.count) 等待数=\(self.queuedUrls.count) active数=\(self.activeTasks.count) active队列=\(self.shortLabels(Array(self.activeTasks.keys))) 可见视频=\(self.shortLabels(visibleUrls)) 等待队列=\(self.shortLabels(self.queuedUrls))"
      )
      self.startNext()
    }
  }

  /// 点击视频时给未有效命中的当前视频让路。
  func prioritize(url: String?, title: String?) {
    guard let videoUrl = sanitize(url) else {
      return
    }
    lockQueue.async {
      self.putTitle(url: videoUrl, title: title)
      let cachedBytes = self.cachedBytesLocked(url: videoUrl)
      self.log(
        "preload.queue.prioritize",
        "\(self.videoLabel(videoUrl))被點擊 effectiveHit=\(cachedBytes >= Self.effectiveBytes) 已缓存字节=\(cachedBytes) \(self.urlFields(videoUrl))"
      )
      if cachedBytes >= Self.effectiveBytes {
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
      return 0
    }
    return lockQueue.sync {
      cachedBytesLocked(url: videoUrl)
    }
  }

  private func startNext() {
    while activeTasks.count < Self.maxActivePreloads, !queuedUrls.isEmpty {
      let nextUrl = queuedUrls.removeFirst()
      let cachedBytes = cachedBytesLocked(url: nextUrl)
      if cachedBytes >= Self.preloadBytes {
        log(
          "preload.queue.skipCached",
          "\(videoLabel(nextUrl))已緩存 已缓存字节=\(cachedBytes) \(urlFields(nextUrl))"
        )
        continue
      }
      generation += 1
      let currentGeneration = generation
      activeStartMs[nextUrl] = nowMs()
      log(
        "preload.start",
        "generation=\(currentGeneration) \(videoLabel(nextUrl))開始緩存 range=bytes=0-1048575 已缓存字节=\(cachedBytes) \(urlFields(nextUrl))"
      )
      var request = URLRequest(url: URL(string: nextUrl)!)
      request.setValue("bytes=0-\(Self.preloadBytes - 1)", forHTTPHeaderField: "Range")
      let task = URLSession.shared.dataTask(with: request) { data, _, error in
        self.lockQueue.async {
          self.finish(url: nextUrl, generation: currentGeneration, data: data, error: error)
        }
      }
      activeTasks[nextUrl] = task
      task.resume()
    }
  }

  private func finish(url: String, generation: Int, data: Data?, error: Error?) {
    activeTasks.removeValue(forKey: url)
    let startMs = activeStartMs.removeValue(forKey: url) ?? nowMs()
    if let error {
      let cachedBytes = cachedBytesLocked(url: url)
      log(
        "preload.error",
        "generation=\(generation) \(videoLabel(url))緩存失敗 error=\(type(of: error)) cachedBytesAfter=\(cachedBytes) \(urlFields(url))"
      )
      startNext()
      return
    }
    if let data {
      try? data.write(to: cacheFile(url: url), options: .atomic)
    }
    let cachedBytes = cachedBytesLocked(url: url)
    log(
      "preload.end",
      "generation=\(generation) \(videoLabel(url))緩存完成 effectiveHit=\(cachedBytes >= Self.effectiveBytes) bytes=\(min(cachedBytes, Self.preloadBytes)) cachedBytesAfter=\(cachedBytes) durationMs=\(nowMs() - startMs) \(urlFields(url))"
    )
    startNext()
  }

  private func cancelActive(url: String, reason: String) {
    guard let task = activeTasks.removeValue(forKey: url) else {
      return
    }
    task.cancel()
    activeStartMs.removeValue(forKey: url)
    log("preload.cancel", "\(videoLabel(url))緩存取消 原因=\(readableReason(reason)) \(urlFields(url))")
  }

  private func cachedBytesLocked(url: String) -> Int64 {
    let fileUrl = cacheFile(url: url)
    guard let size = try? fileManager.attributesOfItem(atPath: fileUrl.path)[.size] as? NSNumber else {
      return 0
    }
    return min(size.int64Value, Self.preloadBytes)
  }

  private func cacheFile(url: String) -> URL {
    cacheDirectory.appendingPathComponent("\(abs(url.hashValue)).bin")
  }

  private func syncTitles(visibleUrls: [String], incomingTitlesByUrl: [String: String]) {
    titlesByUrl = titlesByUrl.filter { url, _ in
      visibleUrls.contains(url) || queuedUrls.contains(url) || activeTasks[url] != nil
    }
    for url in visibleUrls {
      putTitle(url: url, title: incomingTitlesByUrl[url])
    }
  }

  private func putTitle(url: String, title: String?) {
    let cleanTitle = (title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
    if !cleanTitle.isEmpty {
      titlesByUrl[url] = cleanTitle
    }
  }

  private func videoLabel(_ url: String) -> String {
    let title = titlesByUrl[url]?.isEmpty == false ? titlesByUrl[url]! : shortPath(url)
    return "《\(title)》的視頻"
  }

  private func shortLabels(_ urls: [String]) -> String {
    "[" + urls.map { "\(videoLabel($0)):\(shortPath($0))" }.joined(separator: ",") + "]"
  }

  private func shortPath(_ url: String) -> String {
    URL(string: url)?.path ?? url
  }

  private func urlFields(_ url: String) -> String {
    guard let components = URLComponents(string: url) else {
      return "urlLength=\(url.count) urlHash=\(abs(url.hashValue))"
    }
    return "urlLength=\(url.count) urlHash=\(abs(url.hashValue)) scheme=\(components.scheme ?? "") host=\(components.host ?? "") path=\(components.path)"
  }

  private func readableReason(_ reason: String) -> String {
    switch reason {
    case "dispose":
      return "销毁"
    case "emptyVisible":
      return "无可见视频"
    case "clear":
      return "清空"
    default:
      return reason
    }
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

  private func nowMs() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
  }

  private func log(_ event: String, _ fields: String) {
    NSLog("VideoLoadTrace event=\(event) \(fields)")
  }
}
