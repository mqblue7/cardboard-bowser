package com.example.vrbrowser

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Minimal VR browser shell.
 *
 * Replaces the PixelCopy-based "duplicate the screen for the other eye"
 * approach with a GPU-only pipeline:
 *
 *   WebView (off-screen, via VirtualDisplay/Presentation)
 *     -> Surface
 *     -> SurfaceTexture (GL_TEXTURE_EXTERNAL_OES)
 *     -> drawn twice (once per eye viewport) in StereoRenderer
 *
 * No Bitmap, no glReadPixels, no CPU round trip per frame.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "VRBrowser"
    }

    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var renderer: StereoRenderer

    private var webView: WebView? = null
    private var presentation: Presentation? = null
    private var virtualDisplay: VirtualDisplay? = null

    // Root view placed in the Presentation (and thus captured by the
    // VirtualDisplay -> Surface pipeline). Holds the WebView plus a
    // fullscreenContainer that HTML5 video fullscreen gets swapped into.
    private var contentContainer: FrameLayout? = null
    private var fullscreenContainer: FrameLayout? = null

    // Tracks the native view WebChromeClient.onShowCustomView() hands us
    // when a <video> goes fullscreen (e.g. the YouTube player's fullscreen
    // button), and the callback used to tell the WebView it's been dismissed.
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        renderer = StereoRenderer(
            onSurfaceReady = { surface -> runOnUiThread { attachWebView(surface) } },
            onFrameAvailable = { glSurfaceView.requestRender() }
        )

        glSurfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
            // Touchscreen taps/drags.
            setOnTouchListener { _, event -> forwardToWebView(event) { dispatchTouchEvent(it) } }
            // Mouse movement/hover and scroll-wheel events.
            setOnGenericMotionListener { _, event ->
                forwardToWebView(event) { dispatchGenericMotionEvent(it) }
            }
        }
        setContentView(buildRootView())
    }

    private var topButtonsOverlay: TopButtonsOverlay? = null
    private var gazeCursorOverlay: GazeCursorOverlay? = null

    /**
     * GLSurfaceView (stereo render), the HUD button overlay, and the gaze
     * cursor on top of both - in that z-order, so the cursor is always
     * visible over the buttons it can dwell-click.
     */
    private fun buildRootView(): FrameLayout {
        val matchParent = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        val root = FrameLayout(this)
        root.addView(glSurfaceView, matchParent)

        val buttons = TopButtonsOverlay(this)
        topButtonsOverlay = buttons
        root.addView(buttons, matchParent)

        val cursor = GazeCursorOverlay(this).apply {
            // clickH is the on/off switch for hover-click itself, so it has
            // to stay dwell-clickable even while hover-click is "off" -
            // otherwise there'd be no way to gaze-click it back on.
            masterTarget = object : HoverClickTarget {
                override fun hitTest(xInEye: Float, yInEye: Float) =
                    buttons.isPointOnClickH(xInEye, yInEye)

                override fun onHoverEnter() = buttons.setClickHHovered(true)
                override fun onHoverExit() = buttons.setClickHHovered(false)
                override fun onHoverClick() {
                    buttons.performClick()
                }
            }
            isHoverClickEnabled = { buttons.hoverClickEnabled }
        }
        gazeCursorOverlay = cursor
        root.addView(cursor, matchParent)

        return root
    }

    /**
     * Both eye viewports show the same off-screen surface, so a touch/click
     * on either half of the screen has to be remapped back into that single
     * surface's coordinate space before being dispatched.
     *
     * This dispatches into contentContainer (the WebView's parent) rather
     * than the WebView directly, so that normal Android hit-testing routes
     * the event to whichever child is actually visible/on top - the WebView
     * normally, or the native fullscreen video controls while a <video> is
     * fullscreened via WebChromeClient.onShowCustomView(). Dispatching
     * straight to the WebView would make those fullscreen controls (e.g.
     * the exit-fullscreen button) untappable.
     *
     * Note: this remaps only the primary pointer, which is enough for taps,
     * drags, mouse clicks, hover, and scroll-wheel input. Multi-touch
     * gestures (e.g. pinch-zoom) are not remapped per-pointer.
     */
    private fun forwardToWebView(
        event: MotionEvent,
        dispatch: View.(MotionEvent) -> Boolean
    ): Boolean {
        val target = contentContainer ?: return false
        val width = glSurfaceView.width
        val height = glSurfaceView.height
        if (width <= 0 || height <= 0) return false

        val halfWidth = width / 2
        val inRightEye = event.x >= halfWidth
        val localX = if (inRightEye) event.x - halfWidth else event.x
        val eyeWidth = (if (inRightEye) width - halfWidth else halfWidth).coerceAtLeast(1)

        val mappedX = (localX / eyeWidth) * StereoRenderer.BROWSER_WIDTH
        val mappedY = (event.y / height) * StereoRenderer.BROWSER_HEIGHT

        val mapped = MotionEvent.obtain(event)
        mapped.offsetLocation(mappedX - event.x, mappedY - event.y)
        val handled = target.dispatch(mapped)
        mapped.recycle()
        return handled
    }

    /** Wires a WebView to render into the Surface the renderer created. */
    private fun attachWebView(browserSurface: Surface) {
        try {
            // The GL surface (and therefore this SurfaceTexture) can be
            // recreated, e.g. after onPause/onResume. Tear down any previous
            // browser plumbing before wiring up the new one.
            presentation?.dismiss()
            virtualDisplay?.release()
            webView?.destroy()
            customView = null
            customViewCallback = null

            val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val densityDpi = resources.displayMetrics.densityDpi

            // Flags = 0 -> a PRIVATE virtual display: only this app may place
            // windows on it. That's exactly what we want (our own
            // Presentation/WebView), and unlike VIRTUAL_DISPLAY_FLAG_PUBLIC it
            // does not require any special permission.
            virtualDisplay = displayManager.createVirtualDisplay(
                "vr_browser_display",
                StereoRenderer.BROWSER_WIDTH,
                StereoRenderer.BROWSER_HEIGHT,
                densityDpi,
                browserSurface,
                0
            )

            val display = virtualDisplay?.display
            if (display == null) {
                Log.e(TAG, "createVirtualDisplay returned null display")
                return
            }

            val newWebView = WebView(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    StereoRenderer.BROWSER_WIDTH,
                    StereoRenderer.BROWSER_HEIGHT
                )
                settings.javaScriptEnabled = true
                settings.javaScriptCanOpenWindowsAutomatically = true
                settings.setSupportMultipleWindows(true)

                // Without this, WebView hands http/https navigation off to
                // the system, which is why links were opening in Chrome or
                // the YouTube app instead of staying in this WebView.
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val scheme = request.url.scheme
                        // false = let the WebView load it normally.
                        // true  = we "handled" it, i.e. ignore non-web
                        //         schemes (intent://, vnd.youtube:,
                        //         market://, ...) instead of launching
                        //         another app.
                        return scheme != "http" && scheme != "https"
                    }
                }

                // Sites that open links via target="_blank" or
                // window.open() otherwise get a detached popup WebView with
                // nowhere to render, which is the other common cause of a
                // blank/white screen after clicking a link. Route those
                // requests back into this same WebView instead.
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message
                    ): Boolean {
                        val transport = resultMsg.obj as WebView.WebViewTransport
                        transport.webView = view
                        resultMsg.sendToTarget()
                        return true
                    }

                    // Called when a <video> (or the page via the Fullscreen
                    // API) goes fullscreen - e.g. tapping YouTube's
                    // fullscreen button. Without this override the default
                    // implementation is a no-op, which is why nothing
                    // happened. `view` is a native Android View (typically
                    // hosting the video surface + native playback controls)
                    // that we need to display ourselves, since this WebView
                    // is off-screen and has no Activity window of its own.
                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                        if (customView != null) {
                            // Already showing one; reject the new request
                            // the same way Chrome does.
                            callback.onCustomViewHidden()
                            return
                        }
                        customView = view
                        customViewCallback = callback

                        fullscreenContainer?.apply {
                            addView(
                                view,
                                FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                                )
                            )
                            visibility = View.VISIBLE
                            bringToFront()
                        }
                        // Hide (not remove) the WebView underneath so the
                        // fullscreen view's own controls get top billing;
                        // the WebView keeps running (audio, JS timers) but
                        // stops drawing.
                        webView?.visibility = View.INVISIBLE
                        glSurfaceView.requestRender()
                    }

                    // Called when the page/user exits fullscreen (e.g. the
                    // player's own exit-fullscreen button, or us calling
                    // customViewCallback.onCustomViewHidden() below).
                    override fun onHideCustomView() {
                        val view = customView ?: return
                        fullscreenContainer?.apply {
                            removeView(view)
                            visibility = View.GONE
                        }
                        webView?.visibility = View.VISIBLE
                        customView = null
                        customViewCallback = null
                        glSurfaceView.requestRender()
                    }
                }

                // Change the URL here.
                loadUrl("https://www.google.com")
            }
            webView = newWebView

            // Root of the Presentation's content: the WebView plus an
            // (initially hidden) layer that fullscreen video gets placed
            // into. Both are captured by the same VirtualDisplay surface,
            // so fullscreen video renders through the same GL pipeline as
            // the regular page - no separate surface/texture needed.
            val container = FrameLayout(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    StereoRenderer.BROWSER_WIDTH,
                    StereoRenderer.BROWSER_HEIGHT
                )
                addView(
                    newWebView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                addView(
                    FrameLayout(this@MainActivity).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        setBackgroundColor(android.graphics.Color.BLACK)
                        visibility = View.GONE
                    }
                )
            }
            contentContainer = container
            fullscreenContainer = container.getChildAt(1) as FrameLayout

            presentation = Presentation(this, display).apply {
                setContentView(container)
                show()
            }
            newWebView.requestFocus()
        } catch (e: Exception) {
            // Surface this in both Logcat and on-screen instead of the app
            // just vanishing with no clue why.
            Log.e(TAG, "Failed to set up off-screen WebView", e)
            runOnUiThread {
                Toast.makeText(this, "Setup failed: " + e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Forwards physical keyboard input (typing into a focused text field on
     * the page, arrow keys, etc.) to the off-screen WebView. Falls back to
     * normal Activity handling for anything the WebView doesn't consume, so
     * keys like Back still work as expected.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val target = webView
        if (target != null && target.dispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Let Back exit fullscreen video instead of closing the app. */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val callback = customViewCallback
        if (customView != null && callback != null) {
            // Tells the WebView the custom view was dismissed; it will
            // call onHideCustomView() back on us to actually remove it.
            callback.onCustomViewHidden()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        glSurfaceView.onPause()
        gazeCursorOverlay?.onHostPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()
        gazeCursorOverlay?.onHostResume()
    }

    override fun onDestroy() {
        presentation?.dismiss()
        virtualDisplay?.release()
        webView?.destroy()
        customView = null
        customViewCallback = null
        contentContainer = null
        fullscreenContainer = null
        super.onDestroy()
    }
}
