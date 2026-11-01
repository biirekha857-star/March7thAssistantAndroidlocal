package com.march7th.hkrpg.starrail.engine

import android.graphics.Bitmap
import com.march7th.hkrpg.third.Ln
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * 模板匹配 —— 移植自 March7thAssistant 的 `utils/image_utils.py`。
 *
 * ## ⚠️ 必须保留的双语义（移植第一坑）
 *
 * 原实现在有无 mask 时用了**两套语义完全相反**的算法：
 *
 * | 情况 | 算法 | 阈值方向 | 典型阈值 |
 * |---|---|---|---|
 * | 模板**带** alpha 掩码 | `TM_SQDIFF` | **越小越好** | 上百万（如 2000000） |
 * | 模板**不带** 掩码 | `TM_CCOEFF_NORMED` | **越大越好** | 0.8 ~ 0.95 |
 *
 * 判据是"alpha 通道是否存在非 255 像素"（见 [readTemplateWithMask]），
 * **不是**"图片有没有 alpha 通道"。
 *
 * 实测 March7thAssistant 的 395 张模板里只有 **6 张**真正走 SQDIFF 分支。
 * 把两套统一成一种，会让其中一批模板立刻全部失效。
 *
 * ## 坐标
 * 返回值都是**归一化图坐标系**下的左上角点。归一化策略见 [NormStrategy]。
 */
object ImageUtils {

    /** 归一化基准高度（实测"高归一化→1080"对模板匹配最友好）。 */
    const val BASE_HEIGHT = 1080

    /** 多尺度搜索默认范围（手游 UI 比 PC 大约 1.25~1.5 倍）。 */
    val DEFAULT_SCALE_RANGE = 0.70f to 1.60f

    private const val SCALE_STEP = 0.05f

    /** 模板缓存：路径 -> (Mat, mask?)。避免每帧读盘。 */
    private val templateCache = mutableMapOf<String, Pair<Mat, Mat?>>()

    /**
     * 资源来源。
     *
     * 模板图随 APK 打包在 `assets/starrail/images/` 下，运行时通过
     * [AssetManager] 读取；调试时也可以指向磁盘目录。
     */
    private var assetManager: android.content.res.AssetManager? = null

    /** assets 内的模板根目录。 */
    private var assetPrefix: String = "starrail/images"

    /** 磁盘模板根目录（优先于 assets，调试用）。 */
    private var diskRoot: File? = null

