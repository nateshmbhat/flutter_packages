// Copyright 2013 The Flutter Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';

/// A demo page that showcases video track (quality) selection functionality.
class VideoTracksDemo extends StatefulWidget {
  /// Creates a VideoTracksDemo widget.
  const VideoTracksDemo({super.key});

  @override
  State<VideoTracksDemo> createState() => _VideoTracksDemoState();
}

class _VideoTracksDemoState extends State<VideoTracksDemo> {
  VideoPlayerController? _controller;
  List<VideoTrack> _videoTracks = <VideoTrack>[];
  bool _isLoading = false;
  String? _error;

  // Track previous state to detect relevant changes
  bool _wasPlaying = false;
  bool _wasInitialized = false;

  // Sample HLS video URLs with multiple quality variants
  static const List<String> _sampleVideos = <String>[
    // Apple's test HLS stream with multiple bitrates
    'https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8',
    // Another test stream
    'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8',
  ];

  int _selectedVideoIndex = 0;

  @override
  void initState() {
    super.initState();
    _initializeVideo();
  }

  Future<void> _initializeVideo() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });

    try {
      await _controller?.dispose();

      final VideoPlayerController controller = VideoPlayerController.networkUrl(
        Uri.parse(_sampleVideos[_selectedVideoIndex]),
      );
      _controller = controller;

      await controller.initialize();

      // Add listener for video player state changes
      _controller!.addListener(_onVideoPlayerValueChanged);

      // Initialize tracking variables
      _wasPlaying = _controller!.value.isPlaying;
      _wasInitialized = _controller!.value.isInitialized;

      // Get video tracks after initialization
      await _loadVideoTracks();
      if (!mounted) {
        return;
      }
      setState(() {
        _isLoading = false;
      });
    } catch (e) {
      if (!mounted) {
        return;
      }
      setState(() {
        _error = 'Failed to initialize video: $e';
        _isLoading = false;
      });
    }
  }

  Future<void> _loadVideoTracks() async {
    final VideoPlayerController? controller = _controller;
    if (controller == null || !controller.value.isInitialized) {
      return;
    }

    try {
      if (!controller.isVideoTrackSupportAvailable()) {
        if (!mounted) {
          return;
        }
        setState(() {
          _error = 'Video track selection is not supported on this platform';
        });
        return;
      }

      final List<VideoTrack> tracks = await _controller!.getVideoTracks();
      if (!mounted) {
        return;
      }
      setState(() {
        _videoTracks = tracks;
      });
    } catch (e) {
      if (!mounted) {
        return;
      }
      setState(() {
        _error = 'Failed to load video tracks: $e';
      });
    }
  }

  Future<void> _selectVideoTrack(VideoTrack? track) async {
    final VideoPlayerController? controller = _controller;
    if (controller == null) {
      return;
    }

    try {
      await controller.selectVideoTrack(track);

      // Reload tracks to update selection status
      await _loadVideoTracks();

      if (!mounted) {
        return;
      }
      final String trackDescription =
          track == null ? 'Auto' : '${track.label} (${_formatResolution(track)})';
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Selected quality: $trackDescription')));
    } catch (e) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Failed to select video track: $e')));
    }
  }

  String _formatResolution(VideoTrack track) {
    if (track.width != null && track.height != null) {
      return '${track.width}x${track.height}';
    }
    return 'Unknown resolution';
  }

  String _formatBitrate(int? bitrate) {
    if (bitrate == null) {
      return 'Unknown';
    }
    final double mbps = bitrate / 1000000;
    return '${mbps.toStringAsFixed(2)} Mbps';
  }

  void _onVideoPlayerValueChanged() {
    if (!mounted || _controller == null) {
      return;
    }

    final VideoPlayerValue currentValue = _controller!.value;
    bool shouldUpdate = false;

    // Check for relevant state changes that affect UI
    if (currentValue.isPlaying != _wasPlaying) {
      _wasPlaying = currentValue.isPlaying;
      shouldUpdate = true;
    }

    if (currentValue.isInitialized != _wasInitialized) {
      _wasInitialized = currentValue.isInitialized;
      shouldUpdate = true;
    }

    // Only call setState if there are relevant changes
    if (shouldUpdate) {
      setState(() {});
    }
  }

  @override
  void dispose() {
    _controller?.removeListener(_onVideoPlayerValueChanged);
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Video Quality Selection Demo'),
        backgroundColor: Theme.of(context).colorScheme.inversePrimary,
      ),
      body: Column(
        children: <Widget>[
          // Video selection dropdown
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: DropdownMenu<int>(
              initialSelection: _selectedVideoIndex,
              label: const Text('Select Video'),
              inputDecorationTheme: const InputDecorationTheme(
                border: OutlineInputBorder(),
              ),
              dropdownMenuEntries:
                  _sampleVideos.indexed.map(((int, String) record) {
                    final (int index, _) = record;
                    return DropdownMenuEntry<int>(
                      value: index,
                      label: 'Video ${index + 1}',
                    );
                  }).toList(),
              onSelected: (int? value) {
                if (value != null && value != _selectedVideoIndex) {
                  setState(() {
                    _selectedVideoIndex = value;
                  });
                  _initializeVideo();
                }
              },
            ),
          ),

          // Video player
          Expanded(
            flex: 2,
            child: ColoredBox(color: Colors.black, child: _buildVideoPlayer()),
          ),

          // Video tracks list
          Expanded(flex: 3, child: _buildVideoTracksList()),
        ],
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: _loadVideoTracks,
        tooltip: 'Refresh Video Tracks',
        child: const Icon(Icons.refresh),
      ),
    );
  }

  Widget _buildVideoPlayer() {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }

    if (_error != null) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Icon(Icons.error, size: 48, color: Colors.red[300]),
            const SizedBox(height: 16),
            Text(
              _error!,
              style: const TextStyle(color: Colors.white),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 16),
            ElevatedButton(onPressed: _initializeVideo, child: const Text('Retry')),
          ],
        ),
      );
    }

    final VideoPlayerController? controller = _controller;
    if (controller?.value.isInitialized ?? false) {
      return Stack(
        alignment: Alignment.center,
        children: <Widget>[
          AspectRatio(
            aspectRatio: controller!.value.aspectRatio,
            child: VideoPlayer(controller),
          ),
          _buildPlayPauseButton(),
        ],
      );
    }

    return const Center(
      child: Text('No video loaded', style: TextStyle(color: Colors.white)),
    );
  }

  Widget _buildPlayPauseButton() {
    final VideoPlayerController? controller = _controller;
    if (controller == null) {
      return const SizedBox.shrink();
    }

    return Container(
      decoration: BoxDecoration(
        color: Colors.black54,
        borderRadius: BorderRadius.circular(30),
      ),
      child: IconButton(
        iconSize: 48,
        color: Colors.white,
        onPressed: () {
          if (controller.value.isPlaying) {
            controller.pause();
          } else {
            controller.play();
          }
        },
        icon: Icon(controller.value.isPlaying ? Icons.pause : Icons.play_arrow),
      ),
    );
  }

  Widget _buildVideoTracksList() {
    return Container(
      padding: const EdgeInsets.all(16.0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              const Icon(Icons.high_quality),
              const SizedBox(width: 8),
              Text(
                'Video Quality (${_videoTracks.length})',
                style: Theme.of(context).textTheme.headlineSmall,
              ),
            ],
          ),
          const SizedBox(height: 16),

          // Auto quality option
          _buildAutoQualityTile(),
          const SizedBox(height: 8),

          if (_videoTracks.isEmpty)
            const Expanded(
              child: Center(
                child: Text(
                  'No video tracks available.\nTry loading an HLS video with multiple quality variants.',
                  textAlign: TextAlign.center,
                  style: TextStyle(fontSize: 16, color: Colors.grey),
                ),
              ),
            )
          else
            Expanded(
              child: ListView.builder(
                itemCount: _videoTracks.length,
                itemBuilder: (BuildContext context, int index) {
                  final VideoTrack track = _videoTracks[index];
                  return _buildVideoTrackTile(track);
                },
              ),
            ),
        ],
      ),
    );
  }

  Widget _buildAutoQualityTile() {
    // Check if auto is currently selected (no tracks are marked as selected)
    final bool isAutoSelected = _videoTracks.every(
      (VideoTrack track) => !track.isSelected,
    );

    return Card(
      color: isAutoSelected ? Colors.blue[50] : null,
      margin: const EdgeInsets.only(bottom: 8.0),
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: isAutoSelected ? Colors.blue : Colors.grey,
          child: Icon(
            isAutoSelected ? Icons.auto_awesome : Icons.auto_fix_high,
            color: Colors.white,
          ),
        ),
        title: Text(
          'Auto (Adaptive)',
          style: TextStyle(
            fontWeight: isAutoSelected ? FontWeight.bold : FontWeight.normal,
          ),
        ),
        subtitle: const Text('Automatically select best quality based on network'),
        trailing:
            isAutoSelected
                ? const Icon(Icons.radio_button_checked, color: Colors.blue)
                : const Icon(Icons.radio_button_unchecked),
        onTap: isAutoSelected ? null : () => _selectVideoTrack(null),
      ),
    );
  }

  Widget _buildVideoTrackTile(VideoTrack track) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8.0),
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor: track.isSelected ? Colors.green : Colors.grey,
          child: Icon(
            track.isSelected ? Icons.check : Icons.videocam,
            color: Colors.white,
          ),
        ),
        title: Text(
          track.label.isNotEmpty ? track.label : 'Track ${track.id}',
          style: TextStyle(
            fontWeight: track.isSelected ? FontWeight.bold : FontWeight.normal,
          ),
        ),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text('ID: ${track.id}'),
            if (track.width != null && track.height != null)
              Text('Resolution: ${track.width}x${track.height}'),
            if (track.bitrate != null) Text('Bitrate: ${_formatBitrate(track.bitrate)}'),
            if (track.frameRate != null)
              Text('Frame Rate: ${track.frameRate!.toStringAsFixed(1)} fps'),
            if (track.codec != null) Text('Codec: ${track.codec}'),
          ],
        ),
        trailing:
            track.isSelected
                ? const Icon(Icons.radio_button_checked, color: Colors.green)
                : const Icon(Icons.radio_button_unchecked),
        onTap: track.isSelected ? null : () => _selectVideoTrack(track),
      ),
    );
  }
}
