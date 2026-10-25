#!/usr/bin/env python3
"""Generate the Android app's strings.xml (en, fr) from the Windows app's Strings.cs.

One source of truth for the UI text: edit Strings.cs, then run
    python3 tools/gen-android-strings.py
"PC" wording becomes "phone", {0} placeholders become %1$s, and the Android-only keys below are added.
"""
import pathlib
import re
from xml.sax.saxutils import escape as xml_escape

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "src/AndroidTVDebloater/Localization/Strings.cs"
OUT = ROOT / "android/app/src/main/res"

ANDROID_ONLY = {
    "en": {
        "App_Name": "UnbloatMyTV",
        "Android_Scanning": "Searching… {0}%",
        "Android_ApproveWait": "Waiting for the TV…",
        "Android_NoWifi": "This phone isn't on Wi-Fi. Connect it to the same network as the TV, then search again.",
        "Android_KeepOpen": "Keep this app open until it is done.",
        "Android_Restore": "Undo",
        "Android_Disconnect": "Change TV",
        "Android_Groups": "{0} of {1} ticked",
        "Android_More": "More",
        "Android_Language": "Language: follows the phone",
        "Android_Menu": "Menu",
        "Android_Steps": "Steps",
        "Android_Tools": "Tools",
        "Android_ConnectFirst": "Connect a TV to use the tools.",
        "Android_NeedPairing": "This TV uses Wireless debugging and doesn't know this phone yet. Pair it with a code below; the phone then connects by itself.",
        "Android_PairText": "Newer Google TV devices: in Developer options open Wireless debugging → Pair device with pairing code. The address fills itself in when the phone sees that screen; type the 6-digit code and tap Pair.",
        "Android_PairSearching": "Looking for the TV's pairing screen…",
        "Android_Pairing": "Pairing…",
        "Android_PairedConnecting": "Paired. Connecting…",
        "Android_PairCode": "6-digit code",
        "Android_Wireless": "Wireless debugging",
        "Android_UpdateText": "UnbloatMyTV {0} is out; you have {1}. Android asks you to confirm, then open the app again. Your choices and backups are kept.",
        "Android_UpdateInstall": "Install the update",
        "Android_UpdateAllow": "Android needs your permission first: allow UnbloatMyTV to install apps, come back, and tap Install the update again.",
        "Android_UpdateInstalling": "Android is installing the update…",
    },
    "fr": {
        "App_Name": "UnbloatMyTV",
        "Android_Scanning": "Recherche… {0} %",
        "Android_ApproveWait": "En attente du téléviseur…",
        "Android_NoWifi": "Ce téléphone n'est pas en Wi-Fi. Connectez-le au même réseau que le téléviseur, puis relancez la recherche.",
        "Android_KeepOpen": "Gardez l'application ouverte jusqu'à la fin.",
        "Android_Restore": "Annuler",
        "Android_Disconnect": "Changer de téléviseur",
        "Android_Groups": "{0} sur {1} cochés",
        "Android_More": "Plus",
        "Android_Language": "Langue : celle du téléphone",
        "Android_Menu": "Menu",
        "Android_Steps": "Étapes",
        "Android_Tools": "Outils",
        "Android_ConnectFirst": "Connectez un téléviseur pour utiliser les outils.",
        "Android_NeedPairing": "Ce téléviseur utilise le débogage sans fil et ne connaît pas encore ce téléphone. Associez-le avec un code ci-dessous ; le téléphone se connecte ensuite tout seul.",
        "Android_PairText": "Google TV récents : dans les Options pour les développeurs, ouvrez Débogage sans fil → Associer l'appareil avec un code d'association. L'adresse se remplit toute seule quand le téléphone voit cet écran ; tapez le code à 6 chiffres et appuyez sur Associer.",
        "Android_PairSearching": "Recherche de l'écran d'association du téléviseur…",
        "Android_Pairing": "Association…",
        "Android_PairedConnecting": "Associé. Connexion…",
        "Android_PairCode": "Code à 6 chiffres",
        "Android_Wireless": "Débogage sans fil",
        "Android_UpdateText": "UnbloatMyTV {0} est disponible ; vous avez la {1}. Android vous demande de confirmer, puis rouvrez l'application. Vos choix et sauvegardes sont conservés.",
        "Android_UpdateInstall": "Installer la mise à jour",
        "Android_UpdateAllow": "Android demande d'abord votre accord : autorisez UnbloatMyTV à installer des applications, revenez et appuyez de nouveau sur Installer la mise à jour.",
        "Android_UpdateInstalling": "Android installe la mise à jour…",
    },
}

# Wording that differs on a phone. Applied in order, whole words only.
REPLACE = {
    "en": [("this PC", "this phone"), ("the PC", "the phone"), ("on PC", "on the phone"), ("PC", "phone")],
    "fr": [("ce PC", "ce téléphone"), ("le PC", "le téléphone"), ("du PC", "du téléphone"), ("PC", "téléphone")],
}


def tables(text: str) -> dict[str, dict[str, str]]:
    out = {}
    for lang, name in (("en", "En"), ("fr", "Fr")):
        start = text.index(f"Dictionary<string, string> {name} = new()")
        body = text[start: text.index("};", start)]
        entries = {}
        for m in re.finditer(r'\["(\w+)"\]\s*=\s*"((?:[^"\\]|\\.)*)"', body):
            entries[m.group(1)] = m.group(2).replace('\\"', '"').replace("\\\\", "\\")
        out[lang] = entries
    return out


def to_android(lang: str, s: str) -> tuple[str, bool]:
    """The resource text, and whether it has placeholders (otherwise it is marked unformatted)."""
    for old, new in REPLACE[lang]:
        s = re.sub(rf"\b{re.escape(old)}\b", new, s)
    has_args = re.search(r"\{\d+\}", s) is not None
    if has_args:
        s = s.replace("%", "%%")
        s = re.sub(r"\{(\d+)\}", lambda m: f"%{int(m.group(1)) + 1}$s", s)
    # \n stays as Android's own newline escape; quotes and apostrophes are escaped.
    s = xml_escape(s).replace("'", "\\'").replace('"', '\\"')
    if s.startswith(("@", "?")):
        s = "\\" + s
    return s, has_args


def main() -> None:
    t = tables(SRC.read_text(encoding="utf-8"))
    for lang in ("en", "fr"):
        entries = {**t[lang], **ANDROID_ONLY[lang]}
        lines = ['<?xml version="1.0" encoding="utf-8"?>',
                 "<!-- Generated by tools/gen-android-strings.py from Strings.cs. Do not edit. -->",
                 '<resources xmlns:tools="http://schemas.android.com/tools" tools:ignore="MissingTranslation,UnusedResources">']
        for key, value in entries.items():
            text, has_args = to_android(lang, value)
            attr = "" if has_args or "%" not in text else ' formatted="false"'
            lines.append(f'    <string name="{key}"{attr}>{text}</string>')
        lines.append("</resources>")
        folder = OUT / ("values" if lang == "en" else "values-fr")
        folder.mkdir(parents=True, exist_ok=True)
        (folder / "strings.xml").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"{lang}: {len(entries)} strings")


if __name__ == "__main__":
    main()
