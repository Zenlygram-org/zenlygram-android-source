#!/usr/bin/env python3
"""Convert Zenlygram's iOS strings (SGLocalizable.strings) into Android string resources.

WHAT IT DOES
  Reads   <ios>/Swiftgram/SGStrings/Strings/{en,ru,uz}.lproj/SGLocalizable.strings
  Writes  TMessagesProj/src/main/res/values/strings_zenlygram.xml      (English, the default)
          TMessagesProj/src/main/res/values-ru/strings_zenlygram.xml   (Russian)
          TMessagesProj/src/main/res/values-uz/strings_zenlygram.xml   (Uzbek)

  1. Every iOS key becomes "ZG_" + key with "." replaced by "_":
         Settings.SecretVault.Title  ->  ZG_Settings_SecretVault_Title
  2. Placeholders become positional Java ones: %@ -> %1$s, "%@ ... %@" -> "%1$s ... %2$s",
     %2$@ -> %2$s, %d/%ld/%lld -> %1$d (as in the Android specs, so code may rely on "%1$s").
     Strings without placeholders are plain text (read them with getString), so their
     %% becomes %. Strings with placeholders keep %% (formatString turns it into %).
  3. Upper-case iOS section headers ("HIDDEN & LOCKED CHATS") also get a sentence-case
     copy for Android HeaderCells:
         ZG_Header_ + converted key without "ZG_" and without a trailing "_Header"
         Settings.Privacy.HiddenChats.Header -> ZG_Header_Settings_Privacy_HiddenChats
         Settings.ContextMenu                -> ZG_Header_Settings_ContextMenu
  4. An Android wording table rewrites iOS-only words (Face ID / Touch ID, iPhone,
     Keychain, App Store, Apple ID, iOS, device passcode, Quick Start) in all three
     languages. The keys it changed are printed on every run.
  4b. Uzbek apostrophes get one style (oʻ / gʻ = U+02BB, tutuq belgisi = U+02BC, foreign word + suffix = ').
  5. A few Android-only brand strings (ANDROID_EXTRA: ZG_AppName, ZG_TelegramVersion,
     ZG_UnlockToUse, the login permission texts, ...) are appended; LocaleController's brand
     override reads them instead of Telegram strings that name the app
     (org.telegram.zenlygram.branding.ZgStrings).
  6. Checks that no new name exists already and that no two names in the build's string
     namespace share a Java String.hashCode() (buildSrc TelegramStringsTask fails the
     build on such a collision, and LocaleController looks strings up by that hash).

  The output is deterministic: running it again with the same iOS files rewrites nothing.

HOW TO RE-RUN (after the iOS strings change)
  cd ~/zenlygram-android
  python3 Tools/zenlygram/ios_strings_to_android.py                 # iOS repo at ~/zenlygram
  python3 Tools/zenlygram/ios_strings_to_android.py --ios-root PATH # iOS repo elsewhere
  python3 Tools/zenlygram/ios_strings_to_android.py --check         # CI: exit 1 if out of date
  (ZENLYGRAM_IOS_ROOT=PATH works too. A file missing on disk is read with `git show HEAD:<path>`;
  --ios-rev REV reads every file from that git revision instead.)

  Never edit the generated XML files by hand. To change an Android wording, edit
  ANDROID_WORDING / ANDROID_OVERRIDES below; to add an Android-only string that is not an
  iOS string, put it in values*/strings_zenlygram_android.xml (also picked up by the build:
  buildSrc includes values*/strings_zenlygram*.xml), and never reuse a ZG_ name made here.

READING THE STRINGS IN JAVA
  LocaleController.getString(R.string.ZG_X) / LocaleController.formatString(R.string.ZG_X, a)
  (never context.getString: the build discards these resources and serves them from
  assets/localization_<lang>.bin).
"""

import argparse
import hashlib
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from collections import OrderedDict
from pathlib import Path

LANGS = ("en", "ru", "uz")                      # en is the default (values/)
VALUES_DIR = {"en": "values", "ru": "values-ru", "uz": "values-uz"}
IOS_STRINGS = "Swiftgram/SGStrings/Strings/{lang}.lproj/SGLocalizable.strings"
RES_DIR = "TMessagesProj/src/main/res"
OUTPUT_NAME = "strings_zenlygram.xml"
SCRIPT_REL = "Tools/zenlygram/ios_strings_to_android.py"

PREFIX = "ZG_"
HEADER_PREFIX = "ZG_Header_"

# iOS keys -> different Android names. Only for a future name or hash collision; keep it small.
KEY_RENAMES = {
}

# Upper-case iOS values that are not section headers (no ZG_Header_ copy).
NOT_HEADERS = {
    "CallRecording.Indicator.Short",            # "REC" badge
}

# Words kept as written when headers are put in sentence case.
HEADER_KEEP_WORDS = {
    "ZENLYGRAM": "Zenlygram", "TELEGRAM": "Telegram", "PRO": "Pro", "PREMIUM": "Premium",
    "AI": "AI", "ИИ": "ИИ", "API": "API", "PIN": "PIN", "ID": "ID", "GIF": "GIF",
    "URL": "URL", "TON": "TON", "NFT": "NFT", "PDF": "PDF", "HD": "HD",
}

