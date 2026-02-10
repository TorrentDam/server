TorrentDam Server
=================

This server bridges the BitTorrent network with HTTP, allowing users to:

- Download torrent metadata and generate .torrent files
- Stream individual files directly from torrents over HTTP
- Access files without downloading the entire torrent first

The server handles peer discovery via DHT, manages connections to BitTorrent peers,
and serves file data on-demand with support for partial content requests.
