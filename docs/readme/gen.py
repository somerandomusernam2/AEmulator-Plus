#!/usr/bin/env python3
"""Собирает README.md (английский) и docs/i18n/README.<код>.md из data.py + texts.py.
Запуск из корня репозитория: python docs/readme/gen.py"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
sys.path.insert(0, HERE)
from data import *  # noqa
from texts import T  # noqa
from texts_plus import P  # noqa

RTL = {"ar", "fa"}
BRAND = "AEmulator Plus"


def path_of(code, from_code):
    """Относительная ссылка на README языка code из файла языка from_code."""
    if code == "en":
        return "README.md" if from_code == "en" else "../../README.md"
    return f"docs/i18n/README.{code}.md" if from_code == "en" else f"README.{code}.md"


def render(code):
    # Old strings: brand the inherited text as Plus. New Plus strings (texts_plus.py) are
    # merged afterwards so repo names inside them are never rewritten.
    t = {k: ([x.replace("AEmulator", BRAND) for x in v] if isinstance(v, list) else v.replace("AEmulator", BRAND) if isinstance(v, str) else v) for k, v in T[code].items()}
    t.update(P[code])
    up = "" if code == "en" else "../../"
    rel = dict(
        build="docs/build-source.md" if code == "en" else "../build-source.md",
        audit="docs/license-audit.md" if code == "en" else "../license-audit.md",
        notice=f"{up}NOTICE.md",
        sunset=SUNSET, upstream=UPSTREAM)
    L = []
    if code in RTL:
        L.append('<div dir="rtl">\n')
    L.append('<div align="center">\n')
    L.append(f'<img src="{up}docs/assets/logo.png" width="128" alt="{BRAND} logo"/>\n')
    L.append(f"# {BRAND}\n")
    L.append(f"**{t['tagline']}**\n")
    L.append(" · ".join(
        (f"**{flag} {name}**" if c == code else f"[{flag} {name}]({path_of(c, code)})") for c, name, flag in LANGS) + "\n")
    L.append("</div>\n")
    L.append("---\n")
    L.append(t["about"] + "\n")
    L.append(f"## ✨ {t['feat_title']}\n")
    L += [f"- {f}" for f in t["feats"]]
    L.append("")
    L.append(f"## 🚀 {t['start_title']}\n")
    # The inherited forum is not affiliated with this fork: skip its firmware-download step;
    # users import firmware they already possess.
    steps = [t["steps"][0], *t["steps"][2:]]
    L += [f"{i + 1}. {s.format(repo=REPO)}" for i, s in enumerate(steps)]
    L.append("")
    L.append(f"## 📋 {t['req_title']}\n")
    L += [f"- {r}" for r in t["reqs"]]
    L.append("")
    L.append(f"## ⚙️ {t['how_title']}\n")
    L.append(t["how_text"] + "\n")
    L.append(f"## 🛠️ {t['build_title']}\n")
    L.append(t["build_text"].format(**rel) + "\n")
    L.append("```bash\ngit clone https://github.com/somerandomusernam2/AEmulator-Plus.git\ncd AEmulator-Plus\n./gradlew copyReleaseApks\n```\n")
    L.append(f"## 🙏 {t['credits_title']}\n")
    L.append(t["credits_text"].format(orig=ORIGINAL) + "\n")
    L.append(t["fork_line"].format(**rel) + "\n")
    L.append(f"## 🔗 {t['links_title']}\n")
    L.append(f"- {t['l_fork']}: [somerandomusernam2/AEmulator-Plus]({REPO})")
    L.append(f"- {t['l_upstream']}: [drel4/AEmulator-Sunset]({SUNSET})")
    L.append(f"- {t['l_original']}: [uxazu/AEmulator]({UPSTREAM})")
    L.append(f"- {t['l_author']}: [{AUTHOR_NAME}]({AUTHOR})")
    L.append(f"- {t['l_orig']}: [t.me/istratiit_ech]({ORIGINAL})\n")
    L.append(f"## 📄 {t['license_title']}\n")
    L.append(t["license_text"] + "\n")
    L.append(t["license_more"].format(**rel) + "\n")
    if code in RTL:
        L.append("</div>\n")
    return "\n".join(L)


def main():
    for code, _, _ in LANGS:
        assert code in T, code
        out = os.path.join(ROOT, "README.md") if code == "en" else os.path.join(ROOT, "docs", "i18n", f"README.{code}.md")
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, "w", encoding="utf-8", newline="\n") as f:
            f.write(render(code))
    print(f"README: {len(LANGS)} языков")


if __name__ == "__main__":
    main()
