package com.march7th.hkrpg.starrail.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import com.march7th.hkrpg.third.Ln
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer

/**
 * 离线 OCR 引擎（PP-OCRv6 small，ONNX Runtime）。
 *
 * ## 为什么需要它
 *
 * 崩铁的副本页、背包页、结算页都是**列表**：同一个界面里，
 * 目标项在第几行会随「已解锁进度」「活动状态」「滚动位置」变化。
 * 靠模板匹配只能认出「这里有个按钮」，认不出「这个按钮叫拟造花萼（金）」。
 *
 * 所以凡是**按名字选东西**的地方（副本类型、副本名、队伍名、
 * 结算界面的关卡名）都必须走 OCR；凡是**位置固定**的地方（返回、
 * 关闭、领取）继续走模板匹配 —— 后者更快也更稳。
 *
 * ## 运行环境
 *
 * 引擎跑在**特权进程**（`:shizuku_service`，用 `app_process CLASSPATH=<apk>`
 * 拉起），那个进程**没有 Context**，所以拿不到 `AssetManager`。
 * 模型文件的来源因此有两条：
 *
 * - App 进程：[init] 传 `AssetManager`；
 * - 特权进程：[initFromApk] 用 `ZipFile` 直接按条目名从 APK 里抠出来。
 *
 * 无论哪条来源，最终都要**落到磁盘文件**才能交给 ONNX Runtime
 * （它只接受文件路径），落到 [cacheDir]。
 *
 * ## 管线
 *
 * ```
 * 图 → 缩放(最长边 DET_MAX_SIDE) → 归一化 → det 推理 → 概率图
 *   → 二值化 → 膨胀 → findContours → 框内均分过滤 → unclip 外扩
 *   → 逐框裁切 → 缩放到高 48 → 归一化 → rec 推理 → CTC 贪心解码
 * ```
 *
 * 关键参数取自 rapidocr 的 `config.yaml` 与 PP-OCR 默认值，见各常量注释。
 * 这套参数在真机（一加 PKG110 / Android 15）上验证过可用。
 */
object OcrEngine {

    private const val TAG = "OcrEngine"

    // ---- assets 内的模型路径（相对 APK 根，不带 starrail/ 前缀）----
    const val DET_FILE = "PP-OCRv6_det_small.onnx"
    const val REC_FILE = "PP-OCRv6_rec_small.onnx"
    const val DICT_FILE = "ppocrv6_dict.txt"

    /**
     * 模型在 APK 里的目录前缀。
     *
     * 与 [ImageUtils] 保持一致的做法：模板在 `starrail/images`，
     * 模型在 `starrail/ocr`。改这里要同步改 assets 的目录结构。
     */
    const val ASSET_PREFIX = "starrail/ocr"

    // ---- 检测后处理参数（PP-OCR 默认值）----
    /** 二值化阈值：概率图 > 此值算文字。 */
    private const val DET_THRESH = 0.3f

    /** 文本框得分阈值：框内平均概率低于此值丢弃。 */
    private const val BOX_THRESH = 0.5f

    /** 文本框外扩比例（DB 的 unclip）。 */
    private const val UNCLIP_RATIO = 1.6f

    /** 小于此边长的框丢弃。 */
    private const val MIN_BOX_SIZE = 3

    // ---- 识别参数 ----
    /** 识别输入高度，PP-OCR 固定 48。 */
    private const val REC_HEIGHT = 48

    /** 识别输入最大宽度（防止超长行占用过多内存）。 */
    private const val REC_MAX_WIDTH = 1280

    /**
     * 检测输入的最长边上限。
     *
     * 一开始设 1600，实测全屏 OCR 要 **15.6 秒**（不可接受）。
     * 降到 960 后面积只剩 ~1/3；UI 文字本身就大，检测对分辨率不敏感。
     */
    private const val DET_MAX_SIDE = 960

    /** 检测输入边长需为 32 的倍数（DB 网络下采样要求）。 */
    private const val DET_SIZE_DIVISOR = 32

    // ---- 状态 ----
    private var env: OrtEnvironment? = null
    private var det: OrtSession? = null
    private var rec: OrtSession? = null
    private var dict: List<String> = emptyList()

    /** 模型释放目录（必须是被当前进程可读可写的真实路径）。 */
    private var cacheDir: File? = null

    /** App 进程来源。 */
    private var assetManager: AssetManager? = null