# Android-only strings (already in Java format).
ANDROID_EXTRA = OrderedDict([
    ("ZG_AppName", {"en": "Zenlygram", "ru": "Zenlygram", "uz": "Zenlygram"}),
    ("ZG_AppNameBeta", {"en": "Zenlygram Beta", "ru": "Zenlygram Beta", "uz": "Zenlygram Beta"}),
    ("ZG_TelegramVersion", {"en": "Zenlygram for Android %1$s",
                            "ru": "Zenlygram для Android %1$s",
                            "uz": "Android uchun Zenlygram %1$s"}),
    ("ZG_UnlockToUse", {"en": "Unlock to use Zenlygram",
                        "ru": "Разблокируйте, чтобы пользоваться Zenlygram",
                        "uz": "Zenlygram'dan foydalanish uchun qulfni oching"}),
    # Passcode lock screen title (Telegram's AppLocked "Telegram Locked")
    ("ZG_AppLocked", {"en": "Zenlygram Locked",
                      "ru": "Zenlygram заблокирован",
                      "uz": "Zenlygram qulflangan"}),
    # Login: permission rationales on the phone and code screens (Telegram's AllowReadCall* / AllowFillNumber)
    ("ZG_AllowReadCall", {"en": "Please allow Zenlygram to receive calls so that we can automatically enter your code for you.",
                          "ru": "Пожалуйста, разрешите Zenlygram принимать звонки, чтобы мы могли автоматически ввести код.",
                          "uz": "Iltimos, kodni avtomatik kiritishimiz uchun Zenlygram'ga qo'ng'iroqlarni qabul qilishga ruxsat bering."}),
    ("ZG_AllowReadCallAndLog", {"en": "Please allow Zenlygram to receive calls and read the call log so that we can automatically enter your code for you.",
                                "ru": "Пожалуйста, разрешите Zenlygram принимать звонки и читать журнал звонков, чтобы мы могли автоматически ввести код.",
                                "uz": "Iltimos, kodni avtomatik kiritishimiz uchun Zenlygram'ga qo'ng'iroqlarni qabul qilish va qo'ng'iroqlar jurnalini o'qishga ruxsat bering."}),
    ("ZG_AllowReadCallLog", {"en": "Please allow Zenlygram to read the call log so that we can automatically enter your code for you.",
                             "ru": "Пожалуйста, разрешите Zenlygram читать журнал звонков, чтобы мы могли автоматически ввести код.",
                             "uz": "Iltimos, kodni avtomatik kiritishimiz uchun Zenlygram'ga qo'ng'iroqlar jurnalini o'qishga ruxsat bering."}),
    ("ZG_AllowFillNumber", {"en": "Please allow Zenlygram to receive calls so that we can automatically confirm your phone number.",
                            "ru": "Пожалуйста, разрешите Zenlygram принимать звонки, чтобы мы могли автоматически подтвердить ваш номер телефона.",
                            "uz": "Iltimos, telefon raqamingizni avtomatik tasdiqlashimiz uchun Zenlygram'ga qo'ng'iroqlarni qabul qilishga ruxsat bering."}),
    # Login code screen, flash call (Telegram's SentCallCode: "... Telegram will process everything automatically")
    ("ZG_SentCallCode", {"en": "We're calling your phone **%1$s**.\n\nDon't accept the call, Zenlygram will process everything automatically.",
                         "ru": "Наш робот звонит на номер **%1$s**.\n\nОтвечать на звонок не требуется, Zenlygram автоматически получит код для входа.",
                         "uz": "**%1$s** raqamingizga qo'ng'iroq qilyapmiz.\n\nQo'ng'iroqqa javob bermang, Zenlygram hammasini avtomatik bajaradi."}),
])

