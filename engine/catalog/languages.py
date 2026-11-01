#!/usr/bin/env python3
"""Builds Language's bundled catalog of translation packs (index_v7.json).

Python 3 standard library only. Run with a saved copy of the records:

    python3 -I engine/catalog/languages.py RECORDS.json --offline-index INDEX.json

or let it read both from the network (records, attachments base URL, and
offline-translator's index at its pinned tag) with --fetch. Nothing is
downloaded but JSON; no model file is fetched. The same input gives the same
bytes (sorted keys, fixed order).

Sources (every function below names its own):
- Records: Mozilla Remote Settings, collection main/translations-models
  https://firefox.settings.services.mozilla.com/v1/buckets/main/collections/translations-models/records
  Every file carries attachment.hash (SHA-256) and attachment.size; the file
  is base_url + attachment.location, base_url from
  https://firefox.settings.services.mozilla.com/v1/ (capabilities.attachments).
- Record selection: Firefox, mozilla-firefox/firefox,
  toolkit/components/translations/actors/TranslationsParent.sys.mjs at
  666f5cd6bd05d6f39de5af65b7c86c39848362eb, the last revision that reads
  "translations-models" (fromLang/toLang, model major versions 1 to 2):
  https://github.com/mozilla-firefox/firefox/blob/666f5cd6bd05d6f39de5af65b7c86c39848362eb/toolkit/components/translations/actors/TranslationsParent.sys.mjs
  Firefox's current revision reads "translations-models-v2" (3.x models
  compressed with zstd, which the native engine does not read); the legacy
  collection holds the raw 1.x/2.x files the engine reads.
- filter_expression: services/settings/remote-settings.sys.mjs (JexlFilter),
  https://github.com/mozilla-firefox/firefox/blob/main/services/settings/remote-settings.sys.mjs
- Version order: xpcom/base/nsVersionComparator.cpp (CompareVersions),
  https://github.com/mozilla-firefox/firefox/blob/main/xpcom/base/nsVersionComparator.cpp
- Catalog format and roles: DavidVentura/offline-translator v0.8.7,
  app/src/main/assets/index_v7.json ("formatVersion": 6), read by
  DavidVentura/translator-rs rev bd32a4f272e239efb6867795c8b7d0c47c619b49,
  crates/translator-core/src/catalog_wire.rs (AssetFileWire, required fields),
  catalog_model.rs (translation_direction_from_files: roles model, lex, vocab,
  srcVocab, tgtVocab) and catalog_planner.rs (download_task_for: a url that
  does not end in ".gz" installs Raw; Mozilla's files are not compressed).
  https://github.com/DavidVentura/offline-translator/blob/v0.8.7/app/src/main/assets/index_v7.json
"""

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.request

SERVER = "https://firefox.settings.services.mozilla.com/v1/"
COLLECTION_URL = SERVER + "buckets/main/collections/translations-models/records"
ATTACHMENTS_BASE = "https://firefox-settings-attachments.cdn.mozilla.net/"
OFFLINE_INDEX_URL = (
    "https://raw.githubusercontent.com/DavidVentura/offline-translator/"
    "v0.8.7/app/src/main/assets/index_v7.json"
)
FIREFOX_REV = "666f5cd6bd05d6f39de5af65b7c86c39848362eb"

# TranslationsParent.sys.mjs: LANGUAGE_MODEL_MAJOR_VERSION_MIN / _MAX.
MODEL_MAJOR_MIN = 1
MODEL_MAJOR_MAX = 2

# Our client: Firefox for Android, release channel. JexlFilter evaluates
# filter_expression against this environment (ClientEnvironment: env.appinfo.OS,
# env.channel).
ENV = {"appinfo.OS": "Android", "channel": "release"}

# Mozilla language code to the code used by offline-translator's index (the
# engine resolves languages by these codes). Mozilla's zh-Hans / zh-Hant are
# "zh" / "zh_hant" in offline-translator's languages and pack ids.
CODE = {"zh-Hans": "zh", "zh-Hant": "zh_hant"}

# fileType (Mozilla) to role (catalog_model.rs FileRole::new).
ROLE = {
    "model": "model",
    "lex": "lex",
    "vocab": "vocab",
    "srcvocab": "srcVocab",
    "trgvocab": "tgtVocab",
}

