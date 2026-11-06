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
 * The map bitmap is the Miller cylindrical projection
 * (O. M. Miller, 1942), rendered from Natural Earth land data
 * (public domain), grey land only on a transparent background,
 * so it shows grey-on-white exactly like the design. Antarctica
 * (below lat -60) is not drawn. The projection is defined by
 * exact math, so the dots land on the right countries: the SAME
 * forward formula places the land and the dots (Hsu, 2026-11-06:
 * region locations must be shown exactly, the map must not be
 * wrong - the design picture itself cannot be used because its
 * projection is unknown). The bitmap is drawn at 0.88x the
 * fit-center size (v1.0.16, Hsu 2026-11-06: the pill and
 * Developed moved to the very bottom, so the map view fills the
 * space; 0.88 is a little smaller than the first 0.95 mockup,
 * per Hsu). toXY below uses the same projection. v1.0.18: the
 * design picture's projection was identified as Miller (Hsu,
 * 2026-11-06: "not too tall, not flat either, just right - a
 * real map"; shape-match IoU Miller 0.487 vs Mercator 0.424,
 * Natural Earth 0.392), so the bitmap and the dot math use
 * Miller now. The map is CENTRED between Location and the
 * country pill (Hsu, 2026-11-06: "exactly between the pill and
 * Location") - mapRect() centres the bitmap in the view.
 * uiScale (set by the home fragment) scales the dots and the
 * route line with the rest of the Home UI, so the whole home
 * shrinks/grows uniformly on smaller/bigger phones (Hsu,
 * 2026-11-06: "the whole Home UI must shrink uniformly").
 */
class TugyiRouteMapView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** lon/lat of a place on the map. */
    data class Place(val lon: Double, val lat: Double)

    companion object {
        val MYANMAR = Place(96.0, 21.0)
        val SINGAPORE = Place(103.85, 1.29)

        /** Map position comes from the region list, not hardcoded here. */
        fun placeOf(country: TugyiCountry): Place =
            Place(country.lon, country.lat)

        /** The world is drawn at 0.88x the fit-center size. */
        private const val MAP_PAD = 0.88f

        // Miller bitmap bounds in projected coordinates (same as
        // the render script ~/workspace/tugyi-map/ for the 960x529
        // bitmap). Miller: x = lambda, y = 1.25 * ln(tan(pi/4 +
        // 0.4*phi). BX is +-pi; BY0 is y at lat -60 (Antarctica
        // cut); BY1 is y at lat 90 plus an 8% top margin, so the
        // Arctic tip has white above it instead of reading as cut
        // off. v1.0.18 replaces Natural Earth (and the v1.0.17
        // x1.54 stretch, which Hsu rejected on his phone: the map
        // became too tall) with Miller, the closest real
        // projection to the design picture (IoU 0.487).
        private const val BITMAP_X0 = -3.141592653589793
        private const val BITMAP_X1 = 3.141592653589793
        private const val BITMAP_Y0 = -1.1968
        private const val BITMAP_Y1 = 2.2627
    }

    /** Uniform-shrink scale for the dots and route line, set by
     * the home fragment (1.0 on Hsu's phone). Scales with the
     * rest of the Home UI so the dots stay proportional to the
     * map on smaller/bigger phones. */
    var uiScale: Float = 1f
        set(value) {
            field = value
            invalidate()
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

    /** Map bitmap placement: fit-center scaling horizontally,
     * drawn at 0.88x, CENTRED in the view (equal white above and
     * below). Hsu, 2026-11-06: the map must be exactly between
     * the Location text and the region pill. (v1.0.17 bottom-
     * anchored it; v1.0.18 reverts to centred.) toXY uses this
     * same rect, so the dots stay exact. */
    private fun mapRect(): FloatArray {
        val bw = mapBitmap.width.toFloat()
        val bh = mapBitmap.height.toFloat()
        val scale = min(width / bw, height / bh) * MAP_PAD
        val dw = bw * scale
        val dh = bh * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        return floatArrayOf(left, top, dw, dh)
    }

    // Miller cylindrical forward projection (O. M. Miller, 1942):
    // x = lambda, y = 1.25 * ln(tan(pi/4 + 0.4*phi); phi/lambda
    // in radians. The SAME formula rendered the bitmap, so a
    // projected point lands on the same place on the bitmap as
    // the land does - the dots are exact.
    private fun project(lon: Double, lat: Double): DoubleArray {
        val phi = Math.toRadians(lat)
        val lambda = Math.toRadians(lon)
        val y = 1.25 * Math.log(Math.tan(Math.PI / 4 + 0.4 * phi))
        return doubleArrayOf(lambda, y)
    }

    private fun toXY(place: Place): FloatArray {
        val (left, top, dw, dh) = mapRect()
        val p = project(place.lon, place.lat)
        val x = left + ((p[0] - BITMAP_X0) / (BITMAP_X1 - BITMAP_X0) * dw).toFloat()
        val y = top + ((BITMAP_Y1 - p[1]) / (BITMAP_Y1 - BITMAP_Y0) * dh).toFloat()
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
        // Dots and route line scale with the Home UI (uiScale),
        // so they stay proportional to the map on smaller/bigger
        // phones (Hsu, 2026-11-06: whole Home UI shrinks uniformly).
        val dotR = 12f * uiScale
        routePaint.strokeWidth = 5f * uiScale
        ringPaint.strokeWidth = 3f * uiScale

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

        // Origin dot: real location (grey), always shown.
        dotPaint.color = Color.parseColor("#757575")
        canvas.drawCircle(from[0], from[1], dotR, dotPaint)
        canvas.drawCircle(from[0], from[1], dotR, ringPaint)
        // Destination dot: blue, ONLY when connected. In Hsu's
        // design pictures the disconnected map has no destination
        // dot at all - this is part of making Connected and
        // Disconnected look clearly different (2026-11-05).
        if (connected) {
            dotPaint.color = Color.parseColor("#1E88E5")
            canvas.drawCircle(to[0], to[1], dotR, dotPaint)
            canvas.drawCircle(to[0], to[1], dotR, ringPaint)
        }
    }
}
