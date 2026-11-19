package app.textolite

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * Uma página escaneada. A foto fica em arquivo (economiza memória).
 * [original] é a foto inteira; [cropped], se existir, é só a região escolhida e passa a ser a usada
 * no reconhecimento e na exportação.
 */
class Page(val original: File, initialThumb: Bitmap) {
    var cropped by mutableStateOf<File?>(null)
    var thumb by mutableStateOf(initialThumb)
    var text by mutableStateOf("")

    val file: File get() = cropped ?: original

    fun deleteFiles() {
        cropped?.delete()
        original.delete()
    }
}
