package app.textolite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { HOME, RESULT, SETTINGS, CROP }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                App()
            }
        }
    }
}

private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())

private fun saveBytes(ctx: Context, uri: Uri, bytes: ByteArray) {
    try {
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        Toast.makeText(ctx, "Arquivo salvo", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(ctx, "Falha ao salvar: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun openLink(ctx: Context, url: String) {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf(Screen.HOME) }
    var engine by remember { mutableStateOf(settings.engine) }
    val pages = remember { mutableStateListOf<Page>() }
    var current by remember { mutableStateOf(0) }
    var cropIndex by remember { mutableStateOf(0) }
    var withImages by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    fun addPages(uris: List<Uri>) {
        busy = true
        error = null
        scope.launch {
            try {
                for (u in uris) {
                    if (Ocr.isPdf(ctx, u)) {
                        progress = "Abrindo PDF…"
                        val pdf = withContext(Dispatchers.IO) { Ocr.copyToCache(ctx, u) }
                        try {
                            val n = withContext(Dispatchers.IO) { Ocr.pdfPageCount(pdf) }
                            for (i in 0 until n) {
                                progress = "Convertendo página ${i + 1} de $n do PDF…"
                                pages.add(withContext(Dispatchers.IO) { Ocr.renderPdfPage(ctx, pdf, i) })
                            }
                        } finally {
                            pdf.delete()
                        }
                    } else {
                        pages.add(withContext(Dispatchers.IO) { Ocr.savePage(ctx, u) })
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: "Não foi possível abrir o arquivo."
            } finally {
                busy = false
                progress = null
            }
        }
    }

    fun removePage(i: Int) {
        if (i in pages.indices) {
            pages[i].deleteFiles()
            pages.removeAt(i)
        }
    }

    fun clearAll() {
        pages.forEach { it.deleteFiles() }
        pages.clear()
        current = 0
        error = null
    }

    fun recognize() {
        busy = true
        error = null
        scope.launch {
            var idx = 0
            try {
                for (i in pages.indices) {
                    idx = i
                    val p = pages[i]
                    if (p.text.isBlank()) {
                        progress = "Reconhecendo página ${i + 1} de ${pages.size}…"
                        val b = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p.file.path) }
                            ?: throw IOException("Imagem indisponível.")
                        p.text = when (engine) {
                            Engine.OFFLINE -> Ocr.offline(b)
                            Engine.GEMINI -> Ocr.gemini(b, settings.geminiKey, settings.geminiModel)
                            Engine.COMPAT -> Ocr.compat(b, settings.compatUrl, settings.compatKey, settings.compatModel)
                        }
                    }
                }
                current = 0
                screen = Screen.RESULT
            } catch (e: Exception) {
                error = "Página ${idx + 1}: " + (e.message ?: "erro ao reconhecer o texto.")
            } finally {
                busy = false
                progress = null
            }
        }
    }

    fun applyCrop(l: Float, t: Float, r: Float, b: Float) {
        val pg = pages.getOrNull(cropIndex) ?: return
        busy = true
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) { Ocr.cropFile(pg, l, t, r, b) }
                pg.cropped?.delete()
                pg.cropped = res.first
                pg.thumb = res.second
                pg.text = ""
                screen = Screen.HOME
            } catch (e: Exception) {
                error = e.message ?: "Não foi possível recortar."
                screen = Screen.HOME
            } finally {
                busy = false
            }
        }
    }

    fun restoreCrop() {
        val pg = pages.getOrNull(cropIndex) ?: return
        busy = true
        scope.launch {
            try {
                val th = withContext(Dispatchers.IO) { Ocr.thumbOf(pg.original) }
                pg.cropped?.delete()
                pg.cropped = null
                pg.thumb = th
                pg.text = ""
            } catch (e: Exception) {
                error = e.message ?: "Não foi possível restaurar a foto."
            } finally {
                busy = false
                screen = Screen.HOME
            }
        }
    }

    fun export(uri: Uri, make: (List<Page>, Boolean) -> ByteArray) {
        val snapshot = pages.toList()
        val imgs = withImages
        busy = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { make(snapshot, imgs) }
                saveBytes(ctx, uri, bytes)
            } catch (e: Exception) {
                Toast.makeText(ctx, "Falha ao exportar: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    val photoUri = remember {
        val dir = File(ctx.cacheDir, "images").apply { mkdirs() }
        FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", File(dir, "captura.jpg"))
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) addPages(listOf(photoUri))
    }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) addPages(uris)
    }
    val openFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) addPages(uris)
    }
    val saveMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) export(uri, Export::md)
    }
    val saveFodt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.oasis.opendocument.text-flat-xml")
    ) { uri ->
        if (uri != null) export(uri, Export::fodt)
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) export(uri, Export::pdf)
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    settings = settings,
                    engine = engine,
                    onEngine = { engine = it; settings.engine = it },
                    pages = pages,
                    busy = busy,
                    progress = progress,
                    error = error,
                    onCamera = { takePicture.launch(photoUri) },
                    onGallery = {
                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onFiles = { openFiles.launch(arrayOf("image/*", "application/pdf")) },
                    onRemove = { removePage(it) },
                    onCrop = { cropIndex = it; screen = Screen.CROP },
                    onClear = { clearAll() },
                    onRecognize = { recognize() },
                    onSettings = { screen = Screen.SETTINGS }
                )
                Screen.RESULT -> ResultScreen(
                    pages = pages,
                    index = current,
                    onIndex = { current = it },
                    withImages = withImages,
                    onWithImages = { withImages = it },
                    busy = busy,
                    onMd = { saveMd.launch("textolite-${stamp()}.md") },
                    onFodt = { saveFodt.launch("textolite-${stamp()}.fodt") },
                    onPdf = { savePdf.launch("textolite-${stamp()}.pdf") },
                    onCopy = {
                        val all = pages.joinToString("\n\n") { it.text }
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Textolite", all))
                        Toast.makeText(ctx, "Texto copiado", Toast.LENGTH_SHORT).show()
                    },
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, pages.joinToString("\n\n") { it.text })
                        }
                        ctx.startActivity(Intent.createChooser(send, null))
                    },
                    onBack = { screen = Screen.HOME }
                )
                Screen.CROP -> {
                    val pg = pages.getOrNull(cropIndex)
                    if (pg != null) {
                        CropScreen(
                            page = pg,
                            busy = busy,
                            onApply = { l, t, r, b -> applyCrop(l, t, r, b) },
                            onRestore = { restoreCrop() },
                            onCancel = { screen = Screen.HOME }
                        )
                    } else {
                        Text("Página não encontrada.", modifier = Modifier.padding(16.dp))
                    }
                }
                Screen.SETTINGS -> SettingsScreen(settings = settings, onDone = {
                    if (!settings.isReady(engine)) {
                        engine = Engine.OFFLINE
                        settings.engine = Engine.OFFLINE
                    }
                    screen = Screen.HOME
                })
            }
        }
    }
}

