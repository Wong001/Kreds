/* Thin JNI shim onto GP libtor.so's tor_api entry points (verified
 * exported in Task 7 / NOTES.md). dlopen at call time resolves within
 * the APK's classloader namespace, so this shim has no link-time
 * dependency on libtor.so. Returns tor's exit code, or:
 *   -100 dlopen failed   -101 symbol missing   -102 set_command_line failed */
#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>

typedef struct tor_main_configuration_t tor_main_configuration_t;

JNIEXPORT jint JNICALL
Java_expo_modules_tormanager_TorRunner_nativeRunTor(JNIEnv *env, jobject thiz,
                                                    jobjectArray jargs) {
    void *h = dlopen("libtor.so", RTLD_NOW);
    if (!h) return -100;
    tor_main_configuration_t *(*cfg_new)(void) =
        (tor_main_configuration_t *(*)(void))dlsym(h, "tor_main_configuration_new");
    int (*cfg_set)(tor_main_configuration_t *, int, char **) =
        (int (*)(tor_main_configuration_t *, int, char **))
            dlsym(h, "tor_main_configuration_set_command_line");
    int (*run_main)(const tor_main_configuration_t *) =
        (int (*)(const tor_main_configuration_t *))dlsym(h, "tor_run_main");
    void (*cfg_free)(tor_main_configuration_t *) =
        (void (*)(tor_main_configuration_t *))dlsym(h, "tor_main_configuration_free");
    if (!cfg_new || !cfg_set || !run_main || !cfg_free) return -101;

    int argc = (*env)->GetArrayLength(env, jargs);
    char **argv = (char **)calloc((size_t)argc, sizeof(char *));
    for (int i = 0; i < argc; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, jargs, i);
        const char *c = (*env)->GetStringUTFChars(env, js, NULL);
        argv[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, js, c);
        (*env)->DeleteLocalRef(env, js);
    }
    tor_main_configuration_t *cfg = cfg_new();
    int rc = -102;
    if (cfg_set(cfg, argc, argv) == 0) rc = run_main(cfg);
    cfg_free(cfg);
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    return rc;
}
