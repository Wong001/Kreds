package expo.modules.tormanager

object TorRunner {
    init {
        System.loadLibrary("torjni")
    }

    /** Blocks until tor exits -- run on a dedicated thread. Returns tor's
     *  exit code, or a negative torjni error (see tor_jni.c). */
    external fun nativeRunTor(args: Array<String>): Int
}