@Composable
fun HomeScreen(
    settings: Settings,
    engine: Engine,
    onEngine: (Engine) -> Unit,
    pages: List<Page>,
    busy: Boolean,
    progress: String?,
    error: String?,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onRemove: (Int) -> Unit,
    onCrop: (Int) -> Unit,
    onClear: () -> Unit,
    onRecognize: () -> Unit,
    onSettings: () -> Unit
) {
    val ready = settings.isReady(engine)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Textolite", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSettings) { Text("Configurações") }
        }

        if (pages.isEmpty()) {
            Text(
                "Tire fotos, escolha imagens ou abra PDFs (inclusive os que são só imagem). Você pode juntar várias páginas num único documento.",
                style = MaterialTheme.typography.bodyLarge
            )
        } else {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                pages.forEachIndexed { i, p ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            bitmap = p.thumb.asImageBitmap(),
                            contentDescription = "Página ${i + 1}",
                            modifier = Modifier.height(130.dp)
                        )
                        Text(
                            "Página ${i + 1}" + if (p.cropped != null) " (recortada)" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = { onCrop(i) }, enabled = !busy) { Text("Recortar") }
                        TextButton(onClick = { onRemove(i) }, enabled = !busy) { Text("Remover") }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCamera, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(if (pages.isEmpty()) "Tirar foto" else "Adicionar foto")
            }
            OutlinedButton(onClick = onGallery, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Galeria") }
        }
        OutlinedButton(onClick = onFiles, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Abrir arquivos (imagens ou PDF)")
        }

        Text("Motor de reconhecimento", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Engine.values().forEach { e ->
                FilterChip(
                    selected = engine == e,
                    onClick = { onEngine(e) },
                    label = { Text(e.shortName) }
                )
            }
        }

        when {
            engine == Engine.OFFLINE ->
                Text(
                    "Funciona sem internet. Ótimo para texto impresso; escrita à mão e tabelas têm resultado limitado.",
                    style = MaterialTheme.typography.bodySmall
                )
            !ready ->
                Text(
                    "Falta configurar a chave desta IA. Toque em Configurações.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            else ->
                Text(
                    "As imagens serão enviadas ao serviço escolhido. Planos gratuitos podem usar o conteúdo para melhorar os modelos; evite documentos sensíveis.",
                    style = MaterialTheme.typography.bodySmall
                )
        }

        Button(
            onClick = onRecognize,
            enabled = pages.isNotEmpty() && !busy && ready,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (pages.size > 1) "Reconhecer ${pages.size} páginas" else "Reconhecer texto") }

        if (pages.isNotEmpty()) {
            TextButton(onClick = onClear, enabled = !busy) { Text("Limpar tudo e começar de novo") }
        }

        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        if (progress != null) Text(progress, style = MaterialTheme.typography.bodySmall)
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun ResultScreen(
    pages: List<Page>,
    index: Int,
    onIndex: (Int) -> Unit,
    withImages: Boolean,
    onWithImages: (Boolean) -> Unit,
    busy: Boolean,
    onMd: () -> Unit,
    onFodt: () -> Unit,
    onPdf: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit
) {
    if (pages.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Nenhuma página.")
            TextButton(onClick = onBack) { Text("Voltar") }
        }
        return
    }
    val i = index.coerceIn(0, pages.size - 1)
    val page = pages[i]

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Revise e edite o texto", style = MaterialTheme.typography.titleLarge)

        if (pages.size > 1) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onIndex(i - 1) }, enabled = i > 0) { Text("‹ Anterior") }
                Text(
                    "Página ${i + 1} de ${pages.size}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                TextButton(onClick = { onIndex(i + 1) }, enabled = i < pages.size - 1) { Text("Próxima ›") }
            }
        }

        OutlinedTextField(
            value = page.text,
            onValueChange = { page.text = it },
            modifier = Modifier.fillMaxWidth().weight(1f)
        )

        Text("O arquivo deve conter", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !withImages, onClick = { onWithImages(false) }, label = { Text("Só o texto") })
            FilterChip(selected = withImages, onClick = { onWithImages(true) }, label = { Text("Texto + imagem original") })
        }

        Text("Salvar como", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onMd, enabled = !busy, modifier = Modifier.weight(1f)) { Text(".md") }
            Button(onClick = onFodt, enabled = !busy, modifier = Modifier.weight(1f)) { Text(".fodt") }
            Button(onClick = onPdf, enabled = !busy, modifier = Modifier.weight(1f)) { Text(".pdf") }
        }
        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Copiar") }
            OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Compartilhar") }
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("Voltar") }
        }
    }
}

