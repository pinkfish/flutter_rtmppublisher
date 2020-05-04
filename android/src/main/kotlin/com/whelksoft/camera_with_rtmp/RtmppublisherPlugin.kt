package com.whelksoft.camera_with_rtmp

import android.app.Activity
import android.os.Build
import androidx.annotation.NonNull
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.PluginRegistry.Registrar
import io.flutter.view.TextureRegistry

interface PermissionStuff {
    fun adddListener(listener: PluginRegistry.RequestPermissionsResultListener);
}

/** RtmppublisherPlugin */
public class RtmppublisherPlugin : FlutterPlugin, ActivityAware {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private var methodCallHandler: MethodCallHandlerImpl? = null
    private var flutterPluginBinding: FlutterPlugin.FlutterPluginBinding? = null

    override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        this.flutterPluginBinding = flutterPluginBinding
    }

    // This static function is optional and equivalent to onAttachedToEngine. It supports the old
    // pre-Flutter-1.12 Android projects. You are encouraged to continue supporting
    // plugin registration via this function while apps migrate to use the new Android APIs
    // post-flutter-1.12 via https://flutter.dev/go/android-project-migration.
    //
    // It is encouraged to share logic between onAttachedToEngine and registerWith to keep
    // them functionally equivalent. Only one of onAttachedToEngine or registerWith will be called
    // depending on the user's project. onAttachedToEngine or registerWith must both be defined
    // in the same class.
    companion object {
        @JvmStatic
        fun registerWith(registrar: Registrar) {
            val plugin = RtmppublisherPlugin();
            plugin.maybeStartListening(
                    registrar.activity(),
                    registrar.messenger(),
                    object : PermissionStuff {
                        override fun adddListener(listener: PluginRegistry.RequestPermissionsResultListener) {
                            registrar.addRequestPermissionsResultListener(listener);
                        }

                    },
                    registrar.view())

        }
    }


    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        flutterPluginBinding = null
    }

    private fun maybeStartListening(
            activity: Activity,
            messenger: BinaryMessenger,
            permissionsRegistry: PermissionStuff,
            textureRegistry: TextureRegistry) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // If the sdk is less than 21 (min sdk for Camera2) we don't register the plugin.
            return
        }
        methodCallHandler = MethodCallHandlerImpl(
                activity, messenger, CameraPermissions(), permissionsRegistry, textureRegistry)
    }

    override fun onDetachedFromActivity() {
        if (methodCallHandler == null) {
            return
        }
        methodCallHandler!!.stopListening();
        methodCallHandler = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        maybeStartListening(
                binding.activity,
                flutterPluginBinding!!.binaryMessenger,
                object : PermissionStuff {
                    override fun adddListener(listener: PluginRegistry.RequestPermissionsResultListener) {
                        binding.addRequestPermissionsResultListener(listener);
                    }

                },

                flutterPluginBinding!!.flutterEngine.renderer
        )
    }

    override fun onDetachedFromActivityForConfigChanges() {
        onDetachedFromActivity()
    }
}
