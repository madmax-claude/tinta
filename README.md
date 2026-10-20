# Tinta

App de notas a mano para tablets Android con lápiz. Está hecha para la Lenovo Tab P12, pero
anda en cualquier tablet con Android 10 o más nuevo.

## Qué tiene

- Lienzo infinito u hojas (A4, A5, A3, B5, Carta, Oficio), con fondos de cuadrícula, rayado,
  puntos, milimetrado, pentagrama u hoja de errores, y color de hoja.
- Pluma sensible a la presión, marcador y resaltador, con líneas a rayas o de puntos.
- Borrador (por trazo o parcial), lazo para mover, escalar, girar y copiar, y figuras: mantener
  el lápiz quieto al terminar un trazo lo convierte en recta, círculo, polígono…
- Imágenes, PDF para escribir encima, gráficos 2D y 3D, cuerpos 3D, notas de voz y videos.
- Búsqueda en lo escrito a mano.
- Carpetas anidadas: se mueven notas y carpetas arrastrándolas.
- Exportar a PDF, imágenes o texto, y copia de todas las notas en Google Drive.
- Modo oscuro, pantalla completa, pestañas y dos notas en pantalla dividida.
- Importar notas de Jnotes (.Jnotes): cuadernos, PDF anotados y lienzos infinitos.

## Instalar

Bajá el `.apk` de la última versión en [Releases](../../releases) y abrilo en la tablet. Android
va a pedir permiso para instalar apps de origen desconocido.

## Compilar

Hace falta el JDK 21 (con [mise](https://mise.jdx.dev): `mise install`) y el SDK de Android.

```
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest  # pruebas
```

## Pasar todas las notas de Jnotes

Jnotes guarda una copia de cada nota en
`/sdcard/Android/data/com.jideos.jnotes.overseas.google/files/Documents/Yunji/backup`.
`tools/jnotes_a_tinta.py` arma, a partir de esa copia, una carpeta con las notas, sus carpetas y
sus fechas; Tinta la importa de una vez (ver la documentación del script y de `JnotesBatch`).
Para una sola nota alcanza con abrir o compartir el `.Jnotes` con Tinta.

## Licencia

Copyright (C) 2026 Octavio

Este programa es software libre: se puede redistribuir y modificar bajo los términos de la
Licencia Pública General de GNU (GPL), versión 3, publicada por la Free Software Foundation.
Se distribuye con la esperanza de que sea útil, pero SIN NINGUNA GARANTÍA. Ver [LICENSE](LICENSE).
