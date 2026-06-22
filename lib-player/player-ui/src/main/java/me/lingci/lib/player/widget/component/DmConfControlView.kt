package me.lingci.lib.player.widget.component

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout.OnTabSelectedListener
import com.google.android.material.tabs.TabLayout.Tab
import me.lingci.lib.base.json.JSON
import me.lingci.lib.base.json.JSONObject
import me.lingci.lib.base.entity.TitleItem
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.SpBase
import me.lingci.lib.base.util.suffix
import me.lingci.lib.player.adapter.FontSelectAdapter
import me.lingci.lib.player.danmaku.PlayerInitializer
import me.lingci.lib.player.ui.databinding.LayoutDmConfControlViewBinding
import me.lingci.lib.player.listener.OnFontChangeListener
import me.lingci.lib.player.ui.R
import xyz.doikki.videoplayer.controller.ControlWrapper
import xyz.doikki.videoplayer.controller.IControlComponent
import java.io.File
import kotlin.math.absoluteValue
import androidx.core.view.isVisible

/**
 * @author : happyc
 * time    : 2023/07/06
 * desc    : 弹幕属性设置
 * version : 1.0
 */
class DmConfControlView : FrameLayout, IControlComponent, OnSeekBarChangeListener {

    companion object {
        private const val TAG = "DmSelectControlView"

        const val TYPE_SIZE = "fontSize"
        const val TYPE_LINE = "showLine"
        const val TYPE_OPACITY = "opacity"
        const val TYPE_STROKE = "stroke"
        const val TYPE_OFFSET = "offset"
        const val TYPE_ROLL = "showRoll"
        const val TYPE_TOP = "showTop"
        const val TYPE_BOTTOM = "showBottom"
        const val TYPE_STYLE = "textStyle"
        const val TYPE_TOP_LINE = "topLine"
        const val TYPE_BOTTOM_LINE = "bottomLine"
        const val TYPE_MARGIN = "margin"
        const val TYPE_VIEW_MARGIN = "viewMargin"
        const val TYPE_SCROLL_SPEED = "scrollSpeed"
        const val TYPE_FPS = "fps"
    }

