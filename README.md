# Ollama Chat (Fabric mod)

A server-side Fabric mod that lets players talk to a local [Ollama](https://ollama.com) model from Minecraft chat with `/ai`. It is a general-purpose AI chat, not Minecraft-specific. The mod adds multiple conversations per player, long-term memory of facts about the player, automatic summaries of long chats, personas, model selection, and saving to the world folder.

Nothing is sent to the internet. All requests go to the Ollama server you configure (default `http://localhost:11434`).

---

## Requirements

- Fabric Loader and Fabric API for Minecraft 26.x, with Java 25
- Ollama installed and running on the machine that runs the server (or singleplayer game)
- At least one model pulled, for example `ollama pull qwen2.5:1.5b`

Gson is already bundled with Minecraft, so there are no extra libraries.

---

## Quick start

1. Start Ollama and pull a model: `ollama pull qwen2.5:1.5b`
2. Start the game or server. The log should say `Connected to Ollama <version>`.
3. In chat, type `/ai hello`. The first time, you get a clickable list of installed models. Click one and your question is sent automatically.
4. Keep chatting with `/ai <message>`. Tell it things about yourself ("my name is Alex, I like building castles") and it remembers them.

---

## How it works

### Conversations
Each player has any number of named chats (default chat: `default`). Every chat has its own history, summary and persona. Replies are shown as `[chat-name] AI: ...`.

### Short-term memory
The most recent messages are sent to the model with every question, limited by a character budget so the context window can't overflow. When a chat reaches 24 messages, the oldest ones are summarised in the background and folded into a running summary. The newest 12 messages stay word for word.

### Long-term memory (facts)
Facts about the player (name, preferences, projects) are stored per player and shared across all their chats. Players never have to save anything: the model does it itself through three tools, `remember_fact`, `update_fact` and `forget_fact`, while you chat. It sees the numbered fact list, so "actually I like green now" replaces an old fact instead of adding a second one, and "forget my name" removes one. A grey `Memory: ...` line shows each change, and you can ask "what do you know about me?" at any time.

If a model doesn't support tools, chat still works but it only remembers the current conversation. The player gets a one-time note.

### Models
Each player picks their own model from the models installed in Ollama. The list marks which models can learn facts. If someone asks a question before choosing, the question is kept and sent as soon as they pick.

### Threading
Ollama requests run on background threads, so the game never freezes while the model thinks. Results come back to the server thread before anything is changed or sent. Only one request runs per chat at a time.

---

## Commands

### Talking
| Command | What it does |
|---|---|
| `/ai <message>` | Ask the AI something |
| `/ai ask <message>` | Same, for messages that start with a command word (e.g. `/ai ask help me plan a trip`) |
| `/ai retry` | Ask the last question again and replace the answer |
| `/ai undo` | Remove the last question and answer from the chat |
| `/ai stop` | Cancel the answer that is being written |
| `/ai help` | Show the command list |
| `/ai status` | Show Ollama status, your model, chat, facts and session statistics |
| `/ai reload` | Re-read `config/ai-chat.json` |

### Chats
| Command | What it does |
|---|---|
| `/ai chat` or `/ai chat list` | List your chats (click one to switch) |
| `/ai chat new <name>` | Create a chat and switch to it |
| `/ai chat switch <name>` | Switch to a chat |
| `/ai chat rename <new_name>` | Rename the current chat |
| `/ai chat delete <name>` | Delete a chat (not while it is busy) |
| `/ai chat clear` | Wipe the current chat's messages and summary (facts and persona are kept) |
| `/ai chat history [count]` | Show the last messages (default 6, max 50) |
| `/ai chat summary` | Show the summary of older messages |
| `/ai chat export` | Save a transcript to `ai_exports/` in the world folder |

Chat names can use letters, numbers and `_ . + -`, up to 24 characters.

### Memory
Memory is automatic and managed by chatting. The only command is a full reset:

| Command | What it does |
|---|---|
| `/ai forget all` | Wipe everything the AI has learned about you |

### Personas (per chat)
| Command | What it does |
|---|---|
| `/ai persona` | Show the current persona and a clickable list |
| `/ai persona <name>` | Use a preset |
| `/ai persona set <text>` | Write your own instructions (up to 400 characters) |
| `/ai persona reset` | Back to the default |

Built-in presets: `default`, `concise`, `teacher`, `coder`, `storyteller`, `pirate`. You can add your own in the config.

### Models and settings
| Command | What it does |
|---|---|
| `/ai model` | Show your current model |
| `/ai model list` | List installed models (click one to use it) |
| `/ai model use <name>` | Switch model. A partial name works if it matches only one, e.g. `qwen2.5` |
| `/ai settings` | Show your settings |
| `/ai settings learn on\|off` | Let the AI save facts on its own |
| `/ai settings notes on\|off` | Show or hide the grey `Memory:` lines |

---

## Configuration

The file `config/ai-chat.json` is created on first start and rewritten on load, so new options always appear. Edit it and run `/ai reload`. Invalid values are clamped, and an unreadable file leaves the old settings in place.

| Option | Default | Meaning |
|---|---|---|
| `ollamaUrl` | `http://localhost:11434` | Where Ollama runs |
| `defaultModel` | `qwen2.5:1.5b` | Recommended model shown in the picker |
| `autoUseDefaultModel` | `false` | New players use `defaultModel` automatically if it is installed |
| `temperature` | `0.4` | Randomness of answers |
| `numCtx` | `6144` | Context window, in tokens |
| `maxReplyTokens` | `400` | Cap on reply length (0 = no cap) |
| `requestTimeoutSeconds` | `120` | Give up on a request after this long |
| `keepAlive` | `10m` | How long Ollama keeps the model loaded |
| `historyCharBudget` | `6000` | Most history characters sent per question |
| `summarizeAtMessages` | `24` | Summarise when a chat reaches this many messages |
| `keepRecentMessages` | `12` | Messages kept word for word after summarising |
| `hardMaxMessages` | `60` | Safety cap if summarising keeps failing |
| `maxFacts` | `40` | Facts per player |
| `maxFactLength` | `120` | Characters per fact |
| `maxChatsPerPlayer` | `20` | Chats per player |
| `maxQuestionLength` | `500` | Characters per message |
| `cooldownSeconds` | `2` | Minimum time between a player's questions |
| `workerThreads` | `2` | Parallel Ollama requests (needs a restart to change) |
| `maxQueuedRequests` | `6` | Requests allowed at once across all players |
| `maxReplyLines` | `12` | Reply lines shown before it is cut off |
| `logConversations` | `true` | Log questions and answers to the server log |
| `serverRules` | `""` | Extra rules added to every system prompt |
| `customPersonas` | `{}` | Your own personas, as `"name": "instructions"` |

Example custom persona:

```json
"customPersonas": {
  "editor": "You proofread text. Reply only with the corrected version."
}
```

---

## Where data is stored

| Path | Contents |
|---|---|
| `config/ai-chat.json` | Settings |
| `saves/<world>/ai_chats.json` | All players' chats, summaries, facts, model choice and settings |
| `saves/<world>/ai_chats.json.bak` | Copy of the file as it was when the world opened |
| `saves/<world>/ai_chats.json.corrupt-<time>` | An unreadable file, moved aside so it isn't overwritten |
| `saves/<world>/ai_exports/` | Transcripts from `/ai chat export` |

Saves happen after every change, are written to a temporary file first, and a final save runs when the server stops. Data is separate per world, and one world's chats never carry into another. Old save files from earlier versions of the mod still load.

---

## Project layout

```
com/ollamachat/
├── OllamaChatMod.java     Entry point: loads config, registers commands and storage
├── AiCommand.java         The /ai command tree and the simple command handlers
├── AiService.java         Asking, retry, stop, summaries, model picking, status
├── config/
│   └── AiConfig.java      Reads and validates config/ai-chat.json
├── ollama/
│   ├── OllamaClient.java  HTTP calls, prompt building, tool calling, summarising
│   ├── OllamaException.java
│   └── RequestHandle.java Lets a running request be cancelled
├── chat/
│   ├── PlayerData.java    One player's chats, facts, model and settings
│   ├── ChatSession.java   One conversation: messages, summary, persona
│   ├── ChatManager.java   All players' data, keyed by UUID
│   ├── ChatStorage.java   Loading and saving
│   ├── ChatExporter.java  Transcript export
│   ├── ChatMessage.java   A single message
│   ├── FactChange.java    A fact change requested by the model
│   └── Personas.java      Built-in and custom personas
└── util/
    └── Msg.java           Coloured, clickable chat output and markdown clean-up
```

### What happens when someone types `/ai hello`

1. `AiCommand` passes the message to `AiService`.
2. `AiService` checks the player has a model, the cooldown, the queue limit and that the chat isn't busy.
3. On a worker thread, `OllamaClient` builds the request: system prompt (persona, server rules, facts, summary, tool instructions), recent history, and the new message.
4. If the model calls memory tools, the client applies them to a private copy of the facts and loops, up to 3 rounds, until it gets a text answer.
5. Back on the server thread, the exchange is added to the chat, fact changes are applied, the data is saved, and the reply is sent to the player with markdown symbols removed.
6. If the chat is long enough, a background summary is started.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `Could not reach Ollama... Is it running?` | Ollama isn't running, or `ollamaUrl` is wrong |
| `No models are installed in Ollama` | Run `ollama pull <model>` on the server machine |
| `That model isn't installed` | The model was removed. Pick another with `/ai model list` |
| It never saves facts | The model doesn't support tools (`/ai model list` shows "no fact learning"), or `/ai settings learn` is off. Use a model like `qwen2.5:1.5b` or larger |
| It ignores or mixes up facts | Very small models (under 1B parameters) follow instructions poorly. Try a larger model |
| `The AI is busy with other requests` | `maxQueuedRequests` was reached. Wait, or raise it |
| Answers are cut off | Raise `maxReplyTokens` or `maxReplyLines` |
| Slow first answer | Ollama is loading the model. Later answers are faster while `keepAlive` holds it in memory |

---

## Good to know

- **Privacy on shared servers:** each player has separate chats and facts, but `logConversations` writes everyone's messages to the server log. Turn it off if that matters.
- **Permissions:** there are no op-only commands. Any player can run `/ai reload`, which only re-reads the config file.
- **One Ollama machine:** all players share the same Ollama server, so many players asking at once will queue up. Raise `workerThreads` only if your hardware can run several requests at once.
- **Not included:** streaming replies as they are written, embedding-based fact retrieval for very large fact lists, and answering when a player types `@ai` in normal chat.
- **Status:** the code was written without compiling against Minecraft 26.x, so expect to fix a few small API differences on first build.
