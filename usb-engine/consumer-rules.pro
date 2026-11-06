# JNI binds UsbStreamEngine's native methods by class and method name
# (Java_io_github_sumirenokai_vesqen_usb_engine_UsbStreamEngine_native...), so R8 must not rename them.
-keepclasseswithmembernames class io.github.sumirenokai.vesqen.usb.engine.UsbStreamEngine {
    native <methods>;
}
