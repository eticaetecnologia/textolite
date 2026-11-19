package app.textolite

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { HOME, RESULT, SETTINGS }

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
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    fun load(uri: Uri) {
        busy = true
        error = null
        scope.launch {
            try {
                bitmap = withContext(Dispatchers.IO) { Ocr.loadBitmap(ctx, uri) }
            } catch (e: Exception) {
                error = e.message ?: "Não foi possível abrir a imagem."
            } finally {
                busy = false
            }
        }
    }

    fun recognize() {
        val b = bitmap ?: return
        busy = true
        error = null
        scope.launch {
            try {
                text = when (engine) {
                    Engine.OFFLINE -> Ocr.offline(b)
                    Engine.GEMINI -> Ocr.gemini(b, settings.geminiKey, settings.geminiModel)
                    Engine.COMPAT -> Ocr.compat(b, settings.compatUrl, settings.compatKey, settings.compatModel)
                }
                screen = Screen.RESULT
            } catch (e: Exception) {
                error = e.message ?: "Erro ao reconhecer o texto."
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
        if (ok) load(photoUri)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) load(uri)
    }
    val saveMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) saveBytes(ctx, uri, Export.md(text))
    }
    val saveFodt = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.oasis.opendocument.text-flat-xml")
    ) { uri ->
        if (uri != null) saveBytes(ctx, uri, Export.fodt(text))
    }
    val savePdf = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) saveBytes(ctx, uri, Export.pdf(text))
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    settings = settings,
                    engine = engine,
                    onEngine = { engine = it; settings.engine = it },
                    bitmap = bitmap,
                    busy = busy,
                    error = error,
                    onCamera = { takePicture.launch(photoUri) },
                    onGallery = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onRecognize = { recognize() },
                    onSettings = { screen = Screen.SETTINGS }
                )
                Screen.RESULT -> ResultScreen(
                    text = text,
                    onText = { text = it },
                    onMd = { saveMd.launch("textolite-${stamp()}.md") },
                    onFodt = { saveFodt.launch("textolite-${stamp()}.fodt") },
                    onPdf = { savePdf.launch("textolite-${stamp()}.pdf") },
                    onCopy = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Textolite", text))
                        Toast.makeText(ctx, "Texto copiado", Toast.LENGTH_SHORT).show()
                    },
                    onShare = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        ctx.startActivity(Intent.createChooser(send, null))
                    },
                    onBack = { screen = Screen.HOME }
                )
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
    bitmap: Bitmap?,
    busy: Boolean,
    error: String?,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
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

        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Imagem selecionada",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().height(280.dp)
            )
        } else {
            Text(
                "Tire uma foto ou escolha uma imagem para converter em texto.",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCamera, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Tirar foto") }
            OutlinedButton(onClick = onGallery, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Galeria") }
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
                    "A imagem será enviada ao serviço escolhido. Planos gratuitos podem usar o conteúdo para melhorar os modelos; evite documentos sensíveis.",
                    style = MaterialTheme.typography.bodySmall
                )
        }

        Button(
            onClick = onRecognize,
            enabled = bitmap != null && !busy && ready,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Reconhecer texto") }

        if (busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
fun ResultScreen(
    text: String,
    onText: (String) -> Unit,
    onMd: () -> Unit,
    onFodt: () -> Unit,
    onPdf: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Revise e edite o texto", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = text,
            onValueChange = onText,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
        Text("Salvar como", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onMd, modifier = Modifier.weight(1f)) { Text(".md") }
            Button(onClick = onFodt, modifier = Modifier.weight(1f)) { Text(".fodt") }
            Button(onClick = onPdf, modifier = Modifier.weight(1f)) { Text(".pdf") }
        }
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
