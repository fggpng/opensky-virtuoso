#!/usr/bin/env python3
"""
Genera core/src/main/resources/catalogs/deepsky.tsv a partir de OpenNGC.

OpenNGC — https://github.com/mattiaverga/OpenNGC — © Mattia Verga y colaboradores, CC-BY-SA-4.0.
El archivo generado es una obra derivada y se distribuye bajo la misma licencia (CC-BY-SA-4.0).

Uso:
    git clone --depth 1 https://github.com/mattiaverga/OpenNGC
    python3 tools/deepsky/build_deepsky.py OpenNGC/database_files

Selección: todos los objetos Messier + objetos NGC/IC/addendum de magnitud ≤ 12 (V, o B si no hay V)
+ los que no tienen magnitud pero sí nombre común (nebulosas extensas). Se excluyen estrellas,
duplicados y entradas inexistentes.
"""
import csv
import os
import re
import sys

MAG_LIMIT = 12.0
SKIP_TYPES = {"*", "**", "Dup", "NonEx", "Other", "Nova", "*Ass"}

# M102 no tiene identificación oficial en OpenNGC; la más aceptada es NGC 5866.
EXTRA_MESSIER = {"NGC5866": 102}

TYPES_ES = {
    "G": "Galaxia", "GPair": "Par de galaxias", "GTrpl": "Trío de galaxias", "GGroup": "Grupo de galaxias",
    "OCl": "Cúmulo abierto", "GCl": "Cúmulo globular", "Cl+N": "Cúmulo con nebulosa", "PN": "Nebulosa planetaria",
    "HII": "Nebulosa de emisión", "EmN": "Nebulosa de emisión", "RfN": "Nebulosa de reflexión", "Neb": "Nebulosa",
    "SNR": "Remanente de supernova", "DrkN": "Nebulosa oscura", "*Ass": "Asociación estelar", "**": "Estrella doble",
    "Other": "Asterismo",
}

# Nombres en español de los objetos más conocidos (se conservan también los nombres en inglés para buscar).
NAMES_ES = {
    "NGC1952": "Nebulosa del Cangrejo", "NGC6523": "Nebulosa de la Laguna", "NGC6205": "Gran Cúmulo de Hércules",
    "NGC6611": "Nebulosa del Águila", "NGC6618": "Nebulosa Omega", "NGC6514": "Nebulosa Trífida",
    "NGC6853": "Nebulosa de la Haltera", "NGC0224": "Galaxia de Andrómeda", "NGC0598": "Galaxia del Triángulo",
    "NGC1976": "Gran Nebulosa de Orión", "NGC2632": "El Pesebre (Colmena)", "Mel022": "Las Pléyades",
    "NGC5194": "Galaxia del Remolino", "NGC6720": "Nebulosa del Anillo", "NGC4826": "Galaxia del Ojo Negro",
    "NGC3031": "Galaxia de Bode", "NGC3034": "Galaxia del Cigarro", "NGC3587": "Nebulosa del Búho",
    "NGC5457": "Galaxia del Molinete", "NGC4594": "Galaxia del Sombrero", "NGC6705": "Cúmulo del Pato Salvaje",
    "NGC6405": "Cúmulo de la Mariposa", "NGC6475": "Cúmulo de Ptolomeo", "IC4715": "Pequeña Nube Estelar de Sagitario",
    "NGC5236": "Galaxia del Molinete Austral", "NGC0869": "Doble Cúmulo de Perseo (h)",
    "NGC0884": "Doble Cúmulo de Perseo (χ)", "NGC7000": "Nebulosa Norteamérica", "NGC6960": "Nebulosa del Velo (oeste)",
    "NGC6992": "Nebulosa del Velo (este)", "NGC0253": "Galaxia del Escultor", "NGC5139": "Omega Centauri",
    "NGC0104": "47 Tucanae", "NGC3372": "Nebulosa de Carina", "NGC2392": "Nebulosa del Esquimal",
    "NGC7293": "Nebulosa de la Hélice", "NGC6543": "Nebulosa Ojo de Gato", "NGC2237": "Nebulosa Roseta",
    "NGC2244": "Cúmulo de la Roseta", "NGC7662": "Bola de Nieve Azul", "NGC6826": "Nebulosa Parpadeante",
    "NGC0457": "Cúmulo de la Lechuza", "B033": "Nebulosa Cabeza de Caballo", "NGC2264": "Cúmulo del Árbol de Navidad",
    "C041": "Las Híades", "C099": "Saco de Carbón", "Mel111": "Cúmulo de Coma Berenices", "NGC1499": "Nebulosa California",
    "NGC2024": "Nebulosa de la Llama", "NGC1977": "Nebulosa del Corredor", "NGC1982": "Nebulosa de De Mairan",
    "NGC3242": "Fantasma de Júpiter", "NGC6888": "Nebulosa Creciente", "NGC0281": "Nebulosa Pac-Man",
    "NGC7635": "Nebulosa de la Burbuja", "C009": "Nebulosa de la Cueva", "C014": "Doble Cúmulo de Perseo",
    "NGC4565": "Galaxia de la Aguja", "NGC2070": "Nebulosa de la Tarántula", "NGC5128": "Centaurus A",
    "NGC4038": "Galaxias Antena", "NGC6231": "Cúmulo de Escorpio", "NGC2068": "Nebulosa M78",
}


