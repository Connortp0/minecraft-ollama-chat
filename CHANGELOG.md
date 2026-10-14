# Changelog

## 1.0.0 - first release

- `/ai <message>` chat with a local Ollama model, answered on background threads so the game never freezes
- Multiple named chats per player: new, list, switch, delete, rename, clear, history, summary, export
- Automatic memory: the model saves, updates and forgets facts about the player while you chat
- Rolling summaries so long conversations keep their important parts
- Personas per chat (built-in presets, `/ai persona set`, and custom ones in the config)
- Per-player model picker with clickable list, tool-support detection and partial-name matching
- `/ai retry`, `/ai undo`, `/ai stop`, `/ai status`, `/ai reload`
- Settings: `/ai settings learn|notes on|off`
- Config file `config/ai-chat.json` with limits, cooldown, request queue and server rules
- Chats saved per world in `ai_chats.json` with atomic writes, a backup, and protection against corrupt files
