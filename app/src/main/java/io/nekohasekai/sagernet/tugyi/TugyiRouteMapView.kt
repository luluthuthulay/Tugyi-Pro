package io.nekohasekai.sagernet.tugyi

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import io.nekohasekai.sagernet.R
import kotlin.math.min

/**
 * Tugyi Pro map (Hsu, 2026-11-05 17:21).
 *
 * The origin dot is the phone's REAL location (from the real IP,
 * Myanmar normally). The destination dot is the selected country
 * (SG/JP/US, Singapore by default) - and after connecting it moves
 * to the REAL exit location from the exit IP, so if the chosen node
 * actually exits via China, the dot shows China (Hsu, 2026-11-05).
 * When the tunnel is connected, a route line is drawn from the real
 * origin to the real destination, with a dot moving along it.
 * When disconnected, only the two dots show.
 *
 * The map bitmap is equirectangular (lon -180..180, lat 90..-90),
 * rendered from Natural Earth land data (public domain).
 */
class TugyiRouteMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** lon/lat of a place on the map. */
    data class Place(val lon: Double, val lat: Double)

    companion object {
        val MYANMAR = Place(96.0, 21.0)
        val SINGAPORE = Place(103.85, 1.29)
        val JAPAN = Place(139.75, 35.68)
        val USA = Place(-98.0, 39.0)

        fun placeOf(country: TugyiCountry): Place = when (country) {
            TugyiCountry.SG -> SINGAPORE
            TugyiCountry.JP -> JAPAN
            TugyiCountry.US -> USA
        }
    }

    private val mapBitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.tugyi_world_map)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#1E88E5")
    }

    /** Real origin (phone's real IP location, Myanmar normally). */
    var origin: Place = MYANMAR
        set(value) {
            field = value
            invalidate()
        }

    var destination: Place = SINGAPORE
        set(value) {
            field = value
            invalidate()
        }

    var connected: Boolean = false
        set(value) {
            field = value
            if (value) startRouteAnimation() else stopRouteAnimation()
            invalidate()
        }

    private var routeProgress = 0f
    private var animator: ValueAnimator? = null

    private fun startRouteAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                routeProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopRouteAnimation() {
        animator?.cancel()
        animator = null
        routeProgress = 0f
    }

    override fun onDetachedFromWindow() {
        stopRouteAnimation()
        super.onDetachedFromWindow()
    }

    /** Map bitmap placement for fit-center scaling. */
    private fun mapRect(): FloatArray {
        val bw = mapBitmap.width.toFloat()
        val bh = mapBitmap.height.toFloat()
        val scale = min(width / bw, height / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        return floatArrayOf(left, top, dw, dh)
    }

    private fun toXY(place: Place): FloatArray {
        val (left, top, dw, dh) = mapRect()
        val x = left + ((place.lon + 180.0) / 360.0 * dw).toFloat()
        val y = top + ((90.0 - place.lat) / 180.0 * dh).toFloat()
        return floatArrayOf(x, y)
    }

    /** Control point of the route arc: midpoint lifted upwards. */
    private fun arcControl(from: FloatArray, to: FloatArray): FloatArray {
        val mx = (from[0] + to[0]) / 2f
        val my = (from[1] + to[1]) / 2f
        val dx = to[0] - from[0]
        val dy = to[1] - from[1]
        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
        // Lift the arc up by ~35% of the route length.
        return floatArrayOf(mx, my - dist * 0.35f)
    }

    private fun bezierPoint(from: FloatArray, ctrl: FloatArray, to: FloatArray, t: Float): FloatArray {
        val u = 1f - t
        val x = u * u * from[0] + 2f * u * t * ctrl[0] + t * t * to[0]
        val y = u * u * from[1] + 2f * u * t * ctrl[1] + t * t * to[1]
        return floatArrayOf(x, y)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val (left, top, dw, dh) = mapRect()
        canvas.drawBitmap(mapBitmap, null, android.graphics.RectF(left, top, left + dw, top + dh), null)

        val from = toXY(origin)
        val to = toXY(destination)
        val dotR = 12f

        if (connected) {
            // Route arc Myanmar -> destination.
            val ctrl = arcControl(from, to)
            val path = Path().apply {
                moveTo(from[0], from[1])
                quadTo(ctrl[0], ctrl[1], to[0], to[1])
            }
            canvas.drawPath(path, routePaint)
            // Moving dot along the route.
            val p = bezierPoint(from, ctrl, to, routeProgress)
            dotPaint.color = Color.parseColor("#1E88E5")
            canvas.drawCircle(p[0], p[1], dotR, dotPaint)
            canvas.drawCircle(p[0], p[1], dotR, ringPaint)
        }

        // Origin dot: real location (grey).
        dotPaint.color = Color.parseColor("#757575")
        canvas.drawCircle(from[0], from[1], dotR, dotPaint)
        canvas.drawCircle(from[0], from[1], dotR, ringPaint)
        // Destination dot: blue.
        dotPaint.color = Color.parseColor("#1E88E5")
        canvas.drawCircle(to[0], to[1], dotR, dotPaint)
        canvas.drawCircle(to[0], to[1], dotR, ringPaint)
    }
}