# ---------------------------------------------------------------------------------------------
# Android wording table. Applied to the unescaped iOS text, in order, per language.
# A replacement that lands at the start of a sentence is capitalised automatically.
# ---------------------------------------------------------------------------------------------
ANDROID_WORDING = {
    "en": [
        (r"\bFace ID, Touch ID or the device passcode\b", "fingerprint, face unlock or the device screen lock"),
        (r"\bFace ID or Touch ID\b", "fingerprint or face unlock"),
        (r"\bFace ID\b", "face unlock"),
        (r"\bTouch ID\b", "fingerprint"),
        (r"\bdevice passcode\b", "device screen lock"),
        (r"\bKeychain Error\b", "Secure Storage Error"),
        (r"\b(Backup to|Restore from) Keychain\b", r"\1 Secure Storage"),
        (r"\bDelete Keychain Backup\b", "Delete Secure Storage Backup"),
        (r"\bKeychain\b", "secure storage"),
        (r"\biPhone\b", "phone"),
        (r"\biPad\b", "tablet"),
        (r"\bApple couldn't renew your App Store subscription\b", "Google Play couldn't renew your subscription"),
        (r"\b[Tt]he App Store\b", "Google Play"),
        (r"\bApp Store\b", "Google Play"),
        (r"\bApple (?:ID|Account)\b", "Google account"),
        (r"\bApple\b", "Google"),
        (r"\biOS Call Integration\b", "system call integration"),
        (r"\biOS\b", "Android"),
        (r"\bQuick Start\b", "device-to-device migration"),
    ],
    "ru": [
        (r"Face ID, Touch ID или код-пароль устройства", "отпечаток пальца, распознавание лица или код блокировки экрана"),
        (r"Face ID, Touch ID или код-пароля устройства", "отпечатка пальца, распознавания лица или кода блокировки экрана"),
        (r"по Face ID или Touch ID", "по отпечатку пальца или распознаванию лица"),
        (r"Face ID или Touch ID", "отпечаток пальца или распознавание лица"),
        (r"Face ID", "распознавание лица"),
        (r"Touch ID", "отпечаток пальца"),
        (r"с код-паролем устройства", "с блокировкой экрана устройства"),
        (r"[Кк]од-пароль устройства", "код блокировки экрана"),
        (r"[Кк]од-пароля устройства", "кода блокировки экрана"),
        (r"Бэкап в Keychain", "Бэкап в защищённое хранилище"),
        (r"Удалить Бэкап из Keychain", "Удалить бэкап из защищённого хранилища"),
        (r"из Keychain", "из защищённого хранилища"),
        (r"в Keychain", "в защищённом хранилище"),
        (r"из Связки ключей", "из защищённого хранилища"),
        (r"в Связке ключей", "в защищённом хранилище"),
        (r"Связки ключей", "защищённого хранилища"),
        (r"Keychain|Связка ключей", "защищённое хранилище"),
        (r"на этом iPhone", "на этом телефоне"),
        (r"iPhone", "телефон"),
        (r"iPad", "планшет"),
        (r"Apple не удалось продлить подписку App Store", "Google Play не удалось продлить подписку"),
        (r"App Store", "Google Play"),
        (r"[Нн]а этом Apple (?:ID|Account)", "в этом аккаунте Google"),
        (r"в этом Apple (?:ID|Account)", "в этом аккаунте Google"),
        (r"[Дд]ля этого Apple (?:ID|Account)", "для этого аккаунта Google"),
        (r"вошли с того Apple (?:ID|Account)", "вошли в тот аккаунт Google"),
        (r"оформленную с Apple (?:ID|Account)", "оформленную в аккаунте Google"),
        (r"с Apple (?:ID|Account)", "с аккаунта Google"),
        (r"Apple (?:ID|Account)", "аккаунт Google"),
        (r"Apple", "Google"),
        (r"интеграция со звонками iOS", "системная интеграция звонков"),
        (r"\biOS\b", "Android"),
        (r"«Быстрым началом»", "переносом данных между устройствами"),
    ],
    "uz": [
        (r"Face ID, Touch ID yoki [Qq]urilma kod-paroli", "barmoq izi, yuz yoki ekran qulfi kodi"),
        (r"Face ID yoki Touch ID bilan", "barmoq izi yoki yuz bilan"),
        (r"Face ID yoki Touch ID", "barmoq izi yoki yuz bilan ochish"),
        (r"Face ID", "yuz bilan ochish"),
        (r"Touch ID", "barmoq izi"),
        (r"[Qq]urilma kod-parolidan", "qurilma ekran qulfidan"),
        (r"[Qq]urilma kod-paroli", "ekran qulfi kodi"),
        (r"[Qq]urilma Keychain'i(ga|dan|da)\b", r"qurilmaning himoyalangan xotirasi\1"),
        (r"Keychain'i(ga|dan|da)\b", r"himoyalangan xotirasi\1"),
        (r"Keychain zaxirasini", "himoyalangan xotiradagi zaxirani"),
        (r"Keychain'(ga|dan|da)\b", r"himoyalangan xotira\1"),
        (r"Keychain", "himoyalangan xotira"),
        (r"iPhone[’']da\b", "telefonda"),
        (r"iPhone", "telefon"),
        (r"iPad", "planshet"),
        (r"Apple App Store", "Google Play"),
        (r"App Store", "Google Play"),
        (r"Apple (?:ID|Account)'da\b", "Google hisobida"),
        (r"Apple Account'ingizdan\b", "Google hisobingizdan"),
        (r"Apple (?:ID|Account) (uchun|bilan)\b", r"Google hisobi \1"),
        (r"Apple (?:ID|Account)", "Google hisobi"),
        (r"Apple", "Google"),
        (r"iOS qoʻngʻiroq integratsiyasi", "tizim qoʻngʻiroq integratsiyasi"),
        (r"\biOS\b", "Android"),
        (r"Quick Start", "qurilmadan qurilmaga ko'chirish"),
        # The vault is "Maxfiy seyf" everywhere on Android (owner decision; iOS Settings use it, the iOS paywall
        # says "xazina"). Every form: Maxfiy xazina(ni), Xazina, xazinaga, Xazinangiz.
        (r"\bXazinangiz", "Seyfingiz"),
        (r"\bxazinangiz", "seyfingiz"),
        (r"\bXazina(ga|ni|da|dan|ning)?\b", r"Seyf\1"),
        (r"\bxazina(ga|ni|da|dan|ning)?\b", r"seyf\1"),
    ],
}

