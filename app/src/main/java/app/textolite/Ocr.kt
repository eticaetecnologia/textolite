package app.textolite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object Ocr {

    private const val PROMPT =
        "Transcreva fielmente todo o conteúdo desta imagem para Markdown, no idioma original. " +
        "Inclua texto impresso e manuscrito. " +
        "Reproduza a estrutura com Markdown: títulos e subtítulos com # e ##, **negrito**, *itálico*, " +
        "~~riscado~~, listas com - ou 1., citações com >, e tabelas Markdown. " +
        "Notas de rodapé: coloque a chamada no texto como [^1] e, ao final da página, " +
        "a nota em uma linha própria no formato [^1]: texto da nota. " +
        "Número de página impresso: escreva em linha própria no formato [p. 12]. " +
        "Para esquemas, setas e marcações manuscritas (sublinhado, destaque), " +
        "use **negrito**, setas (→) e indentação. " +
        "Se for um gabarito ou lista de respostas, escreva uma linha por questão, ex.: 1. A. " +
        "Não invente nem corrija texto; use [ilegível] quando não der para ler. " +
        "Responda somente com o Markdown, sem comentários."

    // ---------- Imagem ----------

    /** Abre a imagem, corrige a rotação e reduz para no máximo [maxSide] pixels. */
    fun loadBitmap(ctx: Context, uri: Uri, maxSide: Int = 2200): Bitmap {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IOException("Não foi possível abrir a imagem.")

        val orientation = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
        }
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) m.postScale(scale, scale)
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        return bmp
    }

    /** Guarda a foto como uma página do documento (arquivo JPEG + miniatura). */
    fun savePage(ctx: Context, uri: Uri): Page {
        val bmp = loadBitmap(ctx, uri)
        val dir = File(ctx.cacheDir, "pages").apply { mkdirs() }
        val file = File(dir, "p${System.nanoTime()}.jpg")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val s = 360f / maxOf(bmp.width, bmp.height)
        val thumb = if (s < 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * s).toInt().coerceAtLeast(1),
                (bmp.height * s).toInt().coerceAtLeast(1),
                true
            )
        } else bmp
        return Page(file, thumb)
    }

    private fun toBase64Jpeg(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ---------- Offline (ML Kit, roda no celular) ----------

    suspend fun offline(bmp: Bitmap): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { cont.resume(format(it)) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    private fun format(t: Text): String =
        t.textBlocks.joinToString("\n\n") { block ->
            block.lines.joinToString("  \n") { it.text }
        }.trim()

    // ---------- Google Gemini ----------

    suspend fun gemini(bmp: Bitmap, key: String, model: String): String =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put(
                    "contents", JSONArray().put(
                        JSONObject().put(
                            "parts", JSONArray()
                                .put(JSONObject().put("text", PROMPT))
                                .put(
                                    JSONObject().put(
                                        "inline_data",
                                        JSONObject()
                                            .put("mime_type", "image/jpeg")
                                            .put("data", toBase64Jpeg(bmp))
                                    )
                                )
                        )
                    )
                )
                .put("generationConfig", JSONObject().put("temperature", 0))

            val name = model.trim().removePrefix("models/")
            val resp = post(
                "https://generativelanguage.googleapis.com/v1beta/models/$name:generateContent",
                mapOf("x-goog-api-key" to key.trim()),
                body.toString()
            )
            val parts = JSONObject(resp)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
            clean(sb.toString())
        }

    // ---------- Qualquer IA compatível com o formato OpenAI (OpenRouter, Groq, OpenAI...) ----------

    suspend fun compat(bmp: Bitmap, baseUrl: String, key: String, model: String): String =
        withContext(Dispatchers.IO) {
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", PROMPT))
                .put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64," + toBase64Jpeg(bmp))
                    )
                )
            val body = JSONObject()
                .put("model", model.trim())
                .put("temperature", 0)
                .put(
                    "messages", JSONArray().put(
                        JSONObject().put("role", "user").put("content", content)
                    )
                )
            val resp = post(
                baseUrl.trim().trimEnd('/') + "/chat/completions",
                mapOf("Authorization" to "Bearer " + key.trim()),
                body.toString()
            )
            val text = JSONObject(resp)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
            clean(text)
        }

    // ---------- Util ----------

    /** Remove cercas ```markdown que alguns modelos colocam em volta da resposta. */
    private fun clean(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter("\n", "").removeSuffix("```").trim()
        }
        return t
    }

    private fun post(url: String, headers: Map<String, String>, body: String): String {
        if (!url.startsWith("https://")) throw IOException("O endereço da IA precisa começar com https://")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000
            c.readTimeout = 120_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IOException(friendly(code))
            return text
        } finally {
            c.disconnect()
        }
    }

    private fun friendly(code: Int): String = when (code) {
        400 -> "A IA recusou o pedido (erro 400). Verifique o nome do modelo."
        401, 403 -> "Chave de API inválida ou sem permissão (erro $code)."
        404 -> "Modelo ou endereço não encontrado (erro 404). Verifique o nome do modelo."
        429 -> "Limite gratuito atingido (erro 429). Tente novamente mais tarde."
        else -> "Erro do serviço de IA (código $code)."
    }
}
