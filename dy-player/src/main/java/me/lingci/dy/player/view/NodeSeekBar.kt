package me.lingci.dy.player.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class NodeSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // 数据源：节点列表，例如 [0.5, 0.75, 1.0, 1.5, 2.0, 3.0, 5.0]
    private var nodes: List<Float> = emptyList()

    // 当前选中的节点索引
    private var selectedIndex: Int = -1

    // 画笔
    private val linePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nodePaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedTextPaint: Paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 尺寸参数
    private var lineHeight = 6f // 轨道高度
    private var nodeRadius = 12f // 节点圆圈半径
    private var textPaddingTop = 30f // 文字距离节点的距离
    private var textOffsetY = 0f // 触摸时的偏移量，用于实现“弹跳”效果（可选）

    private var currentColor: Int = Color.parseColor("#8B5CF6") // 选中项颜色

    // 回调
    var onNodeSelectedListener: ((Float, Int) -> Unit)? = null

    init {
        // 初始化画笔属性
        linePaint.color = Color.WHITE
        linePaint.strokeWidth = lineHeight
        linePaint.strokeCap = Paint.Cap.ROUND

        nodePaint.color = Color.WHITE
        nodePaint.style = Paint.Style.FILL

        textPaint.color = Color.WHITE
        textPaint.textSize = 36f
        textPaint.textAlign = Paint.Align.CENTER

        selectedTextPaint.color = currentColor
        selectedTextPaint.textSize = 42f    // 选中项字体稍大
        selectedTextPaint.textAlign = Paint.Align.CENTER
        selectedTextPaint.isFakeBoldText = true
    }

    /**
     * 设置节点数据
     * @param nodes 节点数值列表
     * @param selectIndex 默认选中的索引，默认为0
     */
    fun setNodes(nodes: List<Float>, selectIndex: Int = 0) {
        this.nodes = nodes.sorted() // 确保从小到大
        if (this.nodes.isNotEmpty()) {
            setSelectedIndex(selectIndex)
        }
        invalidate()
    }

    /**
     * 外部设置当前值
     * @param value 目标值
     */
    fun setValue(value: Float) {
        if (nodes.isEmpty()) return

        // 寻找最接近的节点
        var closestIndex = 0
        var minDiff = Float.MAX_VALUE

        nodes.forEachIndexed { index, node ->
            val diff = abs(node - value)
            if (diff < minDiff) {
                minDiff = diff
                closestIndex = index
            }
        }

        setSelectedIndex(closestIndex, fromUser = false)
    }

    private fun setSelectedIndex(index: Int, fromUser: Boolean = false) {
        if (index < 0 || index >= nodes.size) return

        if (selectedIndex != index) {
            selectedIndex = index
            invalidate()
            // 触发回调
            onNodeSelectedListener?.invoke(nodes[selectedIndex], selectedIndex)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // 计算所需高度：文字高度 + 文字间距 + 节点半径 + 轨道 + 额外空间
        val desiredHeight = (selectedTextPaint.textSize + textPaddingTop + nodeRadius * 2 + lineHeight + 24).toInt()
        setMeasuredDimension(measuredWidth, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (nodes.isEmpty()) return

        val width = width.toFloat()
        val height = height.toFloat()

        // 计算绘制区域，考虑 View 的 padding
        val startX = paddingLeft.toFloat() + nodeRadius
        val endX = width - paddingRight.toFloat() - nodeRadius
        // 轨道Y坐标：从底部往上留出节点半径+轨道高度的空间
        val lineY = height - nodeRadius - paddingBottom

        // 1. 绘制背景轨道
        canvas.drawLine(startX, lineY, endX, lineY, linePaint)

        // 计算节点间距
        val nodeCount = nodes.size
        if (nodeCount < 2) return

        val step = (endX - startX) / (nodeCount - 1)

        // 2. 绘制节点和文字
        nodes.forEachIndexed { index, value ->
            val x = startX + index * step

            // 绘制文字：在轨道上方
            val textY = lineY - nodeRadius - textPaddingTop
            if (index == selectedIndex) {
                canvas.drawText(value.toString(), x, textY, selectedTextPaint)
                // 绘制选中节点
                nodePaint.color = currentColor
                canvas.drawCircle(x, lineY, nodeRadius + 2f, nodePaint)
            } else {
                canvas.drawText(value.toString(), x, textY, textPaint)
                // 绘制普通节点
                nodePaint.color = Color.GRAY
                canvas.drawCircle(x, lineY, nodeRadius - 2f, nodePaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (nodes.isEmpty()) return false

        val width = width.toFloat()
        val startX = nodeRadius + 10
        val endX = width - nodeRadius - 10
        val step = (endX - startX) / (nodes.size - 1)

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val touchX = event.x

                // 计算当前触摸位置对应的索引
                // 公式：(触摸点 - 起点) / 步长
                var indexFloat = (touchX - startX) / step
                var index = indexFloat.toInt()

                // 处理边界情况
                if (index < 0) index = 0
                if (index >= nodes.size) index = nodes.size - 1

                // 简单的吸附逻辑：如果在两个节点中间，判断更靠近哪一个
                // (上面计算 indexFloat 的方式其实已经隐含了吸附到最近的整数索引，但在 move 时我们可以让跟随手指更紧，或者直接吸附)
                // 这里为了体验，我们在 MOVE 过程中实时更新位置（也可以选择只在 UP 时吸附）

                // 这里演示实时跟随并吸附
                if (selectedIndex != index) {
                    setSelectedIndex(index, fromUser = true)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 手指抬起时，确保最终位置是精确吸附的
                // 上面的逻辑已经保证了 index 是整数，这里不需要额外处理
                performClick() // 辅助功能支持
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

}