# Whole-string Android texts where a word swap is not enough (iOS text form: %@ placeholders).
ANDROID_OVERRIDES = {
    # Android: "Forgot PIN" accepts only a strong (Class 3) biometric, and face unlock is weak on many phones; the
    # chat is hidden through the ⋮ menu of the selected chat.
    "Settings.SecretVault.Notice": {
        "en": "Hidden chats disappear from your list in this app and open only after you enter the vault PIN. To hide "
              "a chat, long-press it and choose \"Add to Vault\" in the ⋮ menu. Turning this off asks for the PIN and "
              "brings all hidden chats back.\n\nThe vault PIN is separate from your device screen lock. This is a local "
              "lock in this app on this device, not encryption: hidden chats stay in your Telegram account and remain "
              "visible in other Telegram clients.\n\nIf you forget the PIN, you can get back in with a fingerprint (or "
              "another biometric your phone counts as strong; on many phones face unlock is not) as long as no "
              "fingerprint or face has been added or removed since you set the PIN, or with the Chat Lock master "
              "pincode, if that master was already set when you set the vault PIN (if you create the master later, "
              "change the vault PIN once to link them). The device screen lock is not accepted. Without one of these, "
              "a forgotten PIN cannot be recovered.\n\nHidden chats and Chat Lock stay on this device only — they do "
              "not transfer to a new device and have to be set up again there.",
        "ru": "Скрытые чаты исчезают из списка в этом приложении и открываются только после ввода PIN хранилища. "
              "Чтобы скрыть чат, зажмите его и выберите «Добавить в хранилище» в меню ⋮. При выключении "
              "запрашивается PIN, и все скрытые чаты возвращаются.\n\nPIN хранилища не связан с блокировкой экрана "
              "устройства. Это локальная блокировка в этом приложении на этом устройстве, а не шифрование: скрытые "
              "чаты остаются в вашем аккаунте Telegram и видны в других клиентах Telegram.\n\nЕсли вы забыли PIN, "
              "войти можно по отпечатку пальца (или другой биометрии, которую телефон считает надёжной; "
              "распознавание лица на многих телефонах к ней не относится), пока после установки PIN не добавляли и "
              "не удаляли отпечатки или лица, либо по главному пин-коду блокировки чатов, если он уже был задан, "
              "когда вы устанавливали PIN хранилища (если главный пин-код создан позже, один раз смените PIN "
              "хранилища, чтобы связать их). Код блокировки экрана не принимается. Без этого забытый PIN "
              "восстановить нельзя.\n\nСкрытые чаты и блокировка чатов хранятся только на этом устройстве — на новое "
              "устройство они не переносятся, там их нужно настроить заново.",
        "uz": "Yashirin chatlar shu ilovadagi roʻyxatdan yoʻqoladi va faqat seyf PIN'i kiritilgandan keyin ochiladi. "
              "Chatni yashirish uchun uni bosib turing va ⋮ menyusida \"Seyfga qoʻshish\"ni tanlang. Oʻchirganda PIN "
              "soʻraladi va barcha yashirin chatlar qaytadi.\n\nSeyf PIN'i qurilma ekran qulfidan boshqa. Bu shu "
              "qurilmadagi ilova ichidagi lokal qulf, shifrlash emas: yashirin chatlar Telegram hisobingizda qoladi va "
              "boshqa Telegram mijozlarida koʻrinaveradi.\n\nPIN'ni unutsangiz, barmoq izi (yoki telefoningiz "
              "ishonchli deb hisoblaydigan boshqa biometrika; koʻp telefonlarda yuz bilan ochish bunga kirmaydi) "
              "bilan kira olasiz — PIN oʻrnatilgandan beri barmoq izi yoki yuz qoʻshilmagan va oʻchirilmagan "
              "boʻlsa — yoki Chat qulfining asosiy pinkodi bilan, agar seyf PIN'ini oʻrnatganingizda asosiy pinkod "
              "allaqachon oʻrnatilgan boʻlsa (asosiy pinkodni keyinroq yaratgan boʻlsangiz, ularni bogʻlash uchun "
              "seyf PIN'ini bir marta oʻzgartiring). Ekran qulfi kodi qabul qilinmaydi. Bularsiz unutilgan PIN'ni "
              "tiklab boʻlmaydi.\n\nYashirin chatlar va Chat qulfi faqat shu qurilmada saqlanadi — yangi qurilmaga "
              "koʻchmaydi, u yerda ularni qaytadan sozlash kerak.",
    },
    "PayWall.Disclosure": {
        "en": "Zenlygram Pro is an auto-renewing subscription. Your Google account is charged when you "
              "confirm the purchase and again at the start of each period, until you cancel. Manage or "
              "cancel it in Google Play > Payments & subscriptions > Subscriptions at least 24 hours "
              "before the period ends.",
        "ru": "Zenlygram Pro — автопродлеваемая подписка. Оплата списывается с аккаунта Google при "
              "подтверждении покупки и затем в начале каждого периода, пока вы не отмените подписку. "
              "Управлять ею можно в Google Play > Платежи и подписки > Подписки не позднее чем за 24 часа "
              "до конца периода.",
        "uz": "Zenlygram Pro — avtomatik yangilanadigan obuna. Toʻlov xaridni tasdiqlaganingizda va keyin "
              "har bir davr boshida Google hisobingizdan yechiladi, siz bekor qilmaguningizcha. Uni "
              "Google Play > Toʻlovlar va obunalar > Obunalar boʻlimida, davr tugashiga kamida 24 soat "
              "qolganda boshqaring.",
    },
    "Settings.Pro.Restore.NothingFound": {
        "en": "No Zenlygram Pro purchase was found on this Google account. If you paid with a different "
              "one, switch to it in Google Play and try again.",
        "ru": "В этом аккаунте Google покупок Zenlygram Pro не найдено. Если вы платили с другого — "
              "переключитесь на него в Google Play и повторите.",
        "uz": "Bu Google hisobida Zenlygram Pro xaridi topilmadi. Boshqa akkaunt bilan to'lagan "
              "bo'lsangiz, Google Play'da unga o'ting va qayta urinib ko'ring.",
    },
    "PayWall.SessionBackup.Notice": {
        "en": "Log-in to accounts without code, even after reinstall on this device. Kept in this "
              "device's secure storage.",
        "ru": "Вход в аккаунты без кода, даже после переустановки на этом устройстве. Хранится в "
              "защищённом хранилище устройства.",
        "uz": "Kod kiritmasdan, hatto shu qurilmada qayta o'rnatgandan keyin ham hisoblarga kiring. "
              "Qurilmaning himoyalangan xotirasida saqlanadi.",
    },
}

