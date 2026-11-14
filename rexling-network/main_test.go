package main

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"encoding/pem"
	"fmt"
	"golang.org/x/crypto/ssh"
	"io"
	"net"
	"net/url"
	"os"
	"testing"
	"time"
)

func testSSH(t *testing.T, keyAuth bool) (string, string, string) {
	t.Helper()
	_, private, _ := ed25519.GenerateKey(rand.Reader)
	host, _ := ssh.NewSignerFromKey(private)
	_, userKey, _ := ed25519.GenerateKey(rand.Reader)
	user, _ := ssh.NewSignerFromKey(userKey)
	config := &ssh.ServerConfig{NoClientAuth: !keyAuth}
	if keyAuth {
		config.PublicKeyCallback = func(_ ssh.ConnMetadata, key ssh.PublicKey) (*ssh.Permissions, error) {
			if string(key.Marshal()) != string(user.PublicKey().Marshal()) {
				return nil, fmt.Errorf("key refused")
			}
			return nil, nil
		}
	}
	config.AddHostKey(host)
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { listener.Close() })
	go func() {
		for {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			go func() {
				server, channels, requests, err := ssh.NewServerConn(conn, config)
				if err != nil {
					conn.Close()
					return
				}
				defer server.Close()
				go ssh.DiscardRequests(requests)
				for channel := range channels {
					switch channel.ChannelType() {
					case "session":
						stream, requests, err := channel.Accept()
						if err != nil {
							continue
						}
						go func() {
							defer stream.Close()
							for request := range requests {
								if request.Type == "exec" {
									request.Reply(true, nil)
									io.WriteString(stream, `{"endpoint":"unix:///tmp/rex%20test.sock"}`)
									stream.SendRequest("exit-status", false, ssh.Marshal(struct{ Status uint32 }{0}))
									return
								}
								request.Reply(false, nil)
							}
						}()
					case "direct-streamlocal@openssh.com":
						var payload struct {
							Path     string
							Reserved string
							Port     uint32
						}
						ssh.Unmarshal(channel.ExtraData(), &payload)
						if payload.Path != "/tmp/rex test.sock" {
							channel.Reject(ssh.ConnectionFailed, "wrong socket")
							continue
						}
						stream, requests, err := channel.Accept()
						if err != nil {
							continue
						}
						go ssh.DiscardRequests(requests)
						go func() { defer stream.Close(); io.Copy(stream, stream) }()
					default:
						channel.Reject(ssh.UnknownChannelType, "unsupported")
					}
				}
			}()
		}
	}()
	encoded, _ := ssh.MarshalPrivateKey(userKey, "")
	return "ssh://tester@" + listener.Addr().String(), string(pem.EncodeToMemory(encoded)), ssh.FingerprintSHA256(host.PublicKey())
}
func reset(t *testing.T, config configuration) {
	t.Helper()
	config.Directory = t.TempDir()
	if err := configure(config); err != nil {
		t.Fatal(err)
	}
}
func TestSSHTrustAuthenticationAndStream(t *testing.T) {
	for _, keyAuth := range []bool{false, true} {
		t.Run(fmt.Sprint(keyAuth), func(t *testing.T) {
			endpoint, key, fingerprint := testSSH(t, keyAuth)
			u, _ := url.Parse(endpoint)
			reset(t, configuration{})
			defer reset(t, configuration{})
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			if _, err := dial(ctx, endpoint); err == nil {
				t.Fatal("unknown host accepted")
			}
			state.Lock()
			prompt := state.prompts[hostIdentity(u)]
			state.Unlock()
			if prompt.Fingerprint != fingerprint || prompt.Previous != "" {
				t.Fatalf("missing trust prompt: %+v", prompt)
			}
			config := configuration{Trusted: map[string]string{hostIdentity(u): fingerprint}}
			reset(t, config)
			if keyAuth {
				if _, err := dial(ctx, endpoint); err == nil {
					t.Fatal("missing key accepted")
				}
				config.SSH = map[string]sshCredential{endpoint: {Key: key}}
				reset(t, config)
			}
			conn, err := dial(ctx, endpoint)
			if err != nil {
				t.Fatal(err)
			}
			defer conn.Close()
			conn.SetDeadline(time.Now().Add(time.Second))
			if _, err = conn.Write([]byte("hello")); err != nil {
				t.Fatal(err)
			}
			buffer := make([]byte, 5)
			if _, err = io.ReadFull(conn, buffer); err != nil || string(buffer) != "hello" {
				t.Fatalf("stream: %q %v", buffer, err)
			}
			config.Trusted[hostIdentity(u)] = "SHA256:old"
			reset(t, config)
			if _, err = dial(ctx, endpoint); err == nil {
				t.Fatal("changed key accepted")
			}
			state.Lock()
			prompt = state.prompts[hostIdentity(u)]
			state.Unlock()
			if prompt.Previous != "SHA256:old" {
				t.Fatal("changed-key prompt lost previous fingerprint")
			}
		})
	}
}
func TestConfigurationRejectsUnsafeControlURL(t *testing.T) {
	for _, value := range []string{"ftp://host", "https://user:secret@host", "https://host?token=x", "garbage"} {
		if configure(configuration{Directory: t.TempDir(), ControlURL: value}) == nil {
			t.Fatalf("accepted %s", value)
		}
	}
}

// Optional fixture for the Android integration test. Only reaches the disposable socket supplied by the test runner.
func TestAndroidFixture(t *testing.T) {
	path := os.Getenv("REXLING_SSH_FIXTURE_SOCKET")
	if path == "" {
		t.Skip("Android fixture not requested")
	}
	_, private, _ := ed25519.GenerateKey(rand.Reader)
	host, _ := ssh.NewSignerFromKey(private)
	config := &ssh.ServerConfig{NoClientAuth: true}
	config.AddHostKey(host)
	listener, err := net.Listen("tcp", "127.0.0.1:19703")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	data, _ := json.Marshal(map[string]string{"fingerprint": ssh.FingerprintSHA256(host.PublicKey()), "socket": path})
	if err = os.WriteFile("/tmp/rexling-ssh-fixture.json", data, 0600); err != nil {
		t.Fatal(err)
	}
	for {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		go func() {
			server, channels, requests, err := ssh.NewServerConn(conn, config)
			if err != nil {
				conn.Close()
				return
			}
			defer server.Close()
			go ssh.DiscardRequests(requests)
			for channel := range channels {
				if channel.ChannelType() != "direct-streamlocal@openssh.com" {
					channel.Reject(ssh.UnknownChannelType, "unsupported")
					continue
				}
				var payload struct {
					Path     string
					Reserved string
					Port     uint32
				}
				ssh.Unmarshal(channel.ExtraData(), &payload)
				if payload.Path != path {
					channel.Reject(ssh.ConnectionFailed, "wrong socket")
					continue
				}
				remote, err := net.Dial("unix", path)
				if err != nil {
					channel.Reject(ssh.ConnectionFailed, err.Error())
					continue
				}
				stream, requests, err := channel.Accept()
				if err != nil {
					remote.Close()
					continue
				}
				go ssh.DiscardRequests(requests)
				go func() {
					defer stream.Close()
					defer remote.Close()
					go func() { io.Copy(remote, stream); remote.Close() }()
					io.Copy(stream, remote)
				}()
			}
		}()
	}
}
