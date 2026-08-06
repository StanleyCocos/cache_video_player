// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.content.Context;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import java.util.HashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public final class VideoPlayerWarmCacheTest {
  @After
  public void tearDown() {
    VideoPreloadCache.clearWarmPlayersForTest();
  }

  @Test
  public void setWarmCandidatesKeepsOnlyFirstTwoUrls() {
    VideoPreloadCache.setWarmCandidatesForTest(
        "https://example.com/one.mp4",
        "https://example.com/two.mp4",
        "https://example.com/three.mp4");

    assertEquals(2, VideoPreloadCache.warmCandidateCountForTest());
    assertEquals("https://example.com/one.mp4", VideoPreloadCache.warmCandidateAtForTest(0));
    assertEquals("https://example.com/two.mp4", VideoPreloadCache.warmCandidateAtForTest(1));
  }

  @Test
  public void takeWarmPlayerRemovesUnreadyWarmPlayer() {
    VideoPreloadCache.putUnreadyWarmPlayerForTest("https://example.com/video.mp4");

    assertNull(VideoPreloadCache.takeWarmPlayerByUrlForTest("https://example.com/video.mp4"));
    assertEquals(0, VideoPreloadCache.warmPlayerCountForTest());
  }

  @Test
  public void takeWarmPlayerUsesMediaItemUrlKey() {
    ExoPlayer player = mock(ExoPlayer.class);
    VideoPreloadCache.putWarmPlayerForTest("https://example.com/video.mp4", player);

    assertSame(
        player,
        VideoPreloadCache.takeWarmPlayer(MediaItem.fromUri("https://example.com/video.mp4")));
  }

  @Test
  public void takeWarmPlayerRejectsIncompatibleMimeType() {
    ExoPlayer player = mock(ExoPlayer.class);
    VideoPreloadCache.putWarmPlayerForTest("https://example.com/video.mp4", player);
    MediaItem requestedItem =
        new MediaItem.Builder()
            .setUri("https://example.com/video.mp4")
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build();

    assertNull(VideoPreloadCache.takeWarmPlayer(requestedItem));
    assertEquals(0, VideoPreloadCache.warmPlayerCountForTest());
  }

  @Test
  public void takeWarmPlayerRejectsIncompatibleHeaders() {
    ExoPlayer player = mock(ExoPlayer.class);
    Map<String, String> warmHeaders = new HashMap<>();
    warmHeaders.put("Authorization", "Bearer warm");
    VideoPreloadCache.putWarmPlayerForTest(
        "https://example.com/video.mp4",
        player,
        VideoAsset.StreamingFormat.UNKNOWN,
        warmHeaders,
        null);
    Map<String, String> requestedHeaders = new HashMap<>();
    requestedHeaders.put("Authorization", "Bearer requested");
    VideoAsset requestedAsset =
        VideoAsset.fromRemoteUrl(
            "https://example.com/video.mp4",
            VideoAsset.StreamingFormat.UNKNOWN,
            requestedHeaders,
            null);

    assertNull(VideoPreloadCache.takeWarmPlayer(requestedAsset));
    assertEquals(0, VideoPreloadCache.warmPlayerCountForTest());
  }

  @Test
  public void takeWarmPlayerRejectsIncompatibleUserAgent() {
    ExoPlayer player = mock(ExoPlayer.class);
    VideoPreloadCache.putWarmPlayerForTest(
        "https://example.com/video.mp4",
        player,
        VideoAsset.StreamingFormat.UNKNOWN,
        new HashMap<>(),
        "Warm UA");
    VideoAsset requestedAsset =
        VideoAsset.fromRemoteUrl(
            "https://example.com/video.mp4",
            VideoAsset.StreamingFormat.UNKNOWN,
            new HashMap<>(),
            "Requested UA");

    assertNull(VideoPreloadCache.takeWarmPlayer(requestedAsset));
    assertEquals(0, VideoPreloadCache.warmPlayerCountForTest());
  }

  @Test
  public void takeWarmPlayerAcceptsCompatibleRequestMetadata() {
    ExoPlayer player = mock(ExoPlayer.class);
    Map<String, String> headers = new HashMap<>();
    headers.put("Authorization", "Bearer token");
    VideoPreloadCache.putWarmPlayerForTest(
        "https://example.com/video.mp4",
        player,
        VideoAsset.StreamingFormat.UNKNOWN,
        headers,
        "Test UA");
    VideoAsset requestedAsset =
        VideoAsset.fromRemoteUrl(
            "https://example.com/video.mp4",
            VideoAsset.StreamingFormat.UNKNOWN,
            headers,
            "Test UA");

    assertSame(player, VideoPreloadCache.takeWarmPlayer(requestedAsset));
  }

  @Test
  public void warmMediaItemKeepsStreamingMimeType() {
    MediaItem mediaItem =
        VideoPreloadCache.warmMediaItemForTest(
            "https://example.com/signed-playlist",
            VideoAsset.StreamingFormat.HTTP_LIVE);

    assertNotNull(mediaItem.localConfiguration);
    assertEquals(MimeTypes.APPLICATION_M3U8, mediaItem.localConfiguration.mimeType);
  }

  @Test
  public void unknownWarmMediaItemKeepsProgressiveUrlWithoutMimeType() {
    MediaItem mediaItem =
        VideoPreloadCache.warmMediaItemForTest(
            "https://example.com/video.mp4",
            VideoAsset.StreamingFormat.UNKNOWN);

    assertNotNull(mediaItem.localConfiguration);
    assertNull(mediaItem.localConfiguration.mimeType);
  }

  @Test
  public void defaultMediaSourceFactoryDoesNotRecurse() {
    VideoAsset asset =
        new VideoAsset("https://example.com/video.mp4") {
          @Override
          public MediaItem getMediaItem() {
            return MediaItem.fromUri(assetUrl);
          }
        };

    MediaSource.Factory factory = asset.getMediaSourceFactory(mock(Context.class), 1);

    assertTrue(factory instanceof DefaultMediaSourceFactory);
  }
}