# Uzbek apostrophes, one style for every Uzbek string (the iOS strings mix ', ‘, ’, ʻ and ʼ). Official orthography:
# oʻ / gʻ with U+02BB, the tutuq belgisi with U+02BC (maʼlumot); a plain ' between a foreign word and its Uzbek
# suffix (Zenlygram'ga, PIN'i). Only an apostrophe between two letters of one word is touched (quotes are not).
UZ_APOSTROPHES = "'\u2018\u2019\u02bb\u02bc`"
# Words with a tutuq belgisi (lower case, written with '): a word starting with one of these gets U+02BC.
UZ_TUTUQ_WORDS = ("ma'lum", "noma'lum", "ba'z", "sun'iy", "qat'i", "ta'min", "ta'sir", "ta'rif", "ta'kid", "ta'lim",
                  "ta'qib", "e'lon", "e'tibor", "e'tirof", "san'at", "ma'no", "mas'ul", "jur'at", "in'om", "va'da",
                  "da'vo", "she'r", "mo''", "ra'y")
# Foreign words ending in o / g that take an Uzbek suffix after an apostrophe (Pro'ga): no oʻ / gʻ there.
UZ_FOREIGN_OG = ("pro",)
_UZ_LETTER = r"[^\W\d_\u02bb\u02bc]"
_UZ_WORD = re.compile(r"%s+(?:[%s]%s+)+" % (_UZ_LETTER, UZ_APOSTROPHES, _UZ_LETTER))
_UZ_SPLIT = re.compile("([%s])" % UZ_APOSTROPHES)


def uz_apostrophes(text):
    def fix(match):
        word = match.group(0)
        plain = _UZ_SPLIT.sub("'", word).lower()
        parts = _UZ_SPLIT.split(word)
        out = parts[0]
        for i in range(1, len(parts), 2):
            before = parts[i - 1]
            if before[-1].lower() in "og" and not (i == 1 and before.lower() in UZ_FOREIGN_OG):
                mark = "\u02bb"
            elif plain.startswith(UZ_TUTUQ_WORDS):
                mark = "\u02bc"
            else:
                mark = "'"
            out += mark + parts[i + 1]
        return out
    return _UZ_WORD.sub(fix, text)


# iOS-only words that must not survive the wording table.
IOS_TERM_RE = re.compile(r"Face ID|Touch ID|iPhone|iPad|iCloud|Keychain|Связк[аиуе] ключей|App Store|"
                         r"Apple|\biOS\b|CallKit|Quick Start|«Быстр\w* начал\w*»|device passcode|"
                         r"код-парол|kod-parol")
# Keys whose iOS word is a name, not the platform: no wording table, no warning.
KEEP_IOS_WORDING = {
    "Settings.Folders.BottomTabStyle.ios",      # the name of a tab bar style ("iOS")
    "Settings.Transcription.Backend.apple",     # the name of a speech service ("Apple")
}

PLURAL_FORMS = ("zero", "one", "two", "few", "many", "other")   # as in TelegramStringsTask


class ConversionError(Exception):
    pass


# ---------------------------------------------------------------------------------------------
# .strings parsing
# ---------------------------------------------------------------------------------------------
def parse_strings(text, source):
    """Parse an Apple .strings file into an OrderedDict of key -> unescaped value."""
    i, n = 0, len(text)
    result = OrderedDict()

    def fail(message):
        line = text.count("\n", 0, i) + 1
        raise ConversionError("%s:%d: %s" % (source, line, message))

    def skip():
        nonlocal i
        while i < n:
            if text[i].isspace():
                i += 1
            elif text.startswith("/*", i):
                end = text.find("*/", i + 2)
                if end < 0:
                    fail("unterminated comment")
                i = end + 2
            elif text.startswith("//", i):
                end = text.find("\n", i)
                i = n if end < 0 else end + 1
            else:
                break

    def read_quoted():
        nonlocal i
        if i >= n or text[i] != '"':
            fail("expected a quoted string")
        i += 1
        out = []
        while True:
            if i >= n:
                fail("unterminated string")
            c = text[i]
            if c == '"':
                i += 1
                return "".join(out)
            if c != "\\":
                out.append(c)
                i += 1
                continue
            if i + 1 >= n:
                fail("dangling backslash")
            e = text[i + 1]
            i += 2
            if e == "n":
                out.append("\n")
            elif e == "t":
                out.append("\t")
            elif e == "r":
                out.append("\r")
            elif e in "\"'\\":
                out.append(e)
            elif e in "Uu":
                digits = text[i:i + 4]
                if not re.fullmatch(r"[0-9A-Fa-f]{4}", digits):
                    fail("bad \\U escape")
                out.append(chr(int(digits, 16)))
                i += 4
            else:
                out.append(e)
        # unreachable

    while True:
        skip()
        if i >= n:
            break
        key = read_quoted()
        skip()
        if i >= n or text[i] != "=":
            fail("expected '=' after key %r" % key)
        i += 1
        skip()
        value = read_quoted()
        skip()
        if i >= n or text[i] != ";":
            fail("expected ';' after value of %r" % key)
        i += 1
        if key in result:
            fail("duplicate key %r" % key)
        # join UTF-16 surrogate pairs written as two \U escapes
        value = value.encode("utf-16", "surrogatepass").decode("utf-16")
        result[key] = value
    return result


