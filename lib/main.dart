import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const CallRecorderApp());
}

class CallRecorderApp extends StatelessWidget {
  const CallRecorderApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Call Recorder',
      theme: ThemeData(
        brightness: Brightness.dark,
        useMaterial3: true,
        scaffoldBackgroundColor: const Color(0xFF080C14),
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF6C63FF),
          brightness: Brightness.dark,
        ),
      ),
      home: const HomePage(),
    );
  }
}

class CallRecording {
  final String id;
  final String number;
  final String type;
  final int startedAt;
  final int endedAt;
  final int durationMs;
  final String path;
  final bool audioDetected;

  const CallRecording({
    required this.id,
    required this.number,
    required this.type,
    required this.startedAt,
    required this.endedAt,
    required this.durationMs,
    required this.path,
    required this.audioDetected,
  });

  factory CallRecording.fromMap(Map<dynamic, dynamic> map) {
    return CallRecording(
      id: map['id']?.toString() ?? '',
      number: map['number']?.toString() ?? 'Unknown',
      type: map['type']?.toString() ?? 'Unknown',
      startedAt: (map['startedAt'] as num?)?.toInt() ?? 0,
      endedAt: (map['endedAt'] as num?)?.toInt() ?? 0,
      durationMs: (map['durationMs'] as num?)?.toInt() ?? 0,
      path: map['path']?.toString() ?? '',
      audioDetected:
          map.containsKey('audioDetected')
              ? map['audioDetected'] == true
              : true,
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage>
    with SingleTickerProviderStateMixin {
  static const _channel = MethodChannel('call_recorder/native');

  bool _loading = true;
  bool _permissionsGranted = false;
  bool _armed = false;

  List<CallRecording> _recordings = [];

  Timer? _historyTimer;

  String? _playingPath;
  String? _pausedPath;

  late AnimationController _animationController;
  late Animation<double> _pulse;

  @override
  void initState() {
    super.initState();

    _animationController = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1400),
    )..repeat(reverse: true);

    _pulse = Tween<double>(
      begin: .92,
      end: 1.07,
    ).animate(
      CurvedAnimation(
        parent: _animationController,
        curve: Curves.easeInOut,
      ),
    );

    _channel.setMethodCallHandler(
      _handleNativeEvent,
    );

    WidgetsBinding.instance.addPostFrameCallback((_) {
      _initialize();
    });
  }

  Future<dynamic> _handleNativeEvent(
    MethodCall call,
  ) async {
    if (
      call.method == 'playbackCompleted' ||
      call.method == 'playbackError'
    ) {
      if (!mounted) return;

      setState(() {
        _playingPath = null;
        _pausedPath = null;
      });

      if (call.method == 'playbackError') {
        _showMessage(
          'Playback failed for this recording.',
        );
      }
    }
  }

  Future<void> _initialize() async {
    try {
      bool granted =
          await _channel.invokeMethod<bool>('permissionsGranted') ?? false;

      if (!granted) {
        granted =
            await _channel.invokeMethod<bool>('requestPermissions') ?? false;
      }

      bool armed = false;

      if (granted) {
        armed =
            await _channel.invokeMethod<bool>('armRecorder') ?? false;
      }

      if (!mounted) return;

      setState(() {
        _permissionsGranted = granted;
        _armed = armed;
        _loading = false;
      });

      await _loadHistory();

      _historyTimer?.cancel();

      _historyTimer = Timer.periodic(
        const Duration(seconds: 1),
        (_) => _loadHistory(),
      );
    } catch (e) {
      if (!mounted) return;

      setState(() {
        _loading = false;
      });

      _showMessage('Initialization failed: $e');
    }
  }

  Future<void> _requestPermissions() async {
    try {
      final granted =
          await _channel.invokeMethod<bool>('requestPermissions') ?? false;

      bool armed = false;

      if (granted) {
        armed =
            await _channel.invokeMethod<bool>('armRecorder') ?? false;
      }

      if (!mounted) return;

      setState(() {
        _permissionsGranted = granted;
        _armed = armed;
      });

      if (granted) {
        _showMessage('Automatic recorder is armed.');
      }
    } catch (e) {
      _showMessage('Permission error: $e');
    }
  }

  Future<void> _loadHistory() async {
    try {
      final data =
          await _channel.invokeMethod<List<dynamic>>('getHistory') ?? [];

      final items = data
          .map(
            (e) => CallRecording.fromMap(
              Map<dynamic, dynamic>.from(e as Map),
            ),
          )
          .toList();

      if (!mounted) return;

      setState(() {
        _recordings = items;
      });
    } catch (_) {}
  }

  Future<void> _openSettings() async {
    await _channel.invokeMethod('openSettings');
  }

  Future<void> _play(CallRecording recording) async {
    if (recording.path.isEmpty) return;

    try {
      if (_playingPath == recording.path) {
        if (_pausedPath == recording.path) {
          final resumed =
              await _channel.invokeMethod<bool>(
                'resumePlayback',
              ) ??
              false;

          if (resumed && mounted) {
            setState(() {
              _pausedPath = null;
            });
          }
        } else {
          final paused =
              await _channel.invokeMethod<bool>(
                'pausePlayback',
              ) ??
              false;

          if (paused && mounted) {
            setState(() {
              _pausedPath = recording.path;
            });
          }
        }

        return;
      }

      await _channel.invokeMethod(
        'playRecording',
        {'path': recording.path},
      );

      if (!mounted) return;

      setState(() {
        _playingPath = recording.path;
        _pausedPath = null;
      });

      if (!recording.audioDetected) {
        _showMessage(
          'The file opened, but Android reported no microphone audio during this call.',
        );
      }
    } on PlatformException catch (e) {
      _showMessage(
        e.message ?? 'Could not play recording.',
      );
    } catch (_) {
      _showMessage(
        'Could not play recording.',
      );
    }
  }

  void _showMessage(String message) {
    if (!mounted) return;

    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
      ),
    );
  }

  String _formatDuration(int milliseconds) {
    final duration = Duration(milliseconds: milliseconds);

    final minutes = duration.inMinutes.toString().padLeft(2, '0');
    final seconds =
        (duration.inSeconds % 60).toString().padLeft(2, '0');

    return '$minutes:$seconds';
  }

  String _formatTime(int milliseconds) {
    final date = DateTime.fromMillisecondsSinceEpoch(milliseconds);

    var hour = date.hour;
    final period = hour >= 12 ? 'PM' : 'AM';

    if (hour == 0) {
      hour = 12;
    } else if (hour > 12) {
      hour -= 12;
    }

    final minute = date.minute.toString().padLeft(2, '0');

    return '$hour:$minute $period';
  }

  String _formatDate(int milliseconds) {
    final date = DateTime.fromMillisecondsSinceEpoch(milliseconds);
    final now = DateTime.now();

    if (date.year == now.year &&
        date.month == now.month &&
        date.day == now.day) {
      return 'Today';
    }

    final yesterday = now.subtract(const Duration(days: 1));

    if (date.year == yesterday.year &&
        date.month == yesterday.month &&
        date.day == yesterday.day) {
      return 'Yesterday';
    }

    return '${date.day}/${date.month}/${date.year}';
  }

  String _fileName(String path) {
    if (path.isEmpty) return 'Unknown';

    return path.split('/').last;
  }

  @override
  void dispose() {
    _historyTimer?.cancel();
    _animationController.dispose();

    _channel.setMethodCallHandler(null);
    _channel.invokeMethod('stopPlayback');

    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: _loading
            ? const Center(
                child: CircularProgressIndicator(),
              )
            : RefreshIndicator(
                onRefresh: _loadHistory,
                child: CustomScrollView(
                  physics: const AlwaysScrollableScrollPhysics(),
                  slivers: [
                    SliverToBoxAdapter(
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(
                          20,
                          22,
                          20,
                          12,
                        ),
                        child: _header(),
                      ),
                    ),

                    SliverToBoxAdapter(
                      child: Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 20,
                        ),
                        child: _statusCard(),
                      ),
                    ),

                    SliverToBoxAdapter(
                      child: Padding(
                        padding: const EdgeInsets.fromLTRB(
                          20,
                          28,
                          20,
                          12,
                        ),
                        child: Row(
                          children: [
                            const Expanded(
                              child: Text(
                                'Recording History',
                                style: TextStyle(
                                  fontSize: 20,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                            Text(
                              '${_recordings.length}',
                              style: const TextStyle(
                                color: Colors.white38,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),

                    if (_recordings.isEmpty)
                      SliverFillRemaining(
                        hasScrollBody: false,
                        child: _emptyState(),
                      )
                    else
                      SliverPadding(
                        padding: const EdgeInsets.fromLTRB(
                          16,
                          0,
                          16,
                          40,
                        ),
                        sliver: SliverList(
                          delegate: SliverChildBuilderDelegate(
                            (context, index) {
                              final recording = _recordings[index];

                              return TweenAnimationBuilder<double>(
                                duration: Duration(
                                  milliseconds: 300 + index * 70,
                                ),
                                tween: Tween(
                                  begin: 0,
                                  end: 1,
                                ),
                                builder: (_, value, child) {
                                  return Opacity(
                                    opacity: value,
                                    child: Transform.translate(
                                      offset: Offset(
                                        0,
                                        15 * (1 - value),
                                      ),
                                      child: child,
                                    ),
                                  );
                                },
                                child: _recordingCard(recording),
                              );
                            },
                            childCount: _recordings.length,
                          ),
                        ),
                      ),
                  ],
                ),
              ),
      ),
    );
  }

  Widget _header() {
    return Row(
      children: [
        Container(
          width: 50,
          height: 50,
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            gradient: const LinearGradient(
              colors: [
                Color(0xFF796CFF),
                Color(0xFF5543FF),
              ],
            ),
          ),
          child: const Icon(
            Icons.graphic_eq_rounded,
            size: 29,
          ),
        ),
        const SizedBox(width: 14),
        const Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'Call Recorder',
                style: TextStyle(
                  fontSize: 24,
                  fontWeight: FontWeight.w800,
                ),
              ),
              SizedBox(height: 2),
              Text(
                'Automatic call recording',
                style: TextStyle(
                  color: Colors.white38,
                  fontSize: 13,
                ),
              ),
            ],
          ),
        ),
      ],
    );
  }

  Widget _statusCard() {
    final active = _permissionsGranted && _armed;

    return Container(
      padding: const EdgeInsets.all(22),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Color(0xFF171D2A),
            Color(0xFF10151F),
          ],
        ),
        borderRadius: BorderRadius.circular(25),
        border: Border.all(
          color: Colors.white.withOpacity(.06),
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withOpacity(.20),
            blurRadius: 30,
            offset: const Offset(0, 12),
          ),
        ],
      ),
      child: Column(
        children: [
          Row(
            children: [
              ScaleTransition(
                scale: active
                    ? _pulse
                    : const AlwaysStoppedAnimation(1),
                child: Container(
                  width: 64,
                  height: 64,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: active
                        ? const Color(0xFF16C784)
                            .withOpacity(.13)
                        : Colors.orange.withOpacity(.13),
                    border: Border.all(
                      color: active
                          ? const Color(0xFF16C784)
                              .withOpacity(.40)
                          : Colors.orange.withOpacity(.40),
                    ),
                  ),
                  child: Icon(
                    active
                        ? Icons.mic_rounded
                        : Icons.mic_off_rounded,
                    color: active
                        ? const Color(0xFF16C784)
                        : Colors.orange,
                    size: 30,
                  ),
                ),
              ),
              const SizedBox(width: 17),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      active
                          ? 'Auto Recording Active'
                          : 'Permission Required',
                      style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      active
                          ? 'Recorder service is running in the background.'
                          : 'Grant the required Android permissions.',
                      style: const TextStyle(
                        color: Colors.white54,
                        height: 1.4,
                        fontSize: 13,
                      ),
                    ),
                  ],
                ),
              ),
              Container(
                width: 11,
                height: 11,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: active
                      ? const Color(0xFF16C784)
                      : Colors.orange,
                  boxShadow: [
                    BoxShadow(
                      color: (active
                              ? const Color(0xFF16C784)
                              : Colors.orange)
                          .withOpacity(.6),
                      blurRadius: 10,
                    ),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 20),
          Container(
            padding: const EdgeInsets.all(14),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(14),
              color: Colors.white.withOpacity(.035),
            ),
            child: const Row(
              children: [
                Icon(
                  Icons.auto_awesome_rounded,
                  color: Color(0xFF978DFF),
                  size: 20,
                ),
                SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'No start or stop button needed',
                    style: TextStyle(
                      color: Colors.white70,
                    ),
                  ),
                ),
                Icon(
                  Icons.check_circle,
                  color: Color(0xFF16C784),
                  size: 19,
                ),
              ],
            ),
          ),
          if (!active) ...[
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: FilledButton.icon(
                onPressed: _requestPermissions,
                icon: const Icon(Icons.security_rounded),
                label: const Text('Allow Permissions'),
                style: FilledButton.styleFrom(
                  backgroundColor: const Color(0xFF6C63FF),
                  padding: const EdgeInsets.symmetric(
                    vertical: 14,
                  ),
                ),
              ),
            ),
            TextButton(
              onPressed: _openSettings,
              child: const Text('Open App Settings'),
            ),
          ],
        ],
      ),
    );
  }

  Widget _recordingCard(CallRecording recording) {
    final incoming =
        recording.type.toLowerCase() == 'incoming';

    final selected =
        _playingPath == recording.path;

    final playing =
        selected &&
        _pausedPath != recording.path;

    return Container(
      margin: const EdgeInsets.only(bottom: 11),
      padding: const EdgeInsets.all(15),
      decoration: BoxDecoration(
        color: const Color(0xFF111721),
        borderRadius: BorderRadius.circular(19),
        border: Border.all(
          color: Colors.white.withOpacity(.055),
        ),
      ),
      child: Row(
        children: [
          Container(
            width: 49,
            height: 49,
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(15),
              color: incoming
                  ? const Color(0xFF16C784).withOpacity(.12)
                  : const Color(0xFF7769FF).withOpacity(.14),
            ),
            child: Icon(
              incoming
                  ? Icons.call_received_rounded
                  : Icons.call_made_rounded,
              color: incoming
                  ? const Color(0xFF16C784)
                  : const Color(0xFF9187FF),
            ),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  recording.number.isEmpty
                      ? 'Unknown'
                      : recording.number,
                  style: const TextStyle(
                    fontWeight: FontWeight.w600,
                    fontSize: 15.5,
                  ),
                ),
                const SizedBox(height: 5),
                Row(
                  children: [
                    Text(
                      recording.type,
                      style: TextStyle(
                        color: incoming
                            ? const Color(0xFF16C784)
                            : const Color(0xFF978DFF),
                        fontSize: 12,
                      ),
                    ),
                    const Text(
                      '  •  ',
                      style: TextStyle(
                        color: Colors.white24,
                      ),
                    ),
                    Text(
                      _formatDate(recording.startedAt),
                      style: const TextStyle(
                        color: Colors.white38,
                        fontSize: 12,
                      ),
                    ),
                    const Text(
                      '  •  ',
                      style: TextStyle(
                        color: Colors.white24,
                      ),
                    ),
                    Text(
                      _formatTime(recording.startedAt),
                      style: const TextStyle(
                        color: Colors.white38,
                        fontSize: 12,
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 5),
                Text(
                  recording.audioDetected
                      ? _fileName(recording.path)
                      : 'No microphone audio detected in this file',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    color: recording.audioDetected
                        ? Colors.white24
                        : Colors.orangeAccent.withOpacity(.75),
                    fontSize: 10.5,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(width: 10),
          Column(
            children: [
              InkWell(
                onTap: () => _play(recording),
                borderRadius: BorderRadius.circular(30),
                child: AnimatedContainer(
                  duration: const Duration(
                    milliseconds: 200,
                  ),
                  width: 42,
                  height: 42,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: playing
                        ? const Color(0xFF6C63FF)
                        : Colors.white.withOpacity(.055),
                  ),
                  child: Icon(
                    playing
                        ? Icons.pause_rounded
                        : Icons.play_arrow_rounded,
                  ),
                ),
              ),
              const SizedBox(height: 7),
              Text(
                _formatDuration(recording.durationMs),
                style: const TextStyle(
                  color: Colors.white54,
                  fontSize: 11,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _emptyState() {
    return const Center(
      child: Padding(
        padding: EdgeInsets.only(bottom: 80),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              Icons.graphic_eq_rounded,
              size: 65,
              color: Colors.white12,
            ),
            SizedBox(height: 16),
            Text(
              'No recordings yet',
              style: TextStyle(
                fontWeight: FontWeight.w600,
                fontSize: 17,
              ),
            ),
            SizedBox(height: 6),
            Text(
              'Completed calls will appear here.',
              style: TextStyle(
                color: Colors.white38,
              ),
            ),
          ],
        ),
      ),
    );
  }
}