    private var binding: LayoutDmConfControlViewBinding = LayoutDmConfControlViewBinding.inflate(
        LayoutInflater.from(
            context
        ), this, true
    )
    private lateinit var controlWrapper: ControlWrapper
    private var onValueChange: ((key: String, value: Any) -> Unit)? = null
    private var onFontChange: OnFontChangeListener? = null
    private val spBase by lazy { SpBase(context) }
    private var showAreaMode = 1
    private var customFont: String = ""
    private lateinit var fontSelectAdapter: FontSelectAdapter

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    )

    fun setOnValueChangeListener(onValueChange: ((key: String, value: Any) -> Unit)) {
        this.onValueChange = onValueChange
    }

    fun setOnFontChangeListener(listener: OnFontChangeListener) {
        this.onFontChange = listener
    }

    fun setCustomFont(font: String) {
        this.customFont = font
        changeFont()
    }

    init {
        visibility = GONE
        initView()
        initListener()
        changeTextBold(spBase.dmBold)
        spBase.dmConf?.let {
            initConfValue(it)
        }
    }

    private fun initConfValue(json: String) {
        var confJson = JSON.parseObject(json)
        if (confJson.keys().isEmpty()) {
            confJson = buildDmConf()
            spBase.dmConf = confJson.toJsonStr()
        }
        if (!confJson.getBoolean(TYPE_ROLL)
            && !confJson.getBoolean(TYPE_TOP)
            && !confJson.getBoolean(TYPE_BOTTOM)
        ) {
            confJson.putValue(TYPE_ROLL, true)
            spBase.dmConf = confJson.toJsonStr()
        }
        changeFontSize(confJson.getInt(TYPE_SIZE))
        changeOpacity(confJson.getInt(TYPE_OPACITY))
        changeStroke(confJson.getInt(TYPE_STROKE))
        changeScrollSpeed(confJson.getInt(TYPE_SCROLL_SPEED))
        changeMargin(confJson.getInt(TYPE_MARGIN))
        changeShowRoll(confJson.getBoolean(TYPE_ROLL))
        changeShowTop(confJson.getBoolean(TYPE_TOP))
        changeShowBottom(confJson.getBoolean(TYPE_BOTTOM))
        changeShowLine(confJson.getInt(TYPE_LINE))
        changeTopLine(confJson.getInt(TYPE_TOP_LINE))
        changeBottomLine(confJson.getInt(TYPE_BOTTOM_LINE))
        initShowArea(confJson.getBoolean(TYPE_ROLL))
    }

    private fun buildDmConf(): JSONObject {
        return JSONObject().apply {
            putValue(TYPE_SIZE, 40)
            putValue(TYPE_LINE, 5)
            putValue(TYPE_OPACITY, 75)
            putValue(TYPE_STROKE, 25)
            putValue(TYPE_SCROLL_SPEED, 35)
            putValue(TYPE_ROLL, true)
            putValue(TYPE_TOP, true)
            putValue(TYPE_BOTTOM, true)
            putValue(TYPE_TOP_LINE, PlayerInitializer.Danmu.maxTopLine)
            putValue(TYPE_BOTTOM_LINE, PlayerInitializer.Danmu.maxBottomLine)
            putValue(TYPE_MARGIN, PlayerInitializer.Danmu.margin)
        }
    }

    private fun initShowArea(showed: Boolean) {
        clearShowArea()
        binding.ivShowRoll.isSelected = true
        binding.showAreaShield.isChecked = showed
    }

    private fun changeFontSize(fontSize: Int) {
        binding.fontSize.setProgress(fontSize, true)
        binding.fontSizeValue.text = "$fontSize"
        PlayerInitializer.Danmu.size = fontSize
    }

    private fun changeShowLine(showLine: Int) {
        binding.showLine.setProgress(showLine, true)
        binding.showLineValue.text = "$showLine"
        PlayerInitializer.Danmu.maxLine = showLine
    }

    private fun changeOpacity(opacity: Int) {
        binding.opacity.setProgress(opacity, true)
        binding.opacityValue.text = "$opacity"
        PlayerInitializer.Danmu.alpha = opacity
    }

    private fun changeStroke(stroke: Int) {
        binding.stroke.setProgress(stroke, true)
        binding.strokeValue.text = "$stroke"
        PlayerInitializer.Danmu.stoke = stroke
    }

    private fun changeScrollSpeed(speed: Int) {
        binding.speed.setProgress(speed, true)
        binding.speedValue.text = "$speed"
        PlayerInitializer.Danmu.speed = speed
        onValueChange?.invoke(TYPE_SCROLL_SPEED, speed)
    }

    private fun changeMargin(margin: Int) {
        binding.margin.setProgress(margin, true)
        binding.marginValue.text = "$margin"
        PlayerInitializer.Danmu.margin = margin
    }

    private fun changeShowRoll(showed: Boolean) {
        PlayerInitializer.Danmu.mobileDanmu = showed
    }

    private fun changeShowTop(showed: Boolean) {
        PlayerInitializer.Danmu.topDanmu = showed
    }

    private fun changeShowBottom(showed: Boolean) {
        PlayerInitializer.Danmu.bottomDanmu = showed
    }

    private fun changeTopLine(line: Int) {
        PlayerInitializer.Danmu.maxTopLine = line
    }

    private fun changeBottomLine(line: Int) {
        PlayerInitializer.Danmu.maxBottomLine = line
    }

    private fun changeViewMargin(margin: Int, top: Boolean) {
        if (top) {
            PlayerInitializer.Danmu.viewTopMargin = margin
        } else {
            PlayerInitializer.Danmu.viewBottomMargin = margin
        }
        onValueChange?.invoke(TYPE_VIEW_MARGIN, margin)
    }

    private fun initView() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.fontSize.min = 1
            binding.showLine.min = 1
            binding.opacity.min = 1
        }

        fontSelectAdapter = FontSelectAdapter(arrayListOf())
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = fontSelectAdapter
        fontSelectAdapter.onItemClick { item, _ ->
            binding.recyclerView.visibility = GONE
            setCustomFont(item.name)
            onFontChange?.onFontChange(item.name)
        }
        AppFile(context).buildCustomFolder("font")
            .listFiles { item -> FileOperator.FONT_EXTENSIONS.contains(item.suffix()) }?.map {
                TitleItem(
                    it.nameWithoutExtension, it.path,
                    it.path == customFont
                )
            }?.let {
                fontSelectAdapter.updateData(it)
            }
    }

    private fun initListener() {
        setOnClickListener { switchVib() }
        binding.container.setOnClickListener { }
        binding.actionClose.setOnClickListener { switchVib() }
        // 设置分区
        clearLayout()
        binding.layoutStyle.visibility = VISIBLE
        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(resources.getString(R.string.action_dm_style))
        }, true)
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(resources.getString(R.string.action_dm_show_area))
        }, false)
        binding.tabLayout.addTab(binding.tabLayout.newTab().apply {
            setText(resources.getString(R.string.action_dm_offset))
        }, false)
        val onTabSelectedListener = object :OnTabSelectedListener{
            override fun onTabSelected(tab: Tab?) {
                clearLayout()
                when (binding.tabLayout.selectedTabPosition) {
                    0 -> {
                        binding.layoutStyle.visibility = VISIBLE
                    }
                    1 -> {
                        binding.layoutShowArea.visibility = VISIBLE
                    }
                    2 -> {
                        binding.layoutOffset.visibility = VISIBLE
                    }
                }
            }

            override fun onTabUnselected(tab: Tab?) {

            }

            override fun onTabReselected(tab: Tab?) {

            }
        }
        binding.tabLayout.removeOnTabSelectedListener(onTabSelectedListener)
        binding.tabLayout.addOnTabSelectedListener(onTabSelectedListener)

        // 基础设置
        binding.fontSize.setOnSeekBarChangeListener(this)
        binding.showLine.setOnSeekBarChangeListener(this)
        binding.opacity.setOnSeekBarChangeListener(this)
        binding.stroke.setOnSeekBarChangeListener(this)
        binding.speed.setOnSeekBarChangeListener(this)
        binding.margin.setOnSeekBarChangeListener(this)
        // 字体自定义
        binding.tvFontNormal.isSelected = true
        binding.tvFontNormal.setOnClickListener {
            binding.recyclerView.visibility = GONE
            binding.tvFontNormal.isSelected = true
            binding.tvFontCustom.isSelected = false
            onFontChange?.onFontChange("")
        }
        binding.tvFontCustom.setOnClickListener {
            if (customFont.isBlank()) {
                binding.recyclerView.visibility = VISIBLE
            } else {
                binding.recyclerView.visibility = GONE
                binding.tvFontNormal.isSelected = false
                binding.tvFontCustom.isSelected = true
                onFontChange?.onFontChange(customFont)
            }
        }
        binding.tvFontCustom.setOnLongClickListener{
            binding.recyclerView.visibility = VISIBLE
            true
        }
        binding.showFps.setOnClickListener {
            onValueChange?.invoke(TYPE_FPS, binding.showFps.isChecked)
        }
        // 字体样式
        binding.tvStyleNormal.setOnClickListener {
            changeTextBold(false)
        }
        binding.tvStyleBold.setOnClickListener {
            changeTextBold(true)
        }
        // 滚动弹幕
        binding.ivShowRoll.setOnClickListener {
            clearShowArea()
            binding.ivShowRoll.isSelected = true
            showAreaMode = 1
            changeShowArea(PlayerInitializer.Danmu.mobileDanmu, PlayerInitializer.Danmu.maxLine)
        }
        // 顶部弹幕
        binding.ivShowTop.setOnClickListener {
            clearShowArea()
            binding.ivShowTop.isSelected = true
            showAreaMode = 5
            changeShowArea(PlayerInitializer.Danmu.topDanmu, PlayerInitializer.Danmu.maxTopLine)
        }
        // 底部弹幕
        binding.ivShowBottom.setOnClickListener {
            clearShowArea()
            binding.ivShowBottom.isSelected = true
            showAreaMode = 4
            changeShowArea(
                PlayerInitializer.Danmu.bottomDanmu,
                PlayerInitializer.Danmu.maxBottomLine
            )
        }
        // 弹幕区域显示屏蔽
        binding.showAreaShield.setOnCheckedChangeListener { _, isChecked ->
            when (showAreaMode) {
                1 -> {
                    PlayerInitializer.Danmu.mobileDanmu = isChecked
                    onValueChange?.invoke(TYPE_ROLL, isChecked)
                    saveConfValue(TYPE_ROLL, isChecked)
                }

                5 -> {
                    PlayerInitializer.Danmu.topDanmu = isChecked
                    onValueChange?.invoke(TYPE_TOP, isChecked)
                    saveConfValue(TYPE_TOP, isChecked)
                }

                4 -> {
                    PlayerInitializer.Danmu.bottomDanmu = isChecked
                    onValueChange?.invoke(TYPE_BOTTOM, isChecked)
                    saveConfValue(TYPE_BOTTOM, isChecked)
                }
            }
        }
        // 视图边距
        binding.topMarginValue.text = "${PlayerInitializer.Danmu.viewTopMargin}"
        binding.bottomMarginValue.text = "${PlayerInitializer.Danmu.viewBottomMargin}"
        binding.topMargin.setOnSeekBarChangeListener(this)
        binding.bottomMargin.setOnSeekBarChangeListener(this)

        // 时间偏移
        binding.offsetReset.visibility = GONE
        binding.offsetReset.setOnClickListener {
            binding.offsetReset.visibility = GONE
            binding.offsetValue.text = ""
            PlayerInitializer.Danmu.offsetPosition = 0
            onValueChange?.invoke(TYPE_OFFSET, 0)
        }
        // 偏移量
        binding.offsetAdd10.setOnClickListener {
            setOffset(10000)
        }
        binding.offsetAdd2.setOnClickListener {
            setOffset(2000)
        }
        binding.offsetAdd05.setOnClickListener {
            setOffset(500)
        }
        binding.offsetSub10.setOnClickListener {
            setOffset(-10000)
        }
        binding.offsetSub2.setOnClickListener {
            setOffset(-2000)
        }
        binding.offsetSub05.setOnClickListener {
            setOffset(-500)
        }
    }

    private fun changeFont() {
        if (customFont.isNotBlank()) {
            val file = File(customFont)
            if (file.exists()) {
                binding.tvFontNormal.isSelected = false
                binding.tvFontCustom.isSelected = true
                binding.tvFontCustom.text = file.nameWithoutExtension
                binding.tvFontCustom.typeface = Typeface.createFromFile(file)
                fontSelectAdapter.selected(customFont)
            }
        }
    }

    private fun clearLayout() {
        binding.layoutStyle.visibility = GONE
        binding.layoutShowArea.visibility = GONE
        binding.layoutOffset.visibility = GONE
    }

    private fun clearShowArea() {
        binding.ivShowRoll.isSelected = false
        binding.ivShowTop.isSelected = false
        binding.ivShowBottom.isSelected = false
    }

    private fun changeShowArea(showed: Boolean, line: Int) {
        binding.showAreaShield.isChecked = showed
        binding.showLineValue.text = "$line"
        binding.showLine.progress = if (line == -1) binding.showLine.max else line
    }

    private fun changeTextBold(bold: Boolean) {
        if (bold) {
            binding.tvStyleNormal.isSelected = false
            binding.tvStyleBold.isSelected = true
        } else {
            binding.tvStyleNormal.isSelected = true
            binding.tvStyleBold.isSelected = false
        }
        if (spBase.dmBold == bold) {
            return
        }
        spBase.dmBold = bold
        onValueChange?.invoke(TYPE_STYLE, bold)
    }

    @SuppressLint("SetTextI18n")
    fun setOffset(num: Long) {
        PlayerInitializer.Danmu.offsetPosition.let {
            val offset = it + num
            if (offset == 0L) {
                binding.offsetReset.visibility = GONE
                binding.offsetValue.text = ""
            } else if (offset > 0) {
                binding.offsetReset.visibility = VISIBLE
                binding.offsetValue.text = "提前 ${(offset.absoluteValue.toFloat() / 1000)} s"
            } else {
                binding.offsetReset.visibility = VISIBLE
                binding.offsetValue.text = "延迟 ${(offset.absoluteValue.toFloat() / 1000)} s"
            }
            PlayerInitializer.Danmu.offsetPosition = offset
            onValueChange?.invoke(TYPE_OFFSET, offset.toInt())
        }
    }

    /**
     * 重置偏移为 0：清空 UI 显示，并同步全局 offsetPosition。
     * 供外部（如切换下一集、合并弹幕）在偏移归零时调用，避免 UI 残留旧值。
     */
    fun resetOffset() {
        binding.offsetReset.visibility = GONE
        binding.offsetValue.text = ""
        PlayerInitializer.Danmu.offsetPosition = 0
        onValueChange?.invoke(TYPE_OFFSET, 0)
    }

    /**
     * 应用指定偏移量：根据 offset 更新 UI 显示，并同步全局 offsetPosition。
     * 供外部（如切换轨道时应用 track.offset）调用，确保 UI 与实际生效偏移一致。
     */
    @SuppressLint("SetTextI18n")
    fun applyOffset(offset: Long) {
        if (offset == 0L) {
            binding.offsetReset.visibility = GONE
            binding.offsetValue.text = ""
        } else if (offset > 0) {
            binding.offsetReset.visibility = VISIBLE
            binding.offsetValue.text = "提前 ${(offset.absoluteValue.toFloat() / 1000)} s"
        } else {
            binding.offsetReset.visibility = VISIBLE
            binding.offsetValue.text = "延迟 ${(offset.absoluteValue.toFloat() / 1000)} s"
        }
        PlayerInitializer.Danmu.offsetPosition = offset
        onValueChange?.invoke(TYPE_OFFSET, offset.toInt())
    }

    fun switchVib() {
        if (this.isVisible) {
            this.visibility = GONE
        } else {
            this.visibility = VISIBLE
        }
    }

    private fun saveConfValue(key: String, value: Any) {
        spBase.dmConf?.let {
            val confJson = JSON.parseObject(it)
            confJson.putValue(key, value)
            spBase.dmConf = confJson.toJsonStr()
        }
    }

    override fun attach(controlWrapper: ControlWrapper) {
        this.controlWrapper = controlWrapper
    }

    override fun getView(): View {
        return this
    }

    override fun onVisibilityChanged(isVisible: Boolean, anim: Animation) {}
    override fun onPlayStateChanged(playState: Int) {}
    override fun onPlayerStateChanged(playerState: Int) {}
    override fun setProgress(duration: Int, position: Int) {}
    override fun onLockStateChanged(isLocked: Boolean) {}

    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        when (seekBar) {
            null -> {}
            binding.fontSize -> {
                binding.fontSizeValue.text = "$progress"
                PlayerInitializer.Danmu.size = progress
                onValueChange?.invoke(TYPE_SIZE, progress)
                saveConfValue(TYPE_SIZE, progress)
            }

            binding.showLine -> {
                var line = progress
                if (progress == binding.showLine.max) {
                    line = -1
                }
                binding.showLineValue.text = "$line"
                when (showAreaMode) {
                    1 -> {
                        PlayerInitializer.Danmu.maxLine = line
                        onValueChange?.invoke(TYPE_LINE, line)
                        saveConfValue(TYPE_LINE, line)
                    }

                    5 -> {
                        PlayerInitializer.Danmu.maxTopLine = line
                        onValueChange?.invoke(TYPE_TOP_LINE, line)
                        saveConfValue(TYPE_TOP_LINE, line)
                    }

                    4 -> {
                        PlayerInitializer.Danmu.maxBottomLine = line
                        onValueChange?.invoke(TYPE_BOTTOM_LINE, line)
                        saveConfValue(TYPE_BOTTOM_LINE, line)
                    }
                }
            }

            binding.opacity -> {
                binding.opacityValue.text = "$progress"
                PlayerInitializer.Danmu.alpha = progress
                onValueChange?.invoke(TYPE_OPACITY, progress)
                saveConfValue(TYPE_OPACITY, progress)
            }

            binding.stroke -> {
                binding.strokeValue.text = "$progress"
                PlayerInitializer.Danmu.stoke = progress
                onValueChange?.invoke(TYPE_STROKE, progress)
                saveConfValue(TYPE_STROKE, progress)
            }

            binding.speed -> {
                binding.speedValue.text = "$progress"
                PlayerInitializer.Danmu.speed = progress
                onValueChange?.invoke(TYPE_SCROLL_SPEED, progress)
                saveConfValue(TYPE_SCROLL_SPEED, progress)
            }

            binding.margin -> {
                binding.marginValue.text = "$progress"
                PlayerInitializer.Danmu.margin = progress
                onValueChange?.invoke(TYPE_MARGIN, progress)
                saveConfValue(TYPE_MARGIN, progress)
            }

            binding.topMargin -> {
                binding.topMarginValue.text = "$progress"
                changeViewMargin(progress, true)

            }

            binding.bottomMargin -> {
                binding.bottomMarginValue.text = "$progress"
                changeViewMargin(progress, false)
            }

            else -> {}
        }
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {

    }

    override fun onStopTrackingTouch(seekBar: SeekBar?) {

    }

}