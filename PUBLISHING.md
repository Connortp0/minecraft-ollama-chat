# Publishing checklist (Ollama Chat 1.0.0)

## 1. Things only you can fill in
- **Authors and links:** add `"authors"` and a `"contact"` block (homepage, sources, issues) to `src/main/resources/fabric.mod.json`.
- **License:** the file says `MIT`. Change it if you want something else, and add a matching `LICENSE` file to the repository.
- **Icon (optional):** a 128x128 PNG, e.g. `src/main/resources/assets/ollama-chat/icon.png`, plus `"icon": "assets/ollama-chat/icon.png"` in `fabric.mod.json`.
- **Package name:** the code uses `com.ollamachat`. If you have your own namespace (for example `io.github.yourname`), rename the package with your IDE and update the `main` entrypoint in `fabric.mod.json`.

## 2. Project files to update
In `gradle.properties` (names may differ slightly in your template):
```
mod_version=1.0.0
maven_group=com.ollamachat
archives_base_name=ollama-chat
```
Then remove template leftovers if they exist: `ExampleModClient`, `example-mod.mixins.json`, `ExampleMixin`, and any `assets/example-mod` folder. Replace your old `fabric.mod.json` with the one provided, because it no longer lists a client entrypoint or mixins.

`fabric.mod.json` uses `"version": "${version}"`. The standard Fabric template fills this in during `processResources`. If your build doesn't, put `1.0.0` there directly.

## 3. Build
```
./gradlew clean build
```
Upload the jar in `build/libs/` that has no `-sources` or `-dev` suffix.

## 4. Test before uploading
- Singleplayer: `/ai hello`, pick a model, get an answer
- Dedicated server: the same, with a second player to check chats and memory are separate
- Ollama stopped: you get "Could not reach Ollama" and the game keeps running
- Tell it "my name is Alex", then "what's my name?" in a new chat (memory works across chats)
- Say "forget my name" (memory is removed), and try `/ai forget all`
- `/ai stop` during a long answer, `/ai retry`, `/ai undo`
- Send 25+ messages and check the log for `Summary updated`
- Restart the world: chats and memory are still there
- Edit `config/ai-chat.json` and run `/ai reload`

## 5. Modrinth / CurseForge listing
- **Name:** Ollama Chat
- **Summary:** Chat with a local Ollama AI from Minecraft with /ai. Multiple chats, automatic memory, personas and model selection.
- **Loader:** Fabric. **Game version:** 26.3 (add others only after testing them)
- **Environment:** works in singleplayer and on servers. On a dedicated server, players don't need to install it.
- **Dependency:** Fabric API (required)
- **Release channel:** use Beta for the first upload, and promote to Release once a few people confirm it works.
- **Description:** paste `README.md`. Make clear near the top that **Ollama must be installed separately** and that no models or data leave the Ollama server you configure.
- **Changelog:** paste the `1.0.0` section from `CHANGELOG.md`.

## 6. Known limits to mention
- Needs Ollama running on the same machine as the server (or `ollamaUrl` pointing to another one)
- Small models (under 1B parameters) follow instructions and use memory poorly
- `logConversations` is on by default and writes questions and answers to the server log
- Any player can run `/ai reload` (it only re-reads the config file)
