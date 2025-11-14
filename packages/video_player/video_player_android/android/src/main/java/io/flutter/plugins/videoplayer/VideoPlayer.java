// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static androidx.media3.common.Player.REPEAT_MODE_ALL;
import static androidx.media3.common.Player.REPEAT_MODE_OFF;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import io.flutter.view.TextureRegistry.SurfaceProducer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A class responsible for managing video playback using {@link ExoPlayer}.
 *
 * <p>It provides methods to control playback, adjust volume, and handle seeking.
 */
public abstract class VideoPlayer implements VideoPlayerInstanceApi {
  @NonNull protected final VideoPlayerCallbacks videoPlayerEvents;
  @Nullable protected final SurfaceProducer surfaceProducer;
  @Nullable private DisposeHandler disposeHandler;
  @NonNull protected ExoPlayer exoPlayer;
  @UnstableApi @Nullable protected DefaultTrackSelector trackSelector;

  /** A closure-compatible signature since {@link java.util.function.Supplier} is API level 24. */
  public interface ExoPlayerProvider {
    /**
     * Returns a new {@link ExoPlayer}.
     *
     * @return new instance.
     */
    @NonNull
    ExoPlayer get();
  }

  /** A handler to run when dispose is called. */
  public interface DisposeHandler {
    void onDispose();
  }

  @UnstableApi
  public VideoPlayer(
      @NonNull VideoPlayerCallbacks events,
      @NonNull MediaItem mediaItem,
      @NonNull VideoPlayerOptions options,
      @Nullable SurfaceProducer surfaceProducer,
      @NonNull ExoPlayerProvider exoPlayerProvider) {
    this.videoPlayerEvents = events;
    this.surfaceProducer = surfaceProducer;
    exoPlayer = exoPlayerProvider.get();

    // Try to get the track selector from the ExoPlayer if it was built with one
    if (exoPlayer.getTrackSelector() instanceof DefaultTrackSelector) {
      trackSelector = (DefaultTrackSelector) exoPlayer.getTrackSelector();
    }

    exoPlayer.setMediaItem(mediaItem);
    exoPlayer.prepare();
    exoPlayer.addListener(createExoPlayerEventListener(exoPlayer, surfaceProducer));
    setAudioAttributes(exoPlayer, options.mixWithOthers);
  }

  public void setDisposeHandler(@Nullable DisposeHandler handler) {
    disposeHandler = handler;
  }

  @NonNull
  protected abstract ExoPlayerEventListener createExoPlayerEventListener(
      @NonNull ExoPlayer exoPlayer, @Nullable SurfaceProducer surfaceProducer);

  private static void setAudioAttributes(ExoPlayer exoPlayer, boolean isMixMode) {
    exoPlayer.setAudioAttributes(
        new AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
        !isMixMode);
  }

  @Override
  public void play() {
    exoPlayer.play();
  }

  @Override
  public void pause() {
    exoPlayer.pause();
  }

  @Override
  public void setLooping(boolean looping) {
    exoPlayer.setRepeatMode(looping ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
  }

  @Override
  public void setVolume(double volume) {
    float bracketedValue = (float) Math.max(0.0, Math.min(1.0, volume));
    exoPlayer.setVolume(bracketedValue);
  }

  @Override
  public void setPlaybackSpeed(double speed) {
    // We do not need to consider pitch and skipSilence for now as we do not handle them and
    // therefore never diverge from the default values.
    final PlaybackParameters playbackParameters = new PlaybackParameters((float) speed);

    exoPlayer.setPlaybackParameters(playbackParameters);
  }

  @Override
  public long getCurrentPosition() {
    return exoPlayer.getCurrentPosition();
  }

  @Override
  public long getBufferedPosition() {
    return exoPlayer.getBufferedPosition();
  }

  @Override
  public void seekTo(long position) {
    exoPlayer.seekTo(position);
  }

  @NonNull
  public ExoPlayer getExoPlayer() {
    return exoPlayer;
  }

  @UnstableApi
  @Override
  public @NonNull NativeAudioTrackData getAudioTracks() {
    List<ExoPlayerAudioTrackData> audioTracks = new ArrayList<>();

    // Get the current tracks from ExoPlayer
    Tracks tracks = exoPlayer.getCurrentTracks();

    // Iterate through all track groups
    for (int groupIndex = 0; groupIndex < tracks.getGroups().size(); groupIndex++) {
      Tracks.Group group = tracks.getGroups().get(groupIndex);

      // Only process audio tracks
      if (group.getType() == C.TRACK_TYPE_AUDIO) {
        for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
          Format format = group.getTrackFormat(trackIndex);
          boolean isSelected = group.isTrackSelected(trackIndex);

          // Create audio track data with metadata
          ExoPlayerAudioTrackData audioTrack =
              new ExoPlayerAudioTrackData(
                  (long) groupIndex,
                  (long) trackIndex,
                  format.label,
                  format.language,
                  isSelected,
                  format.bitrate != Format.NO_VALUE ? (long) format.bitrate : null,
                  format.sampleRate != Format.NO_VALUE ? (long) format.sampleRate : null,
                  format.channelCount != Format.NO_VALUE ? (long) format.channelCount : null,
                  format.codecs != null ? format.codecs : null);

          audioTracks.add(audioTrack);
        }
      }
    }
    return new NativeAudioTrackData(audioTracks);
  }