    /** 特权进程来源（APK 文件）。 */
    private var apkFile: File? = null

    val isReady: Boolean get() = det != null && rec != null && dict.isNotEmpty()

    /** 一条 OCR 结果。坐标是**传入那一帧的像素坐标**。 */
    data class OcrResult(
        val text: String,
        val score: Float,
        val x: Int, val y: Int, val w: Int, val h: Int,
    ) {
        val cx: Int get() = x + w / 2
        val cy: Int get() = y + h / 2

        /** 中心点。点按钮时用这个。 */
        fun center(): Point = Point(cx.toDouble(), cy.toDouble())

        /** 文本去空格后的形式（用于匹配）。 */
        fun normalized(): String = text.replace(" ", "").replace("\u3000", "")
    }

    /** 设置模型释放目录。特权进程传 `/data/local/tmp`，App 进程传 cacheDir。 */
    fun setCacheDir(dir: File) {
        cacheDir = dir
    }

    /** **App 进程**用：从 AssetManager 读模型。 */
    @Synchronized
    fun init(assets: AssetManager, prefix: String = ASSET_PREFIX): Boolean {
        if (isReady) return true
        assetManager = assets
        apkFile = null
        return doInit(prefix)
    }

    /**
     * **特权进程**用：从自己的 APK 里抠模型。
     *
     * `app_process CLASSPATH=<apk>` 拉起时 `java.class.path` 就是 APK 路径，
     * 和 [ImageUtils.selfApkPath] 是同一套判据。
     */
    @Synchronized
    fun initFromApk(apkPath: String, assetPrefix: String = "assets/$ASSET_PREFIX"): Boolean {
        if (isReady) return true
        val f = File(apkPath)
        if (!f.isFile) {
            Ln.w("$TAG: APK 不存在 $apkPath")
            return false
        }
        assetManager = null
        apkFile = f
        return doInit(assetPrefix, fromApk = true)
    }

    @Synchronized
    private fun doInit(prefix: String, fromApk: Boolean = false): Boolean {
        val t0 = System.currentTimeMillis()
        return try {
            val e = OrtEnvironment.getEnvironment()
            env = e
            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(4)

            val detPath = materialize(prefix, DET_FILE, fromApk) ?: run {
                Ln.e("$TAG: 检测模型释放失败")
                return false
            }
            val recPath = materialize(prefix, REC_FILE, fromApk) ?: run {
                Ln.e("$TAG: 识别模型释放失败")
                return false
            }
            det = e.createSession(detPath, opts)
            rec = e.createSession(recPath, opts)

            val dictBytes = readAsset(prefix, DICT_FILE, fromApk) ?: run {
                Ln.e("$TAG: 字典释放失败")
                return false
            }
            dict = String(dictBytes, Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }
                .filter { it.isNotEmpty() }

            Ln.i("$TAG: OCR 就绪 dict=${dict.size} 条，耗时 ${System.currentTimeMillis() - t0} ms")
            isReady
        } catch (t: Throwable) {
            Ln.e("$TAG: OCR 初始化失败: ${t.message}")
            det = null
            rec = null
            false
        }
    }

    // ==================== 资源释放 ====================