# ---------------------------------------------------------------------------------------------
# Placeholders
# ---------------------------------------------------------------------------------------------
# No space flag: "50% off" is a literal percent sign, not "% o".
SPEC_RE = re.compile(r"%(?:(\d+)\$)?([-#+0]*)(\d+)?(\.\d+)?(hh|h|ll|l|q|z|t|j|L)?([@diouxXfFeEgGcCsSaA])")
CONVERSIONS = {"@": "s", "s": "s", "S": "s", "d": "d", "i": "d", "u": "d", "D": "d", "U": "d",
               "o": "o", "x": "x", "X": "X", "f": "f", "F": "f", "e": "e", "E": "E", "g": "g",
               "G": "G", "a": "a", "A": "A", "c": "c", "C": "c"}


def convert_placeholders(text, where):
    """iOS format string -> Java format string. Returns (text, signature)."""
    parts = []          # literal strings and spec dicts
    i = 0
    while i < len(text):
        j = text.find("%", i)
        if j < 0:
            parts.append(text[i:])
            break
        parts.append(text[i:j])
        if text.startswith("%%", j):
            parts.append({"percent": True})
            i = j + 2
            continue
        m = SPEC_RE.match(text, j)
        if not m:                                   # a lone % is a literal percent sign
            parts.append({"percent": True})
            i = j + 1
            continue
        index, flags, width, precision, _length, conv = m.groups()
        parts.append({"index": int(index) if index else None, "flags": flags, "width": width or "",
                      "precision": precision or "", "conv": CONVERSIONS[conv]})
        i = m.end()

    specs = [p for p in parts if isinstance(p, dict) and not p.get("percent")]
    positional = [s for s in specs if s["index"] is not None]
    if positional and len(positional) != len(specs):
        raise ConversionError("%s: mixes numbered and unnumbered placeholders" % where)
    if not positional:                              # always positional: %1$s, %2$s (aapt2 needs it for 2+)
        for number, spec in enumerate(specs, 1):
            spec["index"] = number

    out = []
    for p in parts:
        if isinstance(p, str):
            out.append(p)
        elif p.get("percent"):
            out.append("%%" if specs else "%")
        else:
            index = "%d$" % p["index"] if p["index"] is not None else ""
            out.append("%" + index + p["flags"] + p["width"] + p["precision"] + p["conv"])
    signature = sorted(((s["index"] or 1), s["conv"]) for s in specs)
    return "".join(out), signature


# ---------------------------------------------------------------------------------------------
# Android wording
# ---------------------------------------------------------------------------------------------
_COMPILED_WORDING = {lang: [(re.compile(p), r) for p, r in rules] for lang, rules in ANDROID_WORDING.items()}
_SENTENCE_START = re.compile(r"(?:^|[.!?…][\"»”)]?\s+|\n\s*)$")


def apply_wording(key, lang, text):
    if key in KEEP_IOS_WORDING:
        return text
    override = ANDROID_OVERRIDES.get(key, {}).get(lang)
    if override is not None:
        return override
    for pattern, replacement in _COMPILED_WORDING[lang]:
        def substitute(match, replacement=replacement):
            new = match.expand(replacement)
            if new[:1].islower() and _SENTENCE_START.search(match.string[:match.start()]):
                new = new[:1].upper() + new[1:]
            return new
        text = pattern.sub(substitute, text)
    return text


# ---------------------------------------------------------------------------------------------
# Section headers
# ---------------------------------------------------------------------------------------------
def is_upper_header(text):
    cased = [c for c in text if c.lower() != c.upper()]
    return len(cased) >= 2 and all(c == c.upper() for c in cased)


_WORD_RE = re.compile(r"[^\W\d_]+(?:['’ʼʻ][^\W\d_]+)*")


def sentence_case(text):
    out, pos, first = [], 0, True
    for m in _WORD_RE.finditer(text):
        out.append(text[pos:m.start()])
        word = m.group(0)
        if word in HEADER_KEEP_WORDS:
            word = HEADER_KEEP_WORDS[word]
        else:
            word = word.lower()
            if first:
                word = word[:1].upper() + word[1:]
        first = False
        out.append(word)
        pos = m.end()
    out.append(text[pos:])
    return "".join(out)


def header_name(ios_key):
    name = android_name(ios_key)[len(PREFIX):]
    if name.endswith("_Header"):
        name = name[:-len("_Header")]
    return HEADER_PREFIX + name


def android_name(ios_key):
    if ios_key in KEY_RENAMES:
        return KEY_RENAMES[ios_key]
    return PREFIX + ios_key.replace(".", "_")


# ---------------------------------------------------------------------------------------------
# XML
# ---------------------------------------------------------------------------------------------
def android_escape(text, where):
    """Escape for a <string> body so that both aapt2 and buildSrc TelegramStringsTask
    (Groovy XmlParser + normalizeXmlString: \\n -> newline, then every backslash removed)
    end up with exactly `text`."""
    if "\\" in text:
        raise ConversionError("%s: a backslash cannot pass TelegramStringsTask (it drops all backslashes)" % where)
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    for c in text:
        if ord(c) < 0x20 and c != "\n":
            raise ConversionError("%s: control character U+%04X" % (where, ord(c)))
    out = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    out = out.replace("'", "\\'").replace('"', '\\"').replace("\n", "\\n")
    if out[:1] in ("@", "?"):
        out = "\\" + out
    return out


