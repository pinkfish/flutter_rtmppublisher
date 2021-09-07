#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint rtmppublisher.podspec' to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'camera_with_rtmp'
  s.version          = '0.0.1'
  s.summary          = 'FLutter plugin to allow rtmp to work with ios.'
  s.description      = <<-DESC
A new flutter plugin project.
                       DESC
  s.homepage         = 'https://github.com/pinkfish/flutter_rtmppublisher'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'WhelkSoft' => 'pinkfish@gmail.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  s.dependency 'HaishinKit'
  s.platform = :ios, '8.0'

  # Flutter.framework does not contain a i386 slice. Only x86_64 simulators are supported.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'VALID_ARCHS[sdk=iphonesimulator*]' => 'x86_64' }
  s.swift_version = '5.2'
end
