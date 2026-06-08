package me.lingci.lib.player.widget.component

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.Animation
import android.widget.FrameLayout
import android.widget.SeekBar
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import me.lingci.lib.base.entity.TitleItem
import me.lingci.lib.base.storage.entity.FileEntity
import me.lingci.lib.base.util.AppFile
import me.lingci.lib.base.util.FileOperator
import me.lingci.lib.base.util.SpBase
import me.lingci.lib.base.util.newFile
import me.lingci.lib.base.util.suffix
import me.lingci.lib.player.adapter.EpisodeSelectAdapter
import me.lingci.lib.player.adapter.FileSelectAdapter
import me.lingci.lib.player.adapter.FontSelectAdapter
import me.lingci.lib.player.danmaku.PlayerInitializer
import me.lingci.lib.player.ui.R
import me.lingci.lib.player.ui.databinding.LayoutTrackControlViewBinding
import xyz.doikki.videoplayer.controller.ControlWrapper
import xyz.doikki.videoplayer.controller.IControlComponent
import me.lingci.lib.player.subtitle.SubtitleMimeTypes
import java.io.File

/**
 *   @author : lingci
 *   time    : 2026/03/30
 *   desc    : 音频/字幕轨道选择面板，TabLayout切换，共享RecyclerView，内嵌字幕文件选择
 *   version : 1.2
 */
class TrackPanelControlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), IControlComponent {

    companion object {
        const val TAB_AUDIO = 0
        const val TAB_SUBTITLE = 1
        const val TAB_SETTINGS = 2
        val SUBTITLE_EXTENSIONS = arrayListOf(".srt", ".vtt", ".ass", ".ssa")

        fun getSubtitleMimeType(path: String): String? {
            // Use the shared helper so player-ui does not depend on Media3 MimeTypes.
            return SubtitleMimeTypes.fromPath(path)
        }
    }

    private val binding = LayoutTrackControlViewBinding.inflate(
        LayoutInflater.from(context), this, true
    )
    private val audioAdapter = EpisodeSelectAdapter(arrayListOf())
    private val subtitleAdapter = EpisodeSelectAdapter(arrayListOf())
    private val fileSelectAdapter = FileSelectAdapter(ArrayList())
    private val fontSelectAdapter = FontSelectAdapter(arrayListOf())
    private val spBase: SpBase by lazy { SpBase(context) }
    private lateinit var controlWrapper: ControlWrapper

    private var currentTab = TAB_AUDIO
    private var isFileBrowseMode = false
    private var currentFolder: String? = null
    private var subtitleCustomFont = ""

    // name is a legacy UI id field; modularized callers currently pass MediaTrackKey.id through it.
    // position remains only the adapter position shown in the panel.
    private var onChangeTrack: ((tabType: Int, name: String, position: Int) -> Unit)? = null
    private var onSubtitleFileSelected: ((filePath: String) -> Unit)? = null
    private var onAudioLanguageChanged: ((language: String) -> Unit)? = null
    private var onSubtitleLanguageChanged: ((language: String) -> Unit)? = null
    private var onSubtitleFontChanged: ((fontPath: String) -> Unit)? = null
    private var onSubtitleFontSizeChanged: ((size: Int) -> Unit)? = null

    init {
        visibility = GONE
        binding.tvAddTrack.visibility = GONE
        initTabLayout()
        initRecyclerView()
        initFileRecyclerView()
        initSettingsView()
        initListener()
    }

