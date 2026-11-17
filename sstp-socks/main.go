package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/bclswl0827/govpn/protocols/sstp"
	"github.com/things-go/go-socks5"
)

func main() {
	listenAddr := flag.String("listen", "127.0.0.1:10818", "local SOCKS5 listen address")
	server := flag.String("server", "", "SSTP server host or IP")
	port := flag.Int("port", 443, "SSTP server port")
	user := flag.String("user", "", "SSTP username")
	pass := flag.String("pass", "", "SSTP password")
	sni := flag.String("sni", "", "TLS SNI server name")
	insecure := flag.Bool("insecure", false, "skip TLS certificate verification")
	dnsAddr := flag.String("dns", "1.1.1.1:53", "fallback DNS server for Android")
	flag.Parse()

	if *server == "" {
		log.Fatal("missing -server")
	}

	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{Timeout: 5 * time.Second}
			return d.DialContext(ctx, "udp", *dnsAddr)
		},
	}

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	client := sstp.NewClient(sstp.Config{
		Server:       *server,
		Port:         *port,
		Username:     *user,
		Password:     *pass,
		ServerName:   *sni,
		SkipVerify:   *insecure,
		PrefixLength: 24,
		Logger:       log.Default(),
	})

	session, err := client.Start(ctx)
	if err != nil {
		log.Fatalf("sstp start failed: %v", err)
	}

	srv := socks5.NewServer(
		socks5.WithDial(func(ctx context.Context, network, addr string) (net.Conn, error) {
			return session.DialContext(ctx, network, addr)
		}),
	)

	ln, err := net.Listen("tcp", *listenAddr)
	if err != nil {
		log.Fatalf("listen %s failed: %v", *listenAddr, err)
	}
	defer ln.Close()

	go func() {
		<-ctx.Done()
		_ = ln.Close()
	}()

	fmt.Fprintf(os.Stderr, "sstp-socks listening on %s\n", *listenAddr)
	if err := srv.Serve(ln); err != nil && ctx.Err() == nil {
		log.Fatalf("socks5 serve error: %v", err)
	}
}
