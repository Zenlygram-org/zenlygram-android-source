#!/usr/bin/env python3
"""String parity of the customer-data UI (design 50 §14; W18-AND-8), host only, no device, no network.

Zenlygram 13.2.1: the strings live in strings_zenlygram_*.xml (values, values-ru, values-uz) and the screens in
org.telegram.zenlygram.customer. Four files are checked: strings_zenlygram_engine_consent.xml (Zenly*, the cloud
client's customer strings), strings_zenlygram_engine_pro.xml, and the two files of the customer screens,
strings_zenlygram_cloud.xml (ZGC_*) and strings_zenlygram_customer.xml.

For the three customer files:
  * the same keys in all three languages, no duplicates, no empty value (engine_consent: every required key present);
  * the same numbered placeholders per key, and complete plural sets (_zero.._other) for every plural key;
  * Uzbek uses U+02BB / U+02BC only (no ASCII apostrophe, no U+2018 / U+2019);
  * a Russian or Uzbek value equal to the English one is a missing translation, except brand-only values (Telegram ID,
    Telegram Premium) and the one waiver ZenlyDataConsentResetDebug (the same English text in all three files).
The hash-coupled labels read exactly as the vendored consent texts quote them
("Settings → Zenlygram → Data & consent → My data", "… → Delete my Zenlygram account and data"), in each language:
  * the client's own ZenlyDataConsentRow, ZenlyDataConsentMyData, ZenlyDataConsentDelete (and the titles made of them);
  * the labels THIS app shows on that path: the hub entry (ZG_AppName), the hub row (ZGC_Settings_Data_Consent), the
    screen and wall titles (ZGC_DataConsent_Title, ZGC_Consent_Wall_Title), My data (ZGC_DataConsent_MyData,
    ZGC_MyData_Title) and the deletion (ZGC_DataConsent_Delete, ZGC_Consent_Declined_Delete, and the withdraw dialog's
    delete alternative ZenlyDataConsentWithdrawDeleteInstead);
  * the optional-mode notes name the same path.
A label that differs is fixed, never the hashed text.
strings_zenlygram_engine_pro.xml: key parity and placeholders across the three files, incl.
  ZenlyProLinkAttestDisclosure and the ZenlyProUnlinkInfo sentence; the Uzbek rule for the two strings this package owns.
Java: every R.string the customer screens reference is defined, every plural they name has its _other form (so a typo
  fails here before the build), and no string name of these files collides with another one's Java hashCode (the strings
  pipeline refuses collisions).
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "TMessagesProj/src/main/res"
JAVA = ROOT / "TMessagesProj/src/main/java"
ASSETS = ROOT / "TMessagesProj/src/main/assets/zenlygram/consent"
# Zenlygram 13.2.1: one resource tree, Zenlygram strings in strings_zenlygram_*.xml, the screens in their own package.
CONSENT_FILE = "strings_zenlygram_engine_consent.xml"
PRO_FILE = "strings_zenlygram_engine_pro.xml"
SCREEN_FILES = ("strings_zenlygram_cloud.xml", "strings_zenlygram_customer.xml")
SCREENS_DIR = JAVA / "org/telegram/zenlygram/customer"
LANGS = (("en", "values"), ("ru", "values-ru"), ("uz", "values-uz"))
PLURALS = ("zero", "one", "two", "few", "many", "other")
WAIVED = {"ZenlyDataConsentResetDebug"}
BRAND_ONLY = re.compile(r"^(Telegram|ID|Premium|Zenlygram|App Store|Google Play|[\s/·:%0-9$sd])+$")
PLACEHOLDER = re.compile(r"%(\d+)\$([sd])")
# The settings path of the Zenlygram entry as the hashed texts quote it (ui-strings §0.1).
SETTINGS_PATH = {"en": "Settings → Zenlygram", "ru": "Настройки → Zenlygram", "uz": "Sozlamalar → Zenlygram"}
TEXT_FILE = {"en": "v1/en.md", "ru": "v1/ru.md", "uz": "v1/uz.md"}

REQUIRED = """
ZenlyConsentTitle ZenlyConsentAccount ZenlyConsentTextLanguage ZenlyConsentAgree ZenlyConsentDecline ZenlyConsentAgeHint
ZenlyConsentModeNoteRequired ZenlyConsentModeNoteOptional ZenlyConsentNoticeReconsent ZenlyConsentNoticeWithdrawn
ZenlyConsentNoticeWithdrawnRemote ZenlyConsentNoticeLinkEnded ZenlyConsentTextUnavailable ZenlyConsentSaveFailed
ZenlyConsentDeclinedTitle ZenlyConsentDeclinedText ZenlyConsentDeclinedBack ZenlyConsentDeclinedLogout
ZenlyConsentSwitchAccount
ZenlyDataConsentHeader ZenlyDataConsentRow ZenlyDataConsentValueGranted ZenlyDataConsentValueNone
ZenlyDataConsentValueDeclined ZenlyDataConsentValueWithdrawn ZenlyDataConsentValueReview ZenlyDataConsentFooter
ZenlyDataConsentOperator ZenlyDataConsentStorage ZenlyDataConsentStatusGranted ZenlyDataConsentStatusPending
ZenlyDataConsentStatusNone ZenlyDataConsentStatusDeclined ZenlyDataConsentStatusWithdrawn
ZenlyDataConsentStatusWithdrawPending ZenlyDataConsentStatusReconsent ZenlyDataConsentStatusLinkEnded
ZenlyDataConsentStatusRejected ZenlyDataConsentReadText ZenlyDataConsentGrant ZenlyDataConsentMyData
ZenlyDataConsentWithdraw ZenlyDataConsentDelete ZenlyDataConsentPrivacyPolicy ZenlyDataConsentWithdrawTitle
ZenlyDataConsentWithdrawText ZenlyDataConsentWithdrawUnsent ZenlyDataConsentWithdrawRequired
ZenlyDataConsentWithdrawOptional ZenlyDataConsentWithdrawConfirm ZenlyDataConsentWithdrawDeleteInstead
ZenlyDataConsentWithdrawPendingUpload ZenlyDataConsentResetDebug ZenlyDataConsentActiveElsewhere
ZenlyDataConsentDeletePending ZenlyLogoutWithdrawPending
ZenlyDeleteTitle ZenlyDeleteText ZenlyDeleteBilling ZenlyDeleteManageSubscription ZenlyDeleteConfirm
ZenlyDeleteConfirm2Title ZenlyDeleteConfirm2Text ZenlyDeleteConfirm2Action ZenlyDeleteDoneTitle ZenlyDeleteDoneText
ZenlyDeleteNotLinked ZenlyDeleteOpenWeb ZenlyDeleteErrorOffline ZenlyDeleteErrorRateLimited ZenlyDeleteErrorUnavailable
ZenlyDeleteFailed
ZenlyMyDataTitle ZenlyMyDataLoading ZenlyMyDataNotLinked ZenlyMyDataUnavailable ZenlyMyDataErrorOffline
ZenlyMyDataErrorUnavailable ZenlyMyDataErrorRateLimited ZenlyMyDataRetry ZenlyMyDataSectionAccount
ZenlyMyDataSectionConsent ZenlyMyDataSectionHistory ZenlyMyDataSectionProfile ZenlyMyDataSectionDevices
ZenlyMyDataSectionActivity ZenlyMyDataSectionEvents ZenlyMyDataSectionSubscription ZenlyMyDataSectionRetention
ZenlyMyDataSourceTelegramAttest ZenlyMyDataSourceTelegramBot ZenlyMyDataSourceClient ZenlyMyDataSourceTelegramSession
ZenlyMyDataFieldId ZenlyMyDataFieldTelegramId ZenlyMyDataFieldCreated ZenlyMyDataFieldPhone ZenlyMyDataFieldCountry
ZenlyMyDataFieldUsernames ZenlyMyDataFieldFirstName ZenlyMyDataFieldLastName ZenlyMyDataFieldName
ZenlyMyDataFieldLanguage ZenlyMyDataFieldPremium ZenlyMyDataFieldModel ZenlyMyDataFieldOs ZenlyMyDataFieldApp
ZenlyMyDataFieldVersions ZenlyMyDataFieldLocale ZenlyMyDataFieldTimeZone ZenlyMyDataFieldDistribution
ZenlyMyDataFieldFirstSeen ZenlyMyDataFieldLastSeen ZenlyMyDataFieldFirstActive ZenlyMyDataFieldLastActive
ZenlyMyDataFieldActiveDays ZenlyMyDataFieldSessions30 ZenlyMyDataFieldSeconds30 ZenlyMyDataEventsTotal
ZenlyMyDataFieldRetained ZenlyMyDataPrivacyFeatures
""".split()
PRO_OWNED = ("ZenlyProLinkAttestDisclosure", "ZenlyProUnlinkInfo")

failures = []


def fail(message):
    failures.append(message)


def strings(path):
    """name -> text as the app shows it (Telegram's pipeline: \\n is a newline, other backslashes go)."""
    tree = ET.parse(path)
    values = {}
    for node in tree.getroot().iter("string"):
        name = node.get("name")
        if name in values:
            fail("%s: duplicate %s" % (path.name, name))
        raw = "".join(node.itertext())
        values[name] = raw.replace("\\n", "\n").replace("\\", "")
    return values


def placeholders(text):
    return sorted(PLACEHOLDER.findall(text))


def base_key(name):
    for form in PLURALS:
        if name.endswith("_" + form):
            return name[: -len(form) - 1]
    return name


def check_parity(label, files, required=None):
    keys = {lang: set(values) for lang, values in files.items()}
    reference = keys["en"]
    for lang in ("ru", "uz"):
        missing, extra = reference - keys[lang], keys[lang] - reference
        if missing:
            fail("%s %s: missing %s" % (label, lang, sorted(missing)))
        if extra:
            fail("%s %s: not in English %s" % (label, lang, sorted(extra)))
    if required:
        bases = {base_key(name) for name in reference}
        for name in required:
            if name not in bases:
                fail("%s: required key %s is missing" % (label, name))
    for name in sorted(reference):
        for lang, values in files.items():
            if name in values and not values[name].strip():
                fail("%s %s: %s is empty" % (label, lang, name))
        if all(name in values for values in files.values()):
            shapes = {lang: placeholders(values[name]) for lang, values in files.items()}
            if len({tuple(shape) for shape in shapes.values()}) != 1:
                fail("%s: placeholders of %s differ %s" % (label, name, shapes))
            for lang, text in ((lang, values[name]) for lang, values in files.items()):
                stray = re.sub(PLACEHOLDER, "", text)
                if "%" in stray:
                    fail("%s %s: %s has an unnumbered or literal %%" % (label, lang, name))
    plural_bases = {base_key(name) for name in reference if base_key(name) != name}
    for base in sorted(plural_bases):
        for lang, values in files.items():
            forms = {name[len(base) + 1:] for name in values if name.startswith(base + "_") and base_key(name) == base}
            if forms != set(PLURALS):
                fail("%s %s: plural %s has %s, needs all of %s" % (label, lang, base, sorted(forms), PLURALS))


def check_uzbek(label, name, text):
    for bad, what in (("'", "ASCII apostrophe"), ("‘", "U+2018"), ("’", "U+2019"), ("`", "backtick")):
        if bad in text:
            fail("%s uz: %s uses %s (Uzbek takes U+02BB / U+02BC)" % (label, name, what))


def check_translations(label, files):
    for name, english in files["en"].items():
        if name in WAIVED:
            for lang in ("ru", "uz"):
                if files[lang].get(name) != english:
                    fail("%s %s: the waived %s must keep the English text" % (label, lang, name))
            continue
        for lang in ("ru", "uz"):
            if files[lang].get(name) == english and not BRAND_ONLY.match(english):
                fail("%s %s: %s is not translated (%r)" % (label, lang, name, english))


def check_coupling(consent):
    for lang, _ in LANGS:
        values = consent[lang]
        text_path = ASSETS / TEXT_FILE[lang]
        if not text_path.exists():
            fail("vendored consent text missing: %s" % text_path)
            continue
        text = text_path.read_text(encoding="utf-8")
        data = "%s → %s" % (SETTINGS_PATH[lang], values.get("ZenlyDataConsentRow"))
        for quoted in (data, data + " → " + str(values.get("ZenlyDataConsentMyData")),
                       data + " → " + str(values.get("ZenlyDataConsentDelete"))):
            if quoted not in text:
                fail("%s: the hashed text does not quote %r (hash-coupled label changed?)" % (lang, quoted))
        if values.get("ZenlyDataConsentWithdrawDeleteInstead") != values.get("ZenlyDataConsentDelete"):
            fail("%s: ZenlyDataConsentWithdrawDeleteInstead must use the ZenlyDataConsentDelete words" % lang)
        if data not in values.get("ZenlyConsentModeNoteOptional", ""):
            fail("%s: ZenlyConsentModeNoteOptional must name %r" % (lang, data))
        if values.get("ZenlyMyDataTitle") != values.get("ZenlyDataConsentMyData"):
            fail("%s: ZenlyMyDataTitle must read as the coupled My data label" % lang)
        if values.get("ZenlyConsentTitle") != values.get("ZenlyDataConsentRow"):
            fail("%s: ZenlyConsentTitle must read as the coupled Data & consent label" % lang)


def check_app_coupling(screens, consent, brand):
    """The labels this app shows on the paths the hashed texts quote (ZGC_*; the hub entry is ZG_AppName)."""
    for lang, _ in LANGS:
        values = screens[lang]
        text_path = ASSETS / TEXT_FILE[lang]
        if not text_path.exists():
            continue  # reported by check_coupling
        text = text_path.read_text(encoding="utf-8")
        # "Settings → Zenlygram": Telegram's Settings, then the hub entry.
        entry = brand[lang].get("ZG_AppName")
        if not SETTINGS_PATH[lang].endswith(" → %s" % entry):
            fail("%s: the hub entry ZG_AppName (%r) is not the one the hashed text names in %r" % (lang, entry, SETTINGS_PATH[lang]))
        row = values.get("ZGC_Settings_Data_Consent")
        data = "%s → %s" % (SETTINGS_PATH[lang], row)
        for name, quoted in (("ZGC_Settings_Data_Consent", data),
                             ("ZGC_DataConsent_MyData", data + " → " + str(values.get("ZGC_DataConsent_MyData"))),
                             ("ZGC_DataConsent_Delete", data + " → " + str(values.get("ZGC_DataConsent_Delete")))):
            if quoted not in text:
                fail("%s: the hashed text does not quote %r (%s: the label must read as the text names it)" % (lang, quoted, name))
        for name, same in (("ZGC_DataConsent_Title", row), ("ZGC_Consent_Wall_Title", row),
                           ("ZGC_MyData_Title", values.get("ZGC_DataConsent_MyData")),
                           ("ZGC_Consent_Declined_Delete", values.get("ZGC_DataConsent_Delete"))):
            if values.get(name) != same:
                fail("%s: %s (%r) must read as the coupled label %r" % (lang, name, values.get(name), same))
        if consent[lang].get("ZenlyDataConsentWithdrawDeleteInstead") != values.get("ZGC_DataConsent_Delete"):
            fail("%s: the withdraw dialog's delete alternative must use the ZGC_DataConsent_Delete words" % lang)
        if data not in values.get("ZGC_Consent_Wall_ModeNote_Optional", ""):
            fail("%s: ZGC_Consent_Wall_ModeNote_Optional must name %r" % (lang, data))


def java_hash(name):
    value = 0
    for unit in name.encode("utf-16-be").decode("utf-16-be"):
        value = (31 * value + ord(unit)) & 0xFFFFFFFF
    return value


def check_java_references(defined_strings):
    """Every R.string and every plural the customer screens name is defined (the app has no R.id of its own there)."""
    sources = sorted(SCREENS_DIR.glob("*.java"))
    if len(sources) < 10:
        fail("the customer screens were not found in %s" % SCREENS_DIR)
    for path in sources:
        source = path.read_text(encoding="utf-8")
        for name in sorted(set(re.findall(r"R\.string\.(\w+)", source))):
            if name not in defined_strings:
                fail("%s: R.string.%s is not defined" % (path.stem, name))
        for name in sorted(set(re.findall(r'formatPluralString\("(\w+)"', source))):
            if name + "_other" not in defined_strings:
                fail("%s: plural %s is not defined" % (path.stem, name))
        # Strings are read through LocaleController only: a lookup by resource name finds nothing in a release build.
        for banned in ("getIdentifier(", "getResources().getString(", "context.getString(", "getContext().getString("):
            if banned in source:
                fail("%s: reads a string with %s" % (path.stem, banned))


def all_string_names():
    names = set()
    for path in sorted(RES.glob("values/*.xml")):
        if path.name != "strings.xml" and not path.name.startswith("strings_zenlygram"):
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            fail("%s: %s" % (path, error))
            continue
        for node in root.iter("string"):
            names.add(node.get("name"))
    # The pipeline also adds the root of every plural family.
    names |= {base_key(name) for name in names}
    return names


def main():
    consent = {lang: strings(RES / folder / CONSENT_FILE) for lang, folder in LANGS}
    pro = {lang: strings(RES / folder / PRO_FILE) for lang, folder in LANGS}
    check_parity(CONSENT_FILE, consent, REQUIRED)
    check_parity(PRO_FILE, pro, PRO_OWNED)
    for name, text in consent["uz"].items():
        check_uzbek(CONSENT_FILE, name, text)
    for name in PRO_OWNED:
        check_uzbek(PRO_FILE, name, pro["uz"].get(name, ""))
    check_translations(CONSENT_FILE, consent)
    check_coupling(consent)
    # The files of the customer screens: the same rules, and the labels this app shows.
    screens = {lang: {} for lang, _ in LANGS}
    for file_name in SCREEN_FILES:
        files = {lang: strings(RES / folder / file_name) for lang, folder in LANGS}
        check_parity(file_name, files)
        for name, text in files["uz"].items():
            check_uzbek(file_name, name, text)
        check_translations(file_name, files)
        for lang, _ in LANGS:
            for name in files[lang]:
                if name in screens[lang]:
                    fail("%s %s: %s is also defined in another customer file" % (file_name, lang, name))
            screens[lang].update(files[lang])
    brand = {lang: strings(RES / folder / "strings_zenlygram.xml") for lang, folder in LANGS}
    check_app_coupling(screens, consent, brand)
    for lang, _ in LANGS:
        disclosure = pro[lang].get("ZenlyProLinkAttestDisclosure", "")
        if placeholders(disclosure) != [("1", "s")]:
            fail("%s %s: ZenlyProLinkAttestDisclosure needs exactly %%1$s (the bot)" % (PRO_FILE, lang))
    names = all_string_names()
    owned = set(consent["en"]) | set(screens["en"])
    hashes = {}
    for name in sorted(names):
        other = hashes.setdefault(java_hash(name), name)
        if other != name and (name in owned or other in owned or "ZenlyProLinkAttestDisclosure" in (name, other)):
            fail("string hash collision: %s / %s" % (other, name))
    check_java_references(names)
    if failures:
        for message in failures:
            print("FAIL " + message)
        print("strings_parity: %d failure(s)" % len(failures))
        return 1
    print("strings_parity: OK (%d consent keys x3, %d pro keys x3, %d screen keys x3)" % (len(consent["en"]), len(pro["en"]), len(screens["en"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
