package dev.shulhi.rex

/** Executable-only tile lookup; terminal titles never select an icon. */
internal object ProgramCatalog {
    val tiles=setOf(
        "amp","android","antigravity","apple_container","azure","bash","bat","bazel",
        "boundary","brew","browser","btop","buck","buildkite","bun","butler",
        "caddy","caffeinate","charm","clang","claude","claude_code","cloudflare","coder",
        "codex","consul","copilot","crush","cursor","d2","dart",
        "datadog","dbcli","delta","depot","devin","docker","dotnet","droid",
        "duckdb","e2b","elixir","emacs","expo","fish","flutter","fm",
        "fx","gemini","git","gitchanges","github","gitlab","gnu","go",
        "goose","gradle","grok","hermes","heroku","homey","htop","hugging_face",
        "hunk","hx","ibm_bob","inspector","jj","julia","junie","jupyter",
        "k9s","kakoune","kiro","kube","llama_cpp","log","maki","mastra",
        "meta","micro","minimal","mise","mistral","modal","mole","moon",
        "nano","neovim","nextflow","nix","node","nomad","nushell","ollama",
        "omp","openclaw","opencode","opscope","orbstack","oxide","pack","packer",
        "php","pi","pixi","placeholder","pnpm","podman","poolside_icon","porter",
        "postgres","prime_agent","proto","proton_drive","proton_pass","python","railway","rclone",
        "redis","render","rex","ruby","rust","sail","sanity","shell",
        "shell_container","shopify","sleep","snowflake","sofka","sqlite","stripe","swift",
        "tailscale","task","terminal","terraform","top","turborepo","unreal_engine","uv",
        "vagrant","vakna","vault","vercel","vim","vite","vite_plus","waypoint",
        "xcode","yazi","zig","zsh"
    )
    private val aliases=mapOf(
        "agy" to "antigravity","antigravity-cli" to "antigravity","rex-server" to "rex",
        "rex-cli" to "rex","cargo" to "rust","rustc" to "rust",
        "kubectl" to "kube","helm" to "kube","k3s" to "kube",
        "minikube" to "kube","gh" to "github","glab" to "gitlab",
        "helix" to "hx","az" to "azure","psql" to "postgres",
        "pgcli" to "postgres","sqlite3" to "sqlite","duck" to "duckdb",
        "turbo" to "turborepo","mix" to "elixir","iex" to "elixir",
        "kak" to "kakoune","jujutsu" to "jj","orb" to "orbstack",
        "huggingface" to "hugging_face","hf" to "hugging_face","vp" to "vite_plus",
        "batcat" to "bat","clang++" to "clang","cc" to "clang",
        "c++" to "clang","docker-compose" to "docker","podman-compose" to "podman",
        "container" to "apple_container","adb" to "android","homebrew" to "brew",
        "brew.rb" to "brew","brew.sh" to "brew","emacsclient" to "emacs",
        "xcodebuild" to "xcode","xcrun" to "xcode","jupyter-lab" to "jupyter",
        "jupyter-notebook" to "jupyter","llama-cli" to "llama_cpp","llamacpp" to "llama_cpp",
        "oh-my-posh" to "omp","poolside" to "poolside_icon","irb" to "ruby",
        "gem" to "ruby","bundle" to "ruby","unreal" to "unreal_engine",
        "buck2" to "buck","bazelisk" to "bazel","ibazel" to "bazel",
        "gradlew" to "gradle","golang" to "go","nix-shell" to "nix",
        "nixos-rebuild" to "nix","gh-copilot" to "copilot","github-copilot" to "copilot",
        "nu" to "nushell"
    )
    fun tile(executable: String): String? {
        val name=executable.replace('\\','/').substringAfterLast('/').trimStart('-').lowercase(java.util.Locale.ROOT).removeSuffix(".exe").removeSuffix(".cmd")
        return aliases[name] ?: name.replace('-','_').takeIf {it in tiles}
    }
}
