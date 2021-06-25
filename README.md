# rtmppublisher

RTMP streaming and camera plugin.

## Getting Started

This plugin is an extension of the flutter 
[camera plugin](https://pub.dev/packages/camera) to add in
rtmp streaming as part of the system.  It works on android and iOS
(but not web).

This means the API Is exactly the same as the camera and 
installation requirements are the same.  The difference exists in an extra API that is startStreaming(URL), it takes an RTMP
URL and starts streaming to that specific URL.

For android I use [rtmp-rtsp-stream-client-java](https://github.com/pedroSG94/rtmp-rtsp-stream-client-java) 
and for iOS I use 
[HaishinKit.swift](https://github.com/shogo4405/HaishinKit.swift)
   
## Features:

* Display live camera preview in a widget.
* Snapshots can be captured and saved to a file.
* Record video.
* Add access to the image stream from Dart.

## Installation

First, add `camera` as a [dependency in your pubspec.yaml file](https://flutter.io/using-packages/).

### iOS

Add two rows to the `ios/Runner/Info.plist`:

* one with the key `Privacy - Camera Usage Description` and a usage description.
* and one with the key `Privacy - Microphone Usage Description` and a usage description.

Or in text format add the key:

```xml
<key>NSCameraUsageDescription</key>
<string>Can I use the camera please?</string>
<key>NSMicrophoneUsageDescription</key>
<string>Can I use the mic please?</string>
```

### Android

Change the minimum Android sdk version to 21 (or higher) in your `android/app/build.gradle` file.

```
minSdkVersion 21
```

Need to add in a section to the packaging options to exclude a file, or gradle will error on building.

```
packagingOptions {
   exclude 'project.clj'
}
```

### Example

Here is a small example flutter app displaying a full screen camera preview.

```dart
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:camera/camera.dart';

List<CameraDescription> cameras;

Future<void> main() async {
  cameras = await availableCameras();
  runApp(CameraApp());
}

class CameraApp extends StatefulWidget {
  @override
  _CameraAppState createState() => _CameraAppState();
}

class _CameraAppState extends State<CameraApp> {
  CameraController controller;

  @override
  void initState() {
    super.initState();
    controller = CameraController(cameras[0], ResolutionPreset.medium);
    controller.initialize().then((_) {
      if (!mounted) {
        return;
      }
      setState(() {});
    });
  }

  @override
  void dispose() {
    controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (!controller.value.isInitialized) {
      return Container();
    }
    return AspectRatio(
        aspectRatio:
        controller.value.aspectRatio,
        child: CameraPreview(controller));
  }
}
```

A more complete example of doing rtmp streaming is in the
[example code](https://github.com/pinkfish/flutter_rtmppublisher/tree/master/example)
