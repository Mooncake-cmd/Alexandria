package com.alexandria.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Um grupo da Estante: os livros soltos ([title] nulo) ou uma prateleira com nome. */
class ShelfSection(val title: String?, val books: List<LibraryItem>)

/**
 * A Estante desenhada como um móvel de madeira: uma prateleira de cima com decorações e, abaixo,
 * os livros concluídos em pé, de capa para a frente, com lombada, páginas e sombra. Cada livro é
 * uma [ShelfBook] (filho desta view): tocar abre, manter pressionado mostra as opções, e o leitor de
 * tela anuncia o título. Tudo é desenhado em código; só a planta e o globo são imagens.
 */
@SuppressLint("ViewConstructor") // criada só pelo código, com os livros
class WoodenShelf(
    context: Context,
    private val sections: List<ShelfSection>,
    cover: (LibraryItem) -> Bitmap?,
    title: (LibraryItem) -> String,
    status: (LibraryItem) -> String,
    onOpen: (LibraryItem) -> Unit,
    onOptions: (LibraryItem) -> Unit
) : ViewGroup(context) {

    private sealed class Row {
        var top = 0
        var height = 0
        object Decor : Row()
        class Title(val text: String) : Row()
        class Books(val books: List<ShelfBook>) : Row()
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private val post = dp(12f)
    private val crown = dp(20f)
    private val plinth = dp(18f)
    private val board = dp(15f)
    private val boardTop = dp(5f)
    private val headroom = dp(22f)
    private val titleRow = dp(40f)

    private var columns = 3
    private var bookWidth = 0
    private var bookHeight = 0
    private val rows = ArrayList<Row>()
    private val books = ArrayList<ShelfBook>()

    private val plant = loadAsset("shelf/planta.webp")
    private val globe = loadAsset("shelf/globo.webp")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val backWood = Paint().apply { shader = woodShader(false, Color.rgb(40, 23, 13), Color.rgb(74, 45, 27), 11) }
    private val frameWood = Paint().apply { shader = woodShader(false, Color.rgb(78, 48, 27), Color.rgb(132, 86, 50), 23) }
    private val boardWood = Paint().apply { shader = woodShader(true, Color.rgb(92, 57, 32), Color.rgb(146, 96, 56), 37) }
    private val plateText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textSize = dp(13f)
        color = Color.rgb(58, 36, 16)
        textAlign = Paint.Align.CENTER
    }

    init {
        setWillNotDraw(false)
        // As fileiras dependem da largura e são montadas no onMeasure; aqui só os livros, em ordem.
        for (section in sections) {
            for (item in section.books) {
                val book = ShelfBook(context, item, cover(item), title(item), status(item)).apply {
                    setOnClickListener { onOpen(item) }
                    setOnLongClickListener { onOptions(item); true }
                }
                books += book
                addView(book)
            }
        }
    }

    private fun loadAsset(path: String): Bitmap? = try {
        context.assets.open(path).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val inner = width - 2 * post
        columns = max(3, (inner / dp(118f)).toInt())
        val cell = inner / columns
        bookWidth = (cell * 0.70f).roundToInt()
        bookHeight = (bookWidth * 1.48f).roundToInt()

        buildRows()
        var y = crown.roundToInt()
        for (row in rows) {
            row.top = y
            row.height = when (row) {
                Row.Decor -> (headroom + bookHeight * 0.92f + board).roundToInt()
                is Row.Title -> titleRow.roundToInt()
                is Row.Books -> (headroom + bookHeight + board).roundToInt()
            }
            y += row.height
        }
        y += plinth.roundToInt()
        val exact = MeasureSpec.makeMeasureSpec(bookWidth, MeasureSpec.EXACTLY)
        val exactHeight = MeasureSpec.makeMeasureSpec(bookHeight, MeasureSpec.EXACTLY)
        books.forEach { it.measure(exact, exactHeight) }
        setMeasuredDimension(width, y)
    }

    /** Linhas da estante: decoração e, para cada grupo, a placa (se tiver nome) e as fileiras. */
    private fun buildRows() {
        rows.clear()
        rows += Row.Decor
        var index = 0
        for (section in sections) {
            if (section.books.isEmpty()) continue
            section.title?.let { rows += Row.Title(it) }
            val sectionBooks = books.subList(index, index + section.books.size)
            index += section.books.size
            sectionBooks.chunked(columns).forEach { rows += Row.Books(it) }
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cell = (width - 2 * post) / columns
        for (row in rows) {
            if (row !is Row.Books) continue
            row.books.forEachIndexed { column, book ->
                val left = (post + column * cell + (cell - bookWidth) / 2f - dp(2f)).roundToInt()
                val top = (row.top + headroom).roundToInt()
                book.layout(left, top, left + bookWidth, top + bookHeight)
            }
        }
    }

    override fun onDraw(canvas: Canvas) = drawShelf(canvas)

    private fun drawShelf(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Fundo do móvel.
        canvas.drawRect(post, crown, w - post, h - plinth, backWood)
        for (row in rows) drawCompartment(canvas, row)
        drawFrame(canvas, w, h)
    }

    private fun drawCompartment(canvas: Canvas, row: Row) {
        val left = post
        val right = width - post
        val top = row.top.toFloat()
        val bottom = (row.top + row.height).toFloat()
        val floor = if (row is Row.Title) bottom else bottom - board

        // Sombra que a tábua de cima projeta no fundo, e sombras nas laterais.
        shade(canvas, left, top, right, top + dp(30f), Color.argb(150, 0, 0, 0), 0, vertical = true)
        shade(canvas, left, top, left + dp(16f), floor, Color.argb(110, 0, 0, 0), 0, vertical = false)
        shade(canvas, right - dp(16f), top, right, floor, 0, Color.argb(110, 0, 0, 0), vertical = false)
        // Fundo um pouco mais escuro perto da tábua de baixo (luz vem de cima).
        if (row !is Row.Title) shade(canvas, left, floor - dp(26f), right, floor, 0, Color.argb(70, 0, 0, 0), vertical = true)

        when (row) {
            Row.Decor -> drawDecor(canvas, top, floor)
            is Row.Title -> drawPlate(canvas, row.text, (top + bottom) / 2f)
            is Row.Books -> row.books.forEach { drawBookShadow(canvas, it, floor) }
        }
        if (row !is Row.Title) drawBoard(canvas, floor)
    }

    private fun drawBoard(canvas: Canvas, y: Float) {
        val left = post - dp(2f)
        val right = width - post + dp(2f)
        // Face de cima (clara, vista de leve) e frente com veios na horizontal.
        paint.shader = LinearGradient(0f, y, 0f, y + boardTop, Color.rgb(178, 124, 76), Color.rgb(140, 92, 54), Shader.TileMode.CLAMP)
        canvas.drawRect(left, y, right, y + boardTop, paint)
        paint.shader = null
        canvas.drawRect(left, y + boardTop, right, y + board, boardWood)
        shade(canvas, left, y + boardTop, right, y + board, Color.argb(40, 255, 230, 190), Color.argb(90, 0, 0, 0), vertical = true)
        paint.color = Color.argb(120, 20, 10, 4)
        canvas.drawRect(left, y + board - dp(1f), right, y + board, paint)
        paint.color = Color.argb(70, 255, 225, 180)
        canvas.drawRect(left, y + boardTop, right, y + boardTop + dp(0.8f), paint)
    }

    private fun drawFrame(canvas: Canvas, w: Float, h: Float) {
        // Laterais.
        canvas.drawRect(0f, 0f, post, h, frameWood)
        canvas.drawRect(w - post, 0f, w, h, frameWood)
        shade(canvas, 0f, 0f, post, h, Color.argb(60, 255, 220, 170), Color.argb(90, 0, 0, 0), vertical = false)
        shade(canvas, w - post, 0f, w, h, Color.argb(40, 255, 220, 170), Color.argb(110, 0, 0, 0), vertical = false)
        // Topo com moldura e base.
        canvas.drawRect(0f, 0f, w, crown, boardWood)
        shade(canvas, 0f, 0f, w, crown, Color.argb(60, 255, 225, 180), Color.argb(120, 0, 0, 0), vertical = true)
        paint.shader = null
        paint.color = Color.argb(90, 255, 225, 180)
        canvas.drawRect(0f, crown * 0.38f, w, crown * 0.38f + dp(1f), paint)
        paint.color = Color.argb(140, 20, 10, 4)
        canvas.drawRect(0f, crown - dp(1.5f), w, crown, paint)
        canvas.drawRect(0f, h - plinth, w, h, boardWood)
        shade(canvas, 0f, h - plinth, w, h, Color.argb(50, 255, 225, 180), Color.argb(130, 0, 0, 0), vertical = true)
    }

    /** Sombra de um livro: mancha na tábua e sombra para a direita no fundo (luz de cima à esquerda). */
    private fun drawBookShadow(canvas: Canvas, book: ShelfBook, floor: Float) {
        val left = book.left.toFloat()
        val right = book.right.toFloat()
        val top = book.top.toFloat()
        val l = right - dp(2f)
        val r = right + dp(12f)
        val t = top + dp(6f)
        val across = LinearGradient(l, 0f, r, 0f, Color.argb(120, 0, 0, 0), 0, Shader.TileMode.CLAMP)
        val fade = LinearGradient(0f, t, 0f, t + dp(24f), 0, Color.BLACK, Shader.TileMode.CLAMP)
        paint.shader = ComposeShader(across, fade, PorterDuff.Mode.DST_IN)
        canvas.drawRect(l, t, r, floor, paint)
        paint.shader = null
        ellipseShadow(canvas, (left + right) / 2f + dp(3f), floor + dp(1f), (right - left) * 0.62f, dp(5f), 150)
    }

    private fun ellipseShadow(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, alpha: Int) {
        val matrix = Matrix().apply { setScale(1f, ry / rx, cx, cy) }
        paint.shader = RadialGradient(cx, cy, rx, Color.argb(alpha, 0, 0, 0), 0, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
        canvas.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, paint)
        paint.shader = null
    }

    private fun shade(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, from: Int, to: Int, vertical: Boolean) {
        paint.shader = if (vertical) LinearGradient(0f, t, 0f, b, from, to, Shader.TileMode.CLAMP)
        else LinearGradient(l, 0f, r, 0f, from, to, Shader.TileMode.CLAMP)
        canvas.drawRect(l, t, r, b, paint)
        paint.shader = null
    }

    private fun drawDecor(canvas: Canvas, top: Float, floor: Float) {
        paint.color = Color.BLACK
        val inner = width - 2 * post
        val space = floor - top - dp(10f)
        plant?.let { bitmap ->
            val height = space * 0.98f
            val width = height * bitmap.width / bitmap.height
            val left = post + inner * 0.09f
            ellipseShadow(canvas, left + width * 0.5f, floor, width * 0.32f, dp(5f), 170)
            paint.color = Color.BLACK
            canvas.drawBitmap(bitmap, null, RectF(left, floor - height, left + width, floor + dp(1f)), paint)
        }
        globe?.let { bitmap ->
            val height = space * 0.90f
            val width = height * bitmap.width / bitmap.height
            val left = post + inner * 0.70f - width / 2f
            ellipseShadow(canvas, left + width * 0.5f, floor, width * 0.30f, dp(5f), 170)
            paint.color = Color.BLACK
            canvas.drawBitmap(bitmap, null, RectF(left, floor - height, left + width, floor + dp(1f)), paint)
        }
    }

    private fun drawPlate(canvas: Canvas, text: String, cy: Float) {
        val label = TextUtils.ellipsize(text, plateText, width * 0.6f, TextUtils.TruncateAt.END).toString()
        val half = plateText.measureText(label) / 2f + dp(16f)
        val cx = width / 2f
        val plate = RectF(cx - half, cy - dp(12f), cx + half, cy + dp(12f))
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawRoundRect(RectF(plate.left + dp(2f), plate.top + dp(3f), plate.right + dp(2f), plate.bottom + dp(3f)), dp(4f), dp(4f), paint)
        paint.shader = LinearGradient(0f, plate.top, 0f, plate.bottom,
            intArrayOf(Color.rgb(236, 205, 134), Color.rgb(196, 152, 78), Color.rgb(150, 108, 48)), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(plate, dp(4f), dp(4f), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(1f)
        paint.color = Color.rgb(110, 76, 30)
        canvas.drawRoundRect(plate, dp(4f), dp(4f), paint)
        paint.style = Paint.Style.FILL
        for (x in floatArrayOf(plate.left + dp(6f), plate.right - dp(6f))) {
            paint.color = Color.rgb(120, 84, 36)
            canvas.drawCircle(x, cy, dp(2f), paint)
            paint.color = Color.rgb(240, 214, 150)
            canvas.drawCircle(x - dp(0.5f), cy - dp(0.5f), dp(0.8f), paint)
        }
        canvas.drawText(label, cx, cy - (plateText.ascent() + plateText.descent()) / 2f, plateText)
    }

    companion object {
        private val tiles = HashMap<Int, Bitmap>()

        /**
         * Textura de madeira gerada (repete sem emenda): veios em faixas com ondulação suave.
         * [horizontal] deita os veios (tábuas); [seed] varia o desenho entre as peças.
         */
        fun woodShader(horizontal: Boolean, dark: Int, light: Int, seed: Int): BitmapShader {
            val key = seed * 2 + if (horizontal) 1 else 0
            val tile = tiles.getOrPut(key) { woodTile(horizontal, dark, light, seed) }
            return BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }

        private fun woodTile(horizontal: Boolean, dark: Int, light: Int, seed: Int): Bitmap {
            val across = 256
            val along = 512
            val random = java.util.Random(seed.toLong())
            // Ruído 1D circular em várias escalas: largura e tom dos veios.
            val noise = FloatArray(across)
            var amplitude = 1f
            var period = 64
            while (period >= 2) {
                val points = FloatArray(across / period) { random.nextFloat() }
                for (i in 0 until across) {
                    val p = i / period
                    val f = (i % period) / period.toFloat()
                    val a = points[p]
                    val b = points[(p + 1) % points.size]
                    val t = f * f * (3 - 2 * f)
                    noise[i] += (a + (b - a) * t) * amplitude
                }
                amplitude *= 0.55f
                period /= 2
            }
            val lo = noise.min()
            val hi = noise.max()
            for (i in noise.indices) noise[i] = (noise[i] - lo) / (hi - lo)
            val phase = random.nextFloat() * 6.28f
            val pixels = IntArray(across * along)
            for (y in 0 until along) {
                val wave = 3.5f * sin(2 * PI * y / along * 2 + phase).toFloat() + 1.5f * sin(2 * PI * y / along * 5).toFloat()
                for (x in 0 until across) {
                    val shifted = ((x + wave + 2f * sin(x * 0.07f + y * 0.012f)).roundToInt() % across + across) % across
                    var t = noise[shifted]
                    t = t * t * (3 - 2 * t)
                    val grit = (random.nextFloat() - 0.5f) * 0.08f
                    val c = blend(dark, light, (t + grit).coerceIn(0f, 1f))
                    if (horizontal) pixels[x * along + y] = c else pixels[y * across + x] = c
                }
            }
            return if (horizontal) Bitmap.createBitmap(pixels, along, across, Bitmap.Config.ARGB_8888)
            else Bitmap.createBitmap(pixels, across, along, Bitmap.Config.ARGB_8888)
        }

        fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).roundToInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).roundToInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).roundToInt()
        )

        fun darker(color: Int, amount: Float) = blend(color, Color.BLACK, amount)
        fun lighter(color: Int, amount: Float) = blend(color, Color.WHITE, amount)
    }
}

