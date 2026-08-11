// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import AVFoundation
import Foundation
import MobileCoreServices
import UniformTypeIdentifiers

/// AVPlayer loader that serves the preloaded prefix from disk, then streams the rest from network.
final class DuyoCachedVideoResourceLoader: NSObject, AVAssetResourceLoaderDelegate,
  URLSessionDataDelegate
{
  private static let httpScheme = "duyo-video-cache-http"
  private static let httpsScheme = "duyo-video-cache-https"
  private let cache = DuyoVideoPreloadCache.shared
  private let cacheKeyUrl: String
  private var loads: [ObjectIdentifier: StreamingLoad] = [:]
  private var loadsByTaskIdentifier: [Int: StreamingLoad] = [:]
  private var loggedCacheReadUrls = Set<String>()
  private var session: URLSession?

  init(cacheKeyUrl: String) {
    self.cacheKeyUrl = cacheKeyUrl
    super.init()
  }

  deinit {
    cancelActiveLoads()
    session?.invalidateAndCancel()
  }

  static func playerItem(
    for url: URL,
    cacheKeyUrl: String,
    options: [String: Any]?
  ) -> (NSObject & FVPAVPlayerItem)? {
    guard
      DuyoVideoPreloadCache.shared.playableCachedPrefix(
        url: cacheKeyUrl,
        preloadBytes: DuyoVideoPreloadCache.defaultPreloadBytes) != nil,
      let assetUrl = cachedAssetUrl(for: url)
    else {
      return nil
    }
    let loader = DuyoCachedVideoResourceLoader(cacheKeyUrl: cacheKeyUrl)
    let asset = AVURLAsset(url: assetUrl, options: options)
    asset.resourceLoader.setDelegate(loader, queue: .main)
    let realPlayerItem = AVPlayerItem(asset: asset)
    let item = DuyoCachedAVPlayerItem(playerItem: realPlayerItem, loader: loader)
    return item
  }

  func resourceLoader(
    _ resourceLoader: AVAssetResourceLoader,
    shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
  ) -> Bool {
    guard let sourceUrl = Self.sourceUrl(from: loadingRequest.request.url) else {
      return false
    }

    fillContentInfo(loadingRequest.contentInformationRequest, sourceUrl: sourceUrl)

    guard let dataRequest = loadingRequest.dataRequest else {
      loadingRequest.finishLoading()
      return true
    }

    let offset =
      dataRequest.currentOffset > 0 ? dataRequest.currentOffset : dataRequest.requestedOffset
    let requestedLength = dataRequest.requestedLength
    var loadedLength = 0

    if let data = cache.cachedData(url: cacheKeyUrl, offset: offset, length: requestedLength) {
      dataRequest.respond(with: data)
      loadedLength = data.count
      logPlayerCacheRead(url: cacheKeyUrl, bytes: data.count)
    }

    guard loadedLength < requestedLength else {
      loadingRequest.finishLoading()
      return true
    }

    let key = ObjectIdentifier(loadingRequest)
    let load = StreamingLoad(
      loadingRequest: loadingRequest,
      sourceUrl: sourceUrl,
      offset: offset + Int64(loadedLength),
      length: requestedLength - loadedLength
    )
    loads[key] = load
    let task = load.start(session: activeSession())
    loadsByTaskIdentifier[task.taskIdentifier] = load
    return true
  }

  func resourceLoader(
    _ resourceLoader: AVAssetResourceLoader,
    didCancel loadingRequest: AVAssetResourceLoadingRequest
  ) {
    guard let load = loads.removeValue(forKey: ObjectIdentifier(loadingRequest)) else {
      return
    }
    if let taskIdentifier = load.taskIdentifier {
      loadsByTaskIdentifier.removeValue(forKey: taskIdentifier)
    }
    load.cancel()
  }

  func urlSession(
    _ session: URLSession,
    dataTask: URLSessionDataTask,
    didReceive response: URLResponse,
    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
  ) {
    guard let load = loadsByTaskIdentifier[dataTask.taskIdentifier] else {
      completionHandler(.cancel)
      return
    }
    if let error = load.validate(response: response) {
      completionHandler(.cancel)
      finish(load: load, error: error, finishLoading: true)
      return
    }
    DispatchQueue.main.async {
      self.fillContentInfo(load.loadingRequest.contentInformationRequest, response: response)
    }
    completionHandler(.allow)
  }

  func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
    guard let load = loadsByTaskIdentifier[dataTask.taskIdentifier] else {
      return
    }
    DispatchQueue.main.async {
      load.loadingRequest.dataRequest?.respond(with: data)
    }
  }

  func urlSession(
    _ session: URLSession,
    task: URLSessionTask,
    didCompleteWithError error: Error?
  ) {
    guard let load = loadsByTaskIdentifier[task.taskIdentifier] else {
      return
    }
    finish(load: load, error: error, finishLoading: true)
  }

  private func finish(load: StreamingLoad, error: Error?, finishLoading: Bool) {
    DispatchQueue.main.async {
      guard !load.didFinish else {
        return
      }
      load.didFinish = true
      self.loads.removeValue(forKey: ObjectIdentifier(load.loadingRequest))
      if let taskIdentifier = load.taskIdentifier {
        self.loadsByTaskIdentifier.removeValue(forKey: taskIdentifier)
      }
      if finishLoading {
        if let error {
          self.logPlayerNetworkFailed(load: load, error: error)
          load.loadingRequest.finishLoading(with: error)
        } else {
          load.loadingRequest.finishLoading()
        }
      }
    }
  }

  func prepareForDispose() {
    cancelActiveLoads()
    session?.invalidateAndCancel()
    session = nil
  }

  private func activeSession() -> URLSession {
    if let session {
      return session
    }
    let createdSession = URLSession(
      configuration: .default,
      delegate: self,
      delegateQueue: .main)
    session = createdSession
    return createdSession
  }

  private func cancelActiveLoads() {
    for load in loads.values {
      load.cancel()
    }
    loads.removeAll()
    loadsByTaskIdentifier.removeAll()
  }

  private static func cachedAssetUrl(for url: URL) -> URL? {
    guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
      return nil
    }
    if url.scheme == "http" {
      components.scheme = httpScheme
    } else if url.scheme == "https" {
      components.scheme = httpsScheme
    } else {
      return nil
    }
    return components.url
  }

  private static func sourceUrl(from url: URL?) -> URL? {
    guard let url,
      var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
    else {
      return nil
    }
    if url.scheme == httpScheme {
      components.scheme = "http"
    } else if url.scheme == httpsScheme {
      components.scheme = "https"
    } else {
      return nil
    }
    return components.url
  }

  private func fillContentInfo(
    _ contentInfo: AVAssetResourceLoadingContentInformationRequest?,
    sourceUrl _: URL
  ) {
    guard let contentInfo else {
      return
    }
    let metadata = cache.metadata(url: cacheKeyUrl)
    contentInfo.isByteRangeAccessSupported = true
    if let contentType = contentTypeIdentifier(mimeType: metadata?.contentType, url: cacheKeyUrl) {
      contentInfo.contentType = contentType
    }
    if let contentLength = metadata?.contentLength {
      contentInfo.contentLength = contentLength
    }
  }

  private func fillContentInfo(
    _ contentInfo: AVAssetResourceLoadingContentInformationRequest?,
    response: URLResponse?
  ) {
    guard let contentInfo else {
      return
    }
    contentInfo.isByteRangeAccessSupported = true
    if let contentType = contentTypeIdentifier(mimeType: response?.mimeType, url: cacheKeyUrl) {
      contentInfo.contentType = contentType
    }
    if let contentLength = Self.contentLength(response: response), contentLength > 0 {
      contentInfo.contentLength = contentLength
    }
  }

  private func contentTypeIdentifier(mimeType: String?, url: String) -> String? {
    let normalizedMimeType = mimeType?.lowercased()
    if normalizedMimeType == "application/octet-stream" || normalizedMimeType == nil {
      return contentTypeIdentifier(fromFileExtension: url)
    }
    guard let mimeType else {
      return contentTypeIdentifier(fromFileExtension: url)
    }
    guard
      normalizedMimeType == "video/mp4" || normalizedMimeType == "video/quicktime"
        || normalizedMimeType == "application/mp4"
    else {
      return nil
    }
    return contentTypeIdentifier(fromMimeType: mimeType)
  }

  private func contentTypeIdentifier(fromMimeType mimeType: String) -> String? {
    if #available(iOS 14.0, macOS 11.0, *) {
      return UTType(mimeType: mimeType)?.identifier
    }
    return UTTypeCreatePreferredIdentifierForTag(kUTTagClassMIMEType, mimeType as CFString, nil)?
      .takeRetainedValue() as String?
  }

  private func contentTypeIdentifier(fromFileExtension url: String) -> String? {
    let pathExtension = URLComponents(string: url)?.path.split(separator: ".").last?.lowercased()
    let fileExtension: String
    if pathExtension == "mp4" {
      fileExtension = "mp4"
    } else if pathExtension == "m4v" {
      fileExtension = "m4v"
    } else if pathExtension == "mov" {
      fileExtension = "mov"
    } else {
      return nil
    }
    if #available(iOS 14.0, macOS 11.0, *) {
      return UTType(filenameExtension: fileExtension)?.identifier
    }
    return UTTypeCreatePreferredIdentifierForTag(
      kUTTagClassFilenameExtension,
      fileExtension as CFString,
      nil
    )?.takeRetainedValue() as String?
  }

  private static func contentLength(response: URLResponse?) -> Int64? {
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
    return expected > 0 ? expected : nil
  }

  private func logPlayerCacheRead(url: String, bytes: Int) {
    guard cache.isDebugLogEnabled(), bytes > 0, !loggedCacheReadUrls.contains(url) else {
      return
    }
    loggedCacheReadUrls.insert(url)
    print("[VideoLoad] 《\(cache.title(url: url))》播放器读取了缓存 bytes=\(bytes)")
  }

  private func logPlayerNetworkFailed(load: StreamingLoad, error: Error) {
    guard cache.isDebugLogEnabled() else {
      return
    }
    print(
      "[VideoLoad] 《\(cache.title(url: cacheKeyUrl))》播放器网络补齐失败 offset=\(load.offset) length=\(load.length) error=\(error.localizedDescription)"
    )
  }
}