PIVOT = "en"

FILE_ORDER = ["model", "lex", "vocab", "srcVocab", "tgtVocab"]


def lang(code):
    return CODE.get(code, code)


# ---------------------------------------------------------------- versions
# Port of CompareVersions / ParseVP / CompareVP in
# https://github.com/mozilla-firefox/firefox/blob/main/xpcom/base/nsVersionComparator.cpp
# (what Firefox's Services.vc.compare runs).

_INT32_MAX = 2**31 - 1


def _strtol(text):
    """ns_strtol: (value, rest). 0 on overflow, like the C++ helper."""
    m = re.match(r"[ \t\n\r\f\v]*[+-]?[0-9]+", text)
    if not m:
        return 0, text
    value = int(m.group(0))
    if not -(2**31) <= value <= _INT32_MAX:
        value = 0
    return value, text[m.end():]


def _parse_vp(part):
    """ParseVP: one dot-separated part into (numA, strB, numC, extraD)."""
    num_a, str_b, num_c, extra_d = 0, None, 0, None
    if part == "*":
        num_a, rest = _INT32_MAX, ""
    else:
        num_a, rest = _strtol(part)
    if rest:
        if rest[0] == "+":
            num_a += 1
            str_b = "pre"
        else:
            m = re.search(r"[0-9+-]", rest)
            if not m:
                str_b = rest
            else:
                str_b = rest[: m.start()]
                num_c, tail = _strtol(rest[m.start():])
                extra_d = tail or None
    return num_a, str_b, num_c, extra_d


def _cmp(a, b):
    return -1 if a < b else int(a != b)


def _cmp_str_b(s1, s2):
    """ns_strnncmp on strB: characters up to the shorter length, then the
    shorter one is smaller; a missing strB (None) sorts after any string."""
    if s1 is None:
        return int(s2 is not None)
    if s2 is None:
        return -1
    n = min(len(s1), len(s2))
    if s1[:n] < s2[:n]:
        return -1
    if s1[:n] > s2[:n]:
        return 1
    if len(s1) == n:
        return 0 if len(s2) == n else -1
    return 1


def _cmp_extra_d(s1, s2):
    """ns_strcmp on extraD."""
    if s1 is None:
        return int(s2 is not None)
    if s2 is None:
        return -1
    return _cmp(s1, s2)


def compare_versions(a, b):
    """CompareVersions(a, b): negative, 0 or positive. A missing part counts
    as (0, null, 0, null), as ParseVP leaves it; a trailing dot ends the
    version."""
    pa, pb = a.split("."), b.split(".")
    for parts in (pa, pb):
        if parts[-1] == "":
            parts.pop()
    empty = (0, None, 0, None)
    for i in range(max(len(pa), len(pb))):
        va = _parse_vp(pa[i]) if i < len(pa) else empty
        vb = _parse_vp(pb[i]) if i < len(pb) else empty
        r = _cmp(va[0], vb[0]) or _cmp_str_b(va[1], vb[1]) or _cmp(va[2], vb[2]) or _cmp_extra_d(va[3], vb[3])
        if r:
            return r
    return 0


def is_better_record_version(min_major, max_major, contending, current_best=None):
    """TranslationsParent.isBetterRecordVersion (same file, same names)."""
    return (
        compare_versions("%d.0a" % min_major, contending) <= 0
        and compare_versions("%d.0a" % (max_major + 1), contending) > 0
        and (current_best is None or compare_versions(current_best, contending) < 0)
    )


# ------------------------------------------------------- filter_expression
# Port of JexlFilter.filterEntry in
# https://github.com/mozilla-firefox/firefox/blob/main/services/settings/remote-settings.sys.mjs :
# an empty or missing expression keeps the entry; otherwise the entry stays
# only if the expression is truthy; an expression that fails to evaluate
# leaves `result` undefined and the entry is dropped.
# Firefox evaluates JEXL. This collection only uses comparisons of
# env.appinfo.OS and env.channel to a string with == and !=, joined by || and
# &&, so that subset is evaluated against ENV; any other expression counts as
# an evaluation failure (entry dropped) and is reported.

_COMPARE = re.compile(r"^\s*env\.(appinfo\.OS|channel)\s*(==|!=)\s*'([^']*)'\s*$")