    /**
     * APK 内的模板来源。
     *
     * ## 为什么必须有这条路径
     *
     * 引擎跑在**提权进程**（`:shizuku_service`）里，那个进程是用
     * `app_process CLASSPATH=<apk>` 拉起来的，**没有 Context，也就没有
     * AssetManager**。而模板图恰恰打包在 APK 的 assets 里。
     *
     * 提权进程虽然拿不到 Context，但能读到自己的 APK 文件本身
     * （`/data/app/**/base.apk`，shell 可读），于是直接用 `ZipFile`
     * 按条目名把模板取出来 —— 不需要 App 进程投递任何东西。
     */
    private var apkFile: File? = null

    /** APK 内的模板根目录（对应 assets 下的路径）。 */
    private var apkPrefix: String = "assets/starrail/images"

    /** 设置 assets 来源。 */
    fun init(assets: android.content.res.AssetManager, prefix: String = "starrail/images") {
        assetManager = assets
        assetPrefix = prefix
        clearCache()
        Ln.i("ImageUtils: 资源来源 = assets/$prefix")
    }

    /**
     * 设置 APK 来源（提权进程用；优先级低于 [setDiskRoot]，高于 assets）。
     *
     * @param apkPath APK 绝对路径，通常取自 `java.class.path`
     * @param assetPrefix APK 内模板根目录，默认 `assets/starrail/images`
     */
    fun initFromApk(apkPath: String, assetPrefix: String = "assets/starrail/images") {
        val f = File(apkPath)
        if (!f.isFile) {
            Ln.w("ImageUtils: APK 不存在 $apkPath")
            return
        }
        apkFile = f
        this.apkPrefix = assetPrefix
        clearCache()
        Ln.i("ImageUtils: 资源来源 = ${f.name}!$assetPrefix")
    }

    /** 当前进程的 APK 路径（`app_process CLASSPATH=` 传进来的）。 */
    fun selfApkPath(): String? =
        System.getProperty("java.class.path")?.substringBefore(File.pathSeparator)?.takeIf { it.endsWith(".apk") }

    /** 设置磁盘来源（调试用；优先于 assets）。 */
    fun setDiskRoot(root: File?) {
        diskRoot = root
        clearCache()
        if (root != null) Ln.i("ImageUtils: 资源来源 = ${root.absolutePath}")
    }

    /**
     * 把逻辑路径解析成 (Mat, mask?)。
     *
     * @param path 形如 `screen/mail.png` 或 `./assets/images/screen/mail.png`
     */
    private fun loadTemplate(path: String): Pair<Mat, Mat?>? {
        templateCache[path]?.let { return it }

        // 归一化路径：去掉常见前缀
        val rel = path
            .removePrefix("./")
            .removePrefix("assets/images/")
            .removePrefix("images/")
            .trimStart('/')

        // 1) 磁盘优先（调试）
        diskRoot?.let { root ->
            val f = File(root, rel)
            if (f.isFile) {
                readFromBytes(f.readBytes(), f.absolutePath)?.let {
                    templateCache[path] = it
                    return it
                }
            }
        }

        // 2) APK 内（提权进程的唯一来源）
        apkFile?.let { apk ->
            val entry = "$apkPrefix/$rel"
            val bytes = runCatching {
                java.util.zip.ZipFile(apk).use { zip ->
                    zip.getInputStream(zip.getEntry(entry) ?: return@use null)?.use { it.readBytes() }
                }
            }.getOrNull()
            if (bytes != null) {
                readFromBytes(bytes, "$apk!$entry")?.let {
                    templateCache[path] = it
                    return it
                }
            }
        }

        // 3) assets（App 进程内的调用方）
        val am = assetManager
        if (am != null) {
            val assetPath = "$assetPrefix/$rel"
            val bytes = runCatching { am.open(assetPath).use { it.readBytes() } }.getOrNull()
            if (bytes != null) {
                readFromBytes(bytes, assetPath)?.let {
                    templateCache[path] = it
                    return it
                }
            }
        }

        Ln.w("ImageUtils: 模板不存在 $path (rel=$rel)")
        return null
    }

    /** 从字节解码模板 + 掩码（复刻 read_template_with_mask）。 */
    private fun readFromBytes(bytes: ByteArray, label: String): Pair<Mat, Mat?>? {
        if (bytes.isEmpty()) return null
        // OpenCV Android 的 imdecode 需要 MatOfByte（由字节数组构造）
        val buf = org.opencv.core.MatOfByte()
        buf.fromArray(*bytes)
        val raw = Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_UNCHANGED)
        buf.release()
        if (raw.empty()) {
            Ln.w("ImageUtils: 模板解码失败 $label")
            return null
        }

        var mask: Mat? = null
        if (raw.channels() == 4) {
            val alpha = Mat()
            Core.extractChannel(raw, alpha, 3)
            val mm = Core.minMaxLoc(alpha)
            if (mm.minVal < 255.0) {
                mask = alpha
            } else {
                alpha.release()
            }
        }

        // bug #6：4 通道 / 灰度分支里 `cvtColor` 产出的是**新** Mat，
        // 原来的 `raw` 必须释放；只有 3 通道分支是直接把 raw 当结果，不能放。
        val bgr = when (raw.channels()) {
            3 -> raw
            else -> {
                val out = Mat()
                try {
                    Imgproc.cvtColor(
                        raw, out,
                        if (raw.channels() == 4) Imgproc.COLOR_BGRA2BGR
                        else Imgproc.COLOR_GRAY2BGR,
                    )
                } catch (t: Throwable) {
                    out.release()
                    throw t
                } finally {
                    raw.release()
                }
                out
            }
        }
        return bgr to mask
    }

    /**
     * 归一化策略。
     *
     * 手游是 20:9、PC 是 16:9，且手游 UI 按边角锚定，**不能简单拉伸**。
     * 实测三种策略都能命中，但"高归一化"分数最高（0.889 vs 0.846）。
     */
    enum class NormStrategy {
        /** 宽归一化到 1920（M7A 原做法） */
        WIDTH_TO_1920,

        /** 高归一化到 1080（**推荐**） */
        HEIGHT_TO_1080,

        /** 不归一化 */
        NONE,
    }

    /**
     * 把截图缩放到归一化基准。
     *
     * @return (归一化后的 Mat, 缩放比 sf)。原图坐标 = 归一化坐标 / sf
     */
    fun normalize(src: Mat, strategy: NormStrategy = NormStrategy.HEIGHT_TO_1080): Pair<Mat, Double> {
        val w = src.cols()
        val h = src.rows()
        return when (strategy) {
            NormStrategy.HEIGHT_TO_1080 -> {
                val sf = BASE_HEIGHT.toDouble() / h
                if (sf == 1.0) src to 1.0
                else Mat().also {
                    Imgproc.resize(src, it, Size(w * sf, BASE_HEIGHT.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                } to sf
            }
            NormStrategy.WIDTH_TO_1920 -> {
                val sf = 1920.0 / w
                if (sf == 1.0) src to 1.0
                else Mat().also {
                    Imgproc.resize(src, it, Size(1920.0, h * sf), 0.0, 0.0, Imgproc.INTER_AREA)
                } to sf
            }
            NormStrategy.NONE -> src to 1.0
        }
    }

    /** Bitmap -> BGR Mat。 */
    fun bitmapToMat(bmp: Bitmap): Mat {
        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }

    /**
     * 读模板并判断是否需要掩码 —— 复刻 `read_template_with_mask`。
     *
     * 只有 alpha 通道**存在非 255 像素**时才生成掩码。
     */
    fun readTemplateWithMask(path: String): Pair<Mat, Mat?>? {
        templateCache[path]?.let { return it }
        val file = File(path)
        if (!file.isFile) {
            Ln.w("ImageUtils: 模板不存在 $path")
            return null
        }
        // IMREAD_UNCHANGED 保留 alpha
        val raw = Imgcodecs.imread(path, Imgcodecs.IMREAD_UNCHANGED)
        if (raw.empty()) {
            Ln.w("ImageUtils: 模板读取失败 $path")
            return null
        }

        var mask: Mat? = null
        if (raw.channels() == 4) {
            val alpha = Mat()
            Core.extractChannel(raw, alpha, 3)
            // 检查是否存在非完全透明像素
            val mm = Core.minMaxLoc(alpha)
            if (mm.minVal < 255.0) {
                mask = alpha
            } else {
                alpha.release()
            }
        }

        val bgr = when (raw.channels()) {
            4 -> Mat().also { Imgproc.cvtColor(raw, it, Imgproc.COLOR_BGRA2BGR) }
            3 -> raw
            else -> Mat().also { Imgproc.cvtColor(raw, it, Imgproc.COLOR_GRAY2BGR) }
        }
        val entry = bgr to mask
        templateCache[path] = entry
        return entry
    }

    /** 匹配结果。 */
    data class MatchResult(
        /** 分数。语义随 `masked` 而变：masked=true 时越小越好。 */
        val score: Double,
        val x: Int,
        val y: Int,
        val scale: Double,
        val masked: Boolean,
        val templateWidth: Int,
        val templateHeight: Int,
    )

    /**
     * 多尺度模板匹配 —— 复刻 `scale_and_match_template`。
     *
     * @param screenshot 归一化后的 BGR 截图
     * @param template 模板（BGR）
     * @param mask 掩码（可空）
     * @param threshold 阈值。语义随 mask 而变
     * @param scaleRange 缩放范围；为 null 时只试 1.0
     */
    fun match(
        screenshot: Mat,
        template: Mat,
        mask: Mat?,
        threshold: Double,
        scaleRange: Pair<Float, Float>? = DEFAULT_SCALE_RANGE,
    ): MatchResult? {
        val th = template.rows()
        val tw = template.cols()

        if (mask != null) {
            // ⚠️ 带掩码：TM_SQDIFF，越小越好。原实现此分支不做缩放搜索。
            if (th > screenshot.rows() || tw > screenshot.cols()) return null
            val result = Mat()
            return try {
                Imgproc.matchTemplate(screenshot, template, result, Imgproc.TM_SQDIFF, mask)
                val mm = Core.minMaxLoc(result)
                if (mm.minVal <= threshold) {
                    MatchResult(mm.minVal, mm.minLoc.x.toInt(), mm.minLoc.y.toInt(), 1.0, true, tw, th)
                } else null
            } catch (t: Throwable) {
                Ln.w("ImageUtils: SQDIFF 匹配失败: ${t.message}")
                null
            } finally {
                result.release()
            }
        }

        // 无掩码：TM_CCOEFF_NORMED，越大越好
        if (th > screenshot.rows() || tw > screenshot.cols()) return null
        var bestScore = -1.0
        var bestX = 0
        var bestY = 0
        var bestScale = 1.0

        fun tryScale(scale: Double) {
            val nh = (th * scale).toInt()
            val nw = (tw * scale).toInt()
            if (nh < 4 || nw < 4 || nh > screenshot.rows() || nw > screenshot.cols()) return
            val tpl = if (scale == 1.0) template else Mat().also {
                Imgproc.resize(template, it, Size(nw.toDouble(), nh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            }
            val result = Mat()
            try {
                Imgproc.matchTemplate(screenshot, tpl, result, Imgproc.TM_CCOEFF_NORMED)
                val mm = Core.minMaxLoc(result)
                if (mm.maxVal > bestScore) {
                    bestScore = mm.maxVal
                    bestX = mm.maxLoc.x.toInt()
                    bestY = mm.maxLoc.y.toInt()
                    bestScale = scale
                }
            } catch (t: Throwable) {
                Ln.w("ImageUtils: CCOEFF 匹配失败(scale=$scale): ${t.message}")
            } finally {
                result.release()
                if (scale != 1.0) tpl.release()
            }
        }

        tryScale(1.0)
        // 首轮不达标才做多尺度搜索（与原实现一致，省时间）
        if (bestScore < threshold && scaleRange != null) {
            var s = scaleRange.first.toDouble()
            while (s <= scaleRange.second + 1e-4) {
                if (kotlin.math.abs(s - 1.0) > 1e-6) tryScale(s)
                s += SCALE_STEP
            }
        }

        return if (bestScore >= threshold) {
            MatchResult(bestScore, bestX, bestY, bestScale, false, tw, th)
        } else null
    }

    /**
     * 在截图中查找模板（自动从 assets 读模板 + 掩码）。
     *
     * @param screenshot 归一化后的 BGR 截图
     * @param templatePath 模板逻辑路径，如 `screen/mail.png`
     * @return 匹配结果（归一化图坐标系）；未命中返回 null
     */
    fun findTemplate(
        screenshot: Mat,
        templatePath: String,
        threshold: Double = 0.8,
        scaleRange: Pair<Float, Float>? = DEFAULT_SCALE_RANGE,
    ): MatchResult? {
        val (tpl, mask) = loadTemplate(templatePath) ?: return null
        return match(screenshot, tpl, mask, threshold, scaleRange)
    }

    /**
     * 数「**指定颜色的图形**」在画面里出现了几次 —— 对应 M7A 的 `image_count`。
     *
     * ## 为什么不能直接用普通模板匹配
     *
     * 忘却之庭的星星是**金色实心图形**，背景随关卡卡片的底色变化（有时是
     * 深蓝、有时是半透明灰）。直接拿 `star.png` 做 CCOEFF 匹配，背景一亮
     * 分数就掉到 0.4 以下，星数永远读成 0。
     *
     * M7A 的做法是**先把画面按颜色二值化**（只留下金色像素），再拿
     * `star.png`（它本身就是纯黑白掩码，17×17，白星黑底）去匹配二值图。
     * 背景被颜色过滤掉了，所以怎么变都不影响。
     *
     * ## 计数方式
     *
     * 匹配一次只有一个最好位置，所以这里做**迭代抑制**：
     * 命中一次 → 把那一块涂黑 → 再匹配，直到分数不达标或到 [maxCount]。
     *
     * @param src 原始帧（BGR）
     * @param templatePath 黑白掩码模板（如 `forgottenhall/star.png`）
     * @param bgr 目标颜色（B, G, R 顺序，与 Mat 一致）
     * @param region 只在（归一化前的）像素矩形里找
     * @param threshold CCOEFF_NORMED 阈值
     * @param maxCount 最多数几个（星星最多 3 个，数多了就是误判）
     * @param toleranceSq 颜色容差（各通道差的平方和上限）
     * @return 命中个数；模板缺失或区域非法时返回 0
     */
    fun countColoredMatches(
        src: Mat,
        templatePath: String,
        bgr: DoubleArray,
        region: org.opencv.core.Rect? = null,
        threshold: Double = 0.60,
        maxCount: Int = 3,
        toleranceSq: Double = 3000.0,
    ): Int {
        val (tplBgr, _) = loadTemplate(templatePath) ?: run {
            Ln.w("ImageUtils: 计数模板不存在 $templatePath")
            return 0
        }

        // ---- ① 取 ROI（**复制**一份，避免后续涂黑影响原帧）----
        val roi = if (region == null) src.clone() else {
            val x = region.x.coerceIn(0, (src.cols() - 1).coerceAtLeast(0))
            val y = region.y.coerceIn(0, (src.rows() - 1).coerceAtLeast(0))
            val w = region.width.coerceAtMost(src.cols() - x)
            val h = region.height.coerceAtMost(src.rows() - y)
            if (w <= 0 || h <= 0) return 0
            Mat(src, org.opencv.core.Rect(x, y, w, h)).clone()
        }

        val tplGray = Mat()
        try {
            Imgproc.cvtColor(tplBgr, tplGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.threshold(tplGray, tplGray, 128.0, 255.0, Imgproc.THRESH_BINARY)

            // ---- ② 按颜色生成黑白图 ----
            val w = roi.cols()
            val h = roi.rows()
            if (w < tplGray.cols() || h < tplGray.rows()) return 0
            val px = ByteArray(w * h * 3)
            roi.get(0, 0, px)
            val bw = ByteArray(w * h)
            var i = 0
            for (p in 0 until w * h) {
                val db = (px[i].toInt() and 0xFF) - bgr[0]
                val dg = (px[i + 1].toInt() and 0xFF) - bgr[1]
                val dr = (px[i + 2].toInt() and 0xFF) - bgr[2]
                bw[p] = if (db * db + dg * dg + dr * dr <= toleranceSq) 0xFF.toByte() else 0
                i += 3
            }
            val bwMat = Mat(h, w, org.opencv.core.CvType.CV_8UC1)
            bwMat.put(0, 0, bw)
            try {
                // ---- ③ 迭代匹配 + 抑制 ----
                var count = 0
                repeat(maxCount) {
                    val res = Mat()
                    val score: Double
                    val locX: Int
                    val locY: Int
                    try {
                        Imgproc.matchTemplate(bwMat, tplGray, res, Imgproc.TM_CCOEFF_NORMED)
                        val mm = Core.minMaxLoc(res)
                        score = mm.maxVal
                        locX = mm.maxLoc.x.toInt()
                        locY = mm.maxLoc.y.toInt()
                    } finally {
                        res.release()
                    }
                    if (!score.isFinite() || score < threshold) return count
                    count++
                    // 涂黑命中块（含 4px 余量），防止同一个星星被数两次
                    val mx = (locX - 4).coerceAtLeast(0)
                    val my = (locY - 4).coerceAtLeast(0)
                    val mw = (tplGray.cols() + 8).coerceAtMost(w - mx)
                    val mh = (tplGray.rows() + 8).coerceAtMost(h - my)
                    if (mw > 0 && mh > 0) {
                        val sub = Mat(bwMat, org.opencv.core.Rect(mx, my, mw, mh))
                        try {
                            sub.setTo(org.opencv.core.Scalar(0.0))
                        } finally {
                            sub.release()
                        }
                    }
                }
                return count
            } finally {
                bwMat.release()
            }
        } finally {
            roi.release()
            tplGray.release()
        }
    }

    /**
     * 数**同色连通块**的个数 —— 比 [countColoredMatches] 更抗缩放。
     *
     * ## 为什么需要它
     *
     * `forgottenhall/star.png` 只有 **17×17**（PC 端从这个尺寸抠的），
     * 而手游 1920×1080 上那颗星是 **约 40×40**。CCOEFF 匹配对尺度很敏感，
     * 拿 17px 的模板去找 40px 的实心星，分数上不去，"三颗星全满"会被
     * 读成 0 星 —— 于是逻辑会去白打一层已经满星的关卡。
     *
     * 数连通块完全不吃尺度：先按颜色二值化，再数有几个面积达标的色块。
     * 星星的颜色是金色（实测 RGB≈(250,200,110)，背景是深紫 (45,25,90)），
     * 两者差得足够远，颜色过滤后每颗星都是独立一团（实测约 310 像素）。
     *
     * ## 参数怎么定的
     *
     * 实测（1920×1080 实机截图，第 09~12 层）：
     * - 单颗星的金色像素 ≈ 300~330；
     * - 三颗星合计 ≈ 930~970，彼此不粘连。
     *
     * 所以 [minArea] 取 80（单星面积的四分之一，容得下抗锯齿和压缩损失），
     * [maxArea] 取 4000（防止把整片背景误当一颗星）。
     *
     * @param src 原始帧（BGR）
     * @param region 只在这个像素矩形里数；null = 全帧
     * @param bgr 目标颜色（B, G, R）
     * @param toleranceSq 各通道差的平方和上限
     * @param minArea 一块算作"一个图形"的最小面积（像素）
     * @param maxArea 超过这个面积视为背景，不计
     */
    fun countColorBlobs(
        src: Mat,
        region: org.opencv.core.Rect?,
        bgr: DoubleArray,
        toleranceSq: Double = 3000.0,
        minArea: Int = 80,
        maxArea: Int = 4000,
    ): Int {
        if (src.empty()) return 0
        val rx = (region?.x ?: 0).coerceIn(0, (src.cols() - 1).coerceAtLeast(0))
        val ry = (region?.y ?: 0).coerceIn(0, (src.rows() - 1).coerceAtLeast(0))
        val rw = (region?.width ?: src.cols()).coerceAtMost(src.cols() - rx)
        val rh = (region?.height ?: src.rows()).coerceAtMost(src.rows() - ry)
        if (rw <= 0 || rh <= 0) return 0

        val roi = Mat(src, org.opencv.core.Rect(rx, ry, rw, rh)).clone()
        return try {
            val px = ByteArray(rw * rh * 3)
            roi.get(0, 0, px)

            // ---- ① 颜色二值化 ----
            val mask = BooleanArray(rw * rh)
            var i = 0
            for (p in 0 until rw * rh) {
                val db = (px[i].toInt() and 0xFF) - bgr[0]
                val dg = (px[i + 1].toInt() and 0xFF) - bgr[1]
                val dr = (px[i + 2].toInt() and 0xFF) - bgr[2]
                mask[p] = db * db + dg * dg + dr * dr <= toleranceSq
                i += 3
            }

            // ---- ② 洪水填充数连通块 ----
            val visited = BooleanArray(rw * rh)
            val stack = IntArray(rw * rh)
            var count = 0
            for (start in 0 until rw * rh) {
                if (!mask[start] || visited[start]) continue
                var sp = 0
                stack[sp++] = start
                visited[start] = true
                var area = 0
                while (sp > 0) {
                    val cur = stack[--sp]
                    area++
                    val cx = cur % rw
                    val cy = cur / rw
                    // 四邻域足够 —— 星星内部是实心的，不需要八邻域
                    if (cx > 0) {
                        val n = cur - 1
                        if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n }
                    }
                    if (cx < rw - 1) {
                        val n = cur + 1
                        if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n }
                    }
                    if (cy > 0) {
                        val n = cur - rw
                        if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n }
                    }
                    if (cy < rh - 1) {
                        val n = cur + rw
                        if (mask[n] && !visited[n]) { visited[n] = true; stack[sp++] = n }
                    }
                }
                if (area in minArea..maxArea) count++
            }
            // ## 不在这里 clamp（重要）
            //
            // 星星图形万一被抗锯齿切碎，3 颗会数成 4~5 个色块。这时**必须**
            // 把"我数不清"如实报上去，让调用方按"未知"处理（= 去打），
            // 而不是夹到 3 —— 夹到 3 会让它误判成"已满星"从而漏掉该打的层。
            count
        } catch (t: Throwable) {
            Ln.w("ImageUtils: countColorBlobs 失败：${t.message}")
            0
        } finally {
            roi.release()
        }
    }

    /** 清空模板缓存（换资源包时调用）。 */
    fun clearCache() {
        templateCache.values.forEach { (t, m) ->
            if (!t.empty()) t.release()
            m?.release()
        }
        templateCache.clear()
    }

    /** 画一个调试框到 Mat（绿色）。 */
    fun drawRect(mat: Mat, r: MatchResult, color: DoubleArray = doubleArrayOf(0.0, 255.0, 0.0)) {
        Imgproc.rectangle(
            mat,
            Point(r.x.toDouble(), r.y.toDouble()),
            Point((r.x + r.templateWidth * r.scale).toDouble(), (r.y + r.templateHeight * r.scale).toDouble()),
            org.opencv.core.Scalar(color[0], color[1], color[2]),
            3,
        )
    }
}
