#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint flutter_v2ray.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'flutter_v2ray_client'
  s.version          = '1.0.0'
  s.summary          = 'Flutter client plugin for Xray/V2Ray (Android primary)'
  s.description      = <<-DESC
Android-first Flutter plugin providing V2Ray/Xray client control.
iOS bindings are retained for API compatibility but currently unsupported.
                       DESC
  s.homepage         = 'https://github.com/amir-zr/flutter_v2ray_client'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Amir Ziari' => 'ahz85955@gmail.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform = :ios, '11.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'
end