class UnknownExpression(Exception):
    pass


def eval_filter_expression(expression):
    result = False
    for alternative in expression.split("||"):
        value = True
        for term in alternative.split("&&"):
            m = _COMPARE.match(term)
            if not m:
                raise UnknownExpression(expression)
            key, op, literal = m.groups()
            value = value and ((ENV[key] == literal) == (op == "=="))
        result = result or value
    return result


def filter_entries(records):
    """JexlFilter.filterEntry over all records. Returns (kept, unknown)."""
    kept, unknown = [], set()
    cache = {}
    for record in records:
        expression = record.get("filter_expression")
        if not expression:
            kept.append(record)
            continue
        if expression not in cache:
            try:
                cache[expression] = eval_filter_expression(expression)
            except UnknownExpression:
                cache[expression] = False
                unknown.add(expression)
        if cache[expression]:
            kept.append(record)
    return kept, sorted(unknown)


# --------------------------------------------------------------- selection
# Ports of TranslationsParent.sys.mjs at FIREFOX_REV (see header).


def non_pivot_key(source, target, variant=None):
    """TranslationsParent.nonPivotKey."""
    return "%s,%s,%s" % (source, target, variant) if variant else "%s,%s" % (source, target)


def pair_key(record):
    return non_pivot_key(record["fromLang"], record["toLang"], record.get("variant"))


def get_max_supported_version_records(records):
    """TranslationsParent.getMaxSupportedVersionRecords, with the lookupKey of
    #getTranslationModelRecords: name + nonPivotKey. Keeps, per key, the best
    version inside [MIN.0a, (MAX+1).0a)."""
    best = {}
    for record in records:
        if not record.get("version"):
            raise SystemExit("record %s has no version" % record.get("id"))
        key = record["name"] + pair_key(record)
        existing = best.get(key)
        if is_better_record_version(
            MODEL_MAJOR_MIN, MODEL_MAJOR_MAX, record["version"],
            existing["version"] if existing else None,
        ):
            best[key] = record
    return list(best.values())


def ensure_language_pairs_have_pivots(records):
    """TranslationsParent.#ensureLanguagePairsHavePivots (Firefox runs it on
    debug builds only; the engine pivots through English too, so it runs here)."""
    has_target_pivot, has_source_pivot = set(), set()
    sources, targets = set(), set()
    for r in records:
        sources.add(r["fromLang"])
        targets.add(r["toLang"])
        if r["toLang"] == PIVOT:
            has_target_pivot.add(r["fromLang"])
        if r["fromLang"] == PIVOT:
            has_source_pivot.add(r["toLang"])
    drop_sources = {s for s in sources if s != PIVOT and s not in has_target_pivot}
    drop_targets = {t for t in targets if t != PIVOT and t not in has_source_pivot}
    return [r for r in records if r["fromLang"] not in drop_sources and r["toLang"] not in drop_targets]


def filter_by_model_version(records):
    """TranslationsParent.#filterByModelVersion: per pair, keep only the
    records whose version equals the "model" record's version."""
    groups = {}
    for r in records:
        groups.setdefault(pair_key(r), []).append(r)
    kept = []
    for key, group in groups.items():
        model_versions = {r["version"] for r in group if r["fileType"] == "model"}
        if not model_versions:
            raise SystemExit('No model file found for "%s".' % key)
        if len(model_versions) > 1:
            raise SystemExit('Several model versions for "%s": %s' % (key, sorted(model_versions)))
        version = model_versions.pop()
        kept.extend(r for r in group if r["version"] == version)
    return kept


def select_records(records):
    """The Firefox pipeline: JexlFilter, getMaxSupportedVersionRecords,
    ensureLanguagePairsHavePivots, (lex kept: the engine needs it),
    filterByModelVersion. Returns (records, unknown filter expressions)."""
    kept, unknown = filter_entries(records)
    kept = get_max_supported_version_records(kept)
    kept = ensure_language_pairs_have_pivots(kept)
    return filter_by_model_version(kept), unknown


# ----------------------------------------------------------------- catalog
# Format: offline-translator v0.8.7 index_v7.json, translate-* packs.


