# Connecting Rexling

The default uses your phone’s network, including an existing Tailscale VPN. Existing saved HTTPS servers keep working.

## SSH

In Servers, add `ssh://user@host`, optionally with a port (`ssh://user@host:2222`). The host needs Rex installed and an SSH server that permits Unix socket forwarding. Rexling discovers and starts that user’s Rex server. Linux and macOS hosts are supported; Windows named-pipe hosts are not yet supported over SSH.

Import an OpenSSH or PEM private key in the server editor. Encrypted keys prompt for a passphrase during import. The imported key is stored encrypted using Android Keystore; its passphrase is not retained. This authenticates the SSH connection and does not provide SSH agent forwarding. Password authentication is not supported.

For Tailscale SSH, leave the key unset. This works when the host accepts keyless authentication under its Tailscale SSH policy. Browser approval messages appear automatically, with Copy Message and Open Browser actions.

Before first connection, verify the displayed host fingerprint against the host. A changed key requires a new explicit trust decision. Removing a server forgets its key and its trusted host fingerprint unless another saved SSH server uses the same host and port.

An explicit remote Rex socket can be specified as a URL path, such as `ssh://user@host/tmp/rex.sock`. Otherwise Rexling uses Rex’s CLI to discover the socket. A missing Rex installation is reported rather than installed automatically.

## Built-in Tailscale and Headscale

Open Preferences → Network, enable Built-in Tailscale and tap Apply. Tap Sign in, then open the sign-in page. This creates a separate `rexling-android` tailnet device and does not require the Tailscale VPN app. Your tailnet’s access rules still apply.

Leave Control server blank for Tailscale, or enter your Headscale server’s HTTP(S) URL. Each control server retains its own private sign-in state when switching. HTTPS must use a certificate trusted by the phone; certificate checks are not bypassed.

With built-in Tailscale on, Rexling’s SSH and HTTPS connections use it. Use a tailnet short name, full DNS name, or IP address. Active peer routes are listed as direct, peer relay or Tailscale relay. Existing terminal connections reconnect after changing network settings; active uploads and forwards may need restarting.

Turn built-in Tailscale off to return to the phone’s existing network or VPN. No reverse forwarding or exit-node setup is included.
