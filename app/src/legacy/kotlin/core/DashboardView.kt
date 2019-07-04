package core

import android.content.Context
import android.graphics.PorterDuff
import android.os.Handler
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.core.content.ContextCompat.getColorStateList
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import com.github.salomonbrys.kodein.instance
import com.sothree.slidinguppanel.SlidingUpPanelLayout
import gs.environment.inject
import gs.presentation.doAfter
import org.blokada.R
import tunnel.Events
import tunnel.Persistence
import kotlin.math.max
import kotlin.math.min


typealias PanelState = SlidingUpPanelLayout.PanelState

val OPEN_MENU = "DASHBOARD_OPEN_MENU".newEvent()

class DashboardView(
        ctx: Context,
        attributeSet: AttributeSet
) : FrameLayout(ctx, attributeSet), Backable {

    init {
        inflate(context, R.layout.dashboard_content, this)
    }

    private val sliding = findViewById<SlidingUpPanelLayout>(R.id.panel)
    private val bg_colors = findViewById<ColorfulBackground>(R.id.bg_colors)
    private val bg_nav = findViewById<DotsView>(R.id.bg_nav)
    private val bg_start = findViewById<LinearLayout>(R.id.bg_start)
    private val bg_logo = findViewById<LinearLayout>(R.id.bg_logo)
    private val bg_logo_icon = findViewById<ImageView>(R.id.bg_logo_icon)
    private val bg_off_logo = findViewById<ImageView>(R.id.bg_off_logo)
    private val bg_pager = findViewById<VBPagesView>(R.id.bg_pager)
    private val bg_packets = findViewById<PacketsView>(R.id.bg_packets)
    private val bg_start_text = findViewById<TextView>(R.id.bg_start_text)
    private val bg_chevron_left = findViewById<View>(R.id.bg_chevron_left)
    private val bg_chevron_right = findViewById<View>(R.id.bg_chevron_right)
    private val fg_logo_icon = findViewById<ImageView>(R.id.fg_logo_icon)
    private val fg_pager = findViewById<VBPagesView>(R.id.fg_pager)
    private val fg_drag = findViewById<View>(R.id.fg_drag)
    private val fg_nav_panel = findViewById<View>(R.id.fg_nav_panel)

    var notchPx: Int = 0
    var navigationBarPx: Int = 0
    var onSectionClosed = {}

    private val ktx = ctx.ktx("dashboard")

    private val tunnelEvents by lazy { ctx.inject().instance<EnabledStateActor>() }
    private val tun by lazy { ctx.inject().instance<Tunnel>() }

    private val inter = DecelerateInterpolator(2f)
    private var scrolledView: View? = null

    private var lastSubsectionTab = 0
    private var arrowsSwipes = 0

    private val model by lazy {
        DashboardNavigationModel(
                createDashboardSections(ktx),
                on = tun.enabled(),
                onAnchored = { sectionIndex ->
                    ktx.v("onAnchored")
                    setOn(sectionIndex + 1)
                    if (sliding.panelState != PanelState.ANCHORED) sliding.panelState = PanelState.ANCHORED
                },
                onCollapsed = { sectionIndex ->
//                    ktx.v("onCollapsed")
//                    setOff(sectionIndex + 1)
                },
                onMenuOpened = { section, sectionIndex, menu, menuIndex ->
                    ktx.v("onMenuOpened")
                    setMenu(sectionIndex + 1)
                    setMenuNav(section, section.subsections[menuIndex])
                    fg_pager.currentItem = menuIndex
                    bg_pager.lock = true
                    onOpenSection {  }
                },
                onMenuClosed = { sectionIndex ->
                    ktx.v("onMenuClosed")
                    setOn(sectionIndex + 1)
                    bg_pager.lock = false
                    onCloseSection()
                },
                onSectionChanged = { section, sectionIndex ->
                    ktx.v("onSectionChanged")
                    setMainSectionLabelAndMenuIcon(section)
                    bg_pager.currentItem = sectionIndex
                },
                onTurnOn = {
                    sliding.panelState = PanelState.ANCHORED
                },
                onTurnOff = {
//                    sliding.panelState = PanelState.COLLAPSED
                },
                onOpenMenu = {
                    sliding.panelState = PanelState.EXPANDED
                },
                onCloseMenu = {
                    sliding.panelState = PanelState.ANCHORED
                }
        )
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        setupParentContainer()
        setupSlidingPanel()
        setupBg()
        setupExternalEventListeners()
        setupMenu()
        adjustMargins()
        listenToEvents()
    }

    private fun listenToEvents() {
        ktx.on(OPEN_MENU, {
            sliding.panelState = PanelState.EXPANDED
//            model.menuViewPagerSwiped(0)
        })
    }

    private fun setOff(fromColorIndex: Int) {
        ktx.v("setOff")
        bg_colors.onScroll(1f, fromColorIndex, 0)
        bg_nav.alpha = 0f
        fg_logo_icon.alpha = 0f
        bg_start.alpha = 1f
        bg_logo.alpha = 0f
        bg_off_logo.alpha = 1f
        bg_packets.alpha = 0f
        fg_pager.alpha = 0f
        bg_pager.alpha = 0f
        bg_pager.visibility = View.GONE

        val lp = fg_drag.layoutParams as FrameLayout.LayoutParams
        lp.height = LayoutParams.MATCH_PARENT
        lp.topMargin = 0
        fg_drag.layoutParams = lp

        bg_off_logo.animate().alpha(1f).interpolator = inter
        animateStart()
    }

    private fun setOn(toColorIndex: Int) {
        ktx.v("setOn")
        bg_colors.onScroll(1f, 0, toColorIndex)
        bg_nav.alpha = 1f
        fg_logo_icon.alpha = 0.7f
        bg_start.alpha = 0f
        bg_logo.alpha = 0.6f
        bg_off_logo.alpha = 0f
        bg_packets.alpha = 1f
        fg_pager.alpha = 0f
        bg_pager.visibility = View.VISIBLE
        bg_pager.alpha = 1f

        val lp = fg_drag.layoutParams as FrameLayout.LayoutParams
        lp.height = resources.getDimensionPixelSize(R.dimen.dashboard_fg_drag_height)
        lp.topMargin = resources.getDimensionPixelSize(R.dimen.dashboard_fg_drag_margin_top)
        fg_drag.layoutParams = lp
        stopAnimatingStart()
    }

    private fun setMenu(toColorIndex: Int) {
        ktx.v("setMenu")
        bg_colors.onScroll(1f, 0, toColorIndex)
        fg_logo_icon.alpha = 0f
        bg_start.alpha = 0f
        bg_logo.alpha = 1f
        bg_off_logo.alpha = 0f
        bg_packets.alpha = 1f
        fg_pager.alpha = 1f
        bg_pager.alpha = 0f

        val lp = fg_drag.layoutParams as FrameLayout.LayoutParams
        lp.height = context.dpToPx(130)
        lp.topMargin = 0
        fg_drag.layoutParams = lp
    }

    private fun setDragging() {
        fg_pager.alpha = 0f
    }

    //    private var wasAdvanced = false
    private val advanced by lazy { getColorStateList(ctx, R.color.dashboard_menu_advanced) }
    private val tintAdvanced = resources.getColor(R.color.gradient4_c3)
    private val adblocking by lazy { getColorStateList(ctx, R.color.dashboard_menu_adblocking) }
    private val tintAdblocking = resources.getColor(R.color.gradient3_c3)
    private val tintNormal = resources.getColor(R.color.colorText)
    private fun setMainSectionLabelAndMenuIcon(section: DashboardSection) {
        bg_nav.section = makeSectionName(section)
        fg_nav_panel.backgroundTintMode = PorterDuff.Mode.MULTIPLY
        section.run {
            val (icon, isAdvanced) = when (nameResId) {
                R.string.panel_section_advanced -> R.drawable.ic_tune to true
                else -> R.drawable.ic_menu to false
            }
//            if (isAdvanced != wasAdvanced) {
//                wasAdvanced = isAdvanced
            fg_logo_icon.animate().setDuration(200).alpha(0f).doAfter {
                fg_logo_icon.setImageResource(icon)
                val (color1, color2) = when (nameResId) {
                    R.string.panel_section_advanced -> advanced to tintAdvanced
                    R.string.panel_section_ads -> adblocking to tintAdblocking
                    else -> null to tintNormal
                }

                fg_nav_panel.backgroundTintList = color1
                fg_logo_icon.setColorFilter(color2)
                fg_logo_icon.animate().setDuration(200).alpha(0.7f)
            }
//            }
        }

    }

    private fun setupExternalEventListeners() {
        ktx.on(Events.REQUEST) {
            bg_packets.addToHistory(it)
        }

        tunnelEvents.listeners.add(object : IEnabledStateActorListener {
            override fun startActivating() {
                bg_packets.setTunnelState(TunnelState.ACTIVATING)
                bg_logo_icon.setColorFilter(resources.getColor(R.color.colorLogoWaiting))
                model.tunnelActivating()
            }

            override fun finishActivating() {
                bg_packets.setTunnelState(TunnelState.ACTIVE)
                bg_logo_icon.setColorFilter(resources.getColor(android.R.color.transparent))
                Persistence.request.load(0).onSuccess {
                    bg_packets.setRecentHistory(it)
                }
            }

            override fun startDeactivating() {
                bg_packets.setTunnelState(TunnelState.DEACTIVATING)
                bg_logo_icon.setColorFilter(resources.getColor(R.color.colorLogoWaiting))
            }

            override fun finishDeactivating() {
                bg_packets.setTunnelState(TunnelState.INACTIVE)
                bg_logo_icon.setColorFilter(resources.getColor(R.color.colorLogoInactive))
                model.tunnelDeactivated()
            }
        })

        if (tun.tunnelState() !in listOf(TunnelState.DEACTIVATED, TunnelState.INACTIVE)) {
            model.tunnelActivating()
        } else {
            model.tunnelDeactivated()
        }
    }

    private fun setupParentContainer() {
        isFocusable = true
        setDragView(fg_drag)
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
    }

    private fun setupSlidingPanel() {
        sliding.apply {
            panelHeight = resources.getDimensionPixelSize(R.dimen.dashboard_panel_height)
            shadowHeight = 0
            isOverlayed = true

            addPanelSlideListener(object : SlidingUpPanelLayout.PanelSlideListener {
                override fun onPanelSlide(panel: View?, slideOffset: Float) {
                    if (slideOffset < anchorPoint) {
                        val ratio = slideOffset / anchorPoint
                        bg_colors.onScroll(1 - ratio, model.getOpenedSectionIndex() + 1, 0)
                        // bg_start.alpha = 1 - min(1f, ratio)
                        bg_packets.alpha = min(1f, ratio)
                        bg_pager.alpha = min(1f, ratio)
                        fg_logo_icon.alpha = min(0.7f, ratio)
                    } else {
                        fg_nav_panel.alpha = max(0.7f, slideOffset)
                        bg_pager.alpha = 1 - min(1f, (slideOffset - anchorPoint) * 3)
                        fg_logo_icon.alpha = 0.7f - min(1f, (slideOffset - anchorPoint) * 0.5f)
                        bg_logo.alpha = 0.6f + (slideOffset - anchorPoint) / (0.4f - anchorPoint)
                    }
                }

                private var previousMeaningfulState = PanelState.COLLAPSED
                override fun onPanelStateChanged(panel: View, previousState: PanelState, newState: PanelState) {
                    when (newState) {
                        PanelState.DRAGGING -> {
                            ktx.v("panel dragging")
                            setDragging()
                        }
                        PanelState.ANCHORED -> {
                            ktx.v("panel anchored")
                            model.panelAnchored()
                            if (previousMeaningfulState == PanelState.COLLAPSED && !tun.enabled()) {
                                ktx.v("enabling app as panel got anchored from collapsed state")
                                tun.enabled %= true
                            }
                            previousMeaningfulState = PanelState.ANCHORED
                        }
                        PanelState.COLLAPSED -> {
                            ktx.v("panel collapsed")
//                            model.panelCollapsed()
//                            if (tun.enabled()) tun.enabled %= false
//                            previousMeaningfulState = PanelState.COLLAPSED
                            sliding.panelState = PanelState.ANCHORED
                        }
                        PanelState.EXPANDED -> {
                            ktx.v("panel expanded")
                            model.panelExpanded()
                            if (previousMeaningfulState == PanelState.COLLAPSED && !tun.enabled()) {
                                ktx.v("enabling app as panel got expanded from collapsed state")
                                tun.error %= false
                                tun.enabled %= true
                            }
                            previousMeaningfulState = PanelState.EXPANDED
                        }
                    }
                }
            })
        }
    }

    private fun setupBg() {
//        bg_pager.setOnClickListener { openSelectedSection() }

        bg_pager.pages = model.sections.map { it.dash }

        bg_nav.viewPager = bg_pager
        bg_nav.sleeping = false

        lastSubsectionTab = 0
        bg_pager.currentItem = 0
        bg_pager.offscreenPageLimit = 3

        bg_pager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {}

            override fun onPageScrolled(position: Int, positionOffset: Float, posPixels: Int) {
                val next = position + 1
                bg_colors.onScroll(positionOffset, next, next + 1)
            }

            override fun onPageSelected(position: Int) {
                model.mainViewPagerSwiped(position)
                lastSubsectionTab = 0
                val shouldShow = arrowsSwipes++ < 2
                bg_chevron_left.visibility = if (position == 0 || !shouldShow) View.GONE else View.VISIBLE
                bg_chevron_right.visibility = if (position == bg_pager.pages.count() - 1 || !shouldShow) View.GONE else View.VISIBLE
            }
        })

        bg_chevron_left.setOnClickListener {
            model.mainViewPagerSwipedLeft()
        }

        bg_chevron_right.setOnClickListener {
            model.mainViewPagerSwipedRight()
        }

        model.getOpenedSection().run {
            bg_nav.section = context.getText(nameResId)
            bg_pager.currentItem = model.getOpenedSectionIndex()
        }

        bg_packets.setTunnelState(tun.tunnelState())

        bg_start.setOnClickListener {
            sliding.panelState = PanelState.ANCHORED
        }

        bg_logo_icon.setOnClickListener {
            tun.error %= false
            tun.enabled %= !tun.enabled()
        }

        fg_pager.offscreenPageLimit = 5
    }

    private var adjusted = false
    private fun adjustMargins() {
        if (!adjusted) viewTreeObserver.addOnGlobalLayoutListener(::resize)
    }

    private fun resize() {
        if (adjusted) return
        adjusted = true
        ktx.v("resize")
        val percentHeight = (
                resources.getDimensionPixelSize(R.dimen.dashboard_panel_anchor_size)
                        +  navigationBarPx
                ).toFloat() / height
        sliding.anchorPoint = percentHeight

        bg_logo.addToTopMargin(notchPx)
        bg_pager.addToTopMargin(notchPx)
        bg_nav.addToTopMargin(notchPx)
        fg_pager.addToTopMargin(notchPx)
        fg_logo_icon.addToTopMargin(notchPx)

        bg_pager.addToBottomMargin(navigationBarPx)
        fg_pager.addToBottomMargin(navigationBarPx)

        setNavPanelMargins()

        if (width >= resources.getDimensionPixelSize(R.dimen.dashboard_nav_align_end_width)) {
            bg_nav.alignEnd()
        }
        model.inflateFinished()
    }

    private fun View.addToTopMargin(size: Int) {
        Result.of {
            val lp = layoutParams as RelativeLayout.LayoutParams
            lp.topMargin += size
        }.onFailure {
            val lp = layoutParams as FrameLayout.LayoutParams
            lp.topMargin += size
        }
    }

    private fun View.addToBottomMargin(size: Int) {
        Result.of {
            val lp = layoutParams as RelativeLayout.LayoutParams
            lp.bottomMargin += size
        }.onFailure {
            val lp = layoutParams as FrameLayout.LayoutParams
            lp.bottomMargin += size
        }
    }

    private fun setNavPanelMargins() {
        val lp = fg_nav_panel.layoutParams as FrameLayout.LayoutParams
        lp.bottomMargin = resources.getDimensionPixelSize(R.dimen.dashboard_panel_margin_bottom) - notchPx
        lp.topMargin = resources.getDimensionPixelSize(R.dimen.dashboard_panel_margin_top) + notchPx
    }

    private fun animateStart() {
        val anim = AlphaAnimation(0.2f, 1f)
        anim.duration = 800
        anim.repeatCount = Animation.INFINITE
        anim.repeatMode = Animation.REVERSE
        bg_start_text.startAnimation(anim)
    }

    private fun stopAnimatingStart() {
        bg_start_text.clearAnimation()
    }

    private fun onOpenSection(after: () -> Unit) {
        ktx.v("onopensection")
        bg_nav.viewPager = fg_pager
        bg_nav.sleeping = false
        fg_pager.visibility = View.VISIBLE
        after()
    }

    private fun onCloseSection() {
        ktx.v("onclosesection")

        // Workaround for wrong dots
//        bg_pager.pages = model.sections.map { it.dash }

        model.getOpenedSection().run {
            bg_nav.section = context.getText(nameResId)
            bg_nav.viewPager = bg_pager
            bg_nav.sleeping = false
        }

        fg_pager.visibility = View.GONE
        fg_pager.pages = emptyList()
    }

    private var showTime = 3000L

    private fun flashPlaceholder() {
        hidePlaceholder.removeMessages(0)
        hidePlaceholder.sendEmptyMessageDelayed(0, showTime)
        showTime = max(2000L, showTime - 500L)
    }

    private val hidePlaceholder = Handler {
        true
    }

    private fun setDragView(dragView: View?) {
        sliding.setDragView(dragView)
        dragView?.apply {
            setOnClickListener {
                when {
                    !isEnabled || !sliding.isTouchEnabled -> Unit
                    sliding.panelState == PanelState.EXPANDED -> sliding.panelState = PanelState.ANCHORED
                    else -> sliding.panelState = PanelState.EXPANDED
                }
            }
        }
    }

    private fun updateScrollableView() {
        scrolledView = try {
            val child = fg_pager.getChildAt(0)
            when (child) {
                is Scrollable -> child.getScrollableView()
                is ScrollView -> child
                is RecyclerView -> child
                is ListView -> child
                is GridView -> child
                else -> null
            }
        } catch (e: Exception) {
            null
        }
        sliding.setScrollableView(scrolledView)
    }

    override fun handleBackPressed() = model.backPressed()

    private fun makeSectionName(section: DashboardSection, subsection: DashboardNavItem? = null): String {
        return if (subsection == null) context.getString(section.nameResId)
        else {
            context.getString(subsection.nameResId)
        }
    }

    private fun setupMenu() {
        fg_pager.setOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrollStateChanged(state: Int) {
            }

            override fun onPageScrolled(position: Int, positionOffset: Float, posPixels: Int) {
            }

            override fun onPageSelected(position: Int) {
                model.menuViewPagerSwiped(position)
                lastSubsectionTab = position
                updateScrollableView()
            }
        })
    }

    fun setMenuNav(section: DashboardSection, subsection: DashboardNavItem) {
        ktx.v("setmenunav ${section.nameResId} ${subsection.nameResId}")
        bg_nav.section = makeSectionName(section, subsection)

        val newPages = section.subsections.map {
            it.dash
        }

        if (newPages != fg_pager.pages) {
            fg_pager.pages = newPages
        }

        flashPlaceholder()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when(keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                model.upKey()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                model.downKey()
                true
            }
            else -> false
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when(keyCode) {
            in buttonsEnter -> model.selectKey()
            in buttonsBack -> model.backPressed()
            KeyEvent.KEYCODE_DPAD_LEFT -> model.leftKey()
            KeyEvent.KEYCODE_DPAD_RIGHT -> model.rightKey()
        }
        return true
    }
}

val buttonsEnter = listOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
val buttonsBack = listOf(KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL,
        KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ESCAPE)

