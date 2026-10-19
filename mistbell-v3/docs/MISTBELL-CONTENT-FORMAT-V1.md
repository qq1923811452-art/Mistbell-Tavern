# Mistbell 3.0 Character and Worldbook Format Notes

This document describes the **current implementation** in the independent `mistbell-v3` app. It is an implementation guide, not a promise that every SillyTavern field is losslessly round-tripped. Legacy app classes and PR #29 Room schemas are outside this format.

## Character cards

### Internal model

The current `CharacterCard` model stores:

- `name`, `description`, `personality`, and `scenario`
- `firstMessage`, `exampleDialogue`, `creatorNotes`
- `systemPrompt`, `postHistoryInstructions`
- `alternateGreetings`, `tags`, and `creator`
- `characterBookJson`: optional raw JSON for an embedded `character_book`

The private character library stores cards in app-private files. File names use generated IDs rather than character names. Import does not delete or modify the selected source file.

### JSON compatibility

- Import accepts root-level legacy fields and cards with a nested `data` object.
- Common SillyTavern fields include `first_mes`, `mes_example`, `creator_notes` / `creatorcomment`, `system_prompt`, `post_history_instructions`, `alternate_greetings`, `tags`, and `creator`.
- Embedded `character_book` JSON is retained on the character model so prompt/worldbook integration can consume it.
- Export emits a SillyTavern `chara_card_v2` JSON envelope with `spec_version: "2.0"`.
- Import currently operates on JSON documents. PNG metadata extraction and CHARX archive unpacking are not implemented by this importer.

### Stable prompt definition

`CharacterCard.stableDefinition()` emits the trimmed name, description, personality, scenario, system instructions, and post-history instructions in a fixed order, separated by blank lines. First messages, alternate greetings, conversation history, memories, and activated worldbook entries are not part of this stable definition; prompt assembly handles variable content separately.

## Worldbooks

The current worldbook domain model is `WorldBook` plus ordered `WorldBookEntry` records. Entry fields include:

- Identity and text: `id`, `bookId`, `comment`, `content`
- Activation: `keys`, `secondaryKeys`, `requireSecondaryMatch`, `recursive`, `constant`, `disable`
- Ordering and placement: `order`, `insertPosition`, `depth`, `depthRole`
- Activation controls: `probability`, `sticky`, `cooldown`, `delay`, `groupName`

The SillyTavern World Info parser accepts an `entries` object or array and recognizes common fields such as `uid`, `comment`, `content`, `key`, `keysecondary`, `selective`, `enabled` / `disable`, `constant`, `recursive`, `order`, `position`, and `depth`. Numeric placement `4` maps to depth placement, including an explicit depth of zero. Probability and depth-role metadata are normalized into the independent model.

Prompt assembly keeps the stable prefix ahead of variable character/worldbook/memory content, applies anchored placements, and injects depth entries into conversation history. A separate bounded history budget limits the selected recent messages.

## Compatibility and limitations

- These notes track the current `mistbell-v3` implementation, not the superseded PR #60 model or its earlier custom-format claims.
- Unknown or unsupported legacy fields may not survive import/export. Validate important cards and worldbooks after importing.
- PNG/CHARX extraction, full 2.9 settings/preview/backup screens, and session JSON/JSONL interchange are separate follow-up work.
- No Room entity, DAO, migration, or PR #29 schema JSON is defined or modified by this document.
