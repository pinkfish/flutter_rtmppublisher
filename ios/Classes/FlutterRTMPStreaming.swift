import Flutter
import UIKit
import AVFoundation
import Accelerate
import CoreMotion
import HaishinKit
import os
import ReplayKit
import VideoToolbox

@objc
public class FlutterRTMPStreaming : NSObject {
  private var rtmpConnection = RTMPConnection()
  private var rtmpStream: RTMPStream!
    private var url: String? = nil
    private var name: String? = nil

  @objc
    public func open(url: String, width: Int, height: Int, bitrate: Int) {
    rtmpStream = RTMPStream(connection: rtmpConnection)
    rtmpConnection.addEventListener(.rtmpStatus, selector:#selector(rtmpStatusHandler), observer: self)
    rtmpConnection.addEventListener(.ioError, selector: #selector(rtmpErrorHandler), observer: self)
        
    let uri = URL(string: url)
    self.name = uri?.pathComponents.last
    var bits = url.components(separatedBy: "/")
    bits.removeLast()
    self.url = bits.joined(separator: "/")
    rtmpStream.videoSettings = [
           .width: width,
           .height: height,
           .profileLevel: kVTProfileLevel_H264_Baseline_AutoLevel,
           .maxKeyFrameIntervalDuration: 2,
           .bitrate: bitrate ?? 160 * 1000
    ]
    rtmpStream.captureSettings = [
      .fps: 24
    ]
    rtmpConnection.connect(self.url ?? "frog")
  }
    
    @objc
       private func rtmpStatusHandler(_ notification: Notification) {
           let e = Event.from(notification)
           guard let data: ASObject = e.data as? ASObject, let code: String = data["code"] as? String else {
               return
           }
        print(e)
     
           switch code {
           case RTMPConnection.Code.connectSuccess.rawValue:
            rtmpStream.publish(name)
            break
           case RTMPConnection.Code.connectFailed.rawValue, RTMPConnection.Code.connectClosed.rawValue: break
           default:
               break
           }
       }
    
    @objc
    private func rtmpErrorHandler(_ notification: Notification) {
        if #available(iOS 10.0, *) {
            os_log("%s", notification.name.rawValue)
        } else {
            // Fallback on earlier versions
        }
    }

  @objc
  public func addVideoData(buffer: CMSampleBuffer) {
    if let description = CMSampleBufferGetFormatDescription(buffer) {
      let dimensions = CMVideoFormatDescriptionGetDimensions(description)
      rtmpStream.videoSettings = [
          .width: dimensions.width,
          .height: dimensions.height,
          .profileLevel: kVTProfileLevel_H264_Baseline_AutoLevel,
          .maxKeyFrameIntervalDuration: 2,
          .bitrate: 3500
      ]
      rtmpStream.captureSettings = [
        .fps: 24
      ]
    }
    rtmpStream.appendSampleBuffer( buffer, withType: .video)
  }

  @objc
  public func addAudioData(buffer: CMSampleBuffer) {
    rtmpStream.appendSampleBuffer( buffer, withType: .audio)
  }

  @objc
  public func close() {
    rtmpConnection.close()
  }
}

/*
class MyRTMPStreamQoSDelagate: RTMPStreamDelegate {
    var bitrate = 1024 * 1024 * 8

    // detect upload insufficent BandWidth
    func didPublishInsufficientBW(_ stream:RTMPStream, withConnection:RTMPConnection) {
          bitrate = bitrate / 2
        stream.videoSettings[.bitrate] = bitrate
    }

    func clear() {
          bitrate = 1024 * 1024 * 8
    }
}
 */