  @UnstableApi
  @Override
  public void selectAudioTrack(long groupIndex, long trackIndex) {
    if (trackSelector == null) {
      Log.w("VideoPlayer", "Cannot select audio track: track selector is null");
      return;
    }

    try {

      // Get current tracks
      Tracks tracks = exoPlayer.getCurrentTracks();

      if (groupIndex >= tracks.getGroups().size()) {
        Log.w(
            "VideoPlayer",
            "Cannot select audio track: groupIndex "
                + groupIndex
                + " is out of bounds (available groups: "
                + tracks.getGroups().size()
                + ")");
        return;
      }

      Tracks.Group group = tracks.getGroups().get((int) groupIndex);

      // Verify it's an audio track and the track index is valid
      if (group.getType() != C.TRACK_TYPE_AUDIO || (int) trackIndex >= group.length) {
        if (group.getType() != C.TRACK_TYPE_AUDIO) {
          Log.w(
              "VideoPlayer",
              "Cannot select audio track: group at index "
                  + groupIndex
                  + " is not an audio track (type: "
                  + group.getType()
                  + ")");
        } else {
          Log.w(
              "VideoPlayer",
              "Cannot select audio track: trackIndex "
                  + trackIndex
                  + " is out of bounds (available tracks in group: "
                  + group.length
                  + ")");
        }
        return;
      }

      // Get the track group and create a selection override
      TrackGroup trackGroup = group.getMediaTrackGroup();
      TrackSelectionOverride override = new TrackSelectionOverride(trackGroup, (int) trackIndex);

      // Apply the track selection override
      trackSelector.setParameters(
          trackSelector.buildUponParameters().setOverrideForType(override).build());

    } catch (ArrayIndexOutOfBoundsException e) {
      Log.w(
          "VideoPlayer",
          "Cannot select audio track: invalid indices (groupIndex: "
              + groupIndex
              + ", trackIndex: "
              + trackIndex
              + "). "
              + e.getMessage());
    }
  }

