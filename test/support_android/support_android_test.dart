// Copyright 2019 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import '../camera_testing.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:camera_with_rtmp/camera.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('Support Android Camera', () {
    group('$CameraController', () {
      final List<MethodCall> log = <MethodCall>[];
      setUpAll(() {
        CameraTesting.channel
            .setMockMethodCallHandler((MethodCall methodCall) async {
          log.add(methodCall);
          switch (methodCall.method) {
            case 'Camera#getNumberOfCameras':
              return 3;
            case 'Camera#open':
              return null;
            case 'Camera#getCameraInfo':
              return <dynamic, dynamic>{
                'id': 3,
                'orientation': 90,
                'facing': 'front',
              };
            case 'Camera#startPreview':
              return null;
            case 'Camera#stopPreview':
              return null;
            case 'Camera#release':
              return null;
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

      test('getNumberOfCameras', () async {
        final result = await availableCameras();

        expect(result.length, 3);
        expect(log, <Matcher>[
          isMethodCall(
            '$CameraController#getNumberOfCameras',
            arguments: null,
          )
        ]);
      });

      test('open', () async {
        final cameras = await availableCameras();
        final controller = CameraController(
          cameras.first, ResolutionPreset.medium,
        );
        controller.initialize();

        expect(log, <Matcher>[
          isMethodCall(
            '$CameraController#open',
            arguments: <String, dynamic>{
              'cameraId': 14,
              'cameraHandle': 0,
            },
          )
        ]);
      });
    });
  });
}
