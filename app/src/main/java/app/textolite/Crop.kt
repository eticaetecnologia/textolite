package app.textolite

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private enum class Drag { NONE, MOVE, TL, TR, BL, BR, L, R, T, B }

/** Tela para escolher a região da foto que será lida. Os valores devolvidos são frações de 0 a 1. */
@Composable
fun CropScreen(
    page: Page,
    busy: Boolean,
    onApply: (Float, Float, Float, Float) -> Unit,
    onRestore: () -> Unit,
    onCancel: () -> Unit
) {
    val image = remember(page) { BitmapFactory.decodeFile(page.original.path)?.asImageBitmap() }
    var l by remember { mutableStateOf(0f) }
    var t by remember { mutableStateOf(0f) }
    var r by remember { mutableStateOf(1f) }
    var b by remember { mutableStateOf(1f) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var mode by remember { mutableStateOf(Drag.NONE) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Escolha a região a ler", style = MaterialTheme.typography.titleLarge)
        Text(
            "Arraste os cantos, as bordas ou o retângulo inteiro. Só o que estiver dentro dele será reconhecido.",
            style = MaterialTheme.typography.bodySmall
        )

        if (image == null) {
            Text("Imagem indisponível.", color = MaterialTheme.colorScheme.error)
        } else {
            val bw = image.width.toFloat()
            val bh = image.height.toFloat()

            // Posição e tamanho da imagem dentro da área: [x, y, largura, altura]
            fun geom(): FloatArray {
                val s = minOf(box.width / bw, box.height / bh)
                val dw = bw * s
                val dh = bh * s
                return floatArrayOf((box.width - dw) / 2f, (box.height - dh) / 2f, dw, dh)
            }

            Canvas(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { box = it }
                    .pointerInput(image) {
                        detectDragGestures(
                            onDragStart = { p ->
                                val g = geom()
                                val x = g[0] + l * g[2]
                                val y = g[1] + t * g[3]
                                val x2 = g[0] + r * g[2]
                                val y2 = g[1] + b * g[3]
                                val s = 56f
                                fun near(a: Float, c: Float) = abs(a - c) < s
                                mode = when {
                                    near(p.x, x) && near(p.y, y) -> Drag.TL
                                    near(p.x, x2) && near(p.y, y) -> Drag.TR
                                    near(p.x, x) && near(p.y, y2) -> Drag.BL
                                    near(p.x, x2) && near(p.y, y2) -> Drag.BR
                                    near(p.x, x) && p.y in y..y2 -> Drag.L
                                    near(p.x, x2) && p.y in y..y2 -> Drag.R
                                    near(p.y, y) && p.x in x..x2 -> Drag.T
                                    near(p.y, y2) && p.x in x..x2 -> Drag.B
                                    p.x in x..x2 && p.y in y..y2 -> Drag.MOVE
                                    else -> Drag.NONE
                                }
                            },
                            onDragEnd = { mode = Drag.NONE },
                            onDragCancel = { mode = Drag.NONE },
                            onDrag = { change, d ->
                                val g = geom()
                                if (g[2] > 0f && g[3] > 0f) {
                                    val dx = d.x / g[2]
                                    val dy = d.y / g[3]
                                    val min = 0.05f
                                    when (mode) {
                                        Drag.MOVE -> {
                                            val w = r - l
                                            val h = b - t
                                            val nl = (l + dx).coerceIn(0f, 1f - w)
                                            val nt = (t + dy).coerceIn(0f, 1f - h)
                                            l = nl; t = nt; r = nl + w; b = nt + h
                                        }
                                        Drag.TL -> { l = (l + dx).coerceIn(0f, r - min); t = (t + dy).coerceIn(0f, b - min) }
                                        Drag.TR -> { r = (r + dx).coerceIn(l + min, 1f); t = (t + dy).coerceIn(0f, b - min) }
                                        Drag.BL -> { l = (l + dx).coerceIn(0f, r - min); b = (b + dy).coerceIn(t + min, 1f) }
                                        Drag.BR -> { r = (r + dx).coerceIn(l + min, 1f); b = (b + dy).coerceIn(t + min, 1f) }
                                        Drag.L -> l = (l + dx).coerceIn(0f, r - min)
                                        Drag.R -> r = (r + dx).coerceIn(l + min, 1f)
                                        Drag.T -> t = (t + dy).coerceIn(0f, b - min)
                                        Drag.B -> b = (b + dy).coerceIn(t + min, 1f)
                                        Drag.NONE -> {}
                                    }
                                }
                                change.consume()
                            }
                        )
                    }
            ) {
                val g = geom()
                drawImage(
                    image,
                    dstOffset = IntOffset(g[0].toInt(), g[1].toInt()),
                    dstSize = IntSize(g[2].toInt(), g[3].toInt())
                )
                val x = g[0] + l * g[2]
                val y = g[1] + t * g[3]
                val x2 = g[0] + r * g[2]
                val y2 = g[1] + b * g[3]
                val dim = Color(0x99000000)
                drawRect(dim, Offset(g[0], g[1]), Size(g[2], y - g[1]))
                drawRect(dim, Offset(g[0], y2), Size(g[2], g[1] + g[3] - y2))
                drawRect(dim, Offset(g[0], y), Size(x - g[0], y2 - y))
                drawRect(dim, Offset(x2, y), Size(g[0] + g[2] - x2, y2 - y))
                drawRect(Color.White, Offset(x, y), Size(x2 - x, y2 - y), style = Stroke(width = 3f))
                for (c in listOf(Offset(x, y), Offset(x2, y), Offset(x, y2), Offset(x2, y2))) {
                    drawCircle(Color.White, 14f, c)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onApply(l, t, r, b) },
                enabled = image != null && !busy,
                modifier = Modifier.weight(1f)
            ) { Text("Aplicar recorte") }
            OutlinedButton(onClick = onRestore, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text("Foto inteira")
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
    }
}