def install_name(record):
    """The file name offline-translator gives the pair's file: Mozilla's name
    with its pair token (".<from><to>.", both without script, "zh" for
    zh-Hans and zh-Hant: the two share file names) replaced by our codes
    (translate-en-zh_hant uses "model.enzh_hant.intgemm.alphas.bin")."""
    old = "." + record["fromLang"].split("-")[0] + record["toLang"].split("-")[0] + "."
    new = "." + lang(record["fromLang"]) + lang(record["toLang"]) + "."
    name = record["name"]
    if name.count(old) != 1:
        raise SystemExit("file name %s does not hold %s" % (name, old))
    return name.replace(old, new)


def asset_file(record, base_url):
    """AssetFileWire of catalog_wire.rs; sha256 is ours (the wire struct
    ignores it, the installer verifies it)."""
    attachment = record["attachment"]
    digest = attachment["hash"]
    name = install_name(record)
    return {
        "name": name,
        "sizeBytes": attachment["size"],
        "installPath": "bin/" + name,
        "url": base_url + attachment["location"],
        "role": ROLE[record["fileType"]],
        "priority": 0,
        "sha256": digest,
    }


def build_packs(records, base_url):
    """Groups the selected records by pair into translation packs."""
    groups = {}
    for r in records:
        groups.setdefault((lang(r["fromLang"]), lang(r["toLang"])), []).append(r)
    packs, incomplete = {}, []
    for (src, dst), group in sorted(groups.items()):
        roles = {ROLE[r["fileType"]] for r in group}
        # translation_direction_from_files (catalog_model.rs): model, lex and
        # a vocab, or both srcVocab and tgtVocab.
        has_vocab = "vocab" in roles or {"srcVocab", "tgtVocab"} <= roles
        if not ({"model", "lex"} <= roles and has_vocab):
            incomplete.append("%s-%s %s" % (src, dst, sorted(roles)))
            continue
        # With separate vocabularies a leftover shared "vocab" is not used by
        # the intended configuration (see #filterByModelVersion's comment).
        if {"srcVocab", "tgtVocab"} <= roles:
            group = [r for r in group if r["fileType"] != "vocab"]
        files = sorted((asset_file(r, base_url) for r in group), key=lambda f: FILE_ORDER.index(f["role"]))
        if len({r["fileType"] for r in group}) != len(group):
            raise SystemExit("two records of one file type for %s-%s" % (src, dst))
        packs["translate-%s-%s" % (src, dst)] = {
            "feature": "translation",
            "from": src,
            "to": dst,
            "experimental": False,
            "files": files,
            "dependsOn": [],
        }
    return packs, incomplete


def build_languages(offline_languages, packs):
    """offline-translator's language entries (meta copied as it is) for every
    language that has a pack. Its OCR, dictionary, support and TTS assets are
    dropped (those packs are not in this catalog); `translate` lists our packs
    of the language."""
    used = set()
    for pack in packs.values():
        used.add(pack["from"])
        used.add(pack["to"])
    languages = {}
    for code in sorted(used):
        entry = offline_languages[code]
        ids = sorted(pid for pid, p in packs.items() if code in (p["from"], p["to"]))
        languages[code] = {"meta": entry["meta"], "assets": {"translate": ids}}
    return languages


def read_json(source):
    if re.match(r"^https?://", source):
        with urllib.request.urlopen(source, timeout=60) as response:
            return json.load(response), response.headers
    with open(source, encoding="utf-8") as handle:
        return json.load(handle), {}


