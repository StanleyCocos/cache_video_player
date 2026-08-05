import Foundation

/// Duyo 视频加载调试日志。
final class DuyoVideoLoadDebugLog {
  private static let key = "duyo_video_load_log_enabled"

  static var enabled: Bool {
    UserDefaults.standard.bool(forKey: key)
  }

  static func setEnabled(_ value: Bool) {
    UserDefaults.standard.set(value, forKey: key)
    NSLog("[DuyoVideoLoad] setLogEnabled enabled=\(value)")
  }

  static func write(_ scope: String, _ message: String) {
    guard enabled else { return }
    NSLog("[DuyoVideoLoad][\(scope)] \(message)")
  }

  static func urlHash(_ url: String?) -> String {
    guard let url else { return "00000000" }
    var hash: UInt32 = 0x811c9dc5
    for byte in url.utf8 {
      hash ^= UInt32(byte)
      hash = hash &* 0x01000193
    }
    return String(format: "%08x", hash)
  }
}
