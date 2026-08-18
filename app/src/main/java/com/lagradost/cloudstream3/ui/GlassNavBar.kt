package com.lagradost.cloudstream3.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding
import com.lagradost.cloudstream3.utils.UIHelper.getResourceColor
import kotlin.math.roundToInt

/**
 * Premium glassmorphism floating pill bottom navigation with an
 * animated, scroll-based collapse/expand behaviour.
 *
 * It ONLY restyles the [BottomNavigationView] (background, tints, shadow and
 * scroll animation). Navigation logic, click handling and the data system
 * are left completely untouched.
 */
class GlassNavBar(
    private val activity: MainActivity,
    private val navView: BottomNavigationView,
) {

    private val density = activity.resources.displayMetrics.density

    // ---- Glass palette -------------------------------------------------
    private val glassFill = 0xFF1B1C20.toInt()          // dark glass base
    private val strokeColor = 0x3DFFFFFF                  // thin white highlight border
    private val inactiveTint = 0xE6FFFFFF.toInt()         // light grey/white inactive icons
    private val labelColor = 0xF2FFFFFF.toInt()           // label text

    private var accentColor = 0
    private var accentRipple = 0
    private var accentIndicator = 0

    // ---- State --------------------------------------------------------
    private var mode = MainActivity.NAV_STYLE_ADAPTIVE
    private var targetFraction = 0f
    private var currentFraction = 0f
    private var animator: ValueAnimator? = null

    private val labelViews = mutableListOf<TextView>()

    // ---- Public API ----------------------------------------------------

    fun applyStyle(mode: Int) {
        this.mode = mode

        accentColor = activity.getResourceColor(R.attr.colorPrimary)
        accentRipple = ColorUtils.setAlphaComponent(accentColor, 0x14)      // ~8% ripple
        accentIndicator = ColorUtils.setAlphaComponent(accentColor, 0x42)   // ~26% indicator

        if (mode == MainActivity.NAV_STYLE_CLASSIC) {
            applyClassic()
        } else {
            applyFloating()
        }

        navView.post { collectLabelViews() }
        render(currentFraction)
    }

    /**
     * Called on vertical scroll.
     * @param dy raw vertical scroll delta in pixels (positive = scrolling down)
     * @param canScrollUp whether the list can still scroll further up
     */
    fun onScroll(dy: Int, canScrollUp: Boolean) {
        if (mode == MainActivity.NAV_STYLE_CLASSIC) return
        if (navView.visibility != View.VISIBLE) return

        // Reached the top of the page -> always restore the full navigation.
        if (!canScrollUp) {
            animateTo(0f)
            return
        }

        // Interpolate directly from scroll position (no show/hide toggle).
        val delta = dy * 0.0016f
        animateTo((targetFraction + delta).coerceIn(0f, 1f))
    }

    // ---- Style application --------------------------------------------

    private fun applyFloating() {
        // Float the pill above the bottom safe area and away from the edges.
        // Replaces the default safe-area listener with our own so the inset is
        // consumed as an outer margin (this is what makes the pill "float").
        ViewCompat.setOnApplyWindowInsetsListener(navView) { _, insets ->
            val bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            navView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                setMargins(
                    (20f * density).roundToInt(),
                    0,
                    (20f * density).roundToInt(),
                    bottomInset + (12f * density).roundToInt()
                )
            }
            insets
        }

        // Fixed, comfortable height (insets are handled by the margin above).
        navView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            height = (70f * density).roundToInt()
        }
        navView.setPadding(0, 0, 0, 0)

        // Frosted glass capsule background.
        navView.background = MaterialShapeDrawable(
            ShapeAppearanceModel.builder()
                .setAllCornerSizes(30f * density)
                .build()
        ).apply {
            fillColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(glassFill, 0xB3))
            setStroke(1f * density, strokeColor)
        }
        navView.elevation = 12f * density

        navView.labelVisibilityMode = when (mode) {
            MainActivity.NAV_STYLE_EXPANDED -> NavigationBarView.LABEL_VISIBILITY_LABELED
            MainActivity.NAV_STYLE_COMPACT -> NavigationBarView.LABEL_VISIBILITY_UNLABELED
            else -> NavigationBarView.LABEL_VISIBILITY_SELECTED // Adaptive
        }

        navView.itemIconSize = (26f * density).roundToInt()
        navView.itemIconTintList = makeSelector(accentColor, inactiveTint)
        navView.itemTextColor = makeSelector(accentColor, labelColor)
        navView.itemActiveIndicatorColor = ColorStateList.valueOf(accentIndicator)
        navView.itemRippleColor = ColorStateList.valueOf(accentRipple)
    }

    private fun applyClassic() {
        // Restore the original full-width bar (safe-area padding included).
        navView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            setMargins(0, 0, 0, 0)
        }
        navView.setPadding(0, 0, 0, 0)
        fixSystemBarsPadding(
            navView,
            R.dimen.nav_view_height,
            padTop = false,
            overlayCutout = false
        )

        navView.background = android.graphics.drawable.ColorDrawable(
            activity.getResourceColor(R.attr.primaryGrayBackground)
        )
        navView.elevation = 8f * density
        navView.labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_UNLABELED

        val defaultTint = makeSelector(
            activity.getResourceColor(R.attr.colorPrimary),
            activity.getResourceColor(R.attr.textColor)
        )
        navView.itemIconTintList = defaultTint
        navView.itemTextColor = defaultTint
        navView.itemActiveIndicatorColor = ColorStateList.valueOf(accentRipple)
        navView.itemRippleColor = ColorStateList.valueOf(accentRipple)
    }

    private fun makeSelector(checked: Int, unchecked: Int): ColorStateList =
        ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(checked, unchecked)
        )

    // ---- Scroll collapse/expand animation -----------------------------

    private fun animateTo(target: Float) {
        targetFraction = target
        if (kotlin.math.abs(targetFraction - currentFraction) < 0.001f) return

        animator?.cancel()
        animator = ValueAnimator.ofFloat(currentFraction, target).apply {
            duration = 280L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { render(it.animatedValue as Float) }
            start()
        }
    }

    private fun render(f: Float) {
        currentFraction = f

        // Smoothly shrink width & height toward a compact centered pill,
        // anchored to the bottom edge so it drifts toward the screen bottom.
        navView.pivotX = navView.width / 2f
        navView.pivotY = navView.height.toFloat()
        navView.scaleX = 1f - 0.30f * f
        navView.scaleY = 1f - 0.38f * f
        navView.translationY = (10f * density) * f

        // Slightly more translucent while collapsed.
        (navView.background as? MaterialShapeDrawable)?.fillColor =
            ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(glassFill, (0xB3 - 0x28 * f).roundToInt())
            )

        // Labels fade + scale out smoothly.
        val labelAlpha = 1f - f
        for (tv in labelViews) {
            tv.alpha = labelAlpha
            tv.scaleY = 0.6f + 0.4f * labelAlpha
        }
    }

    private fun collectLabelViews() {
        labelViews.clear()
        collectTextViews(navView, labelViews)
    }

    private fun collectTextViews(view: View, out: MutableList<TextView>) {
        if (view is TextView) out.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectTextViews(view.getChildAt(i), out)
        }
    }
}
