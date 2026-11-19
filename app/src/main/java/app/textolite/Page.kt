package app.textolite

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/** Uma página escaneada: a foto fica em arquivo (economiza memória) e o texto é editável. */
class Page(val file: File, val thumb: Bitmap) {
    var text by mutableStateOf("")
}
