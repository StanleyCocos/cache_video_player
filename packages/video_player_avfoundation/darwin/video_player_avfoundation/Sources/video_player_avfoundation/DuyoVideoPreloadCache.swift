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
  private var queuedUrls: [String] = []
  private var activeTasks: [String: URLSessionDataTask] = [:]

  private init() {
    let caches = fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first
      ?? URL(fileURLWithPath: NSTemporaryDirectory())
    cacheDirectory = caches.appendingPathComponent("duyo_video_preload", isDirectory: true)
    try? fileManager.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
  }

  /// 预加载单个视频。
  func preload(url: String?, title _: String? = nil) {
    guard let videoUrl = sanitize(url) else {
      return
    }
    syncQueue(urls: [videoUrl], titlesByUrl: [:])
  }

  /// 同步当前可见视频预加载队列。
  func syncQueue(urls: [String], titlesByUrl _: [String: String]) {
    let visibleUrls = sanitize(urls)
    lockQueue.async {
      self.queuedUrls.removeAll { url in
        !visibleUrls.contains(url)
      }
      for activeUrl in Array(self.activeTasks.keys) where !visibleUrls.contains(activeUrl) {
        self.cancelActive(url: activeUrl, reason: "离屏")
      }
      for visibleUrl in visibleUrls {
        let cachedBytes = self.cachedBytesLocked(url: visibleUrl)
        if cachedBytes >= Self.preloadBytes {
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
  func prioritize(url: String?, title _: String?) {
    guard let videoUrl = sanitize(url) else {
      return
    }
    lockQueue.async {
      let cachedBytes = self.cachedBytesLocked(url: videoUrl)
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
        continue
      }
      var request = URLRequest(url: URL(string: nextUrl)!)
      request.setValue("bytes=0-\(Self.preloadBytes - 1)", forHTTPHeaderField: "Range")
      let task = URLSession.shared.dataTask(with: request) { data, _, error in
        self.lockQueue.async {
          self.finish(url: nextUrl, data: data, error: error)
        }
      }
      activeTasks[nextUrl] = task
      task.resume()
    }
  }

  private func finish(url: String, data: Data?, error: Error?) {
    activeTasks.removeValue(forKey: url)
    if let error {
      if (error as? URLError)?.code != .cancelled {
        logPreloadError(url: url, error: error)
      }
      startNext()
      return
    }
    if let data {
      do {
        try data.write(to: cacheFile(url: url), options: .atomic)
      } catch {
        logPreloadError(url: url, error: error)
      }
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
    return min(size.int64Value, Self.preloadBytes)
  }

  private func cacheFile(url: String) -> URL {
    cacheDirectory.appendingPathComponent("\(abs(url.hashValue)).bin")
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

  private func logPreloadError(url: String, error: Error) {
    NSLog(
      "[VideoPreloadCache] preload.error error=\(type(of: error)) cachedBytesAfter=\(cachedBytesLocked(url: url)) urlHash=\(abs(url.hashValue))"
    )
  }

}