def fetch_all():
    """Records (all pages), attachments base URL and the collection's
    timestamp (ETag) from Remote Settings."""
    root, _ = read_json(SERVER)
    base_url = root["capabilities"]["attachments"]["base_url"]
    records, url, etag = [], COLLECTION_URL + "?_limit=1000", None
    while url:
        with urllib.request.urlopen(url, timeout=60) as response:
            records.extend(json.load(response)["data"])
            etag = etag or response.headers.get("ETag", "").strip('"')
            url = response.headers.get("Next-Page")
    return records, base_url, int(etag) if etag else None


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out_default = os.path.normpath(os.path.join(here, "..", "..", "app", "src", "main", "assets", "index_v7.json"))
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("records", nargs="?", help="saved Remote Settings records JSON ({'data': [...]})")
    ap.add_argument("--fetch", action="store_true", help="read the records from Remote Settings")
    ap.add_argument("--offline-index", help="offline-translator's index_v7.json (path or URL); default with --fetch: tag v0.8.7")
    ap.add_argument("--out", default=out_default)
    args = ap.parse_args()

    if args.fetch:
        records, base_url, timestamp = fetch_all()
    elif args.records:
        data, _ = read_json(args.records)
        records = data["data"] if isinstance(data, dict) else data
        base_url, timestamp = ATTACHMENTS_BASE, None
    else:
        ap.error("give a records file or --fetch")
    index_source = args.offline_index or (OFFLINE_INDEX_URL if args.fetch else None)
    if not index_source:
        ap.error("--offline-index is needed with a records file")
    offline, _ = read_json(index_source)

    # The collection's timestamp is its newest record's last_modified (ms).
    newest = max(r["last_modified"] for r in records)
    timestamp = timestamp or newest

    selected, unknown = select_records(records)
    packs, incomplete = build_packs(selected, base_url)

    # A language offline-translator's index does not describe has no name or
    # script to show: its packs are left out and reported.
    missing = sorted({c for p in packs.values() for c in (p["from"], p["to"])} - set(offline["languages"]))
    packs = {k: p for k, p in packs.items() if p["from"] not in missing and p["to"] not in missing}
    languages = build_languages(offline["languages"], packs)

    catalog = {
        "formatVersion": 6,
        "generatedAt": newest // 1000,
        "dictionaryVersion": offline.get("dictionaryVersion", 1),
        "sources": {
            "languageIndexVersion": offline["sources"]["languageIndexVersion"],
            "languageIndexUpdatedAt": offline["sources"]["languageIndexUpdatedAt"],
            "dictionaryIndexVersion": offline["sources"]["dictionaryIndexVersion"],
            "dictionaryIndexUpdatedAt": offline["sources"]["dictionaryIndexUpdatedAt"],
            "remoteSettings": "main/translations-models",
            "remoteSettingsTimestamp": timestamp,
            "attachmentsBaseUrl": base_url,
            "modelMajorVersions": "%d-%d" % (MODEL_MAJOR_MIN, MODEL_MAJOR_MAX),
            "selection": "mozilla-firefox/firefox " + FIREFOX_REV,
            "languageNames": "DavidVentura/offline-translator v0.8.7",
        },
        "languages": languages,
        "packs": packs,
        "migrations": [],
    }
    text = json.dumps(catalog, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)

    report(catalog, offline, unknown, incomplete, missing, args.out)


def report(catalog, offline, unknown, incomplete, missing, out):
    """Validation and counts, printed."""
    packs = catalog["packs"]
    bad = []
    total = 0
    host = "https://firefox-settings-attachments.cdn.mozilla.net/"
    for pid, pack in packs.items():
        for f in pack["files"]:
            total += f["sizeBytes"]
            if not (f["url"].startswith(host) and re.fullmatch(r"[0-9a-f]{64}", f["sha256"])
                    and isinstance(f["sizeBytes"], int) and f["sizeBytes"] > 0
                    and not f["url"].endswith(".gz")):
                bad.append("%s %s" % (pid, f["name"]))
    ours = set(packs)
    theirs = {k for k, v in offline["packs"].items() if v["feature"] == "translation"}
    print("written", out)
    print("languages %d, packs %d, files %d, total %.1f MB (%d bytes)" % (
        len(catalog["languages"]), len(packs), sum(len(p["files"]) for p in packs.values()), total / 1e6, total))
    print("invalid files:", bad or "none")
    print("unknown filter expressions (dropped):", unknown or "none")
    print("incomplete pairs (left out):", incomplete or "none")
    print("languages without an offline-translator entry (packs left out):", missing or "none")
    print("offline-translator has, we lack (%d):" % len(theirs - ours), " ".join(sorted(theirs - ours)))
    print("we have, offline-translator lacks (%d):" % len(ours - theirs), " ".join(sorted(ours - theirs)))
    differing = []
    for pid in sorted(ours & theirs):
        a = {f["role"]: f["installPath"] for f in packs[pid]["files"]}
        b = {f["role"]: f["installPath"] for f in offline["packs"][pid]["files"]}
        if a != b:
            differing.append(pid)
    print("packs whose roles/installPaths differ from offline-translator's:", differing or "none")
    if bad:
        sys.exit(1)


if __name__ == "__main__":
    main()
