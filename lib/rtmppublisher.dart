import 'dart:async';

import 'package:flutter/services.dart';

class Rtmppublisher {
  static const MethodChannel _channel =
      const MethodChannel('rtmppublisher');

  static Future<String> get platformVersion async {
    final String version = await _channel.invokeMethod('getPlatformVersion');
    return version;
  }
}