private final class StreamingLoad {
  let loadingRequest: AVAssetResourceLoadingRequest
  private let sourceUrl: URL
  let offset: Int64
  let length: Int
  private var task: URLSessionDataTask?
  var didFinish = false
  var taskIdentifier: Int? {
    task?.taskIdentifier
  }

  init(
    loadingRequest: AVAssetResourceLoadingRequest,
    sourceUrl: URL,
    offset: Int64,
    length: Int
  ) {
    self.loadingRequest = loadingRequest
    self.sourceUrl = sourceUrl
    self.offset = offset
    self.length = length
  }

  func start(session: URLSession) -> URLSessionDataTask {
    var request = URLRequest(url: sourceUrl)
    if length > 0 {
      request.setValue("bytes=\(offset)-\(offset + Int64(length) - 1)", forHTTPHeaderField: "Range")
    }
    task = session.dataTask(with: request)
    task!.resume()
    return task!
  }

  func cancel() {
    task?.cancel()
  }

  func validate(response: URLResponse) -> Error? {
    guard let response = response as? HTTPURLResponse else {
      return nil
    }
    if offset > 0, response.statusCode != 206 {
      return NSError(
        domain: NSURLErrorDomain,
        code: NSURLErrorBadServerResponse,
        userInfo: [NSLocalizedDescriptionKey: "Range request was not honored."])
    }
    if response.statusCode < 200 || response.statusCode >= 300 {
      return NSError(
        domain: NSURLErrorDomain,
        code: NSURLErrorBadServerResponse,
        userInfo: [NSLocalizedDescriptionKey: "Unexpected HTTP status \(response.statusCode)."])
    }
    return nil
  }
}

