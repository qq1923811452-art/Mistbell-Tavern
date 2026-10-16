# Mistbell 3.0 Content Format v1

This document defines the new, Mistbell-specific formats for worldbooks and character cards. The format is intentionally independent of legacy application classes. Importers are one-way adapters from existing exports; old source code is not reused.

## Goals

- Keep stable character instructions byte-for-byte stable across turns where possible, so prompt caching is not needlessly invalidated.
- Keep worldbook selection deterministic, including always-on entries with no keywords.
- Separate character definition from worldbook entries and volatile conversation state.
- Preserve a reliable import path for common SillyTavern-style worldbooks and Character Card V1/V2 JSON.
- Fail explicitly on unsupported schema versions and malformed required fields rather than silently dropping content.

## Mistbell worldbook v1

Top-level fields:

- `format`: exactly `mistbell-worldbook`
- `schemaVersion`: integer `1`
- `entries`: ordered array of entries

Each entry has:

- `id`, `title`, `content`: required non-empty strings
- `keys`, `secondaryKeys`: arrays of non-empty strings
- `requireSecondaryMatch`: when true, at least one primary and one secondary keyword must match
- `enabled`: disabled entries never activate
- `constant`: enabled entries are always selected, even with no keywords
- `recursive`: entry content may be scanned for further activation
- `priority`, `order`: deterministic ordering controls

Example:

```json
{
  "format": "mistbell-worldbook",
  "schemaVersion": 1,
  "entries": [
    {
      "id": "setting-core",
      "title": "Setting rules",
      "content": "The city is built around a tidal observatory.",
      "keys": [],
      "secondaryKeys": [],
      "requireSecondaryMatch": false,
      "enabled": true,
      "constant": true,
      "recursive": false,
      "priority": 100,
      "order": 0
    }
  ]
}
```

Selection policy in v1:

1. Exclude disabled entries.
2. Include all enabled constant entries, whether or not they have keywords.
3. Match keywords case-insensitively against the supplied text.
4. If `requireSecondaryMatch` is true, require a secondary keyword match too.
5. Expand recursively only through entries marked `recursive`, with a caller-supplied depth bound and ID-based cycle protection.
6. Return a deterministic order: constant entries first, then descending priority, ascending order, then original document order.
7. Do not truncate entry text. Token budgeting belongs to a later prompt-planning layer and must report omissions explicitly.

## Mistbell character card v1

Top-level fields:

- `format`: exactly `mistbell-character`
- `schemaVersion`: integer `1`
- `character`: object containing the card

The character object stores `id`, `name`, `description`, `personality`, `scenario`, `firstMessage`, `exampleDialogue`, `systemPrompt`, `postHistoryInstructions`, `alternateGreetings`, `tags`, `creator`, and `version`.

The stable definition builder emits identity and stable instruction fields in a fixed order. First messages, alternate greetings, conversation history, memories, and activated worldbook entries remain separate from that stable definition; callers should append dynamic content after the stable prefix.

## Legacy compatibility

- Worldbook import accepts an `entries` object or array and recognizes common `uid`, `comment`/`name`, `content`, `key`, `keysecondary`, `selective`, `enabled`, `disable`, `constant`, `always_on`, `recursive`, and `order` fields.
- Character import accepts V1 root fields and V2 nested `data` fields, including `first_mes`, `mes_example`, `system_prompt`, `post_history_instructions`, `alternate_greetings`, and `character_version`.
- An embedded legacy `character_book` is imported into the independent worldbook model rather than being silently discarded.
- The importer does not rewrite or delete source files. Import results must be reviewed and saved as a new Mistbell-format document.

## Current scope and non-goals

This PR establishes domain models, codecs, compatibility importers, deterministic activation, and unit tests. UI editing, persistence, recursive folder scanning, token-count estimation, and chat integration are follow-up work in the worldbook/character project; they are not claimed as implemented here.
