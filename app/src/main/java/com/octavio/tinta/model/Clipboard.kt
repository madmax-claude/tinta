package com.octavio.tinta.model

import android.graphics.RectF

/**
 * Lo copiado con el lazo (o un sticker por pegar). Vive mientras la app está abierta y sirve entre
 * las dos notas abiertas en paralelo, que corren en el mismo proceso. Las imágenes y los medios
 * apuntan a la carpeta de la nota de donde salieron: al pegar se copian.
 */
object Clipboard {
    class Clip(
        val strokes: List<InkStroke>,
        val images: List<NoteImage>,
        val texts: List<NoteText>,
        val widgets: List<Widget>,
        val bounds: RectF,
    )

    @Volatile var clip: Clip? = null
}
