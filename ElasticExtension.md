<font color='red'>
This <b>draft</b> protocol and metadata extension represents a proof of concept, and lacks several features that would be required for real world deployment<br>
</font>

## Summary ##

The Elastic Extension allows a single file within a torrent to be securely extended by its creator, and the extended data to be propagated through the peer cloud in near real time.

An Elastic torrent consists of :
  * A [Merkle extension](http://www.bittorrent.org/beps/bep_0030.html) style Info with a 'root hash', plus
  * A DSA public key stored within the MetaInfo
  * A DSA signature of the root hash (the **root signature**) stored within the Info, which ensures that only one key is valid for the info hash
  * A DSA signature of the info hash (the **info signature**) stored within the MetaInfo, which allows peers to verify that the Info is as intended by the torrent creator

An Elastic torrent originator (the initial seed that signs the torrent) behaves as follows :
  * To extend the torrent, the originator updates its hash tree to create a new **view** that represents the extended data, then publishes a **view signature** to its connected peers consisting of
    * The view length
    * The view root hash
    * A DSA signature of (info hash,view length,view root hash)

Peers connecting on an Elastic torrent behave as follows :
  * On connection, a peer sends the signature of its local view (if it has a view longer than the initial view)
  * On receipt of a view signature longer than its local view, a peer updates its local view and forwards the signature to its peer set
  * Peers exchange **elastic pieces**, which contain a hash list and the view length for which the hash list is valid. Peers may choose any view length to send a piece for which they have a valid signature, after first sending the signature to the remote peer


The Elastic Extension requires no special tracker support, and parts of its peer infrastructure can share an implementation with the Merkle extension

For discussion of a hash tree structure that efficiently supports the expanding data layer, see [Elastic Hash Tree](ElasticTree.md)

## Demonstration ##

A working demonstration is contained within the project source, which is set up as an Eclipse project.

The class `demo.DemoElasticSeed` creates and connects a local tracker, an initial seed, and an ordinary peer on a torrent containing a single file. After checking out and compiling the source, invoke:

```
# java -cp <Bobbin base>/bin demo.DemoElasticSeed <shared file>
```

A window should open to show the state of the ordinary peer. The initial seed will output its status to the console.

Next, extend the shared file on disk. The initial seed polls the shared file periodically and will add the new data to the shared torrent in chunks

```
# cat <something> >> <shared file>
```

The ordinary peer window should reflect that it is receiving the additional data from the initial seed.

The initial seed, ordinary peer and tracker in this example are sharing a process, but communicating normally over the network. On startup, the filename of the created Elastic torrent file will be printed, so an additional peer may be connected by using it:

```
# java -cp <Bobbin base>/bin demo.DemoClient <torrent file>
```

Notes:
  * DemoClient supports save/resume, but in this demonstration the initial seed does not
  * DemoClient writes its data to the current directory, so be sure to invoke it in a temporary directory


## Implementation Details ##

  * Extension identifier
    * `bo_elastic`
  * Info extensions
    * The Info must be a Merkle extension style Info with a "root hash" entry, plus the following keys
      * "`root signature`" : `binary<40>` - A DSA signature of the root hash. Note: This must be a 40 byte, P1363 encoded raw DSA signature on the root hash, not an "SHA1withDSA" signature that would re-hash the bytes of the root hash
      * "`elastic`" : `binary<1> 0x01`
  * Metainfo extensions
    * "`info signature`" : `binary<40>` - A DSA signature of the Info Hash
    * "`public key`" : `binary` - An X.509 encoded DSA public key
  * Message format
    * Elastic Signature - `0:viewLength<8>:viewRootHash<20>:signature(infoHash,viewLength,viewRootHash)<40>`
    * Elastic Piece - `1:pieceNumber<4>:offset<4>:hashLength<4>:hashCount<1>:{viewLength<8>:hashList<20*hashCount>}?:data`
    * Elastic Bitfield - `2:bitfield`
  * Protocol implementation
    * A peer must advertise the Elastic extension only when connecting on an Elastic torrent
    * An Elastic torrent peer must close the connection if the remote peer does not advertise the Elastic extension
    * An Elastic torrent peer must send a "Have None" message during the initial handshake
    * Once extension negotiation is complete, a peer must send:
      * An Elastic Signature message corresponding to the signature of its local view (the largest view it has a signature for), if its local view is longer than the initial view defined by the torrent's Info
      * An Elastic Bitfield message for that view
    * An Elastic torrent peer must only send "Elastic Piece" messages
      * When sending an Elastic piece, a peer may choose any view length for which it possesses a signature and complete hash chain for that piece
      * A peer is required to remember a known signature set for its remote peer consisting of: The signature of the longest view that the remote peer has sent, and of the most recent view sent other than the longest
      * Before sending a block with a particular view length, a peer must have sent an "Elastic Signature" for that view length such that it is in the known signature set defined above
      * A peer may send blocks using the initial view length of the torrent without sending a signature. The initial view length defined by the torrent's Info is implicitly valid
    * An Elastic torrent peer must close the connection if the remote peer sends a "Piece" message
    * A peer must extend their local view each time a longer signature is received
    * When the local view is extended, if the previous view's final piece was irregular (not exactly a piece-size piece) then it is dropped from the present piece set, and outstanding requests for that piece should be rejected
    * Where the local peer's view is longer than a remote peer's, the local peer must ensure that it does not request blocks beyond the length of the remote peer's final piece
    * The length of a remote peer's view as seen by the local peer is defined by the longest view signature the remote peer has sent
    * If a remote peer replaces its longest view **v1** with a new view **v2**, and the final piece **p** of **v1** is irregular (not an exactly piece-size piece), **p** is implicitly dropped from the remote peer's available piece set
    * When a signature extends the peer's local view, it must propagate that signature to the other peers in its peer set
  * Tracker implementation
    * No special tracker support is required
    * An Elastic torrent peer may announce itself as a partial seed as defined in BEP 0021 if it is not interested in downloading data
    * An Elastic torrent peer should not announce itself as a complete seed, as an Elastic torrent may be extended at any time (see below note)

  * Notes
    * Features that should be added or altered for real world deployment
      * A method to exchange key metadata among peers (which may be split into a separate extension in order to allow non-Elastic torrents to benefit from signing)
      * A method to extend the fileset, which may be implemented using an additional Elastic message subtype
      * A method for the torrent originator to declare the torrent data complete at a particular length, which may be implemented using an additional Elastic message subtype, or an alteration to the Signature message
      * A method for peers to send a compact bitfield, which may be implemented as an update to the format of the "Elastic Bitfield" message. This would allow peers to hold complete parts of very large bitsets without the cost of sending a full length bitfield
      * A method for peers to periodically "not have" ranges of pieces, which may be implemented using an additional Elastic message subtype. This would allow peers to participate in live streaming without the obligation to file-back all the pieces they have ever held
    * Issues not faced by non-Elastic torrents
      * Allowing torrent data to be extended replaces a previous safety management issue ("is the known content of this immutable torrent safe") with a reputation management issue ("do I trust this key and whatever new data it may send me")
      * The torrent originator is in a unique position to break an existing torrent by issuing inconsistent, but verifiable signatures
        * This may occur deliberately, or accidentally if for instance the originator dies without having committed signatures that it has published to permanent storage. A statement of best practices for originators may be useful.
        * If this occurs, the peer set will fracture into two or more competing sets