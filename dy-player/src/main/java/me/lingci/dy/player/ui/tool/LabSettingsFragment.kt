package me.lingci.dy.player.ui.tool

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import me.lingci.dy.player.databinding.FragmentLabSettingBinding
import me.lingci.dy.player.ui.long_video.LongVideoActivity
import me.lingci.dy.player.util.SpUtil
import me.lingci.lib.base.ui.BaseFragment

/**
 * 实验室设置
 */
class LabSettingsFragment : BaseFragment() {

    private var _binding: FragmentLabSettingBinding? = null
    private val binding get() = _binding!!
    private val spUtil: SpUtil by lazy { SpUtil(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLabSettingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        init()
    }

    @SuppressLint("SetTextI18n")
    private fun init() {
        binding.toolbar.setNavigationOnClickListener { requireActivity().finish() }
        binding.toolbar.title = "实验室"

        binding.swLabSurfaceRgba.isChecked = spUtil.labSurfaceRgba
        binding.swLabSurfaceRgba.setOnClickListener {
            spUtil.labSurfaceRgba = binding.swLabSurfaceRgba.isChecked
        }

        binding.swLabSurfaceZOrder.isChecked = spUtil.labSurfaceZOrder
        binding.swLabSurfaceZOrder.setOnClickListener {
            spUtil.labSurfaceZOrder = binding.swLabSurfaceZOrder.isChecked
        }
        binding.swLabMpvSpecialRender.isChecked = spUtil.labMpvSpecialRender
        binding.swLabMpvSpecialRender.setOnClickListener {
            spUtil.labMpvSpecialRender = binding.swLabMpvSpecialRender.isChecked
        }
        binding.swLabMpvSequentialRead.isChecked = spUtil.labMpvSequentialRead
        binding.swLabMpvSequentialRead.setOnClickListener {
            spUtil.labMpvSequentialRead = binding.swLabMpvSequentialRead.isChecked
        }
        binding.swLabMpvSuperResolution.isChecked = spUtil.labMpvSuperResolution
        binding.swLabMpvSuperResolution.setOnClickListener {
            val on = binding.swLabMpvSuperResolution.isChecked
            spUtil.labMpvSuperResolution = on
            // 立即对当前 MPV 实例生效（若不在 MPV 内核则忽略，下次切到 MPV 时由 init 应用）
            val action = if (on) LongVideoActivity.ACTION_SUPER_RESOLUTION_ON
                         else LongVideoActivity.ACTION_SUPER_RESOLUTION_OFF
            requireContext().sendBroadcast(Intent(action))
            Toast.makeText(
                requireContext(),
                if (on) "已开启 MPV 画质增强" else "已关闭 MPV 画质增强",
                Toast.LENGTH_SHORT
            ).show()
        }
        binding.swDebugMode.isChecked = spUtil.debugMode
        binding.swDebugMode.setOnClickListener {
            spUtil.debugMode = binding.swDebugMode.isChecked
        }
        binding.swLabLongVideoPortrait.isChecked = spUtil.labLongVideoPortrait
        binding.swLabLongVideoPortrait.setOnClickListener {
            spUtil.labLongVideoPortrait = binding.swLabLongVideoPortrait.isChecked
        }
    }

    override fun resetView() {

    }

}
