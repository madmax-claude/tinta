#!/usr/bin/env python3
"""Prepara la importación de todas las notas de Jnotes a Tinta, con carpetas y fechas.

Entradas:
- <backup>: la copia de seguridad de Jnotes, bajada de la tablet con
    adb pull /sdcard/Android/data/com.jideos.jnotes.overseas.google/files/Documents/Yunji/backup
  Hay un .Jnotes por nota (también de notas ya borradas en Jnotes).
- <carpetas.json>: {"carpetas": {"<id de carpeta>": "Carpeta/Subcarpeta", ...},
                    "excluir": ["<id de nota>", ...]}
  Cada nota trae el id de su carpeta (campo "i" de NOTE, "" si está suelta), pero los nombres
  sólo están en la base interna de Jnotes: se leen del árbol de la izquierda de su pantalla y se
  empareja cada carpeta por las notas que tiene. "excluir": notas que ya no están en Jnotes.
- <fechas.txt>: la fecha de los archivos internos de Jnotes, sacada con
    adb shell 'cd /sdcard/Android/data/com.jideos.jnotes.overseas.google/files/.data && stat -c "%Y|%n" *'
  La miniatura más nueva de la nota o de sus hojas coincide (casi siempre al minuto) con la fecha
  de modificación que muestra Jnotes, que es por la que ordena; Tinta ordena igual.

Deja en <salida>/ cada nota como `<id>.Jnotes` y el `orden.json` que lee `JnotesBatch` en Tinta.

Uso: jnotes_a_tinta.py <backup> <carpetas.json> <fechas.txt> <salida>
"""
import io
import json
import shutil
import struct
import sys
import uuid
import zipfile
from pathlib import Path


def records(path):
    """Los registros NOTE y PAGE (tipo, JSON) de un .Jnotes; los demás se saltean."""
    data = path.read_bytes()
    if data[:2] == b"PK":
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            data = z.read(next(n for n in z.namelist() if n.lower().endswith(".jzip")))
    p = 0

    def utf():
        nonlocal p
        n = struct.unpack_from(">H", data, p)[0]
        s = data[p + 2 : p + 2 + n].decode("utf-8", "replace")
        p += 2 + n
        return s

    if utf() != "TRY":
        raise ValueError(f"{path.name}: no es un archivo de Jnotes")
    p += 4
    utf()
    while p + 4 <= len(data) and struct.unpack_from(">i", data, p)[0] == 1:
        p += 4
        typ = utf()
        utf()
        utf()
        jlen = struct.unpack_from(">q", data, p)[0]
        p += 8
        utf()
        utf()
        blen = utf()
        if typ in ("NOTE", "PAGE"):
            yield typ, json.loads(data[p : p + jlen])
        p += jlen + (int(blen) if blen.isdigit() else 0)


def main(backup, folders_file, dates_file, out):
    backup, out = Path(backup), Path(out)
    config = json.loads(Path(folders_file).read_text())
    paths = {k: v.strip("/") for k, v in config["carpetas"].items()}
    exclude = set(config.get("excluir", []))
    mtimes = {}
    for line in Path(dates_file).read_text().splitlines():
        t, _, name = line.partition("|")
        if t.isdigit():
            mtimes[name] = int(t)

    # Ids fijos para las carpetas: el de Jnotes, o uno sacado de la ruta para las que no tienen
    # notas propias (y por lo tanto ningún id conocido).
    folder_id = {"": None}
    for fid, path in paths.items():
        if path:
            folder_id[path] = fid
    for path in list(folder_id):
        parts = path.split("/") if path else []
        for k in range(1, len(parts)):
            sub = "/".join(parts[:k])
            folder_id.setdefault(sub, str(uuid.uuid5(uuid.NAMESPACE_URL, "jnotes:" + sub)))
    folders = []
    for path in sorted((p for p in folder_id if p), key=lambda p: (p.count("/"), p)):
        parent = path.rpartition("/")[0]
        folders.append(dict(id=folder_id[path], nombre=path.rpartition("/")[2], padre=folder_id.get(parent)))

    # Todas las fechas de cada nota en una pasada: los archivos empiezan con el id de la nota o de una hoja.
    by_prefix = {}
    for name, t in mtimes.items():
        key = name[:36]
        by_prefix[key] = max(by_prefix.get(key, 0), t)

    out.mkdir(parents=True, exist_ok=True)
    items, skipped = [], []
    # Algunas notas empiezan con punto; según la versión de Python, "*" las incluye o no.
    for f in sorted(set(backup.glob("*.Jnotes")) | set(backup.glob(".*.Jnotes"))):
        note, pages = None, []
        for typ, rec in records(f):
            if typ == "NOTE":
                note = rec
            else:
                pages.append(rec["a"])
        if note is None:
            print(f"aviso: {f.name} no tiene registro NOTE")
            continue
        nid, folder = note["a"], note.get("i") or ""
        if nid in exclude:
            skipped.append(f"«{note['b']}» (excluida)")
            continue
        if folder not in paths:
            skipped.append(f"«{note['b']}» (carpeta {folder} desconocida)")
            continue
        t = max([by_prefix.get(i, 0) for i in [nid] + pages] + [0])
        if t == 0:
            t = int(f.stat().st_mtime)
            print(f"aviso: «{note['b']}» sin miniaturas; uso la fecha del archivo")
        shutil.copyfile(f, out / f"{nid}.Jnotes")
        items.append(dict(archivo=f"{nid}.Jnotes", id=nid, titulo=note["b"], carpeta=folder_id[paths[folder]], modificada=t * 1000))
    # Primero las más chicas: si algo falla, falla al final y con casi todo ya importado.
    items.sort(key=lambda i: (out / i["archivo"]).stat().st_size)
    (out / "orden.json").write_text(json.dumps(dict(carpetas=folders, notas=items), ensure_ascii=False, indent=1))
    for s in skipped:
        print("no se importa:", s)
    print(f"{len(items)} notas en {len(folders)} carpetas → {out}")


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    main(*sys.argv[1:])
