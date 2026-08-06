// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.util.LongSparseArray;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.platform.PlatformViewRegistry;
import io.flutter.plugins.videoplayer.platformview.PlatformVideoViewFactory;
import io.flutter.plugins.videoplayer.platformview.PlatformViewVideoPlayer;
import io.flutter.plugins.videoplayer.texture.TextureVideoPlayer;
import io.flutter.view.TextureRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class VideoPlayerPluginTest {
  @Mock private TextureRegistry mockTextureRegistry;
  @Mock private TextureRegistry.SurfaceProducer mockSurfaceProducer;
  @Mock private PlatformViewRegistry mockPlatformViewRegistry;
  private VideoPlayerPlugin plugin;

  @Before
  public void setUp() {
    MockitoAnnotations.openMocks(this);
    when(mockTextureRegistry.createSurfaceProducer()).thenReturn(mockSurfaceProducer);

    FlutterPlugin.FlutterPluginBinding binding = mock(FlutterPlugin.FlutterPluginBinding.class);
    when(binding.getApplicationContext()).thenReturn(mock(Context.class));
    when(binding.getTextureRegistry()).thenReturn(mockTextureRegistry);
    when(binding.getBinaryMessenger())
        .thenReturn(mock(io.flutter.plugin.common.BinaryMessenger.class));
    when(binding.getPlatformViewRegistry()).thenReturn(mockPlatformViewRegistry);

    plugin = new VideoPlayerPlugin();
    plugin.onAttachedToEngine(binding);
  }

  @SuppressWarnings("unchecked")
  private LongSparseArray<VideoPlayer> getVideoPlayers() throws Exception {
    final Field field = VideoPlayerPlugin.class.getDeclaredField("videoPlayers");
    field.setAccessible(true);
    return (LongSparseArray<VideoPlayer>) field.get(plugin);
  }

  @SuppressWarnings("unchecked")
  private LinkedHashMap<Long, Long> getPlayerStartMsById() throws Exception {
    final Field field = VideoPreloadCache.class.getDeclaredField("playerStartMsById");
    field.setAccessible(true);
    return (LinkedHashMap<Long, Long>) field.get(null);
  }

  private VideoAsset.StreamingFormat streamingFormatArgument(Object formatHint) throws Exception {
    Method method =
        VideoPlayerPlugin.class.getDeclaredMethod("streamingFormatArgument", MethodCall.class);
    method.setAccessible(true);
    HashMap<String, Object> arguments = new HashMap<>();
    arguments.put("formatHint", formatHint);
    return (VideoAsset.StreamingFormat) method.invoke(null, new MethodCall("preload", arguments));
  }

  // This is only a placeholder test and doesn't actually initialize the plugin.
  @Test
  public void initPluginDoesNotThrow() {
    final VideoPlayerPlugin plugin = new VideoPlayerPlugin();
  }

  @Test
  public void registersPlatformVideoViewFactory() {
    verify(mockPlatformViewRegistry)
        .registerViewFactory(
            eq("plugins.flutter.dev/video_player_android"), any(PlatformVideoViewFactory.class));
  }

  @Test
  public void createsPlatformViewVideoPlayer() throws Exception {
    try (MockedStatic<PlatformViewVideoPlayer> mockedPlatformViewVideoPlayerStatic =
        mockStatic(PlatformViewVideoPlayer.class)) {
      mockedPlatformViewVideoPlayerStatic
          .when(() -> PlatformViewVideoPlayer.create(any(), any(), any(), any(), anyLong()))
          .thenReturn(mock(PlatformViewVideoPlayer.class));

      final CreationOptions options =
          new CreationOptions(
              "https://flutter.github.io/assets-for-api-docs/assets/videos/bee.mp4",
              null,
              new HashMap<>(),
              null);

      final long playerId = plugin.createForPlatformView(options);

      final LongSparseArray<VideoPlayer> videoPlayers = getVideoPlayers();
      assertTrue(videoPlayers.get(playerId) instanceof PlatformViewVideoPlayer);
    }
  }

  @Test
  public void createsTextureVideoPlayer() throws Exception {
    try (MockedStatic<TextureVideoPlayer> mockedTextureVideoPlayerStatic =
        mockStatic(TextureVideoPlayer.class)) {
      mockedTextureVideoPlayerStatic
          .when(() -> TextureVideoPlayer.create(any(), any(), any(), any(), any(), anyLong()))
          .thenReturn(mock(TextureVideoPlayer.class));

      final CreationOptions options =
          new CreationOptions(
              "https://flutter.github.io/assets-for-api-docs/assets/videos/bee.mp4",
              null,
              new HashMap<>(),
              null);

      final TexturePlayerIds ids = plugin.createForTextureView(options);

      final LongSparseArray<VideoPlayer> videoPlayers = getVideoPlayers();
      assertTrue(videoPlayers.get(ids.getPlayerId()) instanceof TextureVideoPlayer);
    }
  }

  @Test
  public void disposeClearsRememberedPlayerStart() throws Exception {
    long playerId = 42L;
    VideoPlayer videoPlayer = mock(VideoPlayer.class);
    getVideoPlayers().put(playerId, videoPlayer);
    getPlayerStartMsById().clear();
    VideoPreloadCache.rememberPlayerStart(playerId, 123L);

    plugin.dispose(playerId);

    assertFalse(getPlayerStartMsById().containsKey(playerId));
  }

  @Test
  public void constructorFailureClearsRememberedPlayerStart() throws Exception {
    long playerId = 43L;
    getPlayerStartMsById().clear();

    assertThrows(
        RuntimeException.class,
        () ->
            new VideoPlayer(
                mock(VideoPlayerCallbacks.class),
                MediaItem.fromUri("https://example.com/video.mp4"),
                new VideoPlayerOptions(),
                playerId,
                null,
                () -> {
                  throw new RuntimeException("boom");
                }) {
              @Override
              protected ExoPlayerEventListener createExoPlayerEventListener(
                  ExoPlayer exoPlayer,
                  TextureRegistry.SurfaceProducer surfaceProducer,
                  long playerId,
                  String videoUrl,
                  long playerCreateStartMs) {
                throw new AssertionError("not reached");
              }
            });

    assertFalse(getPlayerStartMsById().containsKey(playerId));
  }

  @Test
  public void streamingFormatArgumentAcceptsStringHints() throws Exception {
    assertEquals(VideoAsset.StreamingFormat.HTTP_LIVE, streamingFormatArgument("hls"));
    assertEquals(VideoAsset.StreamingFormat.DYNAMIC_ADAPTIVE, streamingFormatArgument("dash"));
    assertEquals(VideoAsset.StreamingFormat.SMOOTH, streamingFormatArgument("ss"));
  }

  @Test
  public void streamingFormatArgumentAcceptsNumericHints() throws Exception {
    assertEquals(VideoAsset.StreamingFormat.DYNAMIC_ADAPTIVE, streamingFormatArgument(0));
    assertEquals(VideoAsset.StreamingFormat.HTTP_LIVE, streamingFormatArgument(1));
    assertEquals(VideoAsset.StreamingFormat.SMOOTH, streamingFormatArgument(2));
  }
}
