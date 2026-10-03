import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:call_recorder/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('call_recorder/native');

  setUp(() {
    TestDefaultBinaryMessengerBinding
        .instance
        .defaultBinaryMessenger
        .setMockMethodCallHandler(
      channel,
      (call) async {
        switch (call.method) {
          case 'permissionsGranted':
            return true;
          case 'requestPermissions':
            return true;
          case 'armRecorder':
            return true;
          case 'getHistory':
            return <dynamic>[];
          case 'stopPlayback':
            return true;
          default:
            return null;
        }
      },
    );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding
        .instance
        .defaultBinaryMessenger
        .setMockMethodCallHandler(
      channel,
      null,
    );
  });

  testWidgets(
    'renders automatic call recorder home screen',
    (tester) async {
      await tester.pumpWidget(
        const CallRecorderApp(),
      );

      await tester.pump();
      await tester.pump(
        const Duration(milliseconds: 100),
      );

      expect(
        find.text('Call Recorder'),
        findsOneWidget,
      );

      expect(
        find.text('Auto Recording Active'),
        findsOneWidget,
      );

      expect(
        find.text('Recording History'),
        findsOneWidget,
      );
    },
  );
}