def xml_comment(text):
    return "<!-- " + text.replace("--", "- -") + " -->"


def render(lang, entries, source_digest):
    lines = ['<?xml version="1.0" encoding="utf-8"?>']
    lines.append(xml_comment("GENERATED by %s - DO NOT EDIT. Source: iOS %s (sha256 %s). "
                             "Change the iOS strings or the script's Android tables, then re-run it."
                             % (SCRIPT_REL, IOS_STRINGS.format(lang=lang), source_digest)))
    lines.append(xml_comment("Read only through LocaleController.getString / formatString (R.string.ZG_*): "
                             "the build serves these from assets/localization_*.bin."))
    lines.append("<resources>")
    section = None
    for name, value, group in entries:
        if group != section:
            section = group
            lines.append("")
            lines.append("    " + xml_comment(SECTION_TITLES[group]))
        if value != value.strip():
            lines.append("    " + xml_comment("leading/trailing space is intentional (kept by TelegramStringsTask)"))
        lines.append('    <string name="%s">%s</string>' % (name, android_escape(value, "%s/%s" % (lang, name))))
    lines.append("</resources>")
    return "\n".join(lines) + "\n"


SECTION_TITLES = {
    "ios": "iOS SGLocalizable.strings: key A.B.C is ZG_A_B_C",
    "header": "Section headers in sentence case (ZG_Header_*), made from the upper-case iOS headers",
    "extra": "Android-only brand strings (LocaleController brand override: org.telegram.zenlygram.branding.ZgStrings)",
}


# ---------------------------------------------------------------------------------------------
# Name / hash collision checks
# ---------------------------------------------------------------------------------------------
def java_hash(s):
    h = 0
    data = s.encode("utf-16-be")
    for k in range(0, len(data), 2):
        h = (31 * h + ((data[k] << 8) | data[k + 1])) & 0xFFFFFFFF
    return h - 0x100000000 if h & 0x80000000 else h


def existing_string_names(android_root, generated_paths):
    """name -> files, for every <string> in the app modules' values*/ XML files, except our outputs."""
    names = {}
    for module in sorted(android_root.glob("TMessagesProj*")):
        for xml_file in sorted(module.glob("src/*/res/values*/*.xml")):
            if xml_file.resolve() in generated_paths:
                continue
            try:
                root = ET.parse(str(xml_file)).getroot()
            except ET.ParseError:
                continue
            if root.tag != "resources":
                continue
            for node in root.iter("string"):
                name = node.get("name")
                if name:
                    names.setdefault(name, []).append(xml_file.relative_to(android_root).as_posix())
    return names


def check_collisions(new_names, existing):
    problems = []
    for name in new_names:
        if name in existing:
            problems.append("%s already exists in %s" % (name, ", ".join(sorted(set(existing[name])))))
    namespace = set(existing) | set(new_names)
    for name in list(namespace):                    # synthetic plural roots, as TelegramStringsTask adds
        root, _, suffix = name.rpartition("_")
        if root and suffix in PLURAL_FORMS:
            namespace.add(root)
    by_hash = {}
    for name in sorted(namespace):
        by_hash.setdefault(java_hash(name), []).append(name)
    new_set = set(new_names)
    for h, names in sorted(by_hash.items()):
        if len(names) > 1 and any(n in new_set or n.startswith(PREFIX) for n in names):
            problems.append("String.hashCode() collision 0x%08X: %s" % (h & 0xFFFFFFFF, ", ".join(names)))
    return problems


