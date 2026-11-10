# Translations — eleven interface languages

French is both the default interface language and the source language. Keys in
the bundled catalog **are** the French source strings, so a missing key means the
user sees raw French on an English, German, Korean… screen.

- Catalog: `app/src/main/assets/translations.json`, one table per language
  (`en`, `hi`, `ja`, `zh`, `ru`, `it`, `es`, `de`, `pt`, `ko`).
- Runtime lookup: `AppText` / `UiTextDictionary`, including `{0}` argument
  reordering, so templates may move placeholders but must never drop them.
- Visual-refresh labels live in `app/src/main/assets/visual-refresh-translations.json`
  and are merged on top of the catalog.

## Adding or changing a string

1. Write the French literal in the UI code as usual.
2. Add it as a key to **every** table, in the same order.
3. Keep placeholders (`{0}`, `{1}`), brand names, units (`Hz`), code-ish tokens
   and the leading/trailing spaces of composite labels intact.
4. Run `python tools/audit_ui_texts.py --check` — it fails while a user-facing
   message has no bundled translation in any language.

## Guard rails

| Command | Purpose |
| --- | --- |
| `python tools/audit_ui_texts.py` | Scan UI/message sources, write `tools/ui-messages.json` and `tools/ui-messages-missing.json`. |
| `python tools/audit_ui_texts.py --check` | Same scan, exit 1 when a user-facing message is missing or when an ignore entry went stale. CI runs this. |
| `./gradlew :app:testMobileReleaseUnitTest` | `UiTextDictionaryTest` enforces identical key sets, non-blank values and placeholder preservation across all ten tables. |

`tools/ui-text-ignored.json` maps every string the scanner reports but that is
**not** user text (cache keys, HTTP headers, Compose animation tags, file
patterns) to a reason. It is an allowlist, not a place to park real copy: CI also
fails when one of its entries becomes translated, so the list cannot rot.

## Machine translation is not shippable

`tools/complete_ui_translations.py` drafts the missing labels with the public
Google endpoint and writes `tools/machine-translations.proposal.json` (ignored by
Git). Do not ship that file as-is: it turns "Reprendre" into *Lebenslauf* /
*Currículum* / *이력서*, renders "DISCORD" as *不和谐*, and prefixes some Chinese
values with `#`. `--apply` exists for emergencies only and always requires a full
human review afterwards.