/**
 * Um livro em pé na Estante: capa de frente, lombada sombreada à esquerda e o bloco de páginas à
 * direita. Ao ser tocado, gira um pouco em 3D a partir da lombada, como se fosse puxado.
 * Sem capa (TXT, DOCX...), ganha uma capa de couro com o título em dourado.
 */
@SuppressLint("ViewConstructor")
class ShelfBook(context: Context, val item: LibraryItem, private val cover: Bitmap?, private val title: String, status: String) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var titleLayout: StaticLayout? = null

    init {
        isClickable = true
        isLongClickable = true
        contentDescription = "$title. $status"
        cameraDistance = 9000 * density
    }

    override fun setPressed(pressed: Boolean) {
        if (pressed != isPressed) {
            pivotX = 0f
            pivotY = height / 2f
            animate().rotationY(if (pressed) -22f else 0f).setDuration(if (pressed) 140 else 220).start()
        }
        super.setPressed(pressed)
    }

    override fun onDraw(canvas: Canvas) = drawBook(canvas)

    private fun drawBook(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pages = dp(4f)
        val body = RectF(0f, 0f, w - pages, h)

        // Bloco de páginas (aparece do lado direito, um pouco recuado).
        val block = RectF(w - pages - dp(2f), dp(2.5f), w, h - dp(1.5f))
        paint.color = Color.rgb(226, 214, 186)
        canvas.drawRoundRect(block, dp(1.5f), dp(1.5f), paint)
        paint.color = Color.argb(60, 120, 96, 60)
        var y = block.top + dp(1.5f)
        while (y < block.bottom) {
            canvas.drawRect(block.left, y, block.right, y + dp(0.5f), paint)
            y += dp(1.6f)
        }
        shadeRect(canvas, block, Color.argb(0, 0, 0, 0), Color.argb(90, 0, 0, 0), vertical = false)

        // Capa.
        val clip = Path().apply { addRoundRect(body, floatArrayOf(dp(1f), dp(1f), dp(2.5f), dp(2.5f), dp(2.5f), dp(2.5f), dp(1f), dp(1f)), Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        paint.color = Color.BLACK // a transparência da cor também valeria para a imagem
        if (cover != null) drawCover(canvas, cover, body) else drawLeather(canvas, body)

        // Lombada: dobra da encadernação à esquerda.
        val spine = body.width() * 0.11f
        paint.shader = LinearGradient(0f, 0f, spine, 0f,
            intArrayOf(Color.argb(170, 0, 0, 0), Color.argb(40, 255, 255, 255), Color.argb(90, 0, 0, 0), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0f, 0.32f, 0.55f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, spine, h, paint)
        paint.shader = null
        paint.color = Color.argb(70, 0, 0, 0)
        canvas.drawRect(spine * 0.72f, 0f, spine * 0.72f + dp(0.8f), h, paint)
        paint.color = Color.argb(45, 255, 255, 255)
        canvas.drawRect(spine * 0.72f + dp(0.8f), 0f, spine * 0.72f + dp(1.4f), h, paint)
        // Luz de cima e borda direita mais escura dão volume.
        shadeRect(canvas, body, Color.argb(34, 255, 245, 220), Color.argb(70, 0, 0, 0), vertical = true)
        shadeRect(canvas, RectF(body.right - dp(5f), 0f, body.right, h), Color.argb(0, 0, 0, 0), Color.argb(80, 0, 0, 0), vertical = false)
        canvas.restore()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.8f)
        paint.color = Color.argb(110, 0, 0, 0)
        canvas.drawPath(clip, paint)
        paint.style = Paint.Style.FILL
    }

    /** Capa recortada no centro para ocupar o livro inteiro. */
    private fun drawCover(canvas: Canvas, bitmap: Bitmap, target: RectF) {
        val scale = max(target.width() / bitmap.width, target.height() / bitmap.height)
        val sw = target.width() / scale
        val sh = target.height() / scale
        val sx = (bitmap.width - sw) / 2f
        val sy = (bitmap.height - sh) / 2f
        canvas.drawBitmap(bitmap, Rect(sx.roundToInt(), sy.roundToInt(), (sx + sw).roundToInt(), (sy + sh).roundToInt()), target, paint)
    }

    private fun drawLeather(canvas: Canvas, body: RectF) {
        val leather = LEATHERS[abs(title.hashCode()) % LEATHERS.size]
        paint.shader = LinearGradient(0f, 0f, body.right, body.bottom,
            WoodenShelf.lighter(leather, 0.10f), WoodenShelf.darker(leather, 0.30f), Shader.TileMode.CLAMP)
        canvas.drawRect(body, paint)
        paint.shader = WoodenShelf.woodShader(false, Color.BLACK, Color.WHITE, 91)
        paint.alpha = 18
        canvas.drawRect(body, paint)
        paint.alpha = 255
        paint.shader = null

        val gold = Color.rgb(214, 178, 106)
        paint.style = Paint.Style.STROKE
        paint.color = gold
        paint.strokeWidth = dp(1f)
        val inset = body.width() * 0.08f
        canvas.drawRect(body.left + inset + dp(3f), body.top + inset, body.right - inset, body.bottom - inset, paint)
        paint.strokeWidth = dp(0.6f)
        canvas.drawRect(body.left + inset + dp(6f), body.top + inset + dp(3f), body.right - inset - dp(3f), body.bottom - inset - dp(3f), paint)
        paint.style = Paint.Style.FILL

        val textWidth = (body.width() - 2 * inset - dp(16f)).roundToInt().coerceAtLeast(1)
        val layout = titleLayout?.takeIf { it.width == textWidth } ?: run {
            val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
                color = gold
                textSize = body.width() * 0.125f
                letterSpacing = 0.04f
            }
            // Diminui a letra até a palavra mais longa caber inteira na linha (sem "MEMÓRI-AS").
            val upper = title.uppercase()
            val longest = upper.split(' ').maxByOrNull { text.measureText(it) } ?: upper
            while (text.measureText(longest) > textWidth && text.textSize > body.width() * 0.075f) text.textSize *= 0.92f
            StaticLayout.Builder.obtain(upper, 0, upper.length, text, textWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setMaxLines(5)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setLineSpacing(0f, 1.05f)
                .build()
                .also { titleLayout = it }
        }
        val cx = body.centerX() + dp(1.5f)
        val top = body.top + body.height() * 0.40f - layout.height / 2f
        canvas.save()
        canvas.translate(cx - textWidth / 2f, top)
        layout.draw(canvas)
        canvas.restore()
        // Ornamento abaixo do título.
        paint.color = gold
        val oy = top + layout.height + dp(10f)
        val half = body.width() * 0.18f
        canvas.drawRect(cx - half, oy - dp(0.4f), cx - dp(5f), oy + dp(0.4f), paint)
        canvas.drawRect(cx + dp(5f), oy - dp(0.4f), cx + half, oy + dp(0.4f), paint)
        val s = dp(2.6f)
        canvas.drawPath(Path().apply { moveTo(cx, oy - s); lineTo(cx + s, oy); lineTo(cx, oy + s); lineTo(cx - s, oy); close() }, paint)
        // Formato no pé da capa.
        val format = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.NORMAL)
            color = Color.argb(200, 214, 178, 106)
            textSize = body.width() * 0.075f
            letterSpacing = 0.25f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(item.format.uppercase(), cx, body.bottom - inset - dp(9f), format)
    }

    private fun shadeRect(canvas: Canvas, r: RectF, from: Int, to: Int, vertical: Boolean) {
        paint.shader = if (vertical) LinearGradient(0f, r.top, 0f, r.bottom, from, to, Shader.TileMode.CLAMP)
        else LinearGradient(r.left, 0f, r.right, 0f, from, to, Shader.TileMode.CLAMP)
        canvas.drawRect(r, paint)
        paint.shader = null
    }

    companion object {
        private val LEATHERS = intArrayOf(
            Color.rgb(96, 28, 26), Color.rgb(30, 46, 74), Color.rgb(34, 64, 46),
            Color.rgb(78, 48, 26), Color.rgb(44, 40, 38), Color.rgb(88, 70, 34)
        )
    }
}