@Composable
fun SettingsScreen(settings: Settings, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var gKey by remember { mutableStateOf(settings.geminiKey) }
    var gModel by remember { mutableStateOf(settings.geminiModel) }
    var cUrl by remember { mutableStateOf(settings.compatUrl) }
    var cKey by remember { mutableStateOf(settings.compatKey) }
    var cModel by remember { mutableStateOf(settings.compatModel) }
    var msg by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Configurações de IA (opcional)", style = MaterialTheme.typography.titleLarge)
        Text(
            "O modo Offline não precisa de nada. As IAs abaixo leem melhor escrita à mão, tabelas e esquemas, " +
                "mas exigem internet e uma chave de API sua. A chave fica criptografada neste aparelho.",
            style = MaterialTheme.typography.bodyMedium
        )

        HorizontalDivider()
        Text("Google Gemini", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = gKey, onValueChange = { gKey = it }, singleLine = true,
            label = { Text("Chave de API") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = gModel, onValueChange = { gModel = it }, singleLine = true,
            label = { Text("Modelo") }, modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = { openLink(ctx, "https://aistudio.google.com/apikey") }) {
            Text("Obter chave gratuita")
        }

        HorizontalDivider()
        Text("Outra IA (formato OpenAI)", style = MaterialTheme.typography.titleMedium)
        Text("Atalhos de endereço:", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { cUrl = "https://openrouter.ai/api/v1" }, modifier = Modifier.weight(1f)) { Text("OpenRouter") }
            OutlinedButton(onClick = { cUrl = "https://api.groq.com/openai/v1" }, modifier = Modifier.weight(1f)) { Text("Groq") }
            OutlinedButton(onClick = { cUrl = "https://api.openai.com/v1" }, modifier = Modifier.weight(1f)) { Text("OpenAI") }
        }
        OutlinedTextField(
            value = cUrl, onValueChange = { cUrl = it }, singleLine = true,
            label = { Text("Endereço (https)") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cKey, onValueChange = { cKey = it }, singleLine = true,
            label = { Text("Chave de API") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = cModel, onValueChange = { cModel = it }, singleLine = true,
            label = { Text("Modelo (precisa aceitar imagens)") }, modifier = Modifier.fillMaxWidth()
        )
        Row {
            TextButton(onClick = { openLink(ctx, "https://openrouter.ai/keys") }) { Text("Chave OpenRouter") }
            TextButton(onClick = { openLink(ctx, "https://console.groq.com/keys") }) { Text("Chave Groq") }
        }

        if (msg != null) Text(msg!!, color = MaterialTheme.colorScheme.error)

        Button(
            onClick = {
                if (cUrl.isNotBlank() && !cUrl.trim().startsWith("https://")) {
                    msg = "O endereço da outra IA precisa começar com https://"
                } else {
                    settings.geminiKey = gKey.trim()
                    settings.geminiModel = gModel.trim()
                    settings.compatUrl = cUrl.trim()
                    settings.compatKey = cKey.trim()
                    settings.compatModel = cModel.trim()
                    onDone()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Salvar") }
    }
}