    /**
     * 把 APK/assets 里的文件落到磁盘，返回绝对路径。
     *
     * 已经释放过（长度一致）就直接复用 —— 每次启动都重写 30MB 太浪费。
     */
    private fun materialize(prefix: String, name: String, fromApk: Boolean): String? {
        val dir = cacheDir ?: File("/data/local/tmp").also { cacheDir = it }
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, name)
        if (out.exists() && out.length() > 0) {
            Ln.i("$TAG: 复用已释放模型 $out (${out.length()} B)")
            return out.absolutePath
        }
        val bytes = readAsset(prefix, name, fromApk) ?: return null
        out.writeBytes(bytes)
        Ln.i("$TAG: 已释放模型 $out (${bytes.size} B)")
        return out.absolutePath
    }

    /** 统一读取：APK（ZipFile）或 assets（AssetManager）。 */
    private fun readAsset(prefix: String, name: String, fromApk: Boolean): ByteArray? {
        if (fromApk) {
            val apk = apkFile ?: return null
            return runCatching {
                java.util.zip.ZipFile(apk).use { zip ->
                    val entry = zip.getEntry("$prefix/$name") ?: return@use null
                    zip.getInputStream(entry).use { it.readBytes() }
                }
            }.getOrNull()
        }
        val am = assetManager ?: return null
        return runCatching { am.open("$prefix/$name").use { it.readBytes() } }.getOrNull()
    }

    // ==================== 主入口 ====================

    /**
     * 对整张图（或指定区域）做 OCR。
     *
     * @param image BGR Mat（[ImageUtils.bitmapToMat] 的产物）
     * @param region 只识别这块区域；返回的坐标**已换算成整图坐标**
     * @return 识别结果，按「从上到下、从左到右」粗排
     */
    @Synchronized
    fun run(image: Mat, region: Rect? = null): List<OcrResult> {
        if (!isReady) {
            Ln.w("$TAG: run() 时引擎未就绪")
            return emptyList()
        }
        if (image.empty()) return emptyList()

        // 区域裁剪要小心越界：crop 是调用方按比例算的，可能差一两个像素
        val safe = if (region != null) {
            Rect(
                region.x.coerceIn(0, image.cols() - 1),
                region.y.coerceIn(0, image.rows() - 1),
                region.width.coerceIn(1, image.cols() - region.x.coerceIn(0, image.cols() - 1)),
                region.height.coerceIn(1, image.rows() - region.y.coerceIn(0, image.rows() - 1)),
            )
        } else null

        val roi = if (safe != null) Mat(image, safe).clone() else image
        val offsetX = safe?.x ?: 0
        val offsetY = safe?.y ?: 0
        return try {
            val boxes = detect(roi)
            val out = ArrayList<OcrResult>(boxes.size)
            for (b in boxes) {
                val r = recognize(roi, b) ?: continue
                if (r.first.isBlank()) continue
                out.add(
                    OcrResult(r.first, r.second, b.x + offsetX, b.y + offsetY, b.width, b.height)
                )
            }
            out.sortWith(compareBy({ it.y / 20 }, { it.x }))
            out
        } catch (t: Throwable) {
            Ln.w("$TAG: OCR 失败: ${t.message}")
            emptyList()
        } finally {
            if (safe != null) roi.release()
        }
    }

    /**
     * 找出包含指定文字的框。三级匹配：完全相同 → 包含 → 被包含。
     *
     * 之所以要「被包含」这级：OCR 会把相邻符号一起框进来
     * （比如 `拟造花萼（金） x12`），完全相等就永远命不中。
     */
    fun findText(image: Mat, target: String, region: Rect? = null): OcrResult? {
        val norm = target.replace(" ", "").replace("\u3000", "")
        val all = run(image, region)
        return all.firstOrNull { it.normalized() == norm }
            ?: all.firstOrNull { it.normalized().contains(norm) }
            ?: all.firstOrNull { it.normalized().length >= 2 && norm.contains(it.normalized()) }
    }

    /**
     * 找出**全部**包含指定文字的框（同一个词可能出现多次，比如列表里两行同名）。
     *
     * 调用方一般取 `first()`（最靠上的那个）。
     */
    fun findAll(image: Mat, target: String, region: Rect? = null): List<OcrResult> {
        val norm = target.replace(" ", "").replace("\u3000", "")
        return run(image, region).filter {
            val t = it.normalized()
            t == norm || t.contains(norm) || (t.length >= 2 && norm.contains(t))
        }
    }

    /**
     * 调试用：把整帧 OCR 结果打出来。
     *
     * 这是**免校准的关键工具** —— 在实测日志里直接看到界面上所有文字的
     * 内容和坐标，不用截图、不用猜、不用一个个试坐标。
     */
    fun dump(image: Mat, tag: String = "dump", region: Rect? = null): List<OcrResult> {
        val rs = run(image, region)
        Ln.i("$TAG: [$tag] 识别到 ${rs.size} 条：")
        for (r in rs) {
            Ln.i("$TAG: [$tag]   \"${r.text}\" 置信=${"%.2f".format(r.score)} 框=(${r.x},${r.y},${r.w}x${r.h}) 中心=(${r.cx},${r.cy})")
        }
        return rs
    }

    // ==================== 检测 ====================

    private fun detect(src: Mat): List<Rect> {
        val d = det ?: return emptyList()
        val e = env ?: return emptyList()

        val scale = minOf(1.0, DET_MAX_SIDE.toDouble() / maxOf(src.width(), src.height()))
        var w = (src.width() * scale).toInt()
        var h = (src.height() * scale).toInt()
        w = maxOf(DET_SIZE_DIVISOR, (w / DET_SIZE_DIVISOR) * DET_SIZE_DIVISOR)
        h = maxOf(DET_SIZE_DIVISOR, (h / DET_SIZE_DIVISOR) * DET_SIZE_DIVISOR)
        val ratioX = src.width().toDouble() / w
        val ratioY = src.height().toDouble() / h

        val resized = Mat()
        Imgproc.resize(src, resized, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

        // 归一化：(x/255 - mean) / std，ImageNet 均值方差。
        //
        // 用 OpenCV 的 C++ 实现做（`cvtColor` + `convertTo` + `split`），
        // 而不是 Kotlin 逐像素循环 —— 后者在 1600x700 上要跑 340 万次
        // 字节访问，是当时 15.6 秒的主要来源之一。
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        val rgb = Mat()
        Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
        resized.release()
        rgb.convertTo(rgb, CvType.CV_32FC3, 1.0 / 255.0)

        val planes = ArrayList<Mat>(3)
        Core.split(rgb, planes)
        rgb.release()

        val data = FloatArray(3 * h * w)
        val plane = FloatArray(h * w)
        for (c in 0..2) {
            // v = (x - mean) / std，用 convertTo 的 alpha/beta 形式一步完成
            planes[c].convertTo(
                planes[c], CvType.CV_32F,
                1.0 / std[c], (-mean[c] / std[c]).toDouble(),
            )
            planes[c].get(0, 0, plane)
            System.arraycopy(plane, 0, data, c * h * w, h * w)
            planes[c].release()
        }

        val result = FloatArray(h * w)
        try {
            OnnxTensor.createTensor(
                e, FloatBuffer.wrap(data),
                longArrayOf(1, 3, h.toLong(), w.toLong()),
            ).use { input ->
                val name = d.inputNames.iterator().next()
                d.run(mapOf(name to input)).use { out ->
                    val t = out.get(0) as OnnxTensor
                    t.floatBuffer.get(result)
                }
            }
        } catch (t: Throwable) {
            Ln.w("$TAG: 检测推理失败: ${t.message}")
            return emptyList()
        }

        val mask = Mat(h, w, CvType.CV_8UC1)
        val mb = ByteArray(h * w)
        for (i in 0 until h * w) {
            mb[i] = if (result[i] > DET_THRESH) 255.toByte() else 0
        }
        mask.put(0, 0, mb)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
        Imgproc.dilate(mask, mask, kernel)
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            mask, contours, Mat(), Imgproc.RETR_EXTERNAL,
            Imgproc.CHAIN_APPROX_SIMPLE,
        )
        mask.release()
        kernel.release()

        val boxes = ArrayList<Rect>()
        for (c in contours) {
            val r = Imgproc.boundingRect(c)
            c.release()
            if (r.width < MIN_BOX_SIZE || r.height < MIN_BOX_SIZE) continue

            // 框内平均概率过滤（用未膨胀的原始概率图）
            var sum = 0f
            var cnt = 0
            for (yy in r.y until minOf(r.y + r.height, h)) {
                for (xx in r.x until minOf(r.x + r.width, w)) {
                    sum += result[yy * w + xx]; cnt++
                }
            }
            if (cnt == 0 || sum / cnt < BOX_THRESH) continue

            // 外扩并映射回原图坐标
            val pad = ((r.width + r.height) / 2.0 * (UNCLIP_RATIO - 1.0) / 2.0).toInt()
            val rx = ((r.x - pad) * ratioX).toInt().coerceAtLeast(0)
            val ry = ((r.y - pad) * ratioY).toInt().coerceAtLeast(0)
            val rw = ((r.width + pad * 2) * ratioX).toInt().coerceAtMost(src.width() - rx)
            val rh = ((r.height + pad * 2) * ratioY).toInt().coerceAtMost(src.height() - ry)
            if (rw < 3 || rh < 3) continue
            boxes.add(Rect(rx, ry, rw, rh))
        }
        return boxes
    }

    // ==================== 识别 ====================

    private fun recognize(src: Mat, box: Rect): Pair<String, Float>? {
        val r = rec ?: return null
        val e = env ?: return null

        val roi = Mat(src, box)
        val scale = REC_HEIGHT.toDouble() / roi.height()
        var w = (roi.width() * scale).toInt().coerceAtLeast(1)
        if (w > REC_MAX_WIDTH) w = REC_MAX_WIDTH
        val resized = Mat()
        Imgproc.resize(
            roi, resized, Size(w.toDouble(), REC_HEIGHT.toDouble()),
            0.0, 0.0, Imgproc.INTER_LINEAR,
        )

        // 同样用 OpenCV C++ 做归一化，避免逐像素 Kotlin 循环
        val rgb = Mat()
        Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
        resized.release()
        rgb.convertTo(rgb, CvType.CV_32FC3, 1.0 / 255.0)
        val planes = ArrayList<Mat>(3)
        Core.split(rgb, planes)
        rgb.release()

        val data = FloatArray(3 * REC_HEIGHT * w)
        val plane = FloatArray(REC_HEIGHT * w)
        for (c in 0..2) {
            // 识别归一化：(x/255 - 0.5) / 0.5  →  alpha=2, beta=-1
            planes[c].convertTo(planes[c], CvType.CV_32F, 2.0, -1.0)
            planes[c].get(0, 0, plane)
            System.arraycopy(plane, 0, data, c * REC_HEIGHT * w, REC_HEIGHT * w)
            planes[c].release()
        }

        return try {
            OnnxTensor.createTensor(
                e, FloatBuffer.wrap(data),
                longArrayOf(1, 3, REC_HEIGHT.toLong(), w.toLong()),
            ).use { input ->
                val name = r.inputNames.iterator().next()
                r.run(mapOf(name to input)).use { out ->
                    val t = out.get(0) as OnnxTensor
                    val shape = t.info.shape
                    val steps = shape[1].toInt()
                    val classes = shape[2].toInt()
                    val buf = FloatArray(steps * classes)
                    t.floatBuffer.get(buf)
                    decodeCtc(buf, steps, classes)
                }
            }
        } catch (t: Throwable) {
            Ln.w("$TAG: 识别推理失败: ${t.message}")
            null
        }
    }

    /**
     * CTC 贪心解码。
     *
     * 约定（PP-OCR）：index 0 = blank；index i(≥1) 对应 `dict[i-1]`。
     * 去重相邻重复、去 blank、每步取 argmax；得分取有效步的平均概率。
     */
    private fun decodeCtc(buf: FloatArray, steps: Int, classes: Int): Pair<String, Float> {
        val sb = StringBuilder()
        var scoreSum = 0f
        var scoreCnt = 0
        var last = -1
        for (t in 0 until steps) {
            var best = 0
            val base = t * classes
            var bestV = buf[base]
            for (c in 1 until classes) {
                val v = buf[base + c]
                if (v > bestV) { bestV = v; best = c }
            }
            if (best != 0 && best != last) {
                val idx = best - 1
                if (idx < dict.size) sb.append(dict[idx])
                scoreSum += bestV; scoreCnt++
            }
            last = best
        }
        return sb.toString() to (if (scoreCnt == 0) 0f else scoreSum / scoreCnt)
    }

    @Synchronized
    fun close() {
        runCatching { det?.close() }
        runCatching { rec?.close() }
        det = null
        rec = null
        dict = emptyList()
    }

    /** 调试：把一帧裁下来存成 PNG（无 Context 时也能用）。 */
    fun saveMatPng(mat: Mat, file: File): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val ok = org.opencv.imgcodecs.Imgcodecs.imwrite(file.absolutePath, mat)
        ok
    }.getOrDefault(false)

    /**
     * 给识别结果画框并写盘 —— 排查 OCR 问题时最有用的东西：
     * 一眼看出「识别到了什么、框在哪」。
     */
    fun saveAnnotated(mat: Mat, results: List<OcrResult>, file: File): Boolean = runCatching {
        val copy = Mat()
        org.opencv.core.Core.addWeighted(mat, 0.6, Mat.zeros(mat.size(), mat.type()), 0.4, 0.0, copy)
        for (o in results) {
            Imgproc.rectangle(
                copy, Point(o.x.toDouble(), o.y.toDouble()),
                Point((o.x + o.w).toDouble(), (o.y + o.h).toDouble()),
                Scalar(0.0, 255.0, 0.0), 2,
            )
        }
        saveMatPng(copy, file).also { copy.release() }
    }.getOrDefault(false)

    /** 仅供调试：把 BGR Mat 编码成 PNG 字节。 */
    fun encodePng(mat: Mat): ByteArray? = runCatching {
        val buf = MatOfByte()
        org.opencv.imgcodecs.Imgcodecs.imencode(".png", mat, buf)
        val bytes = buf.toArray()
        buf.release()
        bytes
    }.getOrNull()
}