def designation(name: str) -> str:
    """'NGC0224' → 'NGC 224', 'IC0434' → 'IC 434', 'Mel022' → 'Mel 22', 'C009' → 'Caldwell 9', 'B033' → 'Barnard 33'."""
    m = re.fullmatch(r"(NGC|IC|Mel|Cr|C|B|M)(\d+)(.*)", name)
    if not m:
        return name
    prefix, num, rest = m.groups()
    prefix = {"C": "Caldwell", "B": "Barnard", "Cr": "Collinder"}.get(prefix, prefix)
    return f"{prefix} {int(num)}{rest.strip()}"


def magnitude(row):
    for key in ("V-Mag", "B-Mag"):
        if row[key]:
            return float(row[key])
    return None


def ra_deg(text):
    h, m, s = (float(x) for x in text.split(":"))
    return 15.0 * (h + m / 60 + s / 3600)


def dec_deg(text):
    sign = -1.0 if text.startswith("-") else 1.0
    d, m, s = (float(x) for x in text.lstrip("+-").split(":"))
    return sign * (d + m / 60 + s / 3600)


def main(src_dir, out_path):
    rows = []
    for name in ("NGC.csv", "addendum.csv"):
        with open(os.path.join(src_dir, name), encoding="utf-8") as f:
            rows += list(csv.DictReader(f, delimiter=";"))

    out = []
    for r in rows:
        if not r["RA"] or not r["Dec"]:
            continue
        if r["Type"] == "Dup":
            continue  # p. ej. «M102» de OpenNGC = duplicado de M101; M102 se asigna a NGC 5866
        messier = int(r["M"]) if r["M"] else EXTRA_MESSIER.get(r["Name"])
        mag = magnitude(r)
        common_en = [n.strip() for n in r["Common names"].split(",") if n.strip()]
        if messier is None:
            if r["Type"] in SKIP_TYPES:
                continue
            if not ((mag is not None and mag <= MAG_LIMIT) or (mag is None and common_en)):
                continue
        names = ([NAMES_ES[r["Name"]]] if r["Name"] in NAMES_ES else []) + common_en
        out.append([
            r["Name"],
            str(messier or ""),
            designation(r["Name"]),
            r["Type"],
            TYPES_ES.get(r["Type"], "Objeto"),
            "%.5f" % ra_deg(r["RA"]),
            "%.5f" % dec_deg(r["Dec"]),
            "" if mag is None else "%.2f" % mag,
            r["MajAx"] or "",
            r["MinAx"] or "",
            r["Const"] or "",
            "|".join(dict.fromkeys(names)),
        ])

    out.sort(key=lambda x: (x[1] == "", int(x[1]) if x[1] else 0, float(x[7]) if x[7] else 99.0))
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("# Catálogo de cielo profundo de OpenSky Virtuoso, derivado de OpenNGC\n")
        f.write("# https://github.com/mattiaverga/OpenNGC — © Mattia Verga y colaboradores — CC-BY-SA-4.0\n")
        f.write("# Este archivo se distribuye bajo la misma licencia (CC-BY-SA-4.0). Coordenadas J2000.\n")
        f.write("# id\tmessier\tdesignacion\ttipo\ttipo_es\tar_deg\tdec_deg\tmag\teje_mayor_arcmin\teje_menor_arcmin\tconst\tnombres\n")
        for row in out:
            f.write("\t".join(row) + "\n")
    messier_count = sum(1 for x in out if x[1])
    print(f"{len(out)} objetos ({messier_count} Messier) → {out_path}")


if __name__ == "__main__":
    here = os.path.dirname(os.path.abspath(__file__))
    default_out = os.path.join(here, "..", "..", "core", "src", "main", "resources", "catalogs", "deepsky.tsv")
    main(sys.argv[1] if len(sys.argv) > 1 else "OpenNGC/database_files", os.path.normpath(default_out))