private final class DuyoCachedAVAsset: NSObject, FVPAVAsset {
  private let asset: AVAsset

  init(asset: AVAsset) {
    self.asset = asset
    super.init()
  }

  var duration: CMTime {
    asset.duration
  }

  func statusOfValue(forKey key: String, error outError: NSErrorPointer) -> AVKeyValueStatus {
    asset.statusOfValue(forKey: key, error: outError)
  }

  func loadValuesAsynchronously(forKeys keys: [String], completionHandler handler: (() -> Void)?) {
    asset.loadValuesAsynchronously(forKeys: keys, completionHandler: handler)
  }

  func loadTracks(
    withMediaType mediaType: AVMediaType,
    completionHandler: @escaping ([AVAssetTrack]?, Error?) -> Void
  ) {
    if #available(iOS 15.0, macOS 12.0, *) {
      asset.loadTracks(withMediaType: mediaType, completionHandler: completionHandler)
      return
    }
    completionHandler(asset.tracks(withMediaType: mediaType), nil)
  }

  func tracks(withMediaType mediaType: AVMediaType) -> [AVAssetTrack] {
    asset.tracks(withMediaType: mediaType)
  }

}

private final class DuyoCachedAVPlayerItem: NSObject, FVPAVPlayerItem, FVPAVPlayerItemWrapper {
  let playerItem: AVPlayerItem
  private let assetWrapper: DuyoCachedAVAsset
  private let loader: DuyoCachedVideoResourceLoader

  init(playerItem: AVPlayerItem, loader: DuyoCachedVideoResourceLoader) {
    self.playerItem = playerItem
    self.assetWrapper = DuyoCachedAVAsset(asset: playerItem.asset)
    self.loader = loader
    super.init()
  }

  var asset: FVPAVAsset {
    assetWrapper
  }

  var videoComposition: AVVideoComposition? {
    get {
      playerItem.videoComposition
    }
    set {
      playerItem.videoComposition = newValue
    }
  }

  func prepareForDispose() {
    if let asset = playerItem.asset as? AVURLAsset {
      asset.resourceLoader.setDelegate(nil, queue: nil)
    }
    loader.prepareForDispose()
  }

}