    private fun initTabLayout() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.action_tab_audio))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.action_tab_subtitle))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText(R.string.action_tab_settings))
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.let { switchTab(it.position) }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(context)
        binding.recyclerView.adapter = audioAdapter
        audioAdapter.setOnItemClickListener { item, position ->
            binding.tvCloseTrack.isSelected = false
            onChangeTrack?.invoke(TAB_AUDIO, item.name, position)
            switchVib()
        }
        subtitleAdapter.setOnItemClickListener { item, position ->
            binding.tvCloseTrack.isSelected = false
            onChangeTrack?.invoke(TAB_SUBTITLE, item.name, position)
            switchVib()
        }
    }

    private fun initFileRecyclerView() {
        binding.fileRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.fileRecyclerView.adapter = fileSelectAdapter
        fileSelectAdapter.onItemClick { item: FileEntity, _: Int ->
            if (item.isFile) {
                if (item.returnParent) {
                    changeFileData(File(item.path))
                } else {
                    onSubtitleFileSelected?.invoke(item.path)
                    exitFileBrowseMode()
                }
            } else {
                changeFileData(File(item.path))
            }
        }
        binding.fileFastScroller.attachRecyclerView(binding.fileRecyclerView)
    }

    private fun initSettingsView() {
        // player-ui owns preference display/storage, but applying languages/fonts is delegated to
        // callbacks so this module stays independent of concrete backend APIs.
        val audioLang = spBase.preferredAudioLanguage
        val subtitleLang = spBase.preferredSubtitleLanguage
        subtitleCustomFont = spBase.subtitleFont!!
        val fontSize = spBase.subtitleFontSize

        binding.tvAudioLanguageValue.text = getLanguageDisplayName(audioLang!!)
        binding.tvSubtitleLanguageValue.text = getLanguageDisplayName(subtitleLang!!)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.seekSubtitleFontSize.min = 1
        }
        binding.seekSubtitleFontSize.progress = fontSize
        binding.tvSubtitleFontSizeValue.text = "$fontSize"
        binding.seekSubtitleFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val size = if (progress < 1) 1 else progress
                binding.tvSubtitleFontSizeValue.text = "$size"
                PlayerInitializer.Subtitle.textSize = size
                spBase.subtitleFontSize = size
                onSubtitleFontSizeChanged?.invoke(size)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        initFontSelect()

        binding.tvAudioLanguageValue.setOnClickListener { showLanguageSelectDialog(true) }
        binding.tvSubtitleLanguageValue.setOnClickListener { showLanguageSelectDialog(false) }
    }

    private fun initFontSelect() {
        binding.fontRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.fontRecyclerView.adapter = fontSelectAdapter
        fontSelectAdapter.onItemClick { item, _ ->
            binding.fontRecyclerView.visibility = GONE
            subtitleCustomFont = item.name
            spBase.subtitleFont = item.name
            PlayerInitializer.Subtitle.typefacePath = item.name
            binding.tvSubtitleFontCustom.text = File(item.name).nameWithoutExtension
            binding.tvSubtitleFontCustom.isSelected = true
            binding.tvSubtitleFontNormal.isSelected = false
            onSubtitleFontChanged?.invoke(item.name)
        }
        AppFile(context).buildCustomFolder("font")
            .listFiles { item -> FileOperator.FONT_EXTENSIONS.contains(item.suffix()) }
            ?.map {
                TitleItem(
                    it.nameWithoutExtension, it.path,
                    it.path == subtitleCustomFont
                )
            }?.let {
                fontSelectAdapter.updateData(it)
            }

        binding.tvSubtitleFontNormal.isSelected = subtitleCustomFont.isBlank()
        if (subtitleCustomFont.isNotBlank()) {
            val file = File(subtitleCustomFont)
            if (file.exists()) {
                binding.tvSubtitleFontCustom.text = file.nameWithoutExtension
                binding.tvSubtitleFontCustom.isSelected = true
                binding.tvSubtitleFontNormal.isSelected = false
            }
        }
        binding.tvSubtitleFontNormal.setOnClickListener {
            binding.fontRecyclerView.visibility = GONE
            subtitleCustomFont = ""
            spBase.subtitleFont = ""
            PlayerInitializer.Subtitle.typefacePath = ""
            binding.tvSubtitleFontNormal.isSelected = true
            binding.tvSubtitleFontCustom.isSelected = false
            onSubtitleFontChanged?.invoke("")
        }
        binding.tvSubtitleFontCustom.setOnClickListener {
            if (subtitleCustomFont.isBlank()) {
                binding.fontRecyclerView.visibility = VISIBLE
            } else {
                binding.fontRecyclerView.visibility = GONE
                binding.tvSubtitleFontNormal.isSelected = false
                binding.tvSubtitleFontCustom.isSelected = true
                onSubtitleFontChanged?.invoke(subtitleCustomFont)
            }
        }
        binding.tvSubtitleFontCustom.setOnLongClickListener {
            binding.fontRecyclerView.visibility = VISIBLE
            true
        }
    }

    private fun showLanguageSelectDialog(isAudio: Boolean) {
        val languages = arrayOf(
            "ja" to "日语", "zh" to "中文", "en" to "英语",
            "ko" to "韩语", "fr" to "法语", "de" to "德语",
            "es" to "西班牙语", "ru" to "俄语", "" to "无偏好"
        )
        val currentLang = if (isAudio) spBase.preferredAudioLanguage else spBase.preferredSubtitleLanguage
        val selectedIndex = languages.indexOfFirst { it.first == currentLang }.coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle(if (isAudio) R.string.hint_audio_language else R.string.hint_subtitle_language)
            .setSingleChoiceItems(languages.map { it.second }.toTypedArray(), selectedIndex) { dialog, which ->
                val selectedLang = languages[which].first
                if (isAudio) {
                    spBase.preferredAudioLanguage = selectedLang
                    binding.tvAudioLanguageValue.text = getLanguageDisplayName(selectedLang)
                    onAudioLanguageChanged?.invoke(selectedLang)
                } else {
                    spBase.preferredSubtitleLanguage = selectedLang
                    binding.tvSubtitleLanguageValue.text = getLanguageDisplayName(selectedLang)
                    onSubtitleLanguageChanged?.invoke(selectedLang)
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun getLanguageDisplayName(code: String): String {
        return when (code) {
            "ja" -> "日语"
            "zh" -> "中文"
            "en" -> "英语"
            "ko" -> "韩语"
            "fr" -> "法语"
            "de" -> "德语"
            "es" -> "西班牙语"
            "ru" -> "俄语"
            else -> "无偏好"
        }
    }

    private fun initListener() {
        setOnClickListener { switchVib() }
        binding.container.setOnClickListener { }
        binding.actionClose.setOnClickListener {
            if (isFileBrowseMode) {
                exitFileBrowseMode()
            } else {
                switchVib()
            }
        }
        binding.tvCloseTrack.setOnClickListener {
            currentAdapter().cleanSelect()
            binding.tvCloseTrack.isSelected = true
            // Blank name and -1 position mean "disable current audio/subtitle track".
            onChangeTrack?.invoke(currentTab, "", -1)
        }
        binding.tvAddTrack.setOnClickListener {
            enterFileBrowseMode()
        }
        binding.defaultFolder.setOnClickListener {
            changeFileData(FileOperator.buildDownFile("字幕"))
        }
        binding.lastFolder.setOnClickListener {
            val folder = getCurrentFolder()
            if (folder.isNotBlank()) {
                changeFileData(File(folder))
            } else {
                changeFileData(FileOperator.buildDownFile("字幕"))
            }
        }
    }

    private fun getCurrentFolder(): String {
        if (currentFolder == null) {
            currentFolder = spBase.lastSubtitleFolder
        }
        return currentFolder.toString()
    }

    private fun enterFileBrowseMode() {
        isFileBrowseMode = true
        binding.clTrackGroup.visibility = GONE
        binding.tabLayout.visibility = GONE
        binding.clFileGroup.visibility = VISIBLE
        val folder = getCurrentFolder()
        if (folder.isBlank()) {
            changeFileData(FileOperator.rootFolder)
        } else {
            changeFileData(folder.newFile())
        }
        binding.fileFastScroller.scrollNow()
    }

    private fun exitFileBrowseMode() {
        isFileBrowseMode = false
        binding.clFileGroup.visibility = GONE
        binding.clTrackGroup.visibility = VISIBLE
        binding.tabLayout.visibility = VISIBLE
        if (currentTab == TAB_SETTINGS) {
            currentTab = TAB_AUDIO
            binding.tabLayout.selectTab(binding.tabLayout.getTabAt(0))
            binding.clSettingsGroup.visibility = GONE
        }
    }

    private fun changeFileData(file: File) {
        val defaultDir = FileOperator.buildDownFile("字幕")
        if (file.path != defaultDir.path && file.path != FileOperator.rootFolder.path) {
            spBase.lastSubtitleFolder = file.path
            currentFolder = file.path
        }
        FileOperator.getSortedFiles(file, true, SUBTITLE_EXTENSIONS)
            .map { FileEntity(it) }
            .let { list ->
                binding.fileRecyclerView.post { fileSelectAdapter.setData(file, list) }
            }
    }

    private fun switchTab(position: Int) {
        currentTab = position
        when (position) {
            TAB_AUDIO, TAB_SUBTITLE -> {
                binding.clTrackGroup.visibility = VISIBLE
                binding.clSettingsGroup.visibility = GONE
                binding.recyclerView.adapter = currentAdapter()
                binding.tvAddTrack.visibility = if (position == TAB_SUBTITLE) VISIBLE else GONE
            }
            TAB_SETTINGS -> {
                binding.clTrackGroup.visibility = GONE
                binding.clSettingsGroup.visibility = VISIBLE
            }
        }
    }

    private fun currentAdapter() =
        if (currentTab == TAB_AUDIO) audioAdapter else subtitleAdapter

    fun showTab(tabIndex: Int) {
        binding.tabLayout.getTabAt(tabIndex)?.select()
    }

    fun setAudioTracks(tracks: List<TitleItem>) {
        audioAdapter.setData(tracks)
    }

    fun setSubtitleTracks(tracks: List<TitleItem>) {
        subtitleAdapter.setData(tracks)
    }

    fun selectTrack(tabType: Int, position: Int) {
        (if (tabType == TAB_AUDIO) audioAdapter else subtitleAdapter).selected(position)
    }

    fun setOnChangeTrackListener(callback: (tabType: Int, name: String, position: Int) -> Unit) {
        this.onChangeTrack = callback
    }

    fun setOnSubtitleFileSelectedListener(callback: (filePath: String) -> Unit) {
        this.onSubtitleFileSelected = callback
    }

    fun setOnAudioLanguageChangedListener(callback: (language: String) -> Unit) {
        this.onAudioLanguageChanged = callback
    }

    fun setOnSubtitleLanguageChangedListener(callback: (language: String) -> Unit) {
        this.onSubtitleLanguageChanged = callback
    }

    fun setOnSubtitleFontChangedListener(callback: (fontPath: String) -> Unit) {
        this.onSubtitleFontChanged = callback
    }

    fun setOnSubtitleFontSizeChangedListener(callback: (size: Int) -> Unit) {
        this.onSubtitleFontSizeChanged = callback
    }

    fun switchVib() {
        if (isVisible) {
            exitFileBrowseMode()
            visibility = GONE
        } else {
            visibility = VISIBLE
            when (currentTab) {
                TAB_AUDIO, TAB_SUBTITLE -> {
                    binding.tvAddTrack.visibility = if (currentTab == TAB_SUBTITLE) VISIBLE else GONE
                }
                TAB_SETTINGS -> {
                    // 设置Tab不需要额外处理
                }
            }
        }
    }

    override fun attach(controlWrapper: ControlWrapper) {
        this.controlWrapper = controlWrapper
    }

    override fun getView(): View = this
    override fun onVisibilityChanged(isVisible: Boolean, anim: Animation) {}
    override fun onPlayStateChanged(playState: Int) {}
    override fun onPlayerStateChanged(playerState: Int) {}
    override fun setProgress(duration: Int, position: Int) {}
    override fun onLockStateChanged(isLocked: Boolean) {}

}
