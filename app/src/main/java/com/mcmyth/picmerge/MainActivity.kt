package com.mcmyth.picmerge

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Matrix
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import java.io.IOException
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val images = mutableListOf<StitchImage>()
    private val nudges = mutableListOf<Pair<MaterialButton, Int>>()
    private var current: StitchImage? = null
    private var edge = CropEdge.TOP
    private var previewing = false
    private var previewBack: OnBackPressedCallback? = null
    private var busy = false
    private var loading: List<SavedImage> = emptyList()
    private var loadingSelection: Uri? = null
    private var deferredDocument: Uri? = null

    private lateinit var stage: CropStage
    private lateinit var preview: ZoomPreviewLayout
    private lateinit var previewStack: LinearLayout
    private lateinit var empty: View
    private lateinit var progress: ProgressBar
    private lateinit var title: TextView
    private lateinit var add: MaterialButton
    private lateinit var emptyAdd: MaterialButton
    private lateinit var remove: MaterialButton
    private lateinit var actionBar: LinearLayout
    private lateinit var editorRoot: LinearLayout
    private lateinit var zoom: MaterialButton
    private lateinit var cropControls: LinearLayout
    private lateinit var imageStrip: ThumbnailStrip
    private lateinit var gestures: TextView
    private lateinit var topButton: MaterialButton
    private lateinit var bottomButton: MaterialButton
    private lateinit var previewButton: MaterialButton
    private lateinit var save: MaterialButton

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val known = images.map { it.uri } + loading.map { it.uri }
        val available = uris.distinct().filterNot { it in known }
        if (available.isEmpty()) {
            if (uris.isNotEmpty()) toast("这些图片已添加")
            return@registerForActivityResult
        }
        val slots = (MAX_IMAGES - known.size).coerceAtLeast(0)
        if (available.size > slots) toast("最多可添加 $MAX_IMAGES 张图片")
        val additions = available.take(slots).map { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Some providers only grant access for the current session.
            }
            SavedImage(uri, 0, Int.MAX_VALUE)
        }
        if (additions.isNotEmpty()) load(additions, additions.first().uri)
    }

    private val documentSaver = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) {
            if (images.isNotEmpty() && loading.isEmpty()) saveTo(uri) else deferredDocument = uri
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        setContentView(createScreen())
        refresh()
        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() = switchMode()
        }
        previewBack = back
        onBackPressedDispatcher.addCallback(this, back)
        deferredDocument = savedInstanceState?.getString("document")?.let(Uri::parse)
        @Suppress("DEPRECATION")
        val restored = savedInstanceState?.getParcelableArrayList<Bundle>("images").orEmpty()
        val entries = restored.mapNotNull { item ->
            val uri = item.getString("uri") ?: return@mapNotNull null
            SavedImage(Uri.parse(uri), item.getInt("top"), item.getInt("bottom"))
        }
        if (entries.isNotEmpty()) {
            previewing = savedInstanceState?.getBoolean("preview") == true
            if (savedInstanceState?.getBoolean("end") == true) edge = CropEdge.BOTTOM
            val selected = savedInstanceState?.getString("current")?.let(Uri::parse)
            load(entries, selected)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("current", (loadingSelection ?: current?.uri)?.toString())
        outState.putString("document", deferredDocument?.toString())
        outState.putBoolean("preview", previewing)
        outState.putBoolean("end", edge == CropEdge.BOTTOM)
        val entries = images.map { SavedImage(it.uri, it.top, it.bottom) } + loading
        outState.putParcelableArrayList("images", ArrayList(entries.map { image ->
            Bundle().apply {
                putString("uri", image.uri.toString())
                putInt("top", image.top)
                putInt("bottom", image.bottom)
            }
        }))
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun createScreen(): View {
        val coordinator = FrameLayout(this).apply {
            setBackgroundColor(EditorColors.background)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        editorRoot = column().apply {
            setBackgroundColor(EditorColors.background)
            addView(row().apply {
                setPadding(dp(16), 0, dp(10), 0)
                title = label("PicMerge", 22f, bold = true)
                addView(title, LinearLayout.LayoutParams(0, -2, 1f))
                remove = button("移除") { removeCurrent() }
                add = button("＋ 添加图片", primary = true) { addImages() }
                addView(remove, spacedParams())
                addView(add, spacedParams())
            })

            val work = FrameLayout(this@MainActivity)
            previewStack = column()
            preview = ZoomPreviewLayout(this@MainActivity).apply {
                setBackgroundColor(EditorColors.workArea)
                addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
                    if (previewing && right - left != oldRight - oldLeft && images.isNotEmpty()) rebuildPreview()
                }
                setContent(previewStack)
            }
            work.addView(preview, FrameLayout.LayoutParams(-1, -1))
            stage = CropStage(this@MainActivity, ::selectEdge, ::updateCrop)
            work.addView(stage, FrameLayout.LayoutParams(-1, -1))
            empty = column().apply {
                gravity = Gravity.CENTER
                setBackgroundColor(EditorColors.workArea)
                addView(label("拼接长图", 27f, bold = true).apply { gravity = Gravity.CENTER })
                addView(label("添加图片后，直接在图上拖动裁切线。", 14f).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(20), dp(10), dp(20), dp(20))
                })
                emptyAdd = button("＋ 添加图片", primary = true) { addImages() }
                addView(emptyAdd, LinearLayout.LayoutParams(-2, dp(48)))
            }
            work.addView(empty, FrameLayout.LayoutParams(-1, -1))
            progress = ProgressBar(this@MainActivity).apply {
                indeterminateTintList = ColorStateList.valueOf(EditorColors.accent)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            work.addView(progress, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
            addView(work, LinearLayout.LayoutParams(-1, 0, 1f))

            cropControls = column().apply {
                setPadding(dp(10), 0, dp(10), 0)
                addView(row().apply {
                    topButton = button("起点") { selectEdge(CropEdge.TOP, reveal = true) }
                    bottomButton = button("终点") { selectEdge(CropEdge.BOTTOM, reveal = true) }
                    addView(topButton, weightedParams())
                    addView(bottomButton, weightedParams())
                })
                addView(row().apply {
                    for (amount in listOf(-10, -1, 1, 10)) {
                        val signed = if (amount < 0) "−${-amount}" else "+$amount"
                        val step = button("$signed px") {
                            current?.let { updateCrop(edge, it.position(edge) + amount) }
                            stage.reveal(edge)
                        }
                        nudges.add(step to amount)
                        step.setPadding(0, 0, 0, 0)
                        addView(step, weightedParams())
                    }
                })
            }
            addView(cropControls)
            actionBar = row().apply {
                setPadding(dp(10), 0, dp(10), dp(6))
                zoom = button("适屏") { stage.resetZoom() }
                addView(zoom, weightedParams())
                previewButton = button("预览长图", primary = true) { switchMode() }
                addView(previewButton, weightedParams())
                save = button("保存 PNG", primary = true) { startSave() }
                addView(save, weightedParams())
            }
            addView(actionBar)
        }
        coordinator.addView(editorRoot, FrameLayout.LayoutParams(-1, -1))
        imageStrip = ThumbnailStrip(this, images, ::selectImage, { refresh() })
        gestures = label("长按小图排序", 12f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), 0, dp(12), dp(5))
        }
        editorRoot.addView(gestures, 2, LinearLayout.LayoutParams(-1, dp(25)))
        editorRoot.addView(imageStrip, 3, LinearLayout.LayoutParams(-1, dp(62)))
        return coordinator
    }

    private fun addImages() {
        if (busy) return
        if (images.size >= MAX_IMAGES) {
            toast("最多可添加 $MAX_IMAGES 张图片")
            return
        }
        try {
            imagePicker.launch(arrayOf("image/*"))
        } catch (_: ActivityNotFoundException) {
            toast("设备上没有可用的图片来源")
        }
    }

    private fun load(entries: List<SavedImage>, selected: Uri?) {
        loading += entries
        loadingSelection = selected
        busy = true
        progress.contentDescription = "正在添加图片"
        refresh()
        worker.execute {
            val loaded = entries.mapNotNull { entry ->
                try {
                    StitchImages.load(contentResolver, entry.uri).apply {
                        setEdge(CropEdge.TOP, entry.top)
                        setEdge(CropEdge.BOTTOM, entry.bottom)
                    }
                } catch (_: Exception) {
                    null
                } catch (_: OutOfMemoryError) {
                    null
                }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                images.addAll(loaded)
                current = loaded.firstOrNull { it.uri == selected } ?: current ?: images.firstOrNull()
                loading -= entries.toSet()
                if (loading.isEmpty()) loadingSelection = null
                busy = loading.isNotEmpty()
                if (loaded.size < entries.size) toast("${entries.size - loaded.size} 张图片无法读取")
                imageStrip.reload()
                imageStrip.select(current, scroll = true)
                refresh(animate = true)
                deferredDocument?.takeUnless { busy }?.let { uri ->
                    deferredDocument = null
                    if (images.isNotEmpty()) saveTo(uri)
                }
            }
        }
    }

    private fun selectEdge(value: CropEdge) = selectEdge(value, reveal = false)

    private fun selectEdge(value: CropEdge, reveal: Boolean) {
        edge = value
        stage.select(edge)
        refreshEdges()
        if (reveal) stage.reveal(edge)
    }

    private fun updateCrop(target: CropEdge, position: Int) {
        val image = current ?: return
        val previous = image.position(target)
        image.setEdge(target, position)
        if (image.position(target) != previous) {
            stage.updateCrop()
            refreshEdges(updateTint = false)
        }
    }

    private fun refreshEdges(updateTint: Boolean = true) {
        val image = current ?: return
        val top = "起点  ${image.top} px"
        val bottom = "终点  ${image.bottom} px"
        if (topButton.text != top) topButton.text = top
        if (bottomButton.text != bottom) bottomButton.text = bottom
        if (updateTint) {
            tint(topButton, edge == CropEdge.TOP)
            tint(bottomButton, edge == CropEdge.BOTTOM)
        }
        val range = if (edge == CropEdge.TOP) 0 until image.bottom else (image.top + 1)..image.height
        for ((button, amount) in nudges) {
            val position = image.position(edge)
            button.isEnabled = (position + amount).coerceIn(range) != position
        }
    }

    private fun refresh(animate: Boolean = false) {
        val image = current
        if (image == null && !busy) previewing = false
        title.text = when {
            image == null -> "PicMerge"
            previewing -> "拼接预览"
            else -> "${images.indexOf(image) + 1} / ${images.size}"
        }
        add.isEnabled = !busy
        emptyAdd.isEnabled = !busy
        add.visibility = if (image != null) View.VISIBLE else View.GONE
        remove.visibility = if (image != null) View.VISIBLE else View.GONE
        remove.isEnabled = !busy
        remove.contentDescription = "移除当前图片"
        imageStrip.isEnabled = !busy
        previewBack?.isEnabled = previewing
        stage.visibility = if (image != null && !previewing) View.VISIBLE else View.GONE
        preview.visibility = if (image != null && previewing) View.VISIBLE else View.GONE
        empty.visibility = if (image == null) View.VISIBLE else View.GONE
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        imageStrip.visibility = if (image != null) View.VISIBLE else View.GONE
        gestures.visibility = if (image != null) View.VISIBLE else View.GONE
        cropControls.visibility = stage.visibility
        zoom.visibility = stage.visibility
        previewButton.visibility = if (image != null) View.VISIBLE else View.GONE
        previewButton.text = if (previewing) "返回裁切" else "预览长图"
        save.visibility = if (image != null && previewing) View.VISIBLE else View.GONE
        save.isEnabled = !busy
        if (image == null) {
            stage.clear()
            previewStack.removeAllViews()
        } else if (previewing) {
            rebuildPreview()
        } else {
            stage.show(image, edge, animate)
            refreshEdges()
        }
    }

    private fun switchMode() {
        if (previewing) collapsePreview() else expandPreview()
    }

    private fun expandPreview() {
        if (current == null || busy) return
        previewing = true
        preview.resetZoom()
        previewStack.translationX = 0f
        previewStack.translationY = 0f
        rebuildPreview()
        refresh()
        preview.pivotY = 0f
        preview.alpha = 0f
        preview.scaleX = 0.97f
        preview.scaleY = 0.97f
        preview.translationY = dp(18).toFloat()
        preview.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(220)
            .start()
    }

    private fun collapsePreview() {
        if (current == null) return
        if (!previewing) return
        preview.animate()
            .alpha(0f)
            .scaleX(0.98f)
            .scaleY(0.98f)
            .translationY(dp(10).toFloat())
            .setDuration(150)
            .withEndAction {
                previewing = false
                preview.alpha = 1f
                preview.scaleX = 1f
                preview.scaleY = 1f
                preview.translationY = 0f
                preview.resetZoom()
                refresh(animate = true)
            }
            .start()
    }

    private fun selectImage(image: StitchImage) {
        current = image
        imageStrip.select(current, scroll = true)
        if (previewing) collapsePreview() else refresh(animate = true)
    }

    private fun rebuildPreview() {
        if (preview.width == 0 || preview.height == 0 || images.isEmpty()) return
        val viewportWidth = (preview.width - dp(16)).coerceAtLeast(0)
        if (viewportWidth == 0) return
        val outputWidth = StitchImages.outputWidth(images)
        val displayedWidth = minOf(viewportWidth, outputWidth)
        previewStack.removeAllViews()
        previewStack.addView(label("${outputWidth} × ${images.sumOf { StitchImages.scaledHeight(it, outputWidth).toLong() }} px", 12f).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(8))
        })
        for ((index, image) in images.withIndex()) {
            val height = StitchImages.scaledHeight(image, displayedWidth).coerceAtMost(100_000)
            val bitmap = image.preview
            val view = ImageView(this).apply {
                setImageBitmap(bitmap)
                // The source preview contains the full image.  Crop it by translating
                // the matrix; FIT_XY would squeeze that full image into the kept range.
                scaleType = ImageView.ScaleType.MATRIX
                imageMatrix = Matrix().apply {
                    val scaleX = displayedWidth.toFloat() / bitmap.width
                    val scaleY = (height.toDouble() * image.height / image.keptHeight / bitmap.height).toFloat()
                    setScale(scaleX, scaleY)
                    postTranslate(0f, -(image.top.toDouble() * height / image.keptHeight).toFloat())
                }
                contentDescription = "预览第 ${index + 1} 张图片"
                setOnClickListener { selectImage(image) }
            }
            previewStack.addView(view, LinearLayout.LayoutParams(displayedWidth, height).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        previewStack.post { preview.resetZoom() }
    }

    private fun removeCurrent() {
        val image = current ?: return
        val index = images.indexOf(image)
        images.removeAt(index)
        current = images.getOrNull(index) ?: images.lastOrNull()
        edge = CropEdge.TOP
        previewing = false
        imageStrip.removed(index)
        imageStrip.select(current, scroll = true)
        refresh(animate = true)

        val bar = Snackbar.make(actionBar, "已移除第 ${index + 1} 张图片", Snackbar.LENGTH_LONG)
        bar.setAnchorView(actionBar)
        bar.setAction("撤销") {
            if (images.size >= MAX_IMAGES || images.any { it.uri == image.uri } ||
                loading.any { it.uri == image.uri }) return@setAction
            val position = index.coerceAtMost(images.size)
            images.add(position, image)
            current = image
            previewing = false
            imageStrip.inserted(position)
            imageStrip.select(image, scroll = true)
            refresh(animate = true)
        }
        bar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(snackbar: Snackbar?, event: Int) {
                if (images.none { it.uri == image.uri } && loading.none { it.uri == image.uri }) {
                    runCatching {
                        contentResolver.releasePersistableUriPermission(image.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
            }
        })
        bar.show()
    }

    private fun startSave() {
        if (busy || images.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 29) {
            saveTo(null)
        } else {
            try {
                documentSaver.launch(outputName())
            } catch (_: ActivityNotFoundException) {
                toast("设备上没有可用的保存位置")
            }
        }
    }

    private fun saveTo(document: Uri?) {
        val snapshot = images.map { it.copy() }
        busy = true
        progress.contentDescription = "正在生成长图"
        refresh()
        worker.execute {
            var pending: Uri? = null
            val failure = try {
                val resolver = contentResolver
                val output = document ?: if (Build.VERSION.SDK_INT >= 29) {
                    resolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, outputName())
                            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/PicMerge")
                            put(MediaStore.Images.Media.IS_PENDING, 1)
                        }
                    )?.also { pending = it } ?: throw IOException("无法创建相册文件")
                } else {
                    throw IOException("未选择保存位置")
                }
                resolver.openOutputStream(output)?.use { StitchImages.writePng(resolver, snapshot, it) }
                    ?: throw IOException("无法打开输出文件")
                pending?.let { uri ->
                    val updated = resolver.update(uri, ContentValues().apply {
                        put(MediaStore.Images.Media.IS_PENDING, 0)
                    }, null, null)
                    if (updated != 1) throw IOException("无法发布到相册")
                }
                null
            } catch (_: OutOfMemoryError) {
                "图片太大，请缩短裁切范围后重试"
            } catch (_: Exception) {
                "保存失败，请检查图片和存储空间后重试"
            }
            if (failure != null) {
                (pending ?: document)?.let { uri ->
                    runCatching { contentResolver.delete(uri, null, null) }
                }
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                busy = false
                refresh()
                toast(failure ?: if (document == null) "已保存到相册" else "已保存到所选位置")
            }
        }
    }

    private fun button(label: String, primary: Boolean = false, action: () -> Unit) =
        MaterialButton(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            maxLines = 1
            minimumWidth = 0
            minWidth = 0
            minimumHeight = dp(48)
            insetTop = dp(3)
            insetBottom = dp(3)
            setPadding(dp(9), 0, dp(9), 0)
            cornerRadius = dp(14)
            tint(this, primary)
            setOnClickListener { action() }
        }

    private fun tint(button: MaterialButton, primary: Boolean) {
        val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
        button.backgroundTintList = ColorStateList(
            states, intArrayOf(EditorColors.surface, if (primary) EditorColors.accent else EditorColors.surface)
        )
        button.setTextColor(ColorStateList(
            states, intArrayOf(EditorColors.muted, if (primary) EditorColors.ink else EditorColors.text)
        ))
    }

    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(if (bold) EditorColors.text else EditorColors.muted)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun row() = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun spacedParams() = LinearLayout.LayoutParams(-2, dp(48)).apply { marginStart = dp(5) }
    private fun weightedParams() = LinearLayout.LayoutParams(0, dp(48), 1f).apply {
        marginStart = dp(3)
        marginEnd = dp(3)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun outputName() = "PicMerge_${System.currentTimeMillis()}.png"

    private data class SavedImage(val uri: Uri, val top: Int, val bottom: Int)

    private companion object {
        const val MAX_IMAGES = 12
    }
}
