## 0.3.3

* Update to null safety

## 0.3.2

* Fix up IOS to correctly handle recording/streaming independently.
* Add in adaptive bit rate to IOS
* Add in the stats callback (although less data to return)

## 0.3.1

* Streams correctly in all directions now with openGL.  Defaults to openGL being on
in the example app
* Changed the way the preview is displayed to do a boxrotation on the preview widget.
Still a race condition here that means sometimes it doesn't update correctly.

## 0.3.0

* BREAKING CHANGE: moved the useOpenGL call to the initialiation
of the sytem and not to each call.
* Allow the recording/streaming to work independently on
android.
* Change how the recording/streaming happens to avoid issues with
the camera2 api.

## 0.2.3

* Fix restarting on android.  Can stop and restart now and
it all works.

## 0.2.2

* Fix the streaming to work with android.
* Photos no longer work while streaming (hate the android 
  camera api)

## 0.2.1

* Add in github workflows to test/publish the package
* Copy the video encoder from the pedro library and update
  to make it not message with sizes for the surface, but
  include the rotation to make rotation work without opengl.
* Fix up issues with stopping on Android.

## 0.2.0

* Fixed up issues with the stopVideoStreaming api.
* Added a flag to use an openGL surface on android to allow for
  correct rotation when encoding, also sets up to do filters.

## 0.1.9

* Added in a method to record and stream at the same time.
* Fixed issues with streaming/previews not working correctly
* Added in better error handling when the recording/streaming is happening.

## 0.1.8

* Can take a photo at the same time as streaming with out interrupting things.
* Setting up to record and stream at the same time, right now it still stops
  the video when the record starts.  Laying the ground work for all three
  pieces to be independent.

## 0.1.7

* Add in the ability to set the streaming preset on creation.
* Allow setting the bitrrate on stream creation.

## 0.1.6

* Fix the resolution in the android side to correctly stream with the camera
  size itself.

## 0.1.5

* Add in retries and disconnect processing for android on errors.

## 0.1.4

* Fix a couple of issues with running on the android around events.

## 0.1.3

* Fix the length of the description.
* Fix the android build.

## 0.1.2

* Update based on health warnings from pub.dev

## 0.1.1

* Fix iOS build.

## 0.1.0

* First version of the system adding in basic rtmp streaming
to the camera plugin.

