Bobbin is a pure Java BitTorrent library. It is currently being developed as a testbed for protocol extension research.

At present, a selection of core standards have been implemented, but the library has not seen widespread real life deployment. Use in a production situation is not yet recommended. Interfaces and behaviour may change at any time.

Bobbin contains the reference implementation of the [Elastic extension](ElasticExtension.md), an experimental extension that allows torrents to be securely extended after creation.

## Implemented standards ##
| BEP 0003 | The BitTorrent Protocol Specification |
|:---------|:--------------------------------------|
| BEP 0006 | Fast Extension                        |
| BEP 0020 | Peer ID Conventions                   |
| BEP 0023 | Tracker Returns Compact Peer Lists    |
| BEP 0012 | Multitracker Metadata Extension       |
| BEP 0010 | Extension Protocol                    |
| BEP 0030 | Merkle tree torrent extension (Note: Some protocol conformance issues) |
| bo\_elastic | [Elastic extension](ElasticExtension.md) (Under development) |

## Currently unimplemented standards ##
| BEP 0005 | DHT Protocol |
|:---------|:-------------|
| BEP 0007 | IPv6 Tracker Extension |
| BEP 0009 | Extension for Peers to Send Metadata Files (ut\_metadata) |
| BEP 0015 | UDP Tracker Protocol |
| BEP 0017 | HTTP Seeding (Hoffman-style) |
| BEP 0019 | HTTP/FTP Seeding (GetRight-style) |
| BEP 0021 | Extension for partial seeds |
| BEP 0024 | Tracker Returns External IP |
| BEP 0026 | Zeroconf Peer Advertising and Discovery |
| BEP 0027 | Private Torrents |
| BEP 0028 | Tracker Exchange (lt\_tex) |
| BEP 0031 | Tracker Failure Retry Extension |
| BEP 0032 | IPv6 extension for DHT |
| ut\_pex  | Peer exchange extension |