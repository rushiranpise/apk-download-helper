package dev.rushi.apkdownloadhelper.play

import android.opengl.GLES10
import android.text.TextUtils
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

object EglExtensionProvider {
    val eglExtensions: List<String>
        get() {
            val extensions = mutableSetOf<String>()
            val egl10 = EGLContext.getEGL() as EGL10
            val display = egl10.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            egl10.eglInitialize(display, IntArray(2))

            val count = IntArray(1)
            if (egl10.eglGetConfigs(display, null, 0, count)) {
                val configs = arrayOfNulls<EGLConfig>(count[0])
                if (egl10.eglGetConfigs(display, configs, count[0], count)) {
                    val pbufferAttributes = intArrayOf(
                        EGL10.EGL_WIDTH,
                        EGL10.EGL_PBUFFER_BIT,
                        EGL10.EGL_HEIGHT,
                        EGL10.EGL_PBUFFER_BIT,
                        EGL10.EGL_NONE
                    )
                    val pixmapAttributes = intArrayOf(12440, EGL10.EGL_PIXMAP_BIT, EGL10.EGL_NONE)
                    val value = IntArray(1)

                    for (i in 0 until count[0]) {
                        egl10.eglGetConfigAttrib(display, configs[i], EGL10.EGL_CONFIG_CAVEAT, value)
                        if (value[0] == EGL10.EGL_SLOW_CONFIG) continue

                        egl10.eglGetConfigAttrib(display, configs[i], EGL10.EGL_SURFACE_TYPE, value)
                        if (value[0] and 1 == 0) continue

                        egl10.eglGetConfigAttrib(display, configs[i], EGL10.EGL_RENDERABLE_TYPE, value)
                        if (value[0] and 1 != 0) {
                            addExtensionsForConfig(egl10, display, configs[i], pbufferAttributes, null, extensions)
                        }
                        if (value[0] and 4 != 0) {
                            addExtensionsForConfig(egl10, display, configs[i], pbufferAttributes, pixmapAttributes, extensions)
                        }
                    }
                }
            }

            egl10.eglTerminate(display)
            return extensions.sorted()
        }

    private fun addExtensionsForConfig(
        egl10: EGL10,
        display: EGLDisplay,
        config: EGLConfig?,
        surfaceAttributes: IntArray,
        contextAttributes: IntArray?,
        extensions: MutableSet<String>
    ) {
        val context = egl10.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttributes)
        if (context === EGL10.EGL_NO_CONTEXT) return

        val surface = egl10.eglCreatePbufferSurface(display, config, surfaceAttributes)
        if (surface === EGL10.EGL_NO_SURFACE) {
            egl10.eglDestroyContext(display, context)
            return
        }

        egl10.eglMakeCurrent(display, surface, surface, context)
        val rawExtensions = GLES10.glGetString(7939)
        if (!TextUtils.isEmpty(rawExtensions)) {
            extensions.addAll(rawExtensions.split(" "))
        }
        egl10.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
        egl10.eglDestroySurface(display, surface)
        egl10.eglDestroyContext(display, context)
    }
}
