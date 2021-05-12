// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:camera_with_rtmp/camera.dart';
import 'package:camera_with_rtmp/new/common/native_texture.dart';
import 'package:mockito/mockito.dart';

import 'camera_testing.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Camera', () {
    final List<MethodCall> log = <MethodCall>[];

    setUpAll(() {
      CameraTesting.channel
          .setMockMethodCallHandler((MethodCall methodCall) async {
        log.add(methodCall);
        switch (methodCall.method) {
          case 'NativeTexture#allocate':
            return 15;
        }

        throw ArgumentError.value(
          methodCall.method,
          'methodCall.method',
          'No method found for',
        );
      });
    });

    setUp(() {
      log.clear();
      CameraTesting.nextHandle = 0;
    });

    group('$CameraController', () {
      test('Initializing a second controller closes the first', () {
        final MockCameraDescription description = MockCameraDescription();

        final  controller1 =
            CameraController(
           description,
              ResolutionPreset.medium,
        );

        controller1.initialize();

        final  controller2 =
            CameraController(
           description,
              ResolutionPreset.medium,
        );

        controller2.initialize();

      });
    });

    group('$NativeTexture', () {
      test('allocate', () async {
        final NativeTexture texture = await NativeTexture.allocate();

        expect(texture.textureId, 15);
        expect(log, <Matcher>[
          isMethodCall(
            '$NativeTexture#allocate',
            arguments: <String, dynamic>{'textureHandle': 0},
          )
        ]);
      });
    });
  });
}

class MockCameraDescription extends Mock implements CameraDescription {
}
