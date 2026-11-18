package id.put.pdfbaca

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import androidx.recyclerview.widget.RecyclerView

/** RecyclerView yang lebarnya bisa dipaksa (dipakai di dalam HorizontalScrollView saat zoom). */
class FixedWidthRecyclerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {
    var fixedWidth = 0

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = if (fixedWidth > 0) MeasureSpec.makeMeasureSpec(fixedWidth, MeasureSpec.EXACTLY) else widthSpec
        super.onMeasure(w, heightSpec)
    }
}

/** HorizontalScrollView yang hanya bisa digeser ke samping saat halaman lebih lebar dari layar. */
class PanScrollView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : HorizontalScrollView(context, attrs) {
    var panEnabled = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean =
        panEnabled && super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(ev: MotionEvent): Boolean =
        panEnabled && super.onTouchEvent(ev)
}

/** Menangkap cubit (pinch) dua jari dan ketuk-dua-kali untuk zoom. */
class ZoomFrame @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Listener {
        fun clampFactor(factor: Float): Float
        fun onScalePreview(factor: Float, focusX: Float, focusY: Float)
        fun onScaleFinished(factor: Float, focusX: Float, focusY: Float)
        fun onDoubleTap(x: Float, y: Float)
        fun onSingleTap()
    }

    var listener: Listener? = null

    private var factor = 1f
    private var focusX = 0f
    private var focusY = 0f
    private var blocking = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                factor = 1f
                focusX = detector.focusX
                focusY = detector.focusY
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val raw = factor * detector.scaleFactor
                factor = listener?.clampFactor(raw) ?: raw
                listener?.onScalePreview(factor, focusX, focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                listener?.onScaleFinished(factor, focusX, focusY)
                factor = 1f
            }
        }).apply { isQuickScaleEnabled = false }

    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                listener?.onDoubleTap(e.x, e.y)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                listener?.onSingleTap()
                return true
            }
        })

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (!blocking) tapDetector.onTouchEvent(ev)

        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && !blocking) {
            // Jari kedua menyentuh: hentikan scroll anak, mulai mode cubit.
            blocking = true
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        if (blocking) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                blocking = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}