# ---------------------------------------------------------------------------------------------
def read_sources(ios_root, ios_rev):
    sources = {}
    for lang in LANGS:
        rel = IOS_STRINGS.format(lang=lang)
        path = ios_root / rel
        if ios_rev is None and path.is_file():
            data = path.read_bytes()
        else:
            rev = ios_rev or "HEAD"
            try:
                data = subprocess.run(["git", "-C", str(ios_root), "show", "%s:%s" % (rev, rel)],
                                      check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout
            except (OSError, subprocess.CalledProcessError) as e:
                raise ConversionError("cannot read %s (%s)" % (path, getattr(e, "stderr", b"").decode().strip() or e))
            print("note: read %s from git %s" % (rel, rev))
        sources[lang] = data
    return sources


def decode_strings_file(data):
    if data.startswith((b"\xff\xfe", b"\xfe\xff")):         # classic UTF-16 .strings
        return data.decode("utf-16")
    return data.decode("utf-8-sig")


def build(sources):
    parsed = {lang: parse_strings(decode_strings_file(sources[lang]), IOS_STRINGS.format(lang=lang))
              for lang in LANGS}
    base = parsed["en"]
    warnings = []
    for lang in LANGS[1:]:
        missing = [k for k in base if k not in parsed[lang]]
        extra = [k for k in parsed[lang] if k not in base]
        if missing:
            warnings.append("%s: %d keys missing, English is used for them: %s" % (lang, len(missing), ", ".join(missing)))
        if extra:
            warnings.append("%s: %d keys not in en.lproj are skipped: %s" % (lang, len(extra), ", ".join(extra)))

    entries = {lang: [] for lang in LANGS}
    changed = OrderedDict()                         # iOS key -> languages changed by the wording table
    leftovers = []
    seen = {}

    def add(lang, name, value, group, origin):
        if name in seen and seen[name] != origin:
            raise ConversionError("name %s made twice (%s and %s)" % (name, seen[name], origin))
        seen[name] = origin
        if lang == "uz":
            value = uz_apostrophes(value)
        entries[lang].append((name, value, group))

    headers = []
    for key in base:
        name = android_name(key)
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            raise ConversionError("%s: %s is not a valid Android resource name" % (key, name))
        signature_en = None
        for lang in LANGS:
            if key not in parsed[lang]:
                continue
            ios_text = parsed[lang][key]
            text = apply_wording(key, lang, ios_text)
            if text != ios_text:
                changed.setdefault(key, []).append(lang)
            if key not in KEEP_IOS_WORDING:
                found = sorted(set(IOS_TERM_RE.findall(text)))
                if found:
                    leftovers.append("%s/%s still says %s" % (lang, key, ", ".join(found)))
            value, signature = convert_placeholders(text, "%s/%s" % (lang, key))
            if lang == "en":
                signature_en = signature
            elif signature != signature_en:
                raise ConversionError("%s/%s: placeholders %s differ from en %s" % (lang, key, signature, signature_en))
            add(lang, name, value, "ios", key)
        if key not in NOT_HEADERS and is_upper_header(base[key]):
            headers.append(key)

    converted = {lang: {n: v for n, v, _ in entries[lang]} for lang in LANGS}
    for key in headers:
        name = header_name(key)
        for lang in LANGS:
            value = converted[lang].get(android_name(key))
            if value is None:
                continue
            add(lang, name, sentence_case(value), "header", key + " (header)")

    for name, values in ANDROID_EXTRA.items():
        for lang in LANGS:
            add(lang, name, values[lang], "extra", "ANDROID_EXTRA")

    return entries, changed, leftovers, warnings, headers


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0],
                                     formatter_class=argparse.RawDescriptionHelpFormatter, epilog=__doc__)
    default_ios = os.environ.get("ZENLYGRAM_IOS_ROOT") or str(Path.home() / "zenlygram")
    parser.add_argument("--ios-root", default=default_ios, help="iOS repo (default: %(default)s)")
    parser.add_argument("--ios-rev", default=None, help="read the iOS files from this git revision")
    parser.add_argument("--android-root", default=str(Path(__file__).resolve().parents[2]),
                        help="zenlygram-android checkout (default: %(default)s)")
    parser.add_argument("--check", action="store_true", help="write nothing; exit 1 if the XML files are out of date")
    parser.add_argument("--verbose", "-v", action="store_true", help="also print each Android wording change")
    args = parser.parse_args(argv)

    android_root = Path(args.android_root).resolve()
    ios_root = Path(args.ios_root).expanduser().resolve()
    res = android_root / RES_DIR
    if not (res / "values" / "strings.xml").is_file():
        print("error: %s is not a zenlygram-android checkout" % android_root, file=sys.stderr)
        return 2

    try:
        sources = read_sources(ios_root, args.ios_rev)
        entries, changed, leftovers, warnings, headers = build(sources)
    except ConversionError as e:
        print("error: %s" % e, file=sys.stderr)
        return 2

    outputs = {lang: res / VALUES_DIR[lang] / OUTPUT_NAME for lang in LANGS}
    generated = {p.resolve() for p in outputs.values()}
    existing = existing_string_names(android_root, generated)
    new_names = [name for name, _, _ in entries["en"]]
    problems = check_collisions(new_names, existing)
    for lang in LANGS[1:]:
        stray = sorted({n for n, _, _ in entries[lang]} - set(new_names))
        if stray:
            problems.append("%s has names that en lacks: %s" % (lang, ", ".join(stray)))
    if problems:
        for p in problems:
            print("error: " + p, file=sys.stderr)
        print("error: fix it with KEY_RENAMES in %s" % SCRIPT_REL, file=sys.stderr)
        return 2

    stale = []
    for lang in LANGS:
        digest = hashlib.sha256(sources[lang]).hexdigest()[:16]
        content = render(lang, entries[lang], digest)
        path = outputs[lang]
        old = path.read_text(encoding="utf-8") if path.is_file() else None
        rel = path.relative_to(android_root).as_posix()
        if old == content:
            print("unchanged  %s (%d strings)" % (rel, len(entries[lang])))
            continue
        stale.append(rel)
        if args.check:
            print("OUT OF DATE %s" % rel)
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(str(path), "w", encoding="utf-8", newline="\n") as f:
            f.write(content)
        print("wrote      %s (%d strings)" % (rel, len(entries[lang])))

    print("headers: %d ZG_Header_* strings" % len(headers))
    print("Android wording changed %d iOS keys (language codes in brackets):" % len(changed))
    for key, langs in changed.items():
        print("  %s -> %s [%s]" % (key, android_name(key), ",".join(langs)))
        if args.verbose:
            for lang in langs:
                value = next(v for n, v, g in entries[lang] if n == android_name(key))
                print("      %s: %s" % (lang, value.replace("\n", "\\n")))
    for w in warnings:
        print("warning: " + w)
    for left in leftovers:
        print("warning: iOS wording left (extend ANDROID_WORDING): " + left)
    print("checked: %d names, no duplicate names, no String.hashCode() collisions" % len(new_names))
    if args.check and stale:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