  @UnstableApi
  @Override
  public @NonNull NativeVideoTrackData getVideoTracks() {
    List<ExoPlayerVideoTrackData> videoTracks = new ArrayList<>();

    // Get the current tracks from ExoPlayer
    Tracks tracks = exoPlayer.getCurrentTracks();

    // Check if we have a manual override for video tracks (not in auto mode)
    boolean hasManualOverride = false;
    TrackGroup manuallySelectedTrackGroup = null;
    int manuallySelectedTrackIndex = -1;

    if (trackSelector != null) {
      DefaultTrackSelector.Parameters parameters = trackSelector.getParameters();
      // Check if there's an override for video track type
      for (Map.Entry<TrackGroup, TrackSelectionOverride> entry :
          parameters.overrides.entrySet()) {
        TrackGroup trackGroup = entry.getKey();
        TrackSelectionOverride override = entry.getValue();
        // Check if this override is for a video track
        if (trackGroup.length > 0 && trackGroup.getFormat(0).sampleMimeType != null) {
          String mimeType = trackGroup.getFormat(0).sampleMimeType;
          if (mimeType.startsWith("video/")) {
            hasManualOverride = true;
            manuallySelectedTrackGroup = trackGroup;
            // Get the first selected track index from the override
            if (override.trackIndices.size() > 0) {
              manuallySelectedTrackIndex = override.trackIndices.get(0);
            }
            break;
          }
        }
      }
    }

    // Iterate through all track groups
    for (int groupIndex = 0; groupIndex < tracks.getGroups().size(); groupIndex++) {
      Tracks.Group group = tracks.getGroups().get(groupIndex);

      // Only process video tracks
      if (group.getType() == C.TRACK_TYPE_VIDEO) {
        TrackGroup trackGroup = group.getMediaTrackGroup();

        for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
          Format format = group.getTrackFormat(trackIndex);

          // A track is only "selected" if:
          // 1. We have a manual override (not in auto mode), AND
          // 2. This track group matches the manually selected group, AND
          // 3. This track index matches the manually selected index
          boolean isSelected =
              hasManualOverride
                  && trackGroup.equals(manuallySelectedTrackGroup)
                  && trackIndex == manuallySelectedTrackIndex;

          // Create video track data with metadata
          ExoPlayerVideoTrackData videoTrack =
              new ExoPlayerVideoTrackData(
                  (long) groupIndex,
                  (long) trackIndex,
                  format.label,
                  isSelected,
                  format.bitrate != Format.NO_VALUE ? (long) format.bitrate : null,
                  format.width != Format.NO_VALUE ? (long) format.width : null,
                  format.height != Format.NO_VALUE ? (long) format.height : null,
                  format.frameRate != Format.NO_VALUE ? (double) format.frameRate : null,
                  format.codecs != null ? format.codecs : null);

          videoTracks.add(videoTrack);
        }
      }
    }
    return new NativeVideoTrackData(videoTracks);
  }

  @UnstableApi
  @Override
  public void selectVideoTrack(long groupIndex, long trackIndex) {
    if (trackSelector == null) {
      Log.w("VideoPlayer", "Cannot select video track: track selector is null");
      return;
    }

    try {
      // Special handling for auto quality selection
      if (groupIndex == -1 && trackIndex == -1) {
        // Clear video track override to enable adaptive streaming
        trackSelector.setParameters(
            trackSelector.buildUponParameters().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build());
        return;
      }

      // Get current tracks
      Tracks tracks = exoPlayer.getCurrentTracks();

      if (groupIndex >= tracks.getGroups().size()) {
        Log.w(
            "VideoPlayer",
            "Cannot select video track: groupIndex "
                + groupIndex
                + " is out of bounds (available groups: "
                + tracks.getGroups().size()
                + ")");
        return;
      }

      Tracks.Group group = tracks.getGroups().get((int) groupIndex);

      // Verify it's a video track and the track index is valid
      if (group.getType() != C.TRACK_TYPE_VIDEO || (int) trackIndex >= group.length) {
        if (group.getType() != C.TRACK_TYPE_VIDEO) {
          Log.w(
              "VideoPlayer",
              "Cannot select video track: group at index "
                  + groupIndex
                  + " is not a video track (type: "
                  + group.getType()
                  + ")");
        } else {
          Log.w(
              "VideoPlayer",
              "Cannot select video track: trackIndex "
                  + trackIndex
                  + " is out of bounds (available tracks in group: "
                  + group.length
                  + ")");
        }
        return;
      }

      // Get the track group and create a selection override
      TrackGroup trackGroup = group.getMediaTrackGroup();
      TrackSelectionOverride override = new TrackSelectionOverride(trackGroup, (int) trackIndex);

      // Check if the new track has different dimensions than the current track
      Format currentFormat = exoPlayer.getVideoFormat();
      Format newFormat = trackGroup.getFormat((int) trackIndex);
      boolean dimensionsChanged =
          currentFormat != null
              && (currentFormat.width != newFormat.width
                  || currentFormat.height != newFormat.height);

      // When video dimensions change, we need to force a complete renderer reset to avoid
      // surface rendering issues. We do this by temporarily disabling the video track type,
      // which causes ExoPlayer to release the current video renderer and MediaCodec decoder.
      // After a brief delay, we re-enable video with the new track selection, which creates
      // a fresh renderer properly configured for the new dimensions.
      if (dimensionsChanged) {
        final boolean wasPlaying = exoPlayer.isPlaying();
        final long currentPosition = exoPlayer.getCurrentPosition();

        // Disable video track type to force renderer release
        trackSelector.setParameters(
            trackSelector
                .buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build());

        // Re-enable video with the new track selection after allowing renderer to release
        new Handler(Looper.getMainLooper())
            .postDelayed(
                () -> {
                  trackSelector.setParameters(
                      trackSelector
                          .buildUponParameters()
                          .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                          .setOverrideForType(override)
                          .build());

                  // Restore playback state
                  exoPlayer.seekTo(currentPosition);
                  if (wasPlaying) {
                    exoPlayer.play();
                  }
                },
                150);
        return;
      }

      // Apply the track selection override normally if dimensions haven't changed
      trackSelector.setParameters(
          trackSelector.buildUponParameters().setOverrideForType(override).build());

    } catch (ArrayIndexOutOfBoundsException e) {
      Log.w(
          "VideoPlayer",
          "Cannot select video track: invalid indices (groupIndex: "
              + groupIndex
              + ", trackIndex: "
              + trackIndex
              + "). "
              + e.getMessage());
    }
  }

  public void dispose() {
    if (disposeHandler != null) {
      disposeHandler.onDispose();
    }
    exoPlayer.release();
  }
}
