// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import CryptoKit
import Foundation

/// Duyo 视频预加载磁盘缓存。
final class DuyoVideoPreloadCache: NSObject, URLSessionDataDelegate {
  static let shared = DuyoVideoPreloadCache()

  static let defaultPreloadBytes: Int64 = 3 * 1_024 * 1_024
  private static let effectivePlaybackBytes: Int64 = 512 * 1_024
  private static let maxActivePreloads = 2
  private static let maxCacheBytes: Int64 = 200 * 1_024 * 1_024
  private static let maxRememberedTitles = 1_000

  private let lockQueue = DispatchQueue(label: "duyo.video.preload.cache")
  private let fileManager = FileManager.default
  private let cacheDirectory: URL
  private var queuedUrls: [String] = []
  private var queuedPreloadBytesByUrl: [String: Int64] = [:]
  private var activeTasks: [String: URLSessionDataTask] = [:]
  private var activeUrlsByTaskIdentifier: [Int: String] = [:]
  private var activePreloadBytesByUrl: [String: Int64] = [:]
  private var activeStartedAtByUrl: [String: Date] = [:]
  private var activeDataByUrl: [String: Data] = [:]
  private var activeResponseByUrl: [String: URLResponse] = [:]
  private var titlesByUrl: [String: String] = [:]
  private lazy var session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)

  private override init() {
    let caches =
      fileManager.urls(for: .cachesDirectory, in: .userDomainMask).first
      ?? URL(fileURLWithPath: NSTemporaryDirectory())
    cacheDirectory = caches.appendingPathComponent("duyo_video_preload", isDirectory: true)
    super.init()
    try? fileManager.createDirectory(at: cacheDirectory, withIntermediateDirectories: true)
  }

  /// 预加载单个视频。
  func preload(url: String?, title: String? = nil, preloadBytes: Int64) {
    guard let videoUrl = sanitize(url) else {
      return
    }
    syncQueue(urls: [videoUrl], titlesByUrl: [videoUrl: title ?? ""], preloadBytes: preloadBytes)
  }

  /// 同步当前可见视频预加载队列。
  func syncQueue(
    urls: [String],
    titlesByUrl incomingTitlesByUrl: [String: String],
    preloadBytes: Int64
  ) {
    let visibleUrls = sanitize(urls)
    let requestedPreloadBytes = normalizePreloadBytes(preloadBytes)
    lockQueue.async {
      self.rememberTitles(titlesByUrl: incomingTitlesByUrl)
      self.queuedUrls.removeAll()
      self.queuedPreloadBytesByUrl.removeAll()
      for activeUrl in Array(self.activeTasks.keys) {
        if !visibleUrls.contains(activeUrl) {
          self.cancelActive(url: activeUrl, reason: "离屏")
        } else if self.activePreloadBytesByUrl[activeUrl] != requestedPreloadBytes {
          self.cancelActive(url: activeUrl, reason: "缓存大小切换")
        }
      }
      for visibleUrl in visibleUrls {
        let cachedBytes = self.cachedBytesLocked(
          url: visibleUrl, preloadBytes: requestedPreloadBytes)
        if cachedBytes >= requestedPreloadBytes {
          continue
        }
        if self.activeTasks[visibleUrl] != nil || self.queuedUrls.contains(visibleUrl) {
          continue
        }
        self.queuedUrls.append(visibleUrl)
        self.queuedPreloadBytesByUrl[visibleUrl] = requestedPreloadBytes
      }
      self.startNext()
    }
  }

  /// 点击视频时给未有效命中的当前视频让路。
  func prioritize(url: String?, title: String?, preloadBytes: Int64) {
    guard let videoUrl = sanitize(url) else {
      return
    }
    let requestedPreloadBytes = normalizePreloadBytes(preloadBytes)
    lockQueue.async {
      self.rememberTitle(url: videoUrl, title: title)
      let cachedBytes = self.cachedBytesLocked(url: videoUrl, preloadBytes: requestedPreloadBytes)
      self.logCacheState(url: videoUrl, bytes: cachedBytes)
      if cachedBytes >= min(512 * 1_024, requestedPreloadBytes) {
        return
      }
      self.queuedUrls.removeAll()
      self.queuedPreloadBytesByUrl.removeAll()
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
      self.queuedPreloadBytesByUrl.removeAll()
      for activeUrl in Array(self.activeTasks.keys) {
        self.cancelActive(url: activeUrl, reason: reason)
      }
    }
  }

  /// 查询首段缓存字节数。
  func cachedBytes(url: String?, preloadBytes: Int64) -> Int64 {
    guard let videoUrl = sanitize(url) else {
      return 0
    }
    let requestedPreloadBytes = normalizePreloadBytes(preloadBytes)
    let bytes = lockQueue.sync {
      cachedBytesLocked(url: videoUrl, preloadBytes: requestedPreloadBytes)
    }
    return bytes
  }

  /// 判断播放入口是否有可信的可播放首段缓存。
  func playableCachedPrefix(url: String?, preloadBytes: Int64) -> DuyoPlayableCachedPrefix? {
    guard let videoUrl = sanitize(url) else {
      return nil
    }
    return lockQueue.sync {
      let cachedBytes = cachedFileBytesLocked(url: videoUrl)
      guard cachedBytes > 0,
        let metadata = metadataLocked(url: videoUrl),
        let contentLength = metadata.contentLength,
        contentLength > 0,
        isPlayableVideo(url: videoUrl, contentType: metadata.contentType)
      else {
        return nil
      }
      let requiredBytes = min(
        Self.effectivePlaybackBytes, contentLength, normalizePreloadBytes(preloadBytes))
      guard cachedBytes >= requiredBytes else {
        return nil
      }
      return DuyoPlayableCachedPrefix(
        url: videoUrl,
        cachedBytes: cachedBytes,
        contentLength: contentLength,
        contentType: metadata.contentType)
    }
  }

  /// 读取已缓存首段中的指定区间。
  func cachedData(url: String, offset: Int64, length: Int) -> Data? {
    guard offset >= 0, length > 0 else {
      return nil
    }
    return lockQueue.sync {
      let bytes = cachedFileBytesLocked(url: url)
      guard offset < bytes else {
        return nil
      }
      let readLength = min(length, Int(bytes - offset))
      guard let handle = try? FileHandle(forReadingFrom: cacheFile(url: url)) else {
        return nil
      }
      defer {
        try? handle.close()
      }
      do {
        try handle.seek(toOffset: UInt64(offset))
        if #available(iOS 13.4, macOS 10.15.4, *) {
          return try handle.read(upToCount: readLength)
        }
        return handle.readData(ofLength: readLength)
      } catch {
        return nil
      }
    }
  }

  /// 读取缓存首段对应的视频元数据。
  func metadata(url: String) -> DuyoVideoCacheMetadata? {
    lockQueue.sync {
      metadataLocked(url: url)
    }
  }

  /// 读取日志标题。
  func title(url: String) -> String {
    lockQueue.sync {
      titleLocked(url: url)
    }
  }

  private func startNext() {
    while activeTasks.count < Self.maxActivePreloads, !queuedUrls.isEmpty {
      let nextUrl = queuedUrls.removeFirst()
      let preloadBytes = normalizePreloadBytes(queuedPreloadBytesByUrl.removeValue(forKey: nextUrl))
      let cachedBytes = cachedBytesLocked(url: nextUrl, preloadBytes: preloadBytes)
      if cachedBytes >= preloadBytes {
        continue
      }
      var request = URLRequest(url: URL(string: nextUrl)!)
      request.setValue("bytes=0-\(preloadBytes - 1)", forHTTPHeaderField: "Range")
      let task = session.dataTask(with: request)
      activeTasks[nextUrl] = task
      activeUrlsByTaskIdentifier[task.taskIdentifier] = nextUrl
      activePreloadBytesByUrl[nextUrl] = preloadBytes
      activeStartedAtByUrl[nextUrl] = Date()
      activeDataByUrl[nextUrl] = Data()
      logPreloadStarted(url: nextUrl)
      task.resume()
    }
  }

  func urlSession(
    _ session: URLSession,
    dataTask: URLSessionDataTask,
    didReceive response: URLResponse,
    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
  ) {
    lockQueue.async {
      guard let url = self.activeUrlsByTaskIdentifier[dataTask.taskIdentifier] else {
        completionHandler(.cancel)
        return
      }
      guard self.validatedMetadata(url: url, response: response) != nil else {
        completionHandler(.cancel)
        self.finish(
          url: url,
          error: NSError(
            domain: NSURLErrorDomain,
            code: NSURLErrorBadServerResponse,
            userInfo: [NSLocalizedDescriptionKey: "响应不可作为播放缓存"]))
        return
      }
      self.activeResponseByUrl[url] = response
      completionHandler(.allow)
    }
  }

  func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
    lockQueue.async {
      guard let url = self.activeUrlsByTaskIdentifier[dataTask.taskIdentifier],
        let activePreloadBytes = self.activePreloadBytesByUrl[url]
      else {
        return
      }
      var buffer = self.activeDataByUrl[url] ?? Data()
      let remainingBytes = Int(activePreloadBytes) - buffer.count
      guard remainingBytes > 0 else {
        return
      }
      buffer.append(data.prefix(remainingBytes))
      self.activeDataByUrl[url] = buffer
    }
  }

  func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
    lockQueue.async {
      guard let url = self.activeUrlsByTaskIdentifier[task.taskIdentifier] else {
        return
      }
      self.finish(url: url, error: error)
    }
  }

  private func finish(url: String, error: Error?) {
    let startedAt = activeStartedAtByUrl.removeValue(forKey: url)
    let activePreloadBytes = activePreloadBytesByUrl.removeValue(forKey: url)
    let task = activeTasks.removeValue(forKey: url)
    if let task {
      activeUrlsByTaskIdentifier.removeValue(forKey: task.taskIdentifier)
    }
    let data = activeDataByUrl.removeValue(forKey: url)
    let response = activeResponseByUrl.removeValue(forKey: url)
    if let error {
      logPreloadFailed(url: url, reason: error.localizedDescription)
      startNext()
      return
    }
    if let data, let response, let activePreloadBytes {
      do {
        guard let metadata = validatedMetadata(url: url, response: response) else {
          logPreloadFailed(url: url, reason: "响应不可作为播放缓存")
          startNext()
          return
        }
        let prefix = data.prefix(Int(min(Int64(data.count), activePreloadBytes)))
        try Data(prefix).write(to: cacheFile(url: url), options: .atomic)
        try writeMetadata(url: url, metadata: metadata)
        touchCacheFile(url: url)
        logPreloadFinished(
          url: url, bytes: Int64(prefix.count), costMs: elapsedMs(since: startedAt))
      } catch {
        logPreloadFailed(url: url, reason: error.localizedDescription)
      }
      trimCacheLocked(protectedUrl: url)
    }
    startNext()
  }

  private func cancelActive(url: String, reason _: String) {
    queuedPreloadBytesByUrl.removeValue(forKey: url)
    activePreloadBytesByUrl.removeValue(forKey: url)
    activeStartedAtByUrl.removeValue(forKey: url)
    activeDataByUrl.removeValue(forKey: url)
    activeResponseByUrl.removeValue(forKey: url)
    guard let task = activeTasks.removeValue(forKey: url) else {
      return
    }
    activeUrlsByTaskIdentifier.removeValue(forKey: task.taskIdentifier)
    task.cancel()
  }

  private func cachedBytesLocked(url: String, preloadBytes: Int64) -> Int64 {
    min(cachedFileBytesLocked(url: url), normalizePreloadBytes(preloadBytes))
  }

  private func cachedFileBytesLocked(url: String) -> Int64 {
    let fileUrl = cacheFile(url: url)
    guard let size = try? fileManager.attributesOfItem(atPath: fileUrl.path)[.size] as? NSNumber
    else {
      return 0
    }
    touchCacheFile(url: url)
    return size.int64Value
  }

  private func cacheFile(url: String) -> URL {
    cacheDirectory.appendingPathComponent("\(cacheKey(url: url)).bin")
  }

  private func metadataFile(url: String) -> URL {
    cacheDirectory.appendingPathComponent("\(cacheKey(url: url)).json")
  }

  private func cacheKey(url: String) -> String {
    SHA256.hash(data: Data(url.utf8)).map { String(format: "%02x", $0) }.joined()
  }

  private func sanitize(_ url: String?) -> String? {
    guard let url = url?.trimmingCharacters(in: .whitespacesAndNewlines), !url.isEmpty else {
      return nil
    }
    guard let components = URLComponents(string: url),
      components.scheme == "http" || components.scheme == "https"
    else {
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

  private func normalizePreloadBytes(_ preloadBytes: Int64?) -> Int64 {
    guard let preloadBytes, preloadBytes > 0 else {
      return Self.defaultPreloadBytes
    }
    return preloadBytes
  }

  private func touchCacheFile(url: String) {
    try? fileManager.setAttributes(
      [.modificationDate: Date()],
      ofItemAtPath: cacheFile(url: url).path
    )
    try? fileManager.setAttributes(
      [.modificationDate: Date()],
      ofItemAtPath: metadataFile(url: url).path
    )
  }

  private func metadataLocked(url: String) -> DuyoVideoCacheMetadata? {
    guard let data = try? Data(contentsOf: metadataFile(url: url)) else {
      return nil
    }
    return try? JSONDecoder().decode(DuyoVideoCacheMetadata.self, from: data)
  }

  private func writeMetadata(url: String, metadata: DuyoVideoCacheMetadata) throws {
    let data = try JSONEncoder().encode(metadata)
    try data.write(to: metadataFile(url: url), options: .atomic)
  }

  private func validatedMetadata(url: String, response: URLResponse?) -> DuyoVideoCacheMetadata? {
    guard let contentLength = Self.contentLength(response: response),
      contentLength > 0,
      isPlayableVideo(url: url, contentType: response?.mimeType)
    else {
      return nil
    }
    return DuyoVideoCacheMetadata(contentLength: contentLength, contentType: response?.mimeType)
  }

  static func contentLength(response: URLResponse?) -> Int64? {
    if let httpResponse = response as? HTTPURLResponse {
      guard httpResponse.statusCode >= 200, httpResponse.statusCode < 300 else {
        return nil
      }
      if let contentRange = httpResponse.value(forHTTPHeaderField: "Content-Range"),
        let total = contentRange.split(separator: "/").last,
        let value = Int64(total)
      {
        return value
      }
      return nil
    }
    let expected = response?.expectedContentLength ?? -1
    if expected > 0 {
      return expected
    }
    return nil
  }

  static func contentLength(response: URLResponse?, receivedBytes _: Int) -> Int64? {
    contentLength(response: response)
  }

  private func isPlayableVideo(url: String, contentType: String?) -> Bool {
    let mimeType = contentType?.lowercased()
    if mimeType == "video/mp4" || mimeType == "video/quicktime" || mimeType == "application/mp4" {
      return true
    }
    let pathExtension = URLComponents(string: url)?.path.split(separator: ".").last?.lowercased()
    let hasPlayableExtension =
      pathExtension == "mp4" || pathExtension == "m4v" || pathExtension == "mov"
    if mimeType == nil || mimeType == "application/octet-stream" {
      return hasPlayableExtension
    }
    return false
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
    let removableFiles =
      files
      .filter { $0.url.path != protectedPath }
      .sorted { left, right in
        left.modifiedAt < right.modifiedAt
      }

    for file in removableFiles {
      if remainingBytes <= Self.maxCacheBytes {
        break
      }
      try? fileManager.removeItem(at: file.url)
      try? fileManager.removeItem(at: metadataFile(forCacheFile: file.url))
      remainingBytes -= file.size
    }
  }

  private func rememberTitles(titlesByUrl: [String: String]) {
    for (url, title) in titlesByUrl {
      rememberTitle(url: url, title: title)
    }
  }

  private func rememberTitle(url: String, title: String?) {
    guard let videoUrl = sanitize(url) else {
      return
    }
    titlesByUrl[videoUrl] = normalizeTitle(title)
    trimRememberedTitles()
  }

  private func titleLocked(url: String) -> String {
    normalizeTitle(titlesByUrl[url])
  }

  private func normalizeTitle(_ title: String?) -> String {
    let value = title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    return value.isEmpty ? "未命名贴文" : value
  }

  private func logPreloadStarted(url: String) {
    #if DEBUG
      print("[VideoLoad] 列表《\(titleLocked(url: url))》开始缓存")
    #endif
  }

  private func logPreloadFinished(url: String, bytes: Int64, costMs: Int64) {
    #if DEBUG
      print("[VideoLoad] 缓存《\(titleLocked(url: url))》已经完成 bytes=\(bytes) cost=\(costMs)ms")
    #endif
  }

  private func logPreloadFailed(url: String, reason: String) {
    #if DEBUG
      print("[VideoLoad] 缓存《\(titleLocked(url: url))》失败 reason=\(reason)")
    #endif
  }

  private func trimRememberedTitles() {
    guard titlesByUrl.count > Self.maxRememberedTitles else {
      return
    }
    let protectedUrls = Set(queuedUrls).union(activeTasks.keys)
    for url in Array(titlesByUrl.keys) where !protectedUrls.contains(url) {
      titlesByUrl.removeValue(forKey: url)
      if titlesByUrl.count <= Self.maxRememberedTitles {
        return
      }
    }
  }

  private func elapsedMs(since startedAt: Date?) -> Int64 {
    guard let startedAt else {
      return 0
    }
    return Int64(Date().timeIntervalSince(startedAt) * 1_000)
  }

  private func logCacheState(url: String, bytes: Int64) {
    #if DEBUG
      let state = bytes > 0 ? "有缓存" : "没有缓存"
      print("[VideoLoad] 《\(titleLocked(url: url))》\(state) bytes=\(bytes)")
    #endif
  }

  private func cacheFiles() -> [CacheFile] {
    guard
      let urls = try? fileManager.contentsOfDirectory(
        at: cacheDirectory,
        includingPropertiesForKeys: [.contentModificationDateKey, .fileSizeKey],
        options: [.skipsHiddenFiles]
      )
    else {
      return []
    }

    return urls.compactMap { url in
      guard url.pathExtension == "bin",
        let attributes = try? fileManager.attributesOfItem(atPath: url.path),
        let size = attributes[.size] as? NSNumber
      else {
        return nil
      }
      let modifiedAt = attributes[.modificationDate] as? Date ?? .distantPast
      return CacheFile(url: url, size: size.int64Value, modifiedAt: modifiedAt)
    }
  }

  private func metadataFile(forCacheFile url: URL) -> URL {
    url.deletingPathExtension().appendingPathExtension("json")
  }

}

struct DuyoVideoCacheMetadata: Codable {
  let contentLength: Int64?
  let contentType: String?
}

struct DuyoPlayableCachedPrefix {
  let url: String
  let cachedBytes: Int64
  let contentLength: Int64
  let contentType: String?
}

private struct CacheFile {
  let url: URL
  let size: Int64
  let modifiedAt: Date
